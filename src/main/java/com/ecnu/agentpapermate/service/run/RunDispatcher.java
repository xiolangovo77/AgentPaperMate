package com.ecnu.agentpapermate.service.run;

import com.ecnu.agentpapermate.agent.context.AgentRunContext;
import com.ecnu.agentpapermate.agent.AgentHarness;
import com.ecnu.agentpapermate.entity.model.AgentRun;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.service.run.rag.RunStore;
import com.ecnu.agentpapermate.support.ai.RagCalls;
import com.ecnu.agentpapermate.support.json.RagJson;
import com.ecnu.agentpapermate.validator.AnswerValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** 已领取 Run 的唯一执行入口；负责选择策略、创建上下文、完成及执行异常归类。 */
@Slf4j
@Component
public class RunDispatcher {
    /** Run 状态事务服务，负责提交完成结果和执行失败终态。 */
    private final RunStore store;
    /** 解析已领取 Run 的持久化快照，构建共享运行上下文。 */
    private final RagJson json;
    /** 按执行器类型索引已注册的执行策略，用于分发 Run。 */
    private final Map<String, RunExecutor> executors;

    public RunDispatcher(RunStore store, RagJson json, List<RunExecutor> executors) {
        this.store = store;
        this.json = json;
        Map<String, RunExecutor> registered = new HashMap<>();
        for (RunExecutor executor : executors) {
            String type = executor.executorType();
            if (type == null || type.isBlank() || registered.putIfAbsent(type, executor) != null) {
                throw new IllegalArgumentException("执行器类型必须非空且唯一");
            }
        }
        this.executors = Map.copyOf(registered);
    }

    /** 由 Scheduler 在 start 事务提交后调用一次；不重新领取，也不恢复或重放旧 running 任务。 */
    public void dispatch(AgentRun run) {
        try {
            RunExecutor executor = run.getExecutorType() == null ? null : executors.get(run.getExecutorType());
            if (executor == null) {
                store.fail(run.getId(), "UNSUPPORTED_EXECUTOR", "任务执行器类型未知或尚未实现", false);
                return;
            }
            var context = AgentRunContext.fromClaimedRun(run, json);
            var answer = executor.execute(context);
            // complete 在锁内再次检查终态、权限、截止时间和引用；迟到结果不能发布。
            if (answer.isPresent()) store.complete(context.getRunId(), answer.get());
        } catch (AgentHarness.Rejected e) {
            store.fail(run.getId(), e.code(), e.getMessage(), false);
        } catch (RagCalls.RequestTimeout e) {
            store.fail(run.getId(), "REQUEST_TIMEOUT", "外部服务请求超时", true);
        } catch (AnswerValidator.InvalidAnswer e) {
            store.fail(run.getId(), "INVALID_CITATION", "模型回答格式或引用校验失败，请重新提问", false);
        } catch (CancellationException e) {
            // 已取消、超时或预算耗尽的 Run 保持原终态；其他中断转为失败。
            store.fail(run.getId(), "EXECUTION_INTERRUPTED", "任务执行被中断", false);
        } catch (BusinessException e) {
            store.fail(run.getId(), "SOURCE_UNAVAILABLE", "任务资料已不可访问", false);
        } catch (RuntimeException e) {
            log.warn("问答执行失败，runId={}，异常类型={}", run.getId(), e.getClass().getSimpleName());
            store.fail(run.getId(), "EXECUTION_FAILED", "检索或模型服务调用失败，请稍后重试", false);
        }
    }
}
