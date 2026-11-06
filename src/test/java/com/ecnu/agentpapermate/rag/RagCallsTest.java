package com.ecnu.agentpapermate.rag;

import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.entity.model.AgentRun;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RagCallsTest {
    @Test
    void totalRunDeadlineBoundsRequestAndCancelsUnderlyingWait() throws Exception {
        Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));
        AgentRun run = new AgentRun(); run.setDeadline(LocalDateTime.now(clock).plusNanos(200_000_000));
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            var calls = new RagCalls(executor, new RagProperties(), clock);
            assertThrows(RagCalls.RequestTimeout.class, () -> calls.call(run, () -> {
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { interrupted.countDown(); }
                return "迟到答案";
            }));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void alreadyExpiredRunDoesNotStartExternalCall() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Clock clock = Clock.systemDefaultZone();
            AgentRun run = new AgentRun(); run.setDeadline(LocalDateTime.now(clock).minusSeconds(1));
            AtomicBoolean invoked = new AtomicBoolean();
            assertThrows(RagCalls.RequestTimeout.class, () -> new RagCalls(executor, new RagProperties(), clock)
                    .call(run, () -> { invoked.set(true); return "invalid"; }));
            assertFalse(invoked.get());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void wrappedSocketTimeoutIsReportedAsTimeout() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Clock clock = Clock.systemDefaultZone();
            AgentRun run = new AgentRun(); run.setDeadline(LocalDateTime.now(clock).plusSeconds(10));
            assertThrows(RagCalls.RequestTimeout.class, () -> new RagCalls(executor, new RagProperties(), clock)
                    .call(run, () -> { throw new IllegalStateException(new java.net.SocketTimeoutException()); }));
        } finally { executor.shutdownNow(); }
    }
}
