package com.ecnu.agentpapermate.scheduler;

import com.ecnu.agentpapermate.config.paper.PaperJobSchedulerProperties;
import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.ecnu.agentpapermate.service.PaperJobClaimService;
import com.ecnu.agentpapermate.service.PaperJobProcessor;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * 定时回收过期任务并领取待处理任务，通过信号量控制本实例执行并发
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "paper.processing.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PaperJobScheduler {
    /**
     * 任务领取与状态服务，负责领取、回收和失败重试
     */
    private final PaperJobClaimService jobs;
    /**
     * 论文任务执行器，负责单次入库处理
     */
    private final PaperJobProcessor processor;
    /**
     * 调度批次、并发上限等运行参数
     */
    private final PaperJobSchedulerProperties properties;
    /**
     * 提交已领取任务的异步执行线程池
     */
    private final ExecutorService executor;
    /**
     * 并发许可，限制本实例已提交且尚未结束的任务数量
     */
    private final Semaphore permits;
    /**
     * 停止标记，保证关闭状态对调度线程可见
     */
    private volatile boolean stopping;

    /**
     * 初始化调度依赖，并按并发上限创建任务许可
     */
    public PaperJobScheduler(PaperJobClaimService jobs, PaperJobProcessor processor,
                             PaperJobSchedulerProperties properties,
                             @Qualifier("paperJobExecutor") ExecutorService executor) {
        this.jobs = jobs;
        this.processor = processor;
        this.properties = properties;
        this.executor = executor;
        this.permits = new Semaphore(properties.getMaxConcurrentJobs());
    }

    /**
     * 先回收过期租约，再在批次和并发限制内领取并提交任务
     */
    @Scheduled(fixedDelayString = "${paper.processing.scheduler.poll-interval-ms}")
    public void poll() {
        if (stopping || !processor.isEnabled()) {
            return;
        }
        try {
            jobs.recoverExpired();
        } catch (RuntimeException e) {
            log.warn("回收论文任务租约失败，等待下一轮扫描", e);
            return;
        }
        for (int i = 0; i < properties.getBatchSize() && !stopping; i++) {
            if (!permits.tryAcquire()) {
                break;
            }
            boolean submitted = false;
            PaperProcessingJob job = null;
            try {
                job = jobs.claimNext();
                if (job == null) {
                    break;
                }
                PaperProcessingJob claimed = job;
                executor.execute(() -> execute(claimed));
                submitted = true;
            } catch (RuntimeException e) {
                if (job != null) {
                    retrySafely(job, "DISPATCH_FAILED");
                }
                log.warn("论文任务领取或提交失败，等待下一轮扫描", e);
                break;
            } finally {
                if (!submitted) {
                    permits.release();
                }
            }
        }
    }

    /**
     * 执行已领取任务，失败时尝试安排重试，并在结束时释放并发许可
     */
    private void execute(PaperProcessingJob job) {
        try {
            log.debug("虚拟线程领取论文任务，jobId={}", job.getId());
            processor.process(job);
        } catch (RuntimeException e) {
            log.warn("论文任务执行失败，jobId={}", job.getId(), e);
            retrySafely(job, "EXECUTION_FAILED");
        } finally {
            permits.release();
        }
    }

    /**
     * 尝试将失败任务重新排队；更新失败时记录日志，等待后续租约回收
     */
    private void retrySafely(PaperProcessingJob job, String errorCode) {
        try {
            jobs.retryOrFail(job, errorCode);
        } catch (RuntimeException e) {
            log.warn("任务回队失败，将由租约回收，jobId={}", job.getId(), e);
        }
    }

    /**
     * 应用关闭时设置停止标记，使后续扫描不再领取新任务
     */
    @PreDestroy
    public void stop() {
        stopping = true;
    }
}
