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
        run.setCreateTime(LocalDateTime.now(clock)); run.setStepCount(0);
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
        assertEquals("qwen3.7-plus", runCapture.getValue().getModelName());
        assertEquals(2, runCapture.getValue().getScopeVersion());
        assertTrue(runCapture.getValue().getPapersSnapshot().contains("attempt"));
        assertEquals("user", message.getValue().getRole());
        assertEquals(40L, message.getValue().getRunId());
        verifyNoInteractions(events);
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
