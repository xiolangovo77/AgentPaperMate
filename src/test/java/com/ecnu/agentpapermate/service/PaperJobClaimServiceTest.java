package com.ecnu.agentpapermate.service;

import com.ecnu.agentpapermate.config.paper.PaperJobSchedulerProperties;
import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.ecnu.agentpapermate.ingestion.model.ParsedPaper;
import com.ecnu.agentpapermate.mapper.PaperProcessingJobMapper;
import com.ecnu.agentpapermate.service.run.paper.PaperJobClaimService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaperJobClaimServiceTest {
    private final PaperProcessingJobMapper mapper = mock(PaperProcessingJobMapper.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus status = new SimpleTransactionStatus();
    private final PaperJobSchedulerProperties properties = new PaperJobSchedulerProperties();
    private final PaperProcessingJob job = new PaperProcessingJob();
    private PaperJobClaimService service;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(status);
        service = new PaperJobClaimService(mapper, properties, transactions);
        job.setId(1L);
        job.setLeaseToken("old-lease");
    }

    @Test
    void claimCommitsBeforeReturningTaskAndUsesFreshLease() {
        when(mapper.selectDueForUpdate()).thenReturn(job);
        when(mapper.claim(eq(1L), anyString(), eq(120))).thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(job);
        assertSame(job, service.claimNext());
        var order = inOrder(transactions, mapper);
        order.verify(transactions).getTransaction(argThat(def ->
                def.getIsolationLevel() == TransactionDefinition.ISOLATION_READ_COMMITTED
                        && def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        order.verify(mapper).selectDueForUpdate();
        order.verify(mapper).claim(eq(1L), argThat(token -> !token.equals("old-lease") && token.length() == 36), eq(120));
        order.verify(mapper).selectById(1L);
        order.verify(transactions).commit(status);
    }

    @Test
    void failedClaimRollsBackInsteadOfDispatching() {
        when(mapper.selectDueForUpdate()).thenReturn(job);
        assertThrows(IllegalStateException.class, service::claimNext);
        verify(transactions).rollback(status);
        verify(mapper, never()).selectById(anyLong());
    }

    @Test
    void recoveryIsBoundedAndChecksExpiredLeaseToken() {
        when(mapper.selectExpiredForUpdate(16)).thenReturn(List.of(job));
        when(mapper.retryOrFail(1L, "old-lease", true, 30, "LEASE_EXPIRED")).thenReturn(1);
        assertEquals(1, service.recoverExpired());
        verify(transactions).commit(status);
    }

    @Test
    void failureWritesOnlyWithCurrentLease() {
        service.retryOrFail(job, "EXECUTION_FAILED");
        verify(mapper).retryOrFail(1L, "old-lease", false, 30, "EXECUTION_FAILED");
    }

    @Test
    void parsedMetadataCommitsBeforeEmbeddingAndStaleLeaseRollsBack() {
        var parsed = new ParsedPaper("Title", "Author", "Abstract", null, List.of());
        when(mapper.updateParsedMetadata(job, parsed)).thenReturn(2, 0);
        service.updateParsedMetadata(job, parsed);
        verify(transactions).commit(status);
        assertThrows(IllegalStateException.class, () -> service.updateParsedMetadata(job, parsed));
        verify(transactions).rollback(status);
        verify(mapper, never()).complete(any(), any(), anyInt(), anyInt());
    }
}
