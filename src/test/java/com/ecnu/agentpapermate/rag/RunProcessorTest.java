package com.ecnu.agentpapermate.rag;

import com.ecnu.agentpapermate.support.ai.RagCalls;
import com.ecnu.agentpapermate.rag.model.Answer;
import com.ecnu.agentpapermate.rag.model.RetrievalCandidates;
import com.ecnu.agentpapermate.entity.model.AgentRun;
import com.ecnu.agentpapermate.service.run.rag.RunProcessor;
import com.ecnu.agentpapermate.service.run.rag.RunQueries;
import com.ecnu.agentpapermate.service.run.rag.RunStore;
import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.support.json.RagJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunProcessorTest {
    private final RunStore store = mock(RunStore.class);
    private final RunQueries queries = mock(RunQueries.class);
    private final RagRetriever retriever = mock(RagRetriever.class);
    private final RagAnswerGenerator generator = mock(RagAnswerGenerator.class);
    private final RagJson json = new RagJson(new ObjectMapper());
    private final RunProcessor processor = new RunProcessor(store, queries, retriever, generator, json, new RagProperties());
    private final AgentRun run = new AgentRun();

    @BeforeEach
    void setUp() {
        run.setId(1L);
        run.setUserId(20L); run.setUserQuery("问题");
        run.setPapersSnapshot(json.write(List.of(RunStoreTest.scope())));
        run.setDeadline(LocalDateTime.now().plusSeconds(180));
        when(store.retrievalStarted(1L)).thenReturn(true);
        when(store.retrieved(eq(1L), any())).thenReturn(true);
        when(store.modelStarted(1L)).thenReturn(true);
    }

    @Test
    void retrievalFailureDoesNotEmitCompletionOrCallModel() {
        when(retriever.retrieve(any(), any())).thenThrow(new IllegalStateException("secret must not be persisted"));
        processor.process(run);
        verify(store, never()).retrieved(anyLong(), any());
        verify(store).fail(1L, "EXECUTION_FAILED", "检索或模型服务调用失败，请稍后重试", false);
        verifyNoInteractions(generator);
    }

    @Test
    void cancellationAfterRetrievalPreventsModelCall() {
        when(retriever.retrieve(any(), any())).thenReturn(RunStoreTest.candidates());
        when(store.retrieved(eq(1L), any())).thenReturn(false);
        processor.process(run);
        verifyNoInteractions(generator);
        verify(store, never()).complete(anyLong(), any());
    }

    @Test
    void emptyEvidenceReturnsExplicitInsufficiencyWithoutModelCall() {
        when(retriever.retrieve(any(), any())).thenReturn(new RetrievalCandidates(List.of(), List.of()));
        processor.process(run);
        var answer = ArgumentCaptor.forClass(Answer.class);
        verify(store).complete(eq(1L), answer.capture());
        assertTrue(answer.getValue().content().contains("无法确认"));
        assertTrue(answer.getValue().evidenceIds().isEmpty());
        verifyNoInteractions(generator);
    }

    @Test
    void timeoutIsDifferentFromExecutionFailure() {
        when(retriever.retrieve(any(), any())).thenThrow(new RagCalls.RequestTimeout());
        processor.process(run);
        verify(store).fail(1L, "REQUEST_TIMEOUT", "外部服务请求超时", true);
    }

    @Test
    void fullCandidatesAreSavedBeforeGeneratingWithOnlyFinalEvidence() {
        var retrieval = RunStoreTest.retrieval();
        when(retriever.retrieve(any(), any())).thenReturn(RunStoreTest.candidates());
        when(queries.previousQuestions(run)).thenReturn(List.of());
        when(generator.generate(run, retrieval.evidence(), List.of())).thenReturn(RunStoreTest.answer());
        processor.process(run);
        var order = inOrder(store, generator);
        order.verify(store).retrieved(1L, retrieval);
        order.verify(store).modelStarted(1L);
        order.verify(generator).generate(run, retrieval.evidence(), List.of());
        order.verify(store).complete(1L, RunStoreTest.answer());
    }
}
