package com.ecnu.agentpapermate.service;

import com.ecnu.agentpapermate.config.paper.PaperProcessingProperties;
import com.ecnu.agentpapermate.entity.dto.paper.PaperAddRequest;
import com.ecnu.agentpapermate.entity.model.Paper;
import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.ecnu.agentpapermate.entity.vo.paper.PaperAddVO;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.mange.CosManager;
import com.ecnu.agentpapermate.mapper.PaperMapper;
import com.ecnu.agentpapermate.mapper.PaperProcessingJobMapper;
import com.ecnu.agentpapermate.service.impl.PaperServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaperServiceTest {
    private final PaperMapper papers = mock(PaperMapper.class);
    private final PaperProcessingJobMapper jobs = mock(PaperProcessingJobMapper.class);
    private final CosManager cos = mock(CosManager.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus txStatus = new SimpleTransactionStatus();
    private final PaperProcessingProperties config = new PaperProcessingProperties();
    private final ObjectMapper json = new ObjectMapper();
    private PaperServiceImpl service;
    private PaperAddRequest request;

    @BeforeEach
    void setUp() {
        config.setParserVersion("parser-v1");
        config.setChunkingVersion("chunk-v1");
        config.setEmbeddingModel("test-model");
        config.setEmbeddingVersion("test-v1");
        config.setEmbeddingDimension(1024);
        config.setEsIndexName("paper_chunks_test_v1");
        when(transactions.getTransaction(any())).thenReturn(txStatus);
        CosManager.PaperFileInfo file = new CosManager.PaperFileInfo();
        file.setOriginalFilename("原始论文.pdf");
        file.setFileSize(123L);
        file.setFileHash("a".repeat(64));
        when(cos.readPaper(anyString(), eq(42L))).thenReturn(file);
        when(papers.insert(any(Paper.class))).thenAnswer(invocation -> {
            invocation.<Paper>getArgument(0).setId(100L);
            return 1;
        });
        when(jobs.insert(any(PaperProcessingJob.class))).thenAnswer(invocation -> {
            invocation.<PaperProcessingJob>getArgument(0).setId(200L);
            return 1;
        });
        service = new PaperServiceImpl(papers, jobs, cos, config, json,
                new TransactionTemplate(transactions));
        request = new PaperAddRequest();
        request.setFileUrl("https://files.example.com/paper.pdf");
    }

    @Test
    void savesTwoLinkedRecordsWithTrustedMetadataAndConfigSnapshot() throws Exception {
        PaperAddVO result = service.addPaper(request, 42L);
        assertEquals(100L, result.getPaperId());
        assertEquals(200L, result.getJobId());
        assertEquals("uploaded", result.getStatus());
        ArgumentCaptor<Paper> paper = ArgumentCaptor.forClass(Paper.class);
        ArgumentCaptor<PaperProcessingJob> job = ArgumentCaptor.forClass(PaperProcessingJob.class);
        var order = inOrder(cos, transactions, papers, jobs);
        order.verify(cos).readPaper(request.getFileUrl(), 42L);
        order.verify(transactions).getTransaction(any());
        order.verify(papers).insert(paper.capture());
        order.verify(jobs).insert(job.capture());
        order.verify(transactions).commit(txStatus);
        assertEquals("原始论文.pdf", paper.getValue().getTitle());
        assertEquals(123L, paper.getValue().getFileSize());
        assertEquals("a".repeat(64), paper.getValue().getFileHash());
        assertEquals(42L, paper.getValue().getUserId());
        assertNull(paper.getValue().getActiveIndexVersion());
        assertEquals(100L, job.getValue().getPaperId());
        assertEquals(1, job.getValue().getIndexVersion());
        assertEquals("pending", job.getValue().getStatus());
        assertEquals("queued", job.getValue().getCurrentStep());
        assertEquals(0, job.getValue().getTotalChunks());
        assertEquals(1024, job.getValue().getEmbeddingDimension());
        assertEquals("test-model", job.getValue().getEmbeddingModel());
        assertEquals("tokens", json.readTree(job.getValue().getChunkingConfig()).get("unit").asText());
    }

    @Test
    void jobInsertFailureRollsBack() {
        when(jobs.insert(any(PaperProcessingJob.class))).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.addPaper(request, 42L));
        verify(transactions).rollback(txStatus);
    }

    @Test
    void cosFailureNeverOpensDatabaseTransaction() {
        when(cos.readPaper(anyString(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.OPERATION_ERROR));
        assertThrows(BusinessException.class, () -> service.addPaper(request, 42L));
        verifyNoInteractions(transactions, papers, jobs);
    }

    @Test
    void repeatedSaveReturnsExistingPaperWithoutNewTask() {
        when(papers.selectOne(any())).thenReturn(existingPaper());
        when(jobs.selectOne(any())).thenReturn(existingJob());
        PaperAddVO result = service.addPaper(request, 42L);
        assertEquals(100L, result.getPaperId());
        assertEquals(200L, result.getJobId());
        assertEquals("ready", result.getStatus());
        verify(papers, never()).insert(any(Paper.class));
        verify(jobs, never()).insert(any(PaperProcessingJob.class));
    }

    @Test
    void concurrentDuplicateIsReadAfterRollback() {
        when(papers.selectOne(any())).thenReturn(null, existingPaper());
        when(papers.insert(any(Paper.class))).thenThrow(new DuplicateKeyException("duplicate hash"));
        when(jobs.selectOne(any())).thenReturn(existingJob());
        PaperAddVO result = service.addPaper(request, 42L);
        assertEquals(100L, result.getPaperId());
        assertEquals(200L, result.getJobId());
        assertEquals("ready", result.getStatus());
        var order = inOrder(transactions, papers);
        order.verify(papers).insert(any(Paper.class));
        order.verify(transactions).rollback(txStatus);
        order.verify(papers).selectOne(any());
    }

    @Test
    void acceptsSnakeCaseFileUrlWithoutClientMetadata() throws Exception {
        PaperAddRequest parsed = json.readValue("{\"file_url\":\"https://example.com/file.pdf\"}", PaperAddRequest.class);
        assertEquals("https://example.com/file.pdf", parsed.getFileUrl());
    }

    private Paper existingPaper() {
        Paper paper = new Paper();
        paper.setId(100L);
        paper.setStatus("ready");
        return paper;
    }

    private PaperProcessingJob existingJob() {
        PaperProcessingJob job = new PaperProcessingJob();
        job.setId(200L);
        return job;
    }
}
