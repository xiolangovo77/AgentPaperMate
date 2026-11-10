package com.ecnu.agentpapermate.agent.model;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi.FunctionTool;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.ecnu.agentpapermate.agent.context.AgentRunContext;
import com.ecnu.agentpapermate.support.ai.RunChatCaller;
import com.ecnu.agentpapermate.support.json.RagJson;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;

/** 单轮模型适配：只发送工具定义和消息，不执行工具、不自动续轮。 */
@Component
@RequiredArgsConstructor
public class ToolCallModelAdapter {
    /** 公共聊天入口，统一检查模型预算、取消、超时并累计响应中的用量。 */
    private final RunChatCaller caller;
    /** 读取本次 Run 的模型配置快照。 */
    private final RagJson json;

    /** tools 为空时要求模型直接回答；消息历史由调用方按 Run 独立维护。 */
    public ChatResponse call(AgentRunContext context, List<Message> messages, List<ToolDefinition> tools) {
        if (messages == null || messages.isEmpty() || tools == null) {
            throw new IllegalArgumentException("模型请求缺少消息或工具定义集合");
        }
        var names = new HashSet<String>();
        for (var tool : tools) {
            if (tool == null || tool.name() == null || tool.name().isBlank() || !names.add(tool.name())) {
                throw new IllegalArgumentException("模型工具定义必须有效且名称唯一");
            }
        }
        var config = json.tree(context.getModelConfig());
        var options = DashScopeChatOptions.builder()
                .withModel(context.getModelName())
                .withTemperature(config.path("temperature").asDouble(0.2))
                .withMaxToken(config.path("maxTokens").asInt(2048))
                .withEnableSearch(false)
                .withProxyToolCalls(true)
                .withTools(tools.stream().map(tool -> new FunctionTool(new FunctionTool.Function(
                        tool.description(), tool.name(), tool.inputSchema()))).toList())
                .build();
            // 不注册 FunctionCallback，也不使用自动工具 Advisor；SDK 只处理协议转换。
        var response = caller.call(context, new Prompt(List.copyOf(messages), options));
        if (response == null || response.getResults().size() != 1
                || response.getResult().getOutput() == null) {
            throw new IllegalStateException("模型未返回唯一有效响应");
        }
        return response;
    }
}
