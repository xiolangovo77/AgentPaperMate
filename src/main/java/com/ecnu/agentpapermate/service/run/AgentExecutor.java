package com.ecnu.agentpapermate.service.run;

import com.ecnu.agentpapermate.agent.AgentHarness;
import com.ecnu.agentpapermate.agent.context.AgentRunContext;
import com.ecnu.agentpapermate.rag.model.Answer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 将最小 Agent 循环注册到统一分发器，不创建上下文、不领取任务或提交终态。 */
@Component
@RequiredArgsConstructor
public class AgentExecutor implements RunExecutor {
    /** 使用 Dispatcher 创建的同一个上下文执行串行循环。 */
    private final AgentHarness harness;

    @Override
    public String executorType() { return "agent"; }

    @Override
    public Optional<Answer> execute(AgentRunContext context) { return harness.execute(context); }
}
