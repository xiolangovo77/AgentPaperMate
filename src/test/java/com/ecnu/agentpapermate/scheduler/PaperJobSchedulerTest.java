package com.ecnu.agentpapermate.scheduler;

import com.ecnu.agentpapermate.config.paper.PaperJobSchedulerConfiguration;
import com.ecnu.agentpapermate.config.paper.PaperJobSchedulerProperties;
import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.ecnu.agentpapermate.service.PaperJobClaimService;
import com.ecnu.agentpapermate.service.PaperJobProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaperJobSchedulerTest {
    private final PaperJobClaimService jobs = mock(PaperJobClaimService.class);
    private final PaperJobProcessor processor = mock(PaperJobProcessor.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final PaperJobSchedulerProperties properties = new PaperJobSchedulerProperties();
    private final PaperProcessingJob job = new PaperProcessingJob();
    private PaperJobScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties.setMaxConcurrentJobs(1);
        when(processor.isEnabled()).thenReturn(true);
        job.setId(1L);
        job.setLeaseToken("lease-1");
        scheduler = new PaperJobScheduler(jobs, processor, properties, executor);
    }

    @Test
    void doesNotClaimBeyondCapacityAndReleasesAfterExecution() {
        when(jobs.claimNext()).thenReturn(job);
        scheduler.poll();
        scheduler.poll();
        verify(jobs, times(1)).claimNext();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();
        verify(processor).process(job);
        verify(jobs, never()).retryOrFail(any(), any());
        scheduler.poll();
        verify(jobs, times(2)).claimNext();
    }

    @Test
    void noWorkAndClaimFailureDoNotLeakPermits() {
        when(jobs.claimNext()).thenReturn(null).thenThrow(new IllegalStateException("database unavailable")).thenReturn(job);
        scheduler.poll();
        scheduler.poll();
        scheduler.poll();
        verify(jobs, times(3)).claimNext();
        verify(executor).execute(any());
    }

    @Test
    void rejectionRequeuesAndReleasesEvenWhenDatabaseWriteFails() {
        when(jobs.claimNext()).thenReturn(job);
        doThrow(new RejectedExecutionException()).when(executor).execute(any());
        doThrow(new IllegalStateException("database unavailable")).when(jobs).retryOrFail(job, "DISPATCH_FAILED");
        scheduler.poll();
        scheduler.poll();
        verify(jobs, times(2)).claimNext();
        verify(jobs, times(2)).retryOrFail(job, "DISPATCH_FAILED");
    }

    @Test
    void executionFailureRequeuesAndReleasesPermit() {
        when(jobs.claimNext()).thenReturn(job);
        doThrow(new IllegalStateException("processor failure")).when(processor).process(job);
        scheduler.poll();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();
        verify(jobs).retryOrFail(job, "EXECUTION_FAILED");
        scheduler.poll();
        verify(jobs, times(2)).claimNext();
    }

    @Test
    void batchLimitBoundsFastReturningTasks() {
        properties.setBatchSize(3);
        when(jobs.claimNext()).thenReturn(job);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(executor).execute(any());
        scheduler.poll();
        verify(jobs, times(3)).claimNext();
    }

    @Test
    void stoppedSchedulerDoesNotTouchDatabase() {
        scheduler.stop();
        scheduler.poll();
        verifyNoInteractions(jobs, executor);
    }

    @Test
    void disabledPipelineDoesNotClaimOrRecover() {
        when(processor.isEnabled()).thenReturn(false);
        scheduler.poll();
        verifyNoInteractions(jobs, executor);
    }

    @Test
    void recoveryFailureWaitsForNextPoll() {
        when(jobs.recoverExpired()).thenThrow(new IllegalStateException("database unavailable")).thenReturn(0);
        scheduler.poll();
        verify(jobs, never()).claimNext();
        scheduler.poll();
        verify(jobs).claimNext();
    }

    @Test
    void productionExecutorRunsProcessorOnVirtualThread() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean virtual = new AtomicBoolean();
        when(jobs.claimNext()).thenReturn(job);
        doAnswer(call -> {
            virtual.set(Thread.currentThread().isVirtual());
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(processor).process(job);
        ExecutorService actual = new PaperJobSchedulerConfiguration().paperJobExecutor();
        try {
            PaperJobScheduler realScheduler = new PaperJobScheduler(jobs, processor, properties, actual);
            realScheduler.poll();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            realScheduler.poll();
            verify(jobs, times(1)).claimNext();
            assertTrue(virtual.get());
        } finally {
            release.countDown();
            actual.shutdown();
            assertTrue(actual.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
