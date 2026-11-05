package com.ecnu.agentpapermate.ingestion;

import com.ecnu.agentpapermate.config.paper.PaperIngestionProperties;
import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.ecnu.agentpapermate.service.PaperJobClaimService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 中断尽力停止外部调用，尝试命名空间隔离失去租约的写入。
 */
public final class PaperLease implements AutoCloseable {
    /**
     * 租约失效标记，供心跳线程和工作线程共享
     */
    private final AtomicBoolean lost = new AtomicBoolean();
    /**
     * 租约守卫关闭标记，关闭后不再主动续期或触发工作线程中断
     */
    private final AtomicBoolean closed = new AtomicBoolean();
    /**
     * 周期性续租任务句柄，用于关闭时取消后续心跳
     */
    private final ScheduledFuture<?> heartbeat;
    /**
     * 基于单调时钟计算的本地执行截止时间（纳秒）
     */
    private final long deadline;

    /**
     * 立即校验并续期租约，启动周期心跳；续期失败或执行超时时尝试中断工作线程
     */
    public PaperLease(PaperProcessingJob job, PaperJobClaimService jobs,
                      PaperIngestionProperties properties, ScheduledExecutorService executor) {
        // 1、计算本次执行的截止时间，立即续期以确认任务仍由当前工作线程持有
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.getMaxDurationSeconds());
        if (!jobs.renew(job, properties.getMaxDurationSeconds())) {
            throw new IllegalStateException("领取租约已失效");
        }
        // 2、记录工作线程，按心跳间隔调度后续续期
        Thread worker = Thread.currentThread();
        heartbeat = executor.scheduleWithFixedDelay(() -> {
            if (closed.get() || lost.get()) {
                return;
            }
            try {
                // 3、达到截止时间或续期失败时标记租约失效，并尝试中断工作线程
                if (System.nanoTime() >= deadline || !jobs.renew(job, properties.getMaxDurationSeconds())) {
                    lost.set(true);
                    if (!closed.get()) {
                        worker.interrupt();
                    }
                }
            } catch (RuntimeException e) {
                lost.set(true);
                if (!closed.get()) {
                    worker.interrupt();
                }
            }
        }, properties.getHeartbeatSeconds(), properties.getHeartbeatSeconds(), TimeUnit.SECONDS);
    }

    /**
     * 在处理阶段边界检查租约、中断状态及执行截止时间，异常时阻止继续处理
     */
    public void check() {
        if (lost.get() || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
            throw new IllegalStateException("任务租约丢失、被中断或超过执行截止时间");
        }
    }

    /**
     * 关闭租约守卫并取消后续心跳，不强制中断正在执行的心跳任务
     */
    @Override
    public void close() {
        // 1、先标记关闭，让正在执行的心跳可以检查到关闭状态
        closed.set(true);
        // 2、取消后续心跳，允许正在执行的心跳自行结束
        heartbeat.cancel(false);
    }
}
