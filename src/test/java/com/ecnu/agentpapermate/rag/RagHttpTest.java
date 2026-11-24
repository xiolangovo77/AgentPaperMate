package com.ecnu.agentpapermate.rag;

import com.ecnu.agentpapermate.support.ai.RagCalls;
import com.ecnu.agentpapermate.agent.context.AgentRunContext;
import com.ecnu.agentpapermate.support.ai.RunChatCaller;
import com.ecnu.agentpapermate.service.run.rag.RunStore;
import com.ecnu.agentpapermate.service.PaperService;
import com.ecnu.agentpapermate.rag.model.RetrievalRequest;
import com.ecnu.agentpapermate.rag.model.RetrievalPlan;
import com.ecnu.agentpapermate.rag.model.RetrievalPlan.Strategy;
import com.ecnu.agentpapermate.rag.model.ConversationMessage;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingProperties;
import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.ecnu.agentpapermate.config.paper.PaperProcessingProperties;
import com.ecnu.agentpapermate.config.rag.RagConfiguration;
import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.entity.model.AgentRun;
import com.ecnu.agentpapermate.support.json.RagJson;
import com.ecnu.agentpapermate.validator.AnswerValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static com.ecnu.agentpapermate.rag.RunStoreTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用本地 HTTP 替身验证真实 DashScope 与 ES 请求，不调用收费模型或业务数据。 */
class RagHttpTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RagJson json = new RagJson(mapper);
    private final RagProperties properties = new RagProperties();
    private final Map<String, List<JsonNode>> requests = new ConcurrentHashMap<>();
    private final AgentRun run = new AgentRun();
    private HttpServer server;
    private ExecutorService executor;
    private RestClient es;
    private RagRetriever retriever;
    private RagAnswerGenerator generator;
    /** 执行或模拟检索前的查询改写。 */
    private RagQueryRewriter rewriter;
    private RetrievalBenchmark benchmark;
    private AgentRunContext runContext;
    private RunStore store;
    private volatile boolean timedOut;
    /** 控制检索替身是否返回多个候选以触发重排。 */
    private volatile boolean multipleHits;
    private volatile List<String> bm25HitIds;
    private volatile List<String> knnHitIds;
    /** 指定本地聊天模型替身返回的正文。 */
    private volatile String chatOutput = "{\"answer\":\"答案[E1]\",\"evidenceIds\":[\"E1\"]}";
    /** 模拟候选原文的相关性排序。 */
    private final RerankModel reranker = mock(RerankModel.class);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            JsonNode body = mapper.readTree(exchange.getRequestBody());
            requests.computeIfAbsent(path, ignored -> new CopyOnWriteArrayList<>()).add(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            Object response;
            if (path.contains("/embeddings/")) {
                response = Map.of("request_id", "test", "output", Map.of("embeddings", List.of(
                        Map.of("text_index", 0, "embedding", List.of(0.1, 0.2, 0.3)))), "usage", Map.of("total_tokens", 3));
            } else if (path.equals("/paper_test/_search")) {
                var hits = multipleHits ? List.of(hit("chunk1", "原文", true), hit("chunk2", "相关段落", true))
                        : List.of(hit("chunk1", "原文", true));
                var hitIds = body.has("knn") ? knnHitIds : bm25HitIds;
                if (hitIds != null) hits = hitIds.stream().map(id -> hit(id, id + " 外部原文", true)).toList();
                response = Map.of("timed_out", timedOut, "_shards", Map.of("failed", 0), "hits", Map.of("hits", hits));
            } else if (path.equals("/api/v1/services/aigc/multimodal-generation/generation")) {
                response = Map.of("request_id", "test", "output", Map.of("choices", List.of(Map.of("finish_reason", "stop",
                        "message", Map.of("role", "assistant", "content", chatOutput)))),
                        "usage", Map.of("input_tokens", 10, "output_tokens", 20, "total_tokens", 30));
            } else {
                exchange.sendResponseHeaders(404, -1); exchange.close(); return;
            }
            byte[] bytes = mapper.writeValueAsBytes(response);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        executor = Executors.newVirtualThreadPerTaskExecutor();
        properties.setEnabled(true);
        properties.setMaxOutputTokens(512); // 任务快照的 2048 必须覆盖当前默认上限。
        var connection = new DashScopeConnectionProperties(); connection.setBaseUrl(url); connection.setApiKey("fake-test-key");
        var embeddingProperties = new DashScopeEmbeddingProperties();
        embeddingProperties.getOptions().setModel("embedding"); embeddingProperties.getOptions().setTextType("document");
        var processing = new PaperProcessingProperties(); processing.setEmbeddingModel("embedding");
        processing.setEmbeddingVersion("v1"); processing.setEmbeddingDimension(3);
        var embedding = new RagConfiguration().ragQueryEmbeddingModel(properties, processing, connection, embeddingProperties);
        es = RestClient.builder(HttpHost.create(url)).build();
        Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));
        var calls = new RagCalls(executor, properties, clock);
        store = mock(RunStore.class);
        when(store.modelCallStarted(40L)).thenReturn(true);
        var chatModel = new RagConfiguration().ragChatModel(properties, connection);
        var caller = new RunChatCaller(chatModel, calls, store);
        retriever = new RagRetriever(es, embedding, properties, processing, json, calls, mock(PaperService.class),
                Optional.of(reranker));
        generator = new RagAnswerGenerator(caller, json);
        rewriter = new RagQueryRewriter(caller, json);
        benchmark = new RetrievalBenchmark(retriever, rewriter, chatModel, calls, properties);
        run.setId(40L); run.setUserId(20L); run.setUserQuery("论文如何检索？"); run.setModelName("qwen3.7-plus");
        run.setModelConfig("{\"maxTokens\":2048,\"temperature\":0.2}");
        run.setPapersSnapshot(json.write(List.of(scope()))); run.setDeadline(LocalDateTime.now(clock).plusSeconds(30));
        run.setSessionId(10L); run.setStatus("running"); run.setExecutionPhase("ready");
        runContext = AgentRunContext.fromClaimedRun(run, json);
    }

    private RetrievalRequest retrievalRequest() {
        return new RetrievalRequest(run.getUserQuery(), null, properties.getRetrievalTopK());
    }

    @AfterEach
    void close() throws Exception {
        if (es != null) es.close();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void nativeQueryEmbeddingTwoFilteredSearchesAndNativeChatRoundTrip() {
        var retrieval = runContext.getEvidenceRegistry().registerRetrieval(retriever.retrieve(
                retrievalRequest(), runContext.getRetrievalContext(), runContext.getDeadline()));
        var evidence = retrieval.evidence();
        assertEquals(List.of("chunk1"), retrieval.retrievedChunkIds());
        assertEquals(1, evidence.size());
        assertEquals("原文", evidence.getFirst().quotedText());
        JsonNode embedding = requests.get("/api/v1/services/embeddings/text-embedding/text-embedding").getFirst();
        assertEquals("query", embedding.at("/parameters/text_type").asText());
        var searches = requests.get("/paper_test/_search");
        assertEquals(2, searches.size());
        JsonNode lexicalFilter = searches.getFirst().at("/query/bool/filter");
        JsonNode vectorFilter = searches.getLast().at("/knn/filter");
        assertEquals(lexicalFilter, vectorFilter);
        String constraints = vectorFilter.toString();
        for (String value : List.of("metadata.ownerId", "20", "metadata.paperId", "30", "metadata.indexVersion",
                "metadata.attemptToken", "attempt", "_index", "paper_test", "metadata.pageKnown")) assertTrue(constraints.contains(value), value);
        assertEquals(3, searches.getLast().at("/knn/query_vector").size());
        var answer = generator.generate(runContext, evidence, List.of(
                new ConversationMessage("user", "前一个问题"), new ConversationMessage("assistant", "前一个回答")));
        assertEquals("答案[E1]", answer.content());
        verify(store).addModelUsage(40L, 10L, 20L);
        var chat = requests.get("/api/v1/services/aigc/multimodal-generation/generation").getFirst();
        assertEquals("qwen3.7-plus", chat.path("model").asText());
        assertEquals(2048, chat.at("/parameters/max_tokens").asInt());
        assertFalse(chat.at("/parameters/enable_search").asBoolean());
        assertEquals("system", chat.at("/input/messages/0/role").asText());
        String rules = chat.at("/input/messages/0/content/0/text").asText();
        assertTrue(rules.contains("[E1][E2]"));
        assertTrue(rules.contains("每个 ID 只出现一次"));
        assertTrue(rules.contains("数组每项都在正文中有对应标记"));
        assertTrue(chat.at("/input/messages/1/content/0/text").asText().contains("原文"));
        var history = json.tree(chat.at("/input/messages/1/content/0/text").asText()).path("recentMessages");
        assertEquals("user", history.get(0).path("role").asText());
        assertEquals("assistant", history.get(1).path("role").asText());
        assertEquals("前一个回答", history.get(1).path("content").asText());
    }

    @Test
    void rewriteUsesHistoryAndFallsBackToOriginalForInvalidOutput() {
        run.setModelConfig("{\"queryRewriteEnabled\":true,\"maxModelCalls\":2,\"maxTokens\":256}");
        var context = AgentRunContext.fromClaimedRun(run, json);
        var history = List.of(new ConversationMessage("user", "了解一下 ToolScope"));
        chatOutput = "{\"query\":\"ToolScope 的检索方法\"}";
        assertEquals("ToolScope 的检索方法", rewriter.rewrite(context, history));
        var chat = requests.get("/api/v1/services/aigc/multimodal-generation/generation").getFirst();
        var input = json.tree(chat.at("/input/messages/1/content/0/text").asText());
        assertEquals(run.getUserQuery(), input.path("question").asText());
        assertEquals("了解一下 ToolScope", input.at("/recentMessages/0/content").asText());
        assertEquals(256, chat.at("/parameters/max_tokens").asInt());
        chatOutput = "invalid json";
        assertEquals(run.getUserQuery(), rewriter.rewrite(context, history));
        verify(store, times(2)).modelCallStarted(40L);
        verify(store, times(2)).addModelUsage(40L, 10L, 20L);
    }

    @Test
    void skipsRewriteForOldDisabledAndSingleCallRuns() {
        for (String config : List.of("{}", "{\"queryRewriteEnabled\":false,\"maxModelCalls\":2}",
                "{\"queryRewriteEnabled\":true,\"maxModelCalls\":1}")) {
            run.setModelConfig(config);
            assertEquals(run.getUserQuery(), rewriter.rewrite(AgentRunContext.fromClaimedRun(run, json), List.of()));
        }
        verifyNoInteractions(store);
        assertTrue(requests.isEmpty());
    }

    @Test
    void reranksFullCandidateSetBeforeApplyingEvidenceLimit() {
        multipleHits = true;
        properties.setMaxEvidence(1);
        when(reranker.call(any(RerankRequest.class))).thenAnswer(invocation -> {
            RerankRequest request = invocation.getArgument(0);
            assertEquals(run.getUserQuery(), request.getQuery());
            assertEquals(2, request.getInstructions().size());
            var second = request.getInstructions().get(1);
            return new RerankResponse(List.of(DocumentWithScore.builder().withDocument(second).withScore(0.9).build()));
        });
        var result = retriever.retrieve(retrievalRequest(), runContext.getRetrievalContext(), runContext.getDeadline());
        assertEquals(List.of("chunk1", "chunk2"), result.retrievedChunkIds());
        assertEquals(List.of("chunk2"), result.candidates().stream().map(item -> item.chunkId()).toList());
        verifyNoInteractions(store);
    }

    @Test
    void benchmarkBaselinesCallOnlyTheirOwnLaneAndKeepSearchOrder() {
        bm25HitIds = List.of("z", "a");
        knnHitIds = List.of("y", "a");
        var bm25 = evaluate(Strategy.R0, 30, 100, 20);
        assertEquals(List.of("z", "a"), bm25.rankedChunkIds());
        assertEquals(Set.of("/paper_test/_search"), requests.keySet());
        assertEquals(30, requests.get("/paper_test/_search").getFirst().path("size").asInt());
        assertTrue(bm25.knnChunkIds().isEmpty());

        requests.clear();
        var knn = evaluate(Strategy.R1, 30, 200, 20);
        assertEquals(List.of("y", "a"), knn.rankedChunkIds());
        assertEquals(1, requests.get("/paper_test/_search").size());
        var body = requests.get("/paper_test/_search").getFirst();
        assertEquals(30, body.at("/knn/k").asInt());
        assertEquals(200, body.at("/knn/num_candidates").asInt());
        assertEquals(1, requests.get("/api/v1/services/embeddings/text-embedding/text-embedding").size());
        verifyNoInteractions(reranker, store);
    }

    @Test
    void benchmarkHybridInterleavesAndRrfPromotesSharedHits() {
        bm25HitIds = List.of("z", "shared", "b");
        knnHitIds = List.of("a", "shared", "c");
        assertEquals(List.of("z", "a", "shared", "b", "c"), evaluate(Strategy.R2, 20, 100, 20).rankedChunkIds());
        assertEquals(List.of("shared", "a", "z", "b", "c"), evaluate(Strategy.R3, 20, 100, 20).rankedChunkIds());
        verifyNoInteractions(reranker, store);
    }

    @Test
    void benchmarkRrfConstantCanChangeRankWithoutChangingRecallDepth() {
        bm25HitIds = IntStream.range(1, 31).mapToObj(i -> i == 30 ? "shared" : "b" + i).toList();
        knnHitIds = IntStream.range(1, 31).mapToObj(i -> i == 30 ? "shared" : "k" + i).toList();
        var scope = runContext.getRetrievalContext();
        var deadline = runContext.getDeadline();
        var low = benchmark.evaluate(run.getUserQuery(), scope, new RetrievalPlan(Strategy.R3, 30, 100, 10, 20), deadline);
        var high = benchmark.evaluate(run.getUserQuery(), scope, new RetrievalPlan(Strategy.R3, 30, 100, 60, 20), deadline);
        assertEquals(low.bm25ChunkIds(), high.bm25ChunkIds());
        assertEquals(low.knnChunkIds(), high.knnChunkIds());
        assertEquals("b1", low.rankedChunkIds().getFirst());
        assertEquals("shared", high.rankedChunkIds().getFirst());
    }

    @Test
    void benchmarkExposesFullRerankedWindowWithRrfTailRegardlessOfEvidenceBudget() {
        var ids = IntStream.rangeClosed(1, 12).mapToObj(i -> "chunk" + i).toList();
        bm25HitIds = ids;
        knnHitIds = ids;
        properties.setMaxEvidence(1);
        properties.setEvidenceTokenBudget(1);
        when(reranker.call(any(RerankRequest.class))).thenAnswer(invocation -> {
            RerankRequest request = invocation.getArgument(0);
            assertEquals(10, request.getOptions().getTopN());
            assertNull(request.getOptions().getModel());
            assertEquals(ids.subList(0, 10), request.getInstructions().stream().map(org.springframework.ai.document.Document::getId).toList());
            return new RerankResponse(request.getInstructions().reversed().stream()
                    .map(doc -> DocumentWithScore.builder().withDocument(doc).withScore(0.9).build()).toList());
        });
        var result = evaluate(Strategy.R4, 20, 100, 10);
        assertEquals(ids, result.candidateChunkIds());
        assertEquals(10, result.rerankInputCount());
        assertEquals(ids.subList(0, 10).reversed(), result.rankedChunkIds().subList(0, 10));
        assertEquals(ids.subList(10, 12), result.rankedChunkIds().subList(10, 12));
        assertEquals(12, json.tree(json.write(result)).path("ranked_chunk_ids").size());
    }

    @Test
    void benchmarkRewriteChangesOnlyRecallAndRecordsFallbackAndUsage() {
        bm25HitIds = List.of("a", "b");
        knnHitIds = List.of("b", "a");
        when(reranker.call(any(RerankRequest.class))).thenAnswer(invocation -> {
            RerankRequest request = invocation.getArgument(0);
            assertEquals(run.getUserQuery(), request.getQuery());
            return new RerankResponse(request.getInstructions().stream()
                    .map(doc -> DocumentWithScore.builder().withDocument(doc).withScore(0.9).build()).toList());
        });
        chatOutput = "{\"query\":\"检索原理\"}";
        var result = evaluate(Strategy.R5, 20, 100, 20);
        assertEquals("检索原理", result.recallQuery());
        assertEquals(run.getUserQuery(), result.rerankQuery());
        assertEquals("rewritten", result.rewrite().status());
        assertEquals(10, result.rewrite().inputTokens().intValue());
        assertEquals(20, result.rewrite().outputTokens().intValue());
        assertEquals("检索原理", requests.get("/paper_test/_search").getFirst().at("/query/bool/must/multi_match/query").asText());
        assertEquals("检索原理", requests.get("/api/v1/services/embeddings/text-embedding/text-embedding")
                .getFirst().at("/input/texts/0").asText());
        assertEquals(1, requests.get("/api/v1/services/aigc/multimodal-generation/generation").size());

        chatOutput = "invalid json";
        var fallback = evaluate(Strategy.R5, 20, 100, 20);
        assertEquals(run.getUserQuery(), fallback.recallQuery());
        assertEquals("invalid_fallback", fallback.rewrite().status());
        verifyNoInteractions(store);
    }

    private RetrievalBenchmark.Result evaluate(Strategy strategy, int topK, int numCandidates, int rerankInput) {
        return benchmark.evaluate(run.getUserQuery(), runContext.getRetrievalContext(),
                new RetrievalPlan(strategy, topK, numCandidates, 60, rerankInput), runContext.getDeadline());
    }

    @Test
    void expiredRunDeadlinePreventsEmbeddingAndSearchRequests() {
        run.setDeadline(runContext.getDeadline().minusMinutes(1));
        var expiredContext = AgentRunContext.fromClaimedRun(run, json);
        assertThrows(RagCalls.RequestTimeout.class, () -> retriever.retrieve(
                retrievalRequest(), expiredContext.getRetrievalContext(), expiredContext.getDeadline()));
        assertTrue(requests.isEmpty());
    }

    @Test
    void partialSearchTimeoutNeverReturnsSuccessfulEvidence() {
        timedOut = true;
        assertThrows(RagCalls.RequestTimeout.class, () -> retriever.retrieve(
                retrievalRequest(), runContext.getRetrievalContext(), runContext.getDeadline()));
        assertEquals(1, requests.get("/paper_test/_search").size());
    }

    @Test
    void rrfDeduplicatesAndRejectsForeignSourcesAndUnknownPages() {
        var response = mapper.valueToTree(Map.of("hits", Map.of("hits", List.of(hit("a", "第一段", true), hit("b", "第二段", true)))));
        var other = mapper.valueToTree(Map.of("hits", Map.of("hits", List.of(hit("b", "第二段", true)))));
        var merged = runContext.getEvidenceRegistry().registerRetrieval(retriever.fuse(20L, List.of(scope()), response, other));
        assertEquals(List.of("b", "a"), merged.retrievedChunkIds());
        assertEquals(2, merged.evidence().size()); assertEquals("b", merged.evidence().getFirst().chunkId());
        assertEquals("E1", merged.evidence().getFirst().evidenceId());
        var unknown = mapper.valueToTree(Map.of("hits", Map.of("hits", List.of(hit("c", "第三段", false)))));
        var unknownResult = runContext.getEvidenceRegistry().registerRetrieval(retriever.fuse(20L, List.of(scope()), unknown));
        // 同一个 Run 的后续空检索不清空此前已累计的候选。
        assertEquals(merged.retrievedChunkIds(), unknownResult.retrievedChunkIds());
        assertTrue(unknownResult.evidence().isEmpty());
        assertThrows(IllegalStateException.class, () -> retriever.fuse(99L, List.of(scope()), response));
    }

    @Test
    void candidateSnapshotKeepsFullRrfOrderBeyondEvidenceLimit() {
        properties.setMaxEvidence(1);
        var hits = new ArrayList<Object>();
        for (int i = 0; i < 12; i++) hits.add(hit("chunk" + i, "quote " + i, true));
        var response = mapper.valueToTree(Map.of("hits", Map.of("hits", hits)));
        var duplicate = mapper.valueToTree(Map.of("hits", Map.of("hits", List.of(
                hit("chunk11", "quote 11", true), hit("chunk11", "quote 11", true)))));
        var result = runContext.getEvidenceRegistry().registerRetrieval(retriever.fuse(20L, List.of(scope()), response, duplicate));
        var expected = new ArrayList<String>();
        expected.add("chunk11");
        for (int i = 0; i < 11; i++) expected.add("chunk" + i);
        assertEquals(expected, result.retrievedChunkIds());
        assertEquals(1, result.evidence().size());
        assertEquals("chunk11", result.evidence().getFirst().chunkId());
    }

    @Test
    void tokenBudgetAndIdenticalTextOnlyFilterFinalEvidence() {
        properties.setEvidenceTokenBudget(4);
        var response = mapper.valueToTree(Map.of("hits", Map.of("hits", List.of(
                hit("a", "large ".repeat(100), true), hit("b", "tiny", true), hit("c", "tiny", true)))));
        var result = runContext.getEvidenceRegistry().registerRetrieval(retriever.fuse(20L, List.of(scope()), response));
        assertEquals(List.of("a", "b", "c"), result.retrievedChunkIds());
        assertEquals(1, result.evidence().size());
        assertEquals("b", result.evidence().getFirst().chunkId());
        assertEquals("E1", result.evidence().getFirst().evidenceId());
    }

    @Test
    void sourceValidationStillCoversCandidatesPastEvidenceLimit() {
        properties.setMaxEvidence(1);
        var foreign = mapper.valueToTree(hit("b", "foreign", true));
        ((com.fasterxml.jackson.databind.node.ObjectNode) foreign.at("/_source/metadata")).put("ownerId", "99");
        var response = mapper.valueToTree(Map.of("hits", Map.of("hits", List.of(hit("a", "valid", true), foreign))));
        assertThrows(IllegalStateException.class, () -> retriever.fuse(20L, List.of(scope()), response));
    }

    @Test
    void emptySearchProducesRecordedEmptyCandidatesAndEvidence() {
        var empty = mapper.valueToTree(Map.of("hits", Map.of("hits", List.of())));
        var result = runContext.getEvidenceRegistry().registerRetrieval(retriever.fuse(20L, List.of(scope()), empty, empty));
        assertTrue(result.retrievedChunkIds().isEmpty());
        assertTrue(result.evidence().isEmpty());
    }

    @Test
    void inventedOrMismatchedCitationsAreNotPublished() {
        for (String output : List.of("答案[E1]", "{\"answer\":\"伪造[E9]\",\"evidenceIds\":[\"E9\"]}",
                "{\"answer\":\"未标引用\",\"evidenceIds\":[\"E1\"]}", "{\"answer\":\"答案[E1]\",\"evidenceIds\":[]}",
                "{\"answer\":\"答案[E1]\",\"evidenceIds\":[\"E1\"]} {\"extra\":true}")) {
            assertThrows(AnswerValidator.InvalidAnswer.class, () -> generator.parse(output, List.of(evidence())));
        }
    }

    private Object hit(String id, String quote, boolean pageKnown) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("ownerId", "20"); meta.put("paperId", "30"); meta.put("indexVersion", 1); meta.put("attemptToken", "attempt");
        meta.put("embeddingModel", "embedding"); meta.put("embeddingVersion", "v1"); meta.put("chunkId", id);
        meta.put("pageStart", 2); meta.put("pageEnd", 3); meta.put("pageKnown", pageKnown); meta.put("lowConfidence", false);
        return Map.of("_index", "paper_test", "_id", id, "_source", Map.of("metadata", meta, "content", "Paper\nMethods\n\n" + quote));
    }
}
