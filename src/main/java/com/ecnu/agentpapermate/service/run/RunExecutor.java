package com.ecnu.agentpapermate.service.run;

import com.ecnu.agentpapermate.agent.context.AgentRunContext;
import com.ecnu.agentpapermate.rag.model.Answer;

import java.util.Optional;

/** 执行策略：使用分发器提供的共享上下文，不领取 Run、不提交终态、不重试整个流程。 */
public interface RunExecutor {
    /** 返回唯一的执行器类型，供分发器匹配 Run 的 executor_type。 */
    String executorType();

    /** 返回待完成校验的答案；仅在状态检查已阻止继续执行时返回 empty，异常交由分发器处理。 */
    Optional<Answer> execute(AgentRunContext context);
}
