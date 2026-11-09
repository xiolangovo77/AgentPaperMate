package com.ecnu.agentpapermate.scheduler;

import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.entity.model.AgentRun;
import com.ecnu.agentpapermate.mapper.AgentRunMapper;
import com.ecnu.agentpapermate.service.run.RunDispatcher;
import com.ecnu.agentpapermate.service.run.rag.RunStore;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.*;

/** 数据库 pending 队列调度与截止时间回收，不依赖前端 SSE 连接。 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RunScheduler {
    /**
     * 问答任务数据访问接口，用于扫描待执行、超时任务及查询当前状态。
     */
    private final AgentRunMapper runs;
    /**
     * 任务事务服务，负责领取任务、写入终态和释放会话占用。
     */
    private final RunStore store;
    /**
     * 统一执行入口，负责策略分发、完成结果和执行异常；调度器只处理提交失败。
     */
    private final RunDispatcher dispatcher;
    /**
     * 问答运行配置，提供并发上限、排队超时等参数。
     */
    private final RagProperties properties;
    /**
     * 问答任务虚拟线程执行器，运行已领取的任务。
     */
    private final ExecutorService executor;
    /**
     * 上海时区时钟，用于计算任务运行及排队的截止时间。
     */
    private final Clock clock;
    /**
     * 本实例的任务并发许可，限制已提交且尚未结束的任务数量。
     */
    private final Semaphore permits;
    /**
     * 本实例正在执行的任务与线程映射，用于在取消或超时后中断等待。
     */
    private final ConcurrentMap<Long, Thread> executing = new ConcurrentHashMap<>();
    /**
     * 调度停止标记，应用关闭时设置，保证跨线程可见并阻止继续领取任务。
     */
    private volatile boolean stopping;

    public RunScheduler(AgentRunMapper runs, RunStore store, RunDispatcher dispatcher, RagProperties properties,
                        @Qualifier("ragExecutor") ExecutorService executor, Clock ragClock) {
        this.runs = runs; this.store = store; this.dispatcher = dispatcher; this.properties = properties;
        this.executor = executor; this.clock = ragClock; this.permits = new Semaphore(properties.getMaxConcurrentRuns());
    }

    @Scheduled(fixedDelayString = "${rag.poll-interval-ms:1000}")
    public void poll() {
        if (stopping) return;
        try {
            //1、即使关闭新任务入口，也回收已有任务；重启遗留 running 最迟在 deadline 结束
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime queuedBefore = now.minusSeconds(properties.getQueueTimeoutSeconds());
            var expired = runs.selectExpiredCandidates(now, queuedBefore, 100);
            for (AgentRun run : expired) store.expire(run.getId());
            //2、取消和超时落库后，尽可能中断当前实例中的外部请求等待
            executing.forEach((id, thread) -> {
                AgentRun run = runs.selectById(id);
                if (run == null || RunStore.terminal(run.getStatus())) thread.interrupt();
            });
            if (!properties.isEnabled() || stopping) return;
            var candidates = runs.selectPendingCandidates(properties.getMaxConcurrentRuns());
            for (AgentRun candidate : candidates) {
                if (stopping || !permits.tryAcquire()) break;
                boolean submitted = false;
                AgentRun claimed = null;
                try {
                    //3、先领取并提交开始事件，再交给虚拟线程，避免未提交任务被执行
                    claimed = store.start(candidate.getId());
                    if (claimed == null) continue;
                    AgentRun task = claimed;
                    executor.execute(() -> execute(task));
                    submitted = true;
                } catch (RuntimeException e) {
                    if (claimed != null) store.fail(claimed.getId(), "DISPATCH_FAILED", "任务提交执行失败", false);
                    throw e;
                } finally {
                    if (!submitted) permits.release();
                }
            }
        } catch (RuntimeException e) {
            log.warn("问答调度暂时失败，将在下一轮继续，异常类型={}", e.getClass().getSimpleName());
        }
    }

    private void execute(AgentRun run) {
        executing.put(run.getId(), Thread.currentThread());
        try {
            dispatcher.dispatch(run);
        } catch (RuntimeException e) {
            log.warn("问答结果写入失败，将由截止时间回收，runId={}", run.getId());
        } finally {
            executing.remove(run.getId());
            permits.release();
        }
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        executing.values().forEach(Thread::interrupt);
    }
}
