package com.ecnu.agentpapermate.rag;

import com.ecnu.agentpapermate.rag.model.Answer;
import com.ecnu.agentpapermate.rag.model.Evidence;
import com.ecnu.agentpapermate.rag.model.PaperScope;
import com.ecnu.agentpapermate.rag.model.RetrievalResult;
import com.ecnu.agentpapermate.rag.model.RetrievalCandidates;
import com.ecnu.agentpapermate.rag.model.EvidenceCandidate;
import com.ecnu.agentpapermate.config.paper.PaperProcessingProperties;
import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.entity.dto.run.RunCreateRequest;
import com.ecnu.agentpapermate.entity.model.*;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.mapper.*;
import com.ecnu.agentpapermate.service.ChatSessionService;
import com.ecnu.agentpapermate.service.run.rag.RunStore;
import com.ecnu.agentpapermate.support.json.RagJson;
import com.ecnu.agentpapermate.validator.AnswerValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunStoreTest {
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final ChatSessionMapper sessions = mock(ChatSessionMapper.class);
    private final ChatSessionService sessionService = mock(ChatSessionService.class);
    private final SessionPaperMapper relations = mock(SessionPaperMapper.class);
    private final PaperMapper papers = mock(PaperMapper.class);
    private final PaperProcessingJobMapper jobs = mock(PaperProcessingJobMapper.class);
    private final ChatMessageMapper messages = mock(ChatMessageMapper.class);
    private final MessageCitationMapper citations = mock(MessageCitationMapper.class);
    private final RunEventMapper events = mock(RunEventMapper.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    private final RagJson json = new RagJson(new ObjectMapper());
    private final RagProperties properties = new RagProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-06T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final ChatSession session = new ChatSession();
    private final AgentRun run = new AgentRun();
    private RunStore store;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        var processing = new PaperProcessingProperties();
        processing.setEmbeddingModel("embedding"); processing.setEmbeddingVersion("v1"); processing.setEmbeddingDimension(3);
        var target = new RunStore(runs, sessions, sessionService, relations, papers, jobs, messages, citations,
                events, json, properties, processing, clock);
        when(transactions.getTransaction(any())).thenReturn(transaction);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        store = (RunStore) proxy.getProxy();
        session.setId(10L); session.setUserId(20L); session.setScopeVersion(2);
        when(sessionService.lockSession(10L, 20L)).thenReturn(session);
        when(sessionService.getSession(10L, 20L)).thenReturn(session);
        var relation = new SessionPaper(); relation.setPaperId(30L);
        when(relations.selectActiveForRun(10L, properties.getMaxPapers() + 1)).thenReturn(List.of(relation));
        var paper = new Paper(); paper.setId(30L); paper.setUserId(20L); paper.setTitle("Paper");
        paper.setActiveIndexVersion(1); paper.setActiveIndexAttempt("attempt"); paper.setFileUrl("https://files.example/paper.pdf");
        when(papers.selectOne(any())).thenReturn(paper);
        when(papers.selectCount(any())).thenReturn(1L);
        var job = new PaperProcessingJob(); job.setEsIndexName("paper_test"); job.setEmbeddingModel("embedding");
        job.setEmbeddingVersion("v1"); job.setEmbeddingDimension(3);
        when(jobs.selectOne(any())).thenReturn(job);
        when(runs.insert(any(AgentRun.class))).thenAnswer(call -> { call.<AgentRun>getArgument(0).setId(40L); return 1; });
        when(messages.insert(any(ChatMessage.class))).thenAnswer(call -> { call.<ChatMessage>getArgument(0).setId(50L); return 1; });
        when(citations.insert(any(MessageCitation.class))).thenReturn(1);
        when(events.insert(any(RunEvent.class))).thenReturn(1);
        when(runs.updateById(any(AgentRun.class))).thenReturn(1);
        when(sessions.assignActiveRun(10L, 40L)).thenReturn(1);
        when(sessions.releaseActiveRun(10L, 40L)).thenReturn(1);
        run.setId(40L); run.setSessionId(10L); run.setUserId(20L); run.setStatus("pending"); run.setEventSeq(0L);
        run.setExecutorType("rag");
        run.setCreateTime(LocalDateTime.now(clock)); run.setStepCount(0); run.setToolCallCount(0);
        run.setPapersSnapshot(json.write(List.of(scope())));
        run.setEvidenceSnapshot(json.write(List.of(evidence())));
        when(runs.selectById(40L)).thenReturn(run);
        when(runs.selectForUpdate(40L)).thenReturn(run);
    }

    @Test
    void createsRunUserMessageAndSessionOccupationInSingleTransaction() {
        assertEquals(40L, store.create(request(), 20L));
        var runCapture = ArgumentCaptor.forClass(AgentRun.class);
        var message = ArgumentCaptor.forClass(ChatMessage.class);
        var order = inOrder(transactions, sessionService, runs, messages, sessions);
        order.verify(transactions).getTransaction(any());
        order.verify(sessionService).lockSession(10L, 20L);
        order.verify(runs).insert(runCapture.capture());
        order.verify(messages).insert(message.capture());
        order.verify(sessions).assignActiveRun(10L, 40L);
        order.verify(transactions).commit(transaction);
        assertEquals("pending", runCapture.getValue().getStatus());
        assertEquals("rag", runCapture.getValue().getExecutorType());
        assertEquals("qwen3.7-plus", runCapture.getValue().getModelName());
        assertEquals(12, json.tree(runCapture.getValue().getModelConfig()).path("maxToolCalls").asInt());
        assertEquals(20, json.tree(runCapture.getValue().getModelConfig()).path("toolTimeoutSeconds").asInt());
        assertEquals(2, runCapture.getValue().getScopeVersion());
        assertTrue(runCapture.getValue().getPapersSnapshot().contains("attempt"));
        assertEquals("user", message.getValue().getRole());
        assertEquals(40L, message.getValue().getRunId());
        verifyNoInteractions(events);
    }

    @Test
    void agentTypeIsValidatedAndParticipatesInIdempotency() {
        var request = request(); request.setExecutorType("agent");
        assertEquals(40L, store.create(request, 20L));
        var captured = ArgumentCaptor.forClass(AgentRun.class);
        verify(runs).insert(captured.capture());
        assertEquals("agent", captured.getValue().getExecutorType());
        assertEquals(24000, json.tree(captured.getValue().getModelConfig()).path("agentContextTokenBudget").asInt());
        when(runs.selectOne(any())).thenReturn(captured.getValue());
        assertEquals(40L, store.create(request, 20L));
        request.setExecutorType(null);
        assertThrows(BusinessException.class, () -> store.create(request, 20L));
        for (String invalid : List.of("", "AGENT", "custom", " agent")) {
            request.setExecutorType(invalid);
            assertThrows(BusinessException.class, () -> store.create(request, 20L));
        }
        verify(runs, times(1)).insert(any(AgentRun.class));
    }

    @Test
    void agentEvidenceUnionCanCiteEarlierRoundAndCompleteWithConsistentSnapshots() {
        run.setExecutorType("agent"); run.setEvidenceSnapshot(null);
        store.start(40L);
        store.retrievalStarted(40L); store.retrieved(40L, retrieval());
        var later = new Evidence("E2", 30L, "Paper", 1, "paper_test", "later", 2, 2, "后续原文", "url");
        store.retrievalStarted(40L);
        store.retrieved(40L, new RetrievalResult(List.of("later", "candidate-only"), List.of(later)));
        assertEquals(List.of(evidence(), later), json.evidence(run.getEvidenceSnapshot()));
        assertTrue(json.retrievedChunkIds(run.getRetrievalSnapshot()).contains("candidate-only"));
        assertTrue(store.agentAnswerReady(40L, List.of(evidence(), later)));
        assertTrue(store.complete(40L, answer()));
        assertEquals("completed", run.getStatus());
        var citation = ArgumentCaptor.forClass(MessageCitation.class);
        verify(citations).insert(citation.capture());
        assertEquals(evidence().chunkId(), citation.getValue().getChunkId());
        assertEquals(evidence().quotedText(), citation.getValue().getQuotedText());
        // 使用同一完成后的快照及实际引用执行评测查询，候选、上下文、引用保持各自语义。
        var published = new ChatMessage(); published.setId(50L); published.setContent(answer().content());
        when(messages.selectOne(any())).thenReturn(published);
        when(citations.selectList(any())).thenReturn(List.of(citation.getValue()));
        when(runs.selectOne(any())).thenReturn(run);
        var queries = new com.ecnu.agentpapermate.service.run.rag.RunQueries(
                store, events, messages, citations, sessionService, json);
        var evaluation = queries.evaluation(40L, 20L);
        assertEquals(json.retrievedChunkIds(run.getRetrievalSnapshot()), evaluation.retrievedChunkIds());
        assertEquals(List.of(evidence().chunkId(), "later"), evaluation.finalEvidenceChunkIds());
        assertEquals(List.of("E1"), evaluation.citations().stream().map(item -> item.evidenceId()).toList());
        assertEquals(answer().content(), evaluation.answer());
        assertFalse(store.complete(40L, answer()));
        verify(sessions).releaseActiveRun(10L, 40L);
    }

    @Test
    void agentFinalSelectionRejectsUnsavedOrChangedEvidenceAndRetainsOnlySuppliedSubset() {
        run.setExecutorType("agent"); store.start(40L);
        var changed = new Evidence("E1", 30L, "Paper", 1, "paper_test", "forged", 1, 1, "伪造原文", "url");
        assertThrows(IllegalStateException.class, () -> store.agentAnswerReady(40L, List.of(changed)));
        assertThrows(IllegalStateException.class, () -> store.agentAnswerReady(40L, List.of(evidence(), evidence())));
        assertTrue(store.agentAnswerReady(40L, List.of()));
        assertEquals("[]", run.getRetrievalSnapshot());
        assertEquals("[]", run.getEvidenceSnapshot());
        assertTrue(store.complete(40L, new Answer("缺少证据，无法确认", List.of())));
    }

    @Test
    void agentFinalSelectionCannotAdvanceCancelledOrExpiredRuns() {
        run.setExecutorType("agent"); store.start(40L);
        run.setStatus("cancelled");
        assertFalse(store.agentAnswerReady(40L, List.of()));
        assertFalse(store.complete(40L, new Answer("迟到", List.of())));
        run.setStatus("running"); run.setDeadline(LocalDateTime.now(clock));
        assertFalse(store.agentAnswerReady(40L, List.of()));
        assertEquals("timeout", run.getStatus());
        verify(messages, never()).insert(any(ChatMessage.class));
    }

    @Test
    void agentEvidenceSelectionDoesNotBypassRagOrIncompleteRetrievalPhase() {
        store.start(40L);
        assertThrows(IllegalStateException.class, () -> store.agentAnswerReady(40L, List.of()));
        run.setExecutorType("agent"); store.retrievalStarted(40L);
        assertThrows(IllegalStateException.class, () -> store.agentAnswerReady(40L, List.of()));
    }

    @Test
    void budgetFailureRemainsTerminalWhenDispatcherReportsInterruption() {
        run.setExecutorType("agent"); run.setModelConfig("{\"maxModelCalls\":1}");
        store.start(40L);
        assertTrue(store.modelCallStarted(40L));
        assertFalse(store.modelCallStarted(40L));
        store.fail(40L, "EXECUTION_INTERRUPTED", "任务执行被中断", false);
        assertEquals("failed", run.getStatus());
        assertEquals("MODEL_CALL_LIMIT", run.getErrorCode());
        assertFalse(store.agentAnswerReady(40L, List.of()));
        verify(sessions).releaseActiveRun(10L, 40L);
    }

    @Test
    void toolBudgetUsesRunSnapshotAndDoesNotChangeModelCountOrPhase() {
        store.start(40L);
        run.setModelConfig("{\"maxToolCalls\":1}"); properties.setMaxToolCalls(10);
        assertTrue(store.toolCallStarted(40L, "tool-1", "get_paper_metadata"));
        assertFalse(store.toolCallStarted(40L, "tool-2", "get_paper_metadata"));
        assertEquals(1, run.getToolCallCount());
        assertEquals(0, run.getStepCount());
        assertEquals("ready", run.getExecutionPhase());
        assertEquals("failed", run.getStatus());
        assertEquals("TOOL_CALL_LIMIT", run.getErrorCode());
        store.fail(40L, "EXECUTION_INTERRUPTED", "任务执行被中断", false);
        assertEquals("TOOL_CALL_LIMIT", run.getErrorCode());
        verify(sessions).releaseActiveRun(10L, 40L);
    }

    @Test
    void persistentToolIdCannotBeClaimedTwiceAndCompletionIsRecordedOnce() {
        var saved = recordToolEvents();
        store.start(40L);
        assertTrue(store.toolCallStarted(40L, "tool-1", "get_paper_metadata"));
        assertThrows(RunStore.DuplicateToolCall.class,
                () -> store.toolCallStarted(40L, "tool-1", "get_paper_metadata"));
        assertEquals(1, run.getToolCallCount());
        assertTrue(store.toolCallFinished(40L, "tool-1", "get_paper_metadata", null, 3));
        assertFalse(store.toolCallFinished(40L, "tool-1", "get_paper_metadata", null, 3));
        assertThrows(RunStore.DuplicateToolCall.class,
                () -> store.toolCallStarted(40L, "tool-1", "search_paper_chunks"));
        assertEquals(List.of("run_started", "tool_started", "tool_completed"),
                saved.stream().map(RunEvent::getEventType).toList());
    }

    @Test
    void unknownCompletionAndMismatchedToolNamesAreRejected() {
        recordToolEvents(); store.start(40L);
        assertThrows(IllegalStateException.class,
                () -> store.toolCallFinished(40L, "missing", "get_paper_metadata", null, 1));
        assertTrue(store.toolCallStarted(40L, "tool-1", "get_paper_metadata"));
        assertThrows(IllegalStateException.class,
                () -> store.toolCallFinished(40L, "tool-1", "search_paper_chunks", null, 1));
    }

    @Test
    void cancellationBlocksLateToolCompletionAndKeepsOriginalTerminalState() {
        var saved = recordToolEvents(); store.start(40L);
        assertTrue(store.toolCallStarted(40L, "tool-1", "get_paper_metadata"));
        when(runs.selectOne(any())).thenReturn(run);
        store.cancel(40L, 20L);
        assertFalse(store.toolCallFinished(40L, "tool-1", "get_paper_metadata", null, 1));
        assertFalse(store.executionAllowed(40L));
        assertEquals("cancelled", run.getStatus());
        assertEquals(List.of("run_started", "tool_started", "run_cancelled"),
                saved.stream().map(RunEvent::getEventType).toList());
    }

    @Test
    void expiredRunAndInvalidToolBudgetNeverAdmitAnExecution() {
        store.start(40L); run.setModelConfig("{\"maxToolCalls\":\"1\"}");
        assertThrows(IllegalStateException.class, () -> store.toolCallStarted(40L, "tool-1", "get_paper_metadata"));
        assertEquals(0, run.getToolCallCount());
        run.setDeadline(LocalDateTime.now(clock));
        assertFalse(store.toolCallStarted(40L, "tool-1", "get_paper_metadata"));
        assertEquals("timeout", run.getStatus());
    }

    private java.util.ArrayList<RunEvent> recordToolEvents() {
        var saved = new java.util.ArrayList<RunEvent>();
        when(events.insert(any(RunEvent.class))).thenAnswer(call -> { saved.add(call.getArgument(0)); return 1; });
        when(events.selectList(any())).thenAnswer(call -> List.copyOf(saved));
        return saved;
    }

    @Test
    void duplicateSubmissionReturnsOldRunEvenWhenSessionIsBusyOrFeatureIsDisabled() {
        run.setUserQuery("问题"); session.setActiveRunId(40L); properties.setEnabled(false);
        when(runs.selectOne(any())).thenReturn(run);
        assertEquals(40L, store.create(request(), 20L));
        verify(runs, never()).insert(any(AgentRun.class));
        verifyNoInteractions(messages, relations, papers, jobs, events, sessions);
    }

    @Test
    void rejectsReusingKeyForDifferentQuestionAndRejectsAnotherActiveRun() {
        run.setUserQuery("不同问题"); when(runs.selectOne(any())).thenReturn(run);
        assertThrows(BusinessException.class, () -> store.create(request(), 20L));
        when(runs.selectOne(any())).thenReturn(null); session.setActiveRunId(99L);
        assertThrows(BusinessException.class, () -> store.create(request(), 20L));
        verifyNoInteractions(messages, relations);
    }

    @Test
    void missingOrIncompatibleIndexNeverCreatesTask() {
        when(jobs.selectOne(any())).thenReturn(null);
        assertThrows(BusinessException.class, () -> store.create(request(), 20L));
        verify(runs, never()).insert(any(AgentRun.class));
        verifyNoInteractions(messages, sessions);
    }

    @Test
    void cancellationWinsBeforeCompletionAndDoesNotAllowLateAssistantMessage() {
        store.start(40L);
        when(runs.selectOne(any())).thenReturn(run);
        assertEquals("cancelled", store.cancel(40L, 20L));
        assertFalse(store.complete(40L, answer()));
        assertEquals("cancelled", store.cancel(40L, 20L));
        verifyNoInteractions(messages, citations);
        var capture = ArgumentCaptor.forClass(RunEvent.class);
        verify(events, times(2)).insert(capture.capture());
        assertEquals(List.of("run_started", "run_cancelled"), capture.getAllValues().stream().map(RunEvent::getEventType).toList());
        assertEquals(List.of(1L, 2L), capture.getAllValues().stream().map(RunEvent::getEventSeq).toList());
    }

    @Test
    void successfulAnswerCitationAndFinalEventCommitTogetherAndCannotBeCancelledLater() {
        store.start(40L); assertTrue(store.retrievalStarted(40L)); assertTrue(store.retrieved(40L, retrieval()));
        clearInvocations(transactions, messages, citations, events, sessions);
        assertTrue(store.complete(40L, answer()));
        var citation = ArgumentCaptor.forClass(MessageCitation.class);
        var event = ArgumentCaptor.forClass(RunEvent.class);
        var order = inOrder(transactions, messages, citations, events, sessions);
        order.verify(transactions).getTransaction(any());
        order.verify(messages).insert(any(ChatMessage.class));
        order.verify(citations).insert(citation.capture());
        order.verify(events).insert(event.capture());
        order.verify(sessions).releaseActiveRun(10L, 40L);
        order.verify(transactions).commit(transaction);
        assertEquals(50L, citation.getValue().getMessageId());
        assertEquals("原文", citation.getValue().getQuotedText());
        assertEquals("run_completed", event.getValue().getEventType());
        assertEquals(4L, event.getValue().getEventSeq());
        assertTrue(event.getValue().getPayload().contains("citations"));
        when(runs.selectOne(any())).thenReturn(run);
        assertEquals("completed", store.cancel(40L, 20L));
        verify(events, times(1)).insert(any(RunEvent.class));
    }

    @Test
    void citationWriteFailureRollsBackAndNeverPublishesCompletion() {
        runningAfterRetrieval();
        when(citations.insert(any(MessageCitation.class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> store.complete(40L, answer()));
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
        verifyNoInteractions(events, sessions);
    }

    @Test
    void deadlineAtCurrentTimeWinsOverLateCompletion() {
        runningAfterRetrieval(); run.setDeadline(LocalDateTime.now(clock));
        assertFalse(store.complete(40L, answer()));
        assertEquals("timeout", run.getStatus());
        verifyNoInteractions(messages, citations);
        var event = ArgumentCaptor.forClass(RunEvent.class);
        verify(events).insert(event.capture());
        assertEquals("run_timeout", event.getValue().getEventType());
    }

    @Test
    void revokedPaperAndUnknownCitationPreventAnswerWrites() {
        runningAfterRetrieval();
        when(papers.selectCount(any())).thenReturn(0L);
        assertThrows(BusinessException.class, () -> store.complete(40L, answer()));
        when(papers.selectCount(any())).thenReturn(1L);
        assertThrows(AnswerValidator.InvalidAnswer.class, () -> store.complete(40L,
                new Answer("伪造[E9]", List.of("E9"))));
        verifyNoInteractions(messages, citations, events);
    }

    @Test
    void queuedDeadlineProducesTerminalEventWithoutRunStarted() {
        run.setCreateTime(LocalDateTime.now(clock).minusSeconds(301));
        assertNull(store.start(40L));
        assertEquals("timeout", run.getStatus());
        var event = ArgumentCaptor.forClass(RunEvent.class);
        verify(events).insert(event.capture());
        assertEquals("run_timeout", event.getValue().getEventType());
        assertEquals(1L, event.getValue().getEventSeq());
    }

    @Test
    void retrievalSnapshotsAndEventCommitInOneTransactionAndSurviveGenerationFailure() {
        store.start(40L); store.retrievalStarted(40L);
        clearInvocations(transactions, runs, events);
        assertTrue(store.retrieved(40L, retrieval()));
        assertEquals(json.tree("[\"chunk1\",\"chunk2\"]"), json.tree(run.getRetrievalSnapshot()));
        assertEquals(List.of(evidence()), json.evidence(run.getEvidenceSnapshot()));
        var order = inOrder(transactions, runs, events);
        order.verify(transactions).getTransaction(any());
        order.verify(runs, times(2)).updateById(argThat((AgentRun saved) -> saved.getRetrievalSnapshot() != null
                && saved.getEvidenceSnapshot() != null));
        order.verify(events).insert(argThat((RunEvent event) -> "retrieval_completed".equals(event.getEventType())));
        order.verify(transactions).commit(transaction);
        String candidates = run.getRetrievalSnapshot(), evidence = run.getEvidenceSnapshot();
        store.fail(40L, "EXECUTION_FAILED", "generation failed", false);
        assertEquals(candidates, run.getRetrievalSnapshot());
        assertEquals(evidence, run.getEvidenceSnapshot());
    }

    @Test
    void emptyRetrievalIsStoredAsJsonArraysRatherThanNull() {
        store.start(40L); store.retrievalStarted(40L);
        assertTrue(store.retrieved(40L, new RetrievalResult(List.of(), List.of())));
        assertEquals("[]", run.getRetrievalSnapshot());
        assertEquals("[]", run.getEvidenceSnapshot());
    }

    @Test
    void cancellationPreventsLateSnapshotWrites() {
        run.setEvidenceSnapshot(null);
        store.start(40L); store.retrievalStarted(40L);
        when(runs.selectOne(any())).thenReturn(run);
        store.cancel(40L, 20L);
        clearInvocations(runs, events);
        assertFalse(store.retrieved(40L, retrieval()));
        assertNull(run.getRetrievalSnapshot());
        assertNull(run.getEvidenceSnapshot());
        verify(runs, never()).updateById(any(AgentRun.class));
        verifyNoInteractions(events);
    }

    @Test
    void deadlinePreventsLateSnapshotWrites() {
        run.setEvidenceSnapshot(null);
        store.start(40L); store.retrievalStarted(40L);
        run.setDeadline(LocalDateTime.now(clock));
        assertFalse(store.retrieved(40L, retrieval()));
        assertEquals("timeout", run.getStatus());
        assertNull(run.getRetrievalSnapshot());
        assertNull(run.getEvidenceSnapshot());
    }

    @Test
    void retrievalEventFailureRollsBackSnapshotTransaction() {
        store.start(40L); store.retrievalStarted(40L);
        clearInvocations(transactions);
        when(events.insert(any(RunEvent.class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> store.retrieved(40L, retrieval()));
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
    }

    private void runningAfterRetrieval() {
        run.setStatus("running"); run.setEventSeq(3L); run.setDeadline(LocalDateTime.now(clock).plusSeconds(10));
        run.setExecutionPhase("evidence_ready");
        run.setRetrievalSnapshot(json.write(retrieval().retrievedChunkIds()));
    }

    private RunCreateRequest request() {
        var request = new RunCreateRequest(); request.setSessionId(10L);
        request.setRequestKey("0123456789abcdef"); request.setQuestion("问题"); return request;
    }

    static PaperScope scope() { return new PaperScope(30L, "Paper", 1, "attempt", "paper_test", "embedding", "v1", 3, "https://files.example/paper.pdf"); }
    static Evidence evidence() { return new Evidence("E1", 30L, "Paper", 1, "paper_test", "chunk1", 2, 3, "原文", "https://files.example/paper.pdf"); }
    static Answer answer() { return new Answer("答案[E1]", List.of("E1")); }
    static RetrievalResult retrieval() { return new RetrievalResult(List.of("chunk1", "chunk2"), List.of(evidence())); }
    static RetrievalCandidates candidates() {
        return new RetrievalCandidates(List.of("chunk1", "chunk2"),
                List.of(new EvidenceCandidate(scope(), "chunk1", 2, 3, "原文")));
    }
}
