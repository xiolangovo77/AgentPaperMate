package com.ecnu.agentpapermate.scheduler;

import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.entity.model.AgentRun;
import com.ecnu.agentpapermate.mapper.AgentRunMapper;
import com.ecnu.agentpapermate.service.run.RunDispatcher;
import com.ecnu.agentpapermate.service.run.rag.RunStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunSchedulerTest {
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final RunStore store = mock(RunStore.class);
    private final RunDispatcher dispatcher = mock(RunDispatcher.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final RagProperties properties = new RagProperties();
    private final AgentRun run = new AgentRun();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-06T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private RunScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true); properties.setMaxConcurrentRuns(1); run.setId(1L);
        scheduler = new RunScheduler(runs, store, dispatcher, properties, executor, clock);
        when(runs.selectExpiredCandidates(any(), any(), anyInt())).thenReturn(List.of());
        when(runs.selectPendingCandidates(1)).thenReturn(List.of(run));
        when(store.start(1L)).thenReturn(run);
    }

    @Test
    void executionIsBoundedAndPermitReleasedAfterTaskFinishes() {
        scheduler.poll(); scheduler.poll();
        verify(store, times(1)).start(1L);
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();
        verify(dispatcher).dispatch(run);
        scheduler.poll();
        verify(store, times(2)).start(1L);
    }

    @Test
    void submissionFailureMarksRunFailedAndDoesNotLeakCapacity() {
        doThrow(new RejectedExecutionException()).when(executor).execute(any());
        scheduler.poll(); scheduler.poll();
        verify(store, times(2)).fail(1L, "DISPATCH_FAILED", "任务提交执行失败", false);
        verify(store, times(2)).start(1L);
    }

    @Test
    void timeoutRecoveryStillRunsWhenNewQuestionsAreDisabled() {
        properties.setEnabled(false);
        when(runs.selectExpiredCandidates(any(), any(), anyInt())).thenReturn(List.of(run));
        scheduler.poll();
        LocalDateTime now = LocalDateTime.now(clock);
        verify(runs).selectExpiredCandidates(now, now.minusSeconds(properties.getQueueTimeoutSeconds()), 100);
        verify(runs, never()).selectPendingCandidates(anyInt());
        verify(store).expire(1L);
        verify(store, never()).start(anyLong());
        verifyNoInteractions(executor);
    }
}
