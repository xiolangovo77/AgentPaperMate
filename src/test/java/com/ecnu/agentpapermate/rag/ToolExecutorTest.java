package com.ecnu.agentpapermate.rag;

import com.ecnu.agentpapermate.agent.context.AgentRunContext;
import com.ecnu.agentpapermate.agent.tool.*;
import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.entity.model.AgentRun;
import com.ecnu.agentpapermate.entity.model.Paper;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.service.PaperService;
import com.ecnu.agentpapermate.service.run.rag.RunStore;
import com.ecnu.agentpapermate.support.ai.RagCalls;
import com.ecnu.agentpapermate.support.json.RagJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolExecutorTest {
    private final RunStore store = mock(RunStore.class);
    private final PaperService papers = mock(PaperService.class);
    private final RagRetriever retriever = mock(RagRetriever.class);
    private final RagJson json = new RagJson(new ObjectMapper());
    private final RagProperties properties = new RagProperties();
    private final Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));
    private ExecutorService workers;
    private RagCalls calls;
    private ToolRegistry registry;
    private ToolExecutor executor;
    private AgentRunContext context;

    @BeforeEach
    void setUp() {
        workers = Executors.newVirtualThreadPerTaskExecutor();
        calls = new RagCalls(workers, properties, clock);
        registry = new ToolRegistry(new PaperTools(papers, retriever, store, properties));
        executor = new ToolExecutor(registry, store, calls, json, properties, clock);
        context = context(40L, "{\"toolTimeoutSeconds\":1}");
        when(store.executionAllowed(anyLong())).thenReturn(true);
        when(store.toolCallStarted(anyLong(), anyString(), anyString())).thenReturn(true);
        when(store.toolCallFinished(anyLong(), anyString(), anyString(), nullable(String.class), anyLong())).thenReturn(true);
        when(store.retrievalStarted(anyLong())).thenReturn(true);
        when(store.retrieved(anyLong(), any())).thenReturn(true);
    }

    @AfterEach
    void close() { workers.shutdownNow(); }

    @Test
    void duplicateRegistrationFailsAndDefinitionsAreLimitedToBackendWhitelist() {
        var tool = registry.find("get_paper_metadata").orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> new ToolRegistry(List.of(tool, tool)));
        assertEquals(1, registry.definitions(Set.of("get_paper_metadata", "invented_tool")).size());
        assertThrows(UnsupportedOperationException.class, () -> registry.names().clear());
    }

    @Test
    void unknownAndDisallowedToolsNeverConsumeBudgetOrAccessData() {
        for (String name : List.of("invented_tool", "get_paper_metadata")) {
            var result = execute(name, "{}", Set.of());
            assertEquals("TOOL_NOT_ALLOWED", result.path("code").asText());
        }
        verify(store, never()).toolCallStarted(anyLong(), anyString(), anyString());
        verifyNoInteractions(papers, retriever);
    }

    @Test
    void agentSkillRestrictionCannotBeBypassedByPassingAllRegisteredTools() {
        var qa = com.ecnu.agentpapermate.agent.skill.SkillTestSupport.router().select("问题");
        var restricted = com.ecnu.agentpapermate.agent.skill.SkillTestSupport.replace(qa, "SKILL.md",
                qa.files().get("SKILL.md").replace("\r\n", "\n").replace("  - search_paper_chunks\n", ""));
        var run = new AgentRun(); run.setId(40L); run.setUserId(20L); run.setSessionId(10L);
        run.setUserQuery("q"); run.setExecutorType("agent"); run.setStatus("running"); run.setExecutionPhase("ready");
        run.setPapersSnapshot(json.write(List.of(RunStoreTest.scope())));
        run.setDeadline(LocalDateTime.now(clock).plusSeconds(30));
        com.ecnu.agentpapermate.agent.skill.SkillTestSupport.attach(run, json, restricted);
        context = AgentRunContext.fromClaimedRun(run, json);
        assertEquals("TOOL_NOT_ALLOWED", execute("search_paper_chunks", "{\"query\":\"q\"}", registry.names()).path("code").asText());
        verify(store, never()).toolCallStarted(anyLong(), anyString(), anyString());
        verifyNoInteractions(papers, retriever);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"paperId\":30}", "{\"paperId\":\"31\"}",
            "{\"paperId\":\"30\",\"userId\":\"999\"}", "[]", "null", "{} {}"})
    void invalidMetadataArgumentsHaveSafeErrorsWithoutAccessingPapers(String args) {
        var result = execute("get_paper_metadata", args, registry.names());
        assertEquals("INVALID_TOOL_ARGUMENTS", result.path("code").asText());
        assertFalse(result.path("retryable").asBoolean());
        assertTrue(result.path("evidence").isEmpty());
        verifyNoInteractions(papers, retriever);
        verify(store).toolCallFinished(eq(40L), eq("call-1"), eq("get_paper_metadata"), eq("INVALID_TOOL_ARGUMENTS"), anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"query\":\"q\",\"topK\":21}", "{\"query\":\"q\",\"topK\":0}",
            "{\"query\":\"q\",\"topK\":1.5}", "{\"query\":\"q\",\"requestedPaperIds\":null}",
            "{\"query\":\"q\",\"requestedPaperIds\":[]}", "{\"query\":\"q\",\"requestedPaperIds\":[\"31\"]}",
            "{\"query\":\"q\",\"requestedPaperIds\":[\"30\",\"30\"]}"})
    void invalidSearchNeverStartsRetrieval(String args) {
        assertEquals("INVALID_TOOL_ARGUMENTS", execute("search_paper_chunks", args, registry.names()).path("code").asText());
        verify(store, never()).retrievalStarted(anyLong());
        verifyNoInteractions(retriever);
    }

    @Test
    void metadataPreservesMissingFieldsAndHasNoEvidence() {
        when(papers.requireOwnedPaper(30L, 20L)).thenReturn(new Paper());
        var result = execute("get_paper_metadata", "{\"paperId\":\"30\"}", registry.names());
        assertEquals("ok", result.path("status").asText());
        assertTrue(result.at("/data/authors").isNull());
        assertTrue(result.path("evidence").isEmpty());
    }

    @Test
    void multipleSearchesShareEvidenceNumbersAndCandidateUnionButAnotherRunIsIndependent() {
        when(retriever.retrieve(any(), any(), any())).thenReturn(RunStoreTest.candidates());
        var first = execute("search_paper_chunks", "{\"query\":\"q\"}", registry.names());
        var second = executor.execute(context, new ToolCall("call-2", "function", "search_paper_chunks", "{\"query\":\"q2\"}"), registry.names());
        assertEquals("E1", first.at("/evidence/0/evidenceId").asText());
        assertEquals("E1", json.tree(second.responseData()).at("/evidence/0/evidenceId").asText());
        assertEquals(RunStoreTest.candidates().retrievedChunkIds(), context.getEvidenceRegistry().getRetrievedChunkIds());
        var other = context(41L, "{}");
        assertTrue(other.getEvidenceRegistry().snapshot().isEmpty());
    }

    @Test
    void alreadyClaimedCallIsNotExecutedOrCountedAgain() {
        when(store.toolCallStarted(40L, "call-1", "get_paper_metadata")).thenThrow(new RunStore.DuplicateToolCall());
        assertEquals("DUPLICATE_TOOL_CALL", execute("get_paper_metadata", "{\"paperId\":\"30\"}", registry.names()).path("code").asText());
        verifyNoInteractions(papers);
        verify(store, never()).toolCallFinished(anyLong(), anyString(), anyString(), nullable(String.class), anyLong());
    }

    @Test
    void budgetRefusalAndCancelledRunsDoNotExecuteTools() {
        when(store.toolCallStarted(anyLong(), anyString(), anyString())).thenReturn(false);
        assertThrows(CancellationException.class, () -> execute("get_paper_metadata", "{\"paperId\":\"30\"}", registry.names()));
        when(store.executionAllowed(40L)).thenReturn(false);
        assertThrows(CancellationException.class, () -> execute("get_paper_metadata", "{}", registry.names()));
        verifyNoInteractions(papers);
    }

    @Test
    void revokedOwnershipStopsExecutionWithoutReturningExceptionTextToModel() {
        when(papers.requireOwnedPaper(30L, 20L)).thenThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR, "secret detail"));
        assertThrows(BusinessException.class, () -> execute("get_paper_metadata", "{\"paperId\":\"30\"}", registry.names()));
        verify(store).toolCallFinished(eq(40L), eq("call-1"), eq("get_paper_metadata"), eq("SOURCE_UNAVAILABLE"), anyLong());
    }

    @Test
    void corruptTimeoutSnapshotFailsRunExecutionInsteadOfBlamingToolArguments() {
        context = context(40L, "{broken");
        assertThrows(IllegalStateException.class, () -> execute("get_paper_metadata", "{\"paperId\":\"30\"}", registry.names()));
        verifyNoInteractions(papers);
        verify(store).toolCallFinished(eq(40L), eq("call-1"), eq("get_paper_metadata"), eq("TOOL_EXECUTION_FAILED"), anyLong());
    }

    @Test
    void cancellationAfterMetadataReadDiscardsTheResult() {
        when(papers.requireOwnedPaper(30L, 20L)).thenAnswer(call -> {
            when(store.executionAllowed(40L)).thenReturn(false);
            return new Paper();
        });
        assertThrows(CancellationException.class, () -> execute("get_paper_metadata", "{\"paperId\":\"30\"}", registry.names()));
        verify(store, never()).toolCallFinished(anyLong(), anyString(), anyString(), isNull(), anyLong());
    }

    @Test
    void timeoutStopsWaitingAndNeverEmitsSuccessForLateResult() throws Exception {
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        var definition = ToolDefinition.builder().name("slow_tool").description("test").inputSchema("{\"type\":\"object\"}").build();
        var slow = new ToolRegistry(List.of(new ToolRegistry.RegisteredTool(definition, (ctx, args) -> {
            attempts.incrementAndGet();
            try {
                try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                return ToolResult.success("late", List.of());
            } finally { finished.countDown(); }
        })));
        var controlled = new ToolExecutor(slow, store, calls, json, properties, clock);
        try {
            assertThrows(RagCalls.RequestTimeout.class, () -> controlled.execute(context,
                    new ToolCall("slow-1", "function", "slow_tool", "{}"), slow.names()));
            assertEquals(1, attempts.get());
            verify(store).toolCallFinished(eq(40L), eq("slow-1"), eq("slow_tool"), eq("TOOL_TIMEOUT"), anyLong());
            verify(store, never()).toolCallFinished(anyLong(), anyString(), anyString(), isNull(), anyLong());
        } finally {
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
        }
    }

    private com.fasterxml.jackson.databind.JsonNode execute(String name, String args, Set<String> allowed) {
        return json.tree(executor.execute(context, new ToolCall("call-1", "function", name, args), allowed).responseData());
    }

    private AgentRunContext context(Long id, String config) {
        var run = new AgentRun(); run.setId(id); run.setUserId(20L); run.setSessionId(10L);
        run.setUserQuery("q"); run.setStatus("running"); run.setExecutionPhase("ready");
        run.setPapersSnapshot(json.write(List.of(RunStoreTest.scope())));
        run.setDeadline(LocalDateTime.now(clock).plusSeconds(30)); run.setModelConfig(config);
        return AgentRunContext.fromClaimedRun(run, json);
    }
}
