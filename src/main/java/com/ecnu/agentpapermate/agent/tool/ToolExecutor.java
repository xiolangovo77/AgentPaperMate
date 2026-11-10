package com.ecnu.agentpapermate.agent.tool;

import com.ecnu.agentpapermate.agent.context.AgentRunContext;
import com.ecnu.agentpapermate.agent.skill.SkillRegistry;
import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.service.run.rag.RunStore;
import com.ecnu.agentpapermate.support.ai.RagCalls;
import com.ecnu.agentpapermate.support.json.RagJson;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** 工具唯一执行主体：白名单、参数边界、预算、重复防护、限时执行与进度记录。 */
@Component
@RequiredArgsConstructor
public class ToolExecutor {
    /** 系统注册表，只暴露后端绑定的工具实现。 */
    private final ToolRegistry registry;
    /** Run 状态、调用次数和进度的事务入口。 */
    private final RunStore store;
    /** 使用可中断等待丢弃超时工具的迟到返回值。 */
    private final RagCalls calls;
    /** 严格解析工具参数并编码统一结果。 */
    private final RagJson json;
    /** 旧 Run 缺少单工具预算快照时使用当前配置。 */
    private final RagProperties properties;
    /** 用于同时约束单工具时间和整个 Run 的截止时间。 */
    private final Clock ragClock;

    /** allowedTools 由后端运行策略提供，不能绑定用户请求或模型参数。 */
    public ToolResponse execute(AgentRunContext context, AssistantMessage.ToolCall call, Set<String> allowedTools) {
        if (call == null || call.id() == null || call.id().isBlank() || call.id().length() > 128
                || !"function".equals(call.type()) || call.name() == null) {
            throw new IllegalArgumentException("工具调用标识、名称或类型无效");
        }
        requireActive(context);
        var registered = registry.find(call.name());
        // 即使上层误传更大的集合，Agent 快照和本批运行策略仍只能收缩权限。
        if (registered.isEmpty() || !allowedTools.contains(call.name())
                || context.getSkill() != null && (!context.getSkill().allowedTools().contains(call.name())
                || !SkillRegistry.RUN_TOOLS.contains(call.name()))) {
            return response(call, ToolResult.rejected("TOOL_NOT_ALLOWED", "工具未注册或不在本次允许集合中"));
        }
        try {
            if (!store.toolCallStarted(context.getRunId(), call.id(), call.name())) {
                throw new CancellationException("Run 已停止或工具预算已耗尽");
            }
        } catch (RunStore.DuplicateToolCall e) {
            return response(call, ToolResult.rejected("DUPLICATE_TOOL_CALL", "该工具调用 ID 已被使用，不重复执行"));
        }
        long started = System.nanoTime();
        ToolResult result;
        try {
            int timeout = timeoutSeconds(context);
            LocalDateTime deadline = LocalDateTime.now(ragClock).plusSeconds(timeout);
            if (context.getDeadline().isBefore(deadline)) deadline = context.getDeadline();
            result = calls.call(deadline, () -> {
                requireActive(context);
                if (call.arguments() == null || call.arguments().length() > 20000) {
                    throw new IllegalArgumentException("工具参数缺失或过长");
                }
                var args = json.tree(call.arguments());
                if (args == null || !args.isObject()) throw new IllegalArgumentException("工具参数必须是 JSON 对象");
                var value = registered.get().handler().apply(context, args);
                requireActive(context);
                return value;
            });
            if (result == null) throw new IllegalStateException("工具未返回结果");
        } catch (IllegalArgumentException e) {
            result = ToolResult.rejected("INVALID_TOOL_ARGUMENTS", "工具参数无效或超出本次资料范围");
        } catch (RuntimeException e) {
            String code = e instanceof RagCalls.RequestTimeout ? "TOOL_TIMEOUT"
                    : e instanceof CancellationException ? "TOOL_CANCELLED"
                    : e instanceof BusinessException ? "SOURCE_UNAVAILABLE" : "TOOL_EXECUTION_FAILED";
            store.toolCallFinished(context.getRunId(), call.id(), call.name(), code, elapsed(started));
            // 运行故障会使检索阶段不完整，交给运行入口结束任务，不带着损坏的阶段续轮。
            throw e;
        }
        requireActive(context);
        if (!store.toolCallFinished(context.getRunId(), call.id(), call.name(),
                "ok".equals(result.status()) ? null : result.code(), elapsed(started))) {
            throw new CancellationException("Run 已停止");
        }
        return response(call, result);
    }

    private ToolResponse response(AssistantMessage.ToolCall call, ToolResult result) {
        return new ToolResponse(call.id(), call.name(), json.write(result));
    }

    private int timeoutSeconds(AgentRunContext context) {
        try {
            int timeout = properties.getToolTimeoutSeconds();
            var config = context.getModelConfig() == null ? null : json.tree(context.getModelConfig());
            if (config != null && config.has("toolTimeoutSeconds")) {
                var snapshot = config.get("toolTimeoutSeconds");
                if (!snapshot.isIntegralNumber() || !snapshot.canConvertToInt()) throw new IllegalStateException("工具超时快照无效");
                timeout = snapshot.intValue();
            }
            if (timeout < 1 || timeout > 300) throw new IllegalStateException("工具超时预算无效");
            return timeout;
        } catch (IllegalArgumentException e) {
            // 配置快照损坏是运行故障，不能误报为模型工具参数错误。
            throw new IllegalStateException("工具超时快照无法解析", e);
        }
    }

    private void requireActive(AgentRunContext context) {
        if (Thread.currentThread().isInterrupted() || !store.executionAllowed(context.getRunId())) {
            throw new CancellationException("Run 已停止");
        }
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
