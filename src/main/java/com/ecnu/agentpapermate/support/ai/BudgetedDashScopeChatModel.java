package com.ecnu.agentpapermate.support.ai;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.ecnu.agentpapermate.config.rag.RagProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;

import java.time.Duration;
import java.util.List;

/**
 * 保留原生 DashScope ChatModel，修正 M6.1 getMaxTokens 恒为 null 导致输出预算未发送的问题。
 * 每次调用使用独立的不可变上限，不修改共享模型选项，不在并发请求间共享任务参数。
 */
public class BudgetedDashScopeChatModel implements ChatModel {
    /**
     * 原生 DashScope 连接配置，包含服务地址、密钥和工作空间标识。
     */
    private final DashScopeConnectionProperties connection;
    /**
     * 问答模型配置，提供默认模型、输出上限及请求超时。
     */
    private final RagProperties properties;
    /**
     * 配置了连接和读取超时的 HTTP 请求工厂，供各次原生模型调用使用。
     */
    private final SimpleClientHttpRequestFactory factory;

    public BudgetedDashScopeChatModel(DashScopeConnectionProperties connection, RagProperties properties) {
        this.connection = connection; this.properties = properties;
        factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        //1、M6.1 将读取底层 maxTokens 字段的方法误命名为 setMaxTokens，按任务快照读取
        Integer requested = prompt.getOptions() instanceof DashScopeChatOptions options ? options.setMaxTokens()
                : (prompt.getOptions() == null ? null : prompt.getOptions().getMaxTokens());
        int maximum = requested == null ? properties.getMaxOutputTokens() : requested;
        if (maximum < 1 || maximum > 16000) throw new IllegalArgumentException("模型输出预算无效");

        //2、在原生请求边界补齐输出预算和模型端点兼容，响应解析仍交给 SDK
        var api = new DashScopeApi(connection.getBaseUrl(), connection.getApiKey(), connection.getWorkspaceId(),
                org.springframework.web.client.RestClient.builder().requestFactory(factory),
                org.springframework.web.reactive.function.client.WebClient.builder(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER) {
            @Override
            public ResponseEntity<ChatCompletion> chatCompletionEntity(ChatCompletionRequest request) {
                var p = request.parameters();
                var bounded = new ChatCompletionRequestParameter(p.resultFormat(), p.seed(), maximum,
                        p.topP(), p.topK(), p.repetitionPenalty(), p.presencePenalty(), p.temperature(), p.stop(),
                        p.enableSearch(), p.responseFormat(), p.incrementalOutput(), p.tools(), p.toolChoice(),
                        p.stream(), p.vlHighResolutionImages());
                // M6.1 不识别 qwen3.7-plus：即使只传文本，也要求多模态端点和 content 数组。
                // 按任务实际模型选择，避免当前默认模型影响已保存的任务快照。
                boolean multiModel = Boolean.TRUE.equals(request.multiModel())
                        || "qwen3.7-plus".equals(request.model())
                        || (request.model() != null && request.model().startsWith("qwen3.7-plus-"));
                var input = request.input();
                if (multiModel) {
                    input = new ChatCompletionRequestInput(input.messages().stream().map(message ->
                            message.rawContent() instanceof String text
                                    ? new ChatCompletionMessage(List.of(new ChatCompletionMessage.MediaContent(text)),
                                    message.role(), message.name(), message.toolCallId(), message.toolCalls(),
                                    message.reasoningContent()) : message).toList());
                }
                return super.chatCompletionEntity(new ChatCompletionRequest(request.model(), input, bounded,
                        request.stream(), multiModel));
            }
        };
        var defaults = DashScopeChatOptions.builder().withModel(properties.getModel()).withEnableSearch(false).build();
        //3、显式禁用 SDK 自动重试，避免一次 Run 隐式增加模型轮数及费用
        return new DashScopeChatModel(api, defaults, null, RetryTemplate.builder().maxAttempts(1).build()).call(prompt);
    }
}
