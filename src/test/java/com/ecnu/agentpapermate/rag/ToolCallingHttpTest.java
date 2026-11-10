package com.ecnu.agentpapermate.rag;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.ecnu.agentpapermate.agent.context.AgentRunContext;
import com.ecnu.agentpapermate.agent.model.ToolCallModelAdapter;
import com.ecnu.agentpapermate.agent.model.ToolCallProbe;
import com.ecnu.agentpapermate.agent.tool.PaperTools;
import com.ecnu.agentpapermate.agent.tool.ToolRegistry;
import com.ecnu.agentpapermate.agent.tool.ToolExecutor;
import com.ecnu.agentpapermate.config.rag.RagConfiguration;
import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.entity.model.AgentRun;
import com.ecnu.agentpapermate.entity.model.Paper;
import com.ecnu.agentpapermate.service.PaperService;
import com.ecnu.agentpapermate.service.run.rag.RunStore;
import com.ecnu.agentpapermate.support.ai.RagCalls;
import com.ecnu.agentpapermate.support.ai.RunChatCaller;
import com.ecnu.agentpapermate.support.json.RagJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 M6.1 HTTP 协议往返，模型由本地服务替代，论文和 Run 不访问业务数据库。 */
class ToolCallingHttpTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RagJson json = new RagJson(mapper);
    private final RunStore store = mock(RunStore.class);
    private final PaperService papers = mock(PaperService.class);
    private final RagRetriever retriever = mock(RagRetriever.class);
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> selected = new ArrayList<>();
    private HttpServer server;
    private ExecutorService workers;
    private ToolCallProbe probe;
    private AgentRunContext context;

    @BeforeEach
    void setUp() throws Exception {
        selected.add(tool("metadata-1", "get_paper_metadata", "{\"paperId\":\"30\"}"));
        selected.add(tool("search-1", "search_paper_chunks", "{\"query\":\"实验结果\"}"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            boolean first = requests.size() == 1;
            var message = new LinkedHashMap<String, Object>();
            message.put("role", "assistant");
            message.put("content", first ? "" : "已读取论文元数据与原文证据[E1]。");
            if (first) message.put("tool_calls", selected);
            byte[] body = mapper.writeValueAsBytes(Map.of("request_id", "local-test", "output", Map.of("choices",
                    List.of(Map.of("finish_reason", first ? "tool_calls" : "stop", "message", message))),
                    "usage", Map.of("input_tokens", 10, "output_tokens", 20, "total_tokens", 30)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        workers = Executors.newVirtualThreadPerTaskExecutor();
        Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));
        var properties = new RagProperties();
        var connection = new DashScopeConnectionProperties();
        connection.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        connection.setApiKey("fake-test-key");
        var calls = new RagCalls(workers, properties, clock);
        var adapter = new ToolCallModelAdapter(new RunChatCaller(
                new RagConfiguration().ragChatModel(properties, connection), calls, store), json);
        var registry = new ToolRegistry(new PaperTools(papers, retriever, store, properties));
        var toolExecutor = new ToolExecutor(registry, store, calls, json, properties, clock);
        probe = new ToolCallProbe(adapter, registry, toolExecutor, store, json);
        AgentRun run = new AgentRun();
        run.setId(40L); run.setUserId(20L); run.setSessionId(10L); run.setUserQuery("读取论文信息和实验结果");
        run.setStatus("running"); run.setExecutionPhase("ready"); run.setModelName("qwen3.7-plus");
        run.setModelConfig("{\"maxTokens\":1024}"); run.setDeadline(LocalDateTime.now(clock).plusSeconds(30));
        run.setPapersSnapshot(json.write(List.of(RunStoreTest.scope())));
        context = AgentRunContext.fromClaimedRun(run, json);
        when(store.executionAllowed(40L)).thenReturn(true);
        when(store.modelCallStarted(40L)).thenReturn(true);
        when(store.toolCallStarted(eq(40L), anyString(), anyString())).thenReturn(true);
        when(store.toolCallFinished(eq(40L), anyString(), anyString(), nullable(String.class), anyLong())).thenReturn(true);
        when(store.retrievalStarted(40L)).thenReturn(true);
        when(store.retrieved(eq(40L), any())).thenReturn(true);
        when(retriever.retrieve(any(), any(), any())).thenReturn(RunStoreTest.candidates());
        var paper = new Paper(); paper.setId(30L); paper.setAuthors("Author");
        when(papers.requireOwnedPaper(30L, 20L)).thenReturn(paper);
    }

    @AfterEach
    void close() {
        if (server != null) server.stop(0);
        if (workers != null) workers.shutdownNow();
    }

    @Test
    void nativeToolsAreExecutedOnceAndBothIdsAreReturnedToSecondModelCall() {
        var result = probe.verify(context);
        assertEquals(2, requests.size(), "SDK 不得自动执行工具或隐式续轮");
        assertEquals(2, requests.getFirst().at("/parameters/tools").size());
        assertEquals(1024, requests.getFirst().at("/parameters/max_tokens").asInt());
        assertEquals("get_paper_metadata", requests.getFirst().at("/parameters/tools/0/function/name").asText());
        assertEquals("object", requests.getFirst().at("/parameters/tools/0/function/parameters/type").asText());
        var messages = requests.getLast().at("/input/messages");
        assertEquals("metadata-1", messages.get(2).at("/tool_calls/0/id").asText());
        assertEquals("metadata-1", messages.get(3).path("tool_call_id").asText());
        assertEquals("search-1", messages.get(4).path("tool_call_id").asText());
        assertEquals("tool", messages.get(3).path("role").asText());
        assertTrue(requests.getLast().at("/parameters/tools").isEmpty());
        assertEquals(2, result.toolResults().size());
        assertEquals("E1", context.getEvidenceRegistry().snapshot().getFirst().evidenceId());
        verify(papers).requireOwnedPaper(30L, 20L);
        verify(retriever).retrieve(any(), same(context.getRetrievalContext()), eq(context.getDeadline()));
        verify(store, times(2)).modelCallStarted(40L);
        verify(store, times(2)).addModelUsage(40L, 10L, 20L);
        verify(store, times(2)).toolCallStarted(eq(40L), anyString(), anyString());
        verify(store, never()).complete(anyLong(), any());
    }

    @Test
    void duplicateCallIdsAreRejectedBeforeAnyToolRuns() {
        selected.set(1, tool("metadata-1", "search_paper_chunks", "{\"query\":\"实验\"}"));
        assertThrows(IllegalArgumentException.class, () -> probe.verify(context));
        verifyNoInteractions(papers, retriever);
        verify(store, never()).toolCallStarted(anyLong(), anyString(), anyString());
        assertEquals(1, requests.size());
    }

    @Test
    void cancellationAfterFirstModelResponsePreventsToolsAndSecondModelRequest() {
        when(store.executionAllowed(40L)).thenReturn(true, false);
        assertThrows(CancellationException.class, () -> probe.verify(context));
        verifyNoInteractions(papers, retriever);
        assertEquals(1, requests.size());
    }

    private Map<String, Object> tool(String id, String name, String arguments) {
        return Map.of("id", id, "type", "function", "function", Map.of("name", name, "arguments", arguments));
    }
}
