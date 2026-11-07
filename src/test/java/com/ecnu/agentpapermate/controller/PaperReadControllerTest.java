package com.ecnu.agentpapermate.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecnu.agentpapermate.entity.dto.paper.PaperQueryRequest;
import com.ecnu.agentpapermate.entity.dto.sessionPaper.SessionPaperScopeRequest;
import com.ecnu.agentpapermate.entity.model.AppUser;
import com.ecnu.agentpapermate.entity.vo.paper.PaperDetailVO;
import com.ecnu.agentpapermate.entity.vo.paper.PaperProcessingJobVO;
import com.ecnu.agentpapermate.entity.vo.paper.PaperSummaryVO;
import com.ecnu.agentpapermate.entity.vo.sessionPaper.SessionPaperScopeVO;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.exception.GlobalExceptionHandler;
import com.ecnu.agentpapermate.service.*;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaperReadControllerTest {
    private final AppUserService users = mock(AppUserService.class);
    private final PaperService papers = mock(PaperService.class);
    private final PaperFileService files = mock(PaperFileService.class);
    private final PaperProcessingJobService jobs = mock(PaperProcessingJobService.class);
    private final SessionPaperService scopes = mock(SessionPaperService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new PaperController(papers, users, files),
                        new PaperProcessingJobController(jobs, users), new SessionPaperController(scopes, users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        AppUser user = new AppUser(); user.setId(42L);
        when(users.getLoginUser(any())).thenReturn(user);
    }

    @Test
    void allFiveEndpointsRequireLoginBeforeCallingBusinessServices() throws Exception {
        when(users.getLoginUser(any())).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        mvc.perform(post("/paper/list/page").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(40100));
        mvc.perform(get("/paper/get").param("id", "100")).andExpect(jsonPath("$.code").value(40100));
        mvc.perform(get("/paper/file").param("id", "100")).andExpect(jsonPath("$.code").value(40100));
        mvc.perform(get("/paperProcessingJob/get").param("id", "200")).andExpect(jsonPath("$.code").value(40100));
        mvc.perform(post("/sessionPaper/updateScope").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":100,\"paperIds\":[],\"scopeVersion\":1}"))
                .andExpect(jsonPath("$.code").value(40100));
        verifyNoInteractions(papers, files, jobs, scopes);
    }

    @Test
    void listBindsFiltersAndReturnsPaginationWithoutInternalFields() throws Exception {
        PaperSummaryVO paper = new PaperSummaryVO(); paper.setId(100L); paper.setTitle("论文");
        Page<PaperSummaryVO> page = new Page<>(2, 10, 11); page.setRecords(List.of(paper));
        when(papers.listPapers(any(), eq(42L))).thenReturn(page);
        mvc.perform(post("/paper/list/page").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageNum\":2,\"pageSize\":10,\"title\":\"论文\",\"authors\":\"Alice\",\"topic\":\"AI\",\"status\":\"ready\",\"userId\":99}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.records[0].title").value("论文"))
                .andExpect(jsonPath("$.data.records[0].fileUrl").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].activeIndexAttempt").doesNotExist());
        var query = ArgumentCaptor.forClass(PaperQueryRequest.class);
        verify(papers).listPapers(query.capture(), eq(42L));
        assertEquals(2, query.getValue().getPageNum()); assertEquals("Alice", query.getValue().getAuthors());
        assertEquals("AI", query.getValue().getTopic()); assertEquals("ready", query.getValue().getStatus());
    }

    @Test
    void detailAndJobExposeAvailabilityAndProgressThroughExistingResponseEnvelope() throws Exception {
        PaperDetailVO detail = new PaperDetailVO(); detail.setId(100L); detail.setCanAsk(true);
        detail.setAbstractText("摘要"); detail.setPageCount(8);
        when(papers.getPaper(100L, 42L)).thenReturn(detail);
        mvc.perform(get("/paper/get").param("id", "100"))
                .andExpect(jsonPath("$.data.canAsk").value(true)).andExpect(jsonPath("$.data.abstractText").value("摘要"))
                .andExpect(jsonPath("$.data.pageCount").value(8));
        PaperProcessingJobVO job = new PaperProcessingJobVO(); job.setId(200L); job.setStatus("running");
        job.setCurrentStep("embedding"); when(jobs.getJob(200L, 42L)).thenReturn(job);
        mvc.perform(get("/paperProcessingJob/get").param("id", "200"))
                .andExpect(jsonPath("$.data.status").value("running"))
                .andExpect(jsonPath("$.data.currentStep").value("embedding"))
                .andExpect(jsonPath("$.data.leaseToken").doesNotExist());
    }

    @Test
    void filePassesRangeAndStreamsWithoutJsonEnvelope() throws Exception {
        doAnswer(call -> {
            HttpServletResponse response = call.getArgument(4);
            response.setStatus(206); response.setContentType("application/pdf");
            response.setHeader("Content-Range", "bytes 0-4/10");
            response.getOutputStream().write("%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return null;
        }).when(files).writeFile(eq(100L), eq(42L), eq("bytes=0-4"), isNull(), any());
        mvc.perform(get("/paper/file").param("id", "100").header("Range", "bytes=0-4"))
                .andExpect(status().isPartialContent()).andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Range", "bytes 0-4/10")).andExpect(content().string("%PDF-"));
        verify(files).writeFile(eq(100L), eq(42L), eq("bytes=0-4"), isNull(), any());
    }

    @Test
    void scopeAcceptsStringIdsAndReturnsCompleteScopeOrVersionConflict() throws Exception {
        SessionPaperScopeVO result = new SessionPaperScopeVO(); result.setSessionId(100L);
        result.setPaperIds(List.of(200L)); result.setScopeVersion(2);
        when(scopes.updateScope(any(), eq(42L))).thenReturn(result);
        String body = "{\"sessionId\":\"100\",\"paperIds\":[\"200\"],\"scopeVersion\":1,\"userId\":99}";
        mvc.perform(post("/sessionPaper/updateScope").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.paperIds[0]").value(200))
                .andExpect(jsonPath("$.data.scopeVersion").value(2));
        var request = ArgumentCaptor.forClass(SessionPaperScopeRequest.class);
        verify(scopes).updateScope(request.capture(), eq(42L));
        assertEquals(List.of(200L), request.getValue().getPaperIds()); assertEquals(1, request.getValue().getScopeVersion());
        when(scopes.updateScope(any(), eq(42L))).thenThrow(new BusinessException(ErrorCode.CONFLICT_ERROR));
        mvc.perform(post("/sessionPaper/updateScope").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(40900));
    }
}
