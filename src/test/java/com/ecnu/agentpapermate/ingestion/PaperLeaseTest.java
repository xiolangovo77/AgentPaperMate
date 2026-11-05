package com.ecnu.agentpapermate.ingestion;

import com.ecnu.agentpapermate.config.paper.PaperIngestionProperties;
import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.ecnu.agentpapermate.service.PaperJobClaimService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaperLeaseTest {
    @Test
    void failedHeartbeatInterruptsWorkerAndPreventsPublication() {
        var jobs = mock(PaperJobClaimService.class);
        var executor = mock(ScheduledExecutorService.class);
        var future = mock(ScheduledFuture.class);
        doReturn(future).when(executor).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
        when(jobs.renew(any(), anyInt())).thenReturn(true, false);
        try (var lease = new PaperLease(new PaperProcessingJob(), jobs, new PaperIngestionProperties(), executor)) {
            var heartbeat = ArgumentCaptor.forClass(Runnable.class);
            verify(executor).scheduleWithFixedDelay(heartbeat.capture(), anyLong(), anyLong(), any());
            heartbeat.getValue().run();
            assertTrue(Thread.currentThread().isInterrupted());
            assertThrows(IllegalStateException.class, lease::check);
            heartbeat.getValue().run();
            verify(jobs, times(2)).renew(any(), anyInt());
        } finally {
            Thread.interrupted();
        }
        verify(future).cancel(false);
    }
}
