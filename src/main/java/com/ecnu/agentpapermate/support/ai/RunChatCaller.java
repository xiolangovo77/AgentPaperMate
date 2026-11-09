package com.ecnu.agentpapermate.support.ai;

import com.alibaba.cloud.ai.dashscope.metadata.DashScopeAiUsage;
import com.ecnu.agentpapermate.entity.model.AgentRun;
import com.ecnu.agentpapermate.service.run.rag.RunStore;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** 固定 RAG 和 Agent 共用的聊天调用入口；每次响应先累计用量，再交给业务解析。 */
@Component
public class RunChatCaller {
    private final ChatModel model;
    private final RagCalls calls;
    private final RunStore store;

    public RunChatCaller(@Qualifier("ragChatModel") ChatModel model, RagCalls calls, RunStore store) {
        this.model = model;
        this.calls = calls;
        this.store = store;
    }

    public ChatResponse call(AgentRun run, Prompt prompt) {
        ChatResponse response = calls.call(run, () -> model.call(prompt));
        // 即使回答为空或稍后引用校验失败，也先保存上游已经返回的用量。
        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var usage = response.getMetadata().getUsage();
            Number input = usage.getPromptTokens();
            // M6.1 的 getCompletionTokens 固定返回 0，保留原生输出用量兼容处理。
            Number output;
            if (usage instanceof DashScopeAiUsage nativeUsage) {
                output = nativeUsage.getGenerationTokens();
            } else {
                output = usage.getCompletionTokens();
            }
            // 仅累加已知用量；缺失的部分不代表真实消耗为零。
            if (input != null || output != null) {
                store.addModelUsage(run.getId(), input == null ? 0 : input.longValue(),
                        output == null ? 0 : output.longValue());
            }
        }
        // 记账失败直接传播，不能自动重试累加或重新请求模型。
        return response;
    }
}
