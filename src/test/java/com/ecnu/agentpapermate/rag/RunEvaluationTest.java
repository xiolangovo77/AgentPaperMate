package com.ecnu.agentpapermate.rag;

import com.ecnu.agentpapermate.config.JsonConfig;
import com.ecnu.agentpapermate.controller.AgentRunController;
import com.ecnu.agentpapermate.entity.dto.run.RunCreateRequest;
import com.ecnu.agentpapermate.entity.model.*;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.exception.GlobalExceptionHandler;
import com.ecnu.agentpapermate.mapper.*;
import com.ecnu.agentpapermate.rag.model.Evidence;
import com.ecnu.agentpapermate.service.AppUserService;
import com.ecnu.agentpapermate.service.ChatSessionService;
import com.ecnu.agentpapermate.service.impl.AgentRunServiceImpl;
import com.ecnu.agentpapermate.service.run.rag.RunEventStream;
import com.ecnu.agentpapermate.service.run.rag.RunQueries;
import com.ecnu.agentpapermate.service.run.rag.RunStore;
import com.ecnu.agentpapermate.support.json.RagJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 贯通 HTTP、登录身份、业务转换及真实 JSON 配置，数据库使用替身。 */
class RunEvaluationTest {
    private static final long RUN_ID = 9007199254740993L;
    private static final long PAPER_ID = 9007199254740995L;
    private final RunStore store = mock(RunStore.class);
    private final AppUserService users = mock(AppUserService.class);
    private final ChatMessageMapper messages = mock(ChatMessageMapper.class);
    private final MessageCitationMapper citations = mock(MessageCitationMapper.class);
    private final ObjectMapper mapper = new JsonConfig().jacksonObjectMapper(new Jackson2ObjectMapperBuilder());
    private final RagJson json = new RagJson(mapper);
    private final AgentRun run = new AgentRun();
    private RunQueries queries;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queries = new RunQueries(store, mock(RunEventMapper.class), messages, citations,
                mock(ChatSessionService.class), json);
        var service = new AgentRunServiceImpl(store, queries);
        mvc = MockMvcBuilders.standaloneSetup(new AgentRunController(service, users, mock(RunEventStream.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        var user = new AppUser(); user.setId(20L);
        when(users.getLoginUser(any())).thenReturn(user);
        run.setId(RUN_ID); run.setStatus("completed"); run.setUserQuery("question");
        run.setStartTime(LocalDateTime.of(2026, 11, 8, 10, 0));
        run.setCreateTime(run.getStartTime().minusMinutes(5));
        run.setEndTime(run.getStartTime().plusSeconds(18));
        run.setInputTokens(1200L); run.setOutputTokens(310L);
        run.setModelConfig("{\"internal\":\"not for export\"}");
        run.setRetrievalSnapshot("[\"c1\",\"c2\",\"c3\"]");
        run.setEvidenceSnapshot(json.write(List.of(
                new Evidence("E1", PAPER_ID, "Paper", 1, "paper_test", "c1", 5, 5, "actual text 1", "file1"),
                new Evidence("E2", PAPER_ID, "Paper", 1, "paper_test", "c2", 6, 7, "actual text 2", "file1"))));
        when(store.owned(RUN_ID, 20L)).thenReturn(run);
        var answer = new ChatMessage(); answer.setId(50L); answer.setContent("answer [E1]");
        when(messages.selectOne(any())).thenReturn(answer);
        var cite = new MessageCitation(); cite.setEvidenceId("E1"); cite.setPaperId(PAPER_ID);
        cite.setChunkId("c1"); cite.setPageStart(5); cite.setPageEnd(5);
        when(citations.selectList(any())).thenReturn(List.of(cite));
    }

    @Test
    void createAgentUsesExistingAuthenticatedEndpointAndBindsExecutorType() throws Exception {
        when(store.create(any(), eq(20L))).thenReturn(RUN_ID);
        mvc.perform(post("/agentRun/add").contentType("application/json").content("""
                {"sessionId":"10","requestKey":"agent-request-0001","question":"实验结果？",
                 "executorType":"agent","userId":"99"}
                """))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(Long.toString(RUN_ID)));
        var captured = org.mockito.ArgumentCaptor.forClass(RunCreateRequest.class);
        verify(store).create(captured.capture(), eq(20L));
        assertEquals("agent", captured.getValue().getExecutorType());
        assertEquals(10L, captured.getValue().getSessionId());
    }

    @Test
    void exportsOrderedCandidatesExactContextActualCitationsAndNumericMetrics() throws Exception {
        var result = mvc.perform(get("/agentRun/evaluation").param("id", Long.toString(RUN_ID)).param("userId", "99"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        JsonNode data = mapper.readTree(result.getResponse().getContentAsByteArray()).get("data");
        assertTrue(data.get("run_id").isTextual());
        assertEquals(Long.toString(RUN_ID), data.get("run_id").asText());
        assertEquals(json.tree("[\"c1\",\"c2\",\"c3\"]"), data.get("retrieved_chunk_ids"));
        assertEquals(json.tree("[\"c1\",\"c2\"]"), data.get("final_evidence_chunk_ids"));
        assertEquals(2, data.get("final_evidence").size());
        assertEquals("actual text 2", data.at("/final_evidence/1/text").asText());
        assertEquals(Long.toString(PAPER_ID), data.at("/final_evidence/0/paper_id").asText());
        assertTrue(data.at("/final_evidence/0/paper_id").isTextual());
        assertEquals("answer [E1]", data.get("answer").asText());
        assertEquals(1, data.get("citations").size());
        assertEquals("E1", data.at("/citations/0/evidence_id").asText());
        assertEquals("c1", data.at("/citations/0/chunk_id").asText());
        assertEquals(5, data.at("/citations/0/page_start").asInt());
        for (String field : List.of("latency_ms", "input_tokens", "output_tokens")) assertTrue(data.get(field).isIntegralNumber(), field);
        assertEquals(18000, data.get("latency_ms").asLong());
        assertEquals(1200, data.get("input_tokens").asLong());
        assertEquals(310, data.get("output_tokens").asLong());
        assertFalse(data.has("model_config")); assertFalse(data.has("papers_snapshot"));
        assertFalse(data.has("judge_answer_score"));
        verify(store).owned(RUN_ID, 20L);
        assertTrue(mockingDetails(store).getInvocations().stream().allMatch(call -> call.getMethod().getName().equals("owned")));
    }

    @Test
    void unauthenticatedRequestCannotReadAnyRun() throws Exception {
        when(users.getLoginUser(any())).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        mvc.perform(get("/agentRun/evaluation").param("id", Long.toString(RUN_ID)))
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN_ERROR.getCode()));
        verifyNoInteractions(store, messages, citations);
    }

    @Test
    void ownershipIsCheckedBeforeReadingSnapshotsOrMessages() throws Exception {
        run.setRetrievalSnapshot("invalid JSON");
        when(store.owned(RUN_ID, 20L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR));
        mvc.perform(get("/agentRun/evaluation").param("id", Long.toString(RUN_ID)))
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND_ERROR.getCode()));
        verifyNoInteractions(messages, citations);
    }

    @Test
    void legacyMissingSnapshotsStayNullAndAreNotReconstructedFromCitations() {
        run.setRetrievalSnapshot(null); run.setEvidenceSnapshot(null);
        var result = queries.evaluation(RUN_ID, 20L);
        assertNull(result.retrievedChunkIds()); assertNull(result.finalEvidence());
        assertNull(result.finalEvidenceChunkIds());
        assertEquals(1, result.citations().size());
    }

    @Test
    void successfulEmptyRetrievalAndNoModelCallAreValidZeroUsageResults() throws Exception {
        run.setRetrievalSnapshot("[]"); run.setEvidenceSnapshot("[]");
        run.setInputTokens(0L); run.setOutputTokens(0L);
        when(citations.selectList(any())).thenReturn(List.of());
        var result = queries.evaluation(RUN_ID, 20L);
        assertEquals(List.of(), result.retrievedChunkIds()); assertEquals(List.of(), result.finalEvidence());
        assertEquals(List.of(), result.finalEvidenceChunkIds()); assertEquals(List.of(), result.citations());
        assertEquals(0L, result.inputTokens());
        var data = mapper.valueToTree(result);
        assertTrue(data.get("final_evidence").isArray());
        assertTrue(data.get("input_tokens").isIntegralNumber());
    }

    @Test
    void failedRunPreservesCapturedEvidenceAndReportsUnknownUsage() {
        run.setStatus("failed"); run.setInputTokens(0L); run.setOutputTokens(0L);
        run.setErrorCode("EXECUTION_FAILED"); run.setErrorMessage("generation failed");
        var result = queries.evaluation(RUN_ID, 20L);
        assertEquals(3, result.retrievedChunkIds().size()); assertEquals(2, result.finalEvidence().size());
        assertEquals(18000L, result.latencyMs());
        assertNull(result.answer()); assertNull(result.citations());
        assertNull(result.inputTokens()); assertNull(result.outputTokens());
        assertEquals("EXECUTION_FAILED", result.errorCode());
        verifyNoInteractions(messages, citations);
    }

    @Test
    void runningAndNeverStartedTasksDoNotInventExecutionDuration() {
        run.setStatus("running"); run.setEndTime(null);
        assertNull(queries.evaluation(RUN_ID, 20L).latencyMs());
        run.setStatus("timeout"); run.setStartTime(null);
        run.setEndTime(LocalDateTime.of(2026, 11, 8, 10, 0));
        assertNull(queries.evaluation(RUN_ID, 20L).latencyMs());
        verifyNoInteractions(messages, citations);
    }

    @Test
    void malformedCandidateSnapshotsNeverBecomeEmptySuccessfulRetrievals() {
        for (String value : List.of("{}", "null", "[123]", "[\"\"]", "[\"c1\",\"c1\"]", "[] {}")) {
            run.setRetrievalSnapshot(value);
            assertThrows(RuntimeException.class, () -> queries.evaluation(RUN_ID, 20L), value);
        }
        verifyNoInteractions(messages, citations);
    }

    @Test
    void completedRunWithoutAnAnswerIsAnIntegrityError() {
        when(messages.selectOne(any())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> queries.evaluation(RUN_ID, 20L));
        verifyNoInteractions(citations);
    }
}
