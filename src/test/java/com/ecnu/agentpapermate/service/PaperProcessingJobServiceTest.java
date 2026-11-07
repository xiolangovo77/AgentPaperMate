package com.ecnu.agentpapermate.service;

import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.mapper.PaperProcessingJobMapper;
import com.ecnu.agentpapermate.service.impl.PaperProcessingJobServiceImpl;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaperProcessingJobServiceTest {
    private final PaperProcessingJobMapper jobs = mock(PaperProcessingJobMapper.class);
    private final PaperService papers = mock(PaperService.class);
    private final PaperProcessingJobService service = new PaperProcessingJobServiceImpl(jobs, papers);

    @Test
    void readsProgressAndFailureOnlyAfterCheckingPaperOwner() {
        PaperProcessingJob job = new PaperProcessingJob(); job.setId(200L); job.setPaperId(100L);
        job.setStatus("failed"); job.setCurrentStep("parsing"); job.setErrorCode("PARSE_FAILED");
        job.setErrorMessage("解析失败"); job.setUpdateTime(LocalDateTime.of(2026, 11, 7, 12, 0));
        job.setTotalChunks(10); job.setIndexedChunks(4); job.setRetryCount(2);
        when(jobs.selectById(200L)).thenReturn(job);
        var result = service.getJob(200L, 42L);
        assertEquals(200L, result.getId()); assertEquals(100L, result.getPaperId());
        assertEquals("failed", result.getStatus()); assertEquals("parsing", result.getCurrentStep());
        assertEquals("PARSE_FAILED", result.getErrorCode()); assertEquals("解析失败", result.getErrorMessage());
        assertEquals(job.getUpdateTime(), result.getUpdateTime()); assertEquals(4, result.getIndexedChunks());
        verify(papers).requireOwnedPaper(100L, 42L);
        when(papers.requireOwnedPaper(100L, 99L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR));
        assertThrows(BusinessException.class, () -> service.getJob(200L, 99L));
    }

    @Test
    void rejectsInvalidOrMissingJobsAndUnauthenticatedUsers() {
        assertThrows(BusinessException.class, () -> service.getJob(0L, 42L));
        assertThrows(BusinessException.class, () -> service.getJob(null, 42L));
        assertThrows(BusinessException.class, () -> service.getJob(200L, null));
        verifyNoInteractions(jobs, papers);
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> service.getJob(999L, 42L)).getCode());
        verifyNoInteractions(papers);
    }
}
