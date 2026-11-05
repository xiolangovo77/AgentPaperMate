package com.ecnu.agentpapermate.ingestion;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingProperties;
import com.ecnu.agentpapermate.config.paper.PaperIngestionConfiguration;
import com.ecnu.agentpapermate.config.paper.PaperIngestionProperties;
import com.ecnu.agentpapermate.config.paper.PaperJobSchedulerProperties;
import com.ecnu.agentpapermate.config.paper.PaperProcessingProperties;
import org.springframework.ai.autoconfigure.vectorstore.elasticsearch.ElasticsearchVectorStoreProperties;
import org.springframework.ai.vectorstore.elasticsearch.SimilarityFunction;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchProperties;
import com.ecnu.agentpapermate.entity.model.Paper;
import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.ecnu.agentpapermate.mange.CosManager;
import com.ecnu.agentpapermate.mapper.PaperMapper;
import com.ecnu.agentpapermate.service.PaperJobClaimService;
import com.ecnu.agentpapermate.service.PaperJobProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用本地 HTTP 替身跑真实 Spring AI Embedding + ES Bulk 序列化，不调用付费模型。 */
class PaperIngestionTest {
    private static final String TEI = """
            <TEI xmlns="http://www.tei-c.org/ns/1.0"><teiHeader><fileDesc><titleStmt>
            <title>Test Paper</title></titleStmt></fileDesc></teiHeader><text><body><div><head>Methods</head>
            <p coords="2,0,0,10,10;3,0,0,10,10">A scientific paragraph about retrieval and reliable indexing.</p>
            <p>Another paragraph without known physical page coordinates.</p>
            </div></body></text></TEI>
            """;
    private final ObjectMapper json = new ObjectMapper();
    private final PaperMapper papers = mock(PaperMapper.class);
    private final CosManager cos = mock(CosManager.class);
    private final PaperJobClaimService jobs = mock(PaperJobClaimService.class);
    private final PaperIngestionProperties properties = new PaperIngestionProperties();
    private final ElasticsearchVectorStoreProperties vectorStore = new ElasticsearchVectorStoreProperties();
    private final PaperProcessingProperties processing = new PaperProcessingProperties();
    private final PaperProcessingJob job = new PaperProcessingJob();
    private final AtomicInteger indexed = new AtomicInteger();
    private final AtomicBoolean failBulk = new AtomicBoolean();
    private final AtomicBoolean indexExists = new AtomicBoolean(true);
    private final AtomicBoolean wrongEmbeddingDimensions = new AtomicBoolean();
    private final List<String> ids = new ArrayList<>();
    private HttpServer server;
    private org.elasticsearch.client.RestClient es;
    private ScheduledExecutorService heartbeat;
    private PaperJobProcessor processor;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (exchange.getRequestMethod().equals("HEAD") && !indexExists.get()) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if (exchange.getRequestMethod().equals("PUT") && path.equals("/paper_test")) {
                var mapping = json.readTree(requestBody);
                assertEquals(3, mapping.at("/mappings/properties/embedding/dims").asInt());
                assertEquals("english", mapping.at("/mappings/properties/content/fields/en/analyzer").asText());
                assertEquals("test-model", mapping.at("/mappings/_meta/embeddingModel").asText());
                indexExists.set(true);
            }
            String response;
            if (path.equals("/api/processFulltextDocument")) {
                response = TEI;
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("X-Elastic-Product", "Elasticsearch");
                if (path.equals("/api/v1/services/embeddings/text-embedding/text-embedding")) {
                    var body = json.readTree(requestBody);
                    assertEquals("Bearer fake-test-key", exchange.getRequestHeaders().getFirst("Authorization"));
                    assertEquals("test-model", body.path("model").asText());
                    assertEquals("document", body.at("/parameters/text_type").asText());
                    int size = body.at("/input/texts").size();
                    List<Object> data = new ArrayList<>();
                    for (int i = 0; i < size; i++) data.add(Map.of("text_index", i, "embedding",
                            wrongEmbeddingDimensions.get() ? List.of(0.1, 0.2) : List.of(0.1, 0.2, 0.3)));
                    response = json.writeValueAsString(Map.of("request_id", "test-request", "output", Map.of("embeddings", data),
                            "usage", Map.of("total_tokens", 10)));
                } else if (path.equals("/paper_test/_mapping")) {
                    response = """
                            {"paper_test":{"mappings":{"_meta":{"embeddingModel":"test-model","embeddingVersion":"v1"},
                            "properties":{"embedding":{"dims":3,"similarity":"cosine"},"metadata":{"properties":{"attemptToken":{"type":"keyword"},"sourceBlockIds":{"type":"keyword"}}}}}}}
                            """;
                } else if (path.equals("/_bulk")) {
                    String[] lines = requestBody.strip().split("\n");
                    List<Object> items = new ArrayList<>();
                    for (int i = 0; i < lines.length; i += 2) {
                        String id = json.readTree(lines[i]).path("index").path("_id").asText();
                        ids.add(id);
                        assertEquals(3, json.readTree(lines[i + 1]).path("embedding").size());
                        if (!failBulk.get()) indexed.incrementAndGet();
                        items.add(Map.of("index", failBulk.get()
                                ? Map.of("_index", "paper_test", "_id", id, "status", 400,
                                "error", Map.of("type", "mapper_parsing_exception", "reason", "test failure"))
                                : Map.of("_index", "paper_test", "_id", id, "status", 201)));
                    }
                    response = json.writeValueAsString(Map.of("took", 1, "errors", failBulk.get(), "items", items));
                } else if (path.equals("/paper_test/_count")) {
                    response = "{\"count\":" + indexed.get() + ",\"_shards\":{\"failed\":0}}";
                } else response = "{\"_shards\":{\"failed\":0}}";
            }
            if (exchange.getRequestMethod().equals("HEAD")) exchange.sendResponseHeaders(200, -1);
            else {
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.setEnabled(true);
        properties.setGrobidUrl(url);
        var connection = new DashScopeConnectionProperties();
        connection.setBaseUrl(url);
        connection.setApiKey("fake-test-key");
        var embedding = new DashScopeEmbeddingProperties();
        embedding.getOptions().setModel("test-model");
        embedding.getOptions().setTextType("document");
        var elasticsearch = new ElasticsearchProperties();
        elasticsearch.setUris(List.of(url));
        vectorStore.setInitializeSchema(true);
        vectorStore.setSimilarity(SimilarityFunction.cosine);
        properties.setEmbeddingBatchSize(1);
        processing.setParserVersion("grobid-test");
        processing.setChunkingVersion("chunk-v1");
        processing.setEmbeddingModel("test-model");
        processing.setEmbeddingVersion("v1");
        processing.setEmbeddingDimension(3);
        job.setId(10L); job.setPaperId(20L); job.setIndexVersion(1); job.setLeaseToken("attempt-a");
        job.setParserVersion("grobid-test"); job.setChunkingVersion("chunk-v1");
        job.setEmbeddingModel("test-model"); job.setEmbeddingVersion("v1"); job.setEmbeddingDimension(3);
        job.setEsIndexName("paper_test");
        job.setChunkingConfig("{\"unit\":\"tokens\",\"chunkSize\":800,\"chunkOverlap\":100}");
        Paper paper = new Paper(); paper.setId(20L); paper.setUserId(30L); paper.setFileUrl("test.pdf"); paper.setFileHash("expected");
        when(papers.selectById(20L)).thenReturn(paper);
        when(jobs.renew(any(), anyInt())).thenReturn(true);
        when(cos.downloadPaper(any(), anyLong(), any())).thenAnswer(call -> {
            Files.writeString(call.getArgument(2), "%PDF-test");
            var info = new CosManager.PaperFileInfo(); info.setFileHash("expected"); return info;
        });
        var configuration = new PaperIngestionConfiguration();
        es = configuration.paperElasticsearchClient(properties, elasticsearch);
        heartbeat = Executors.newSingleThreadScheduledExecutor();
        processor = new PaperJobProcessor(properties, processing, new PaperJobSchedulerProperties(), papers, cos,
                new GrobidClient(properties), new PaperChunker(json),
                new PaperVectorWriter(es, configuration.paperEmbeddingModel(properties, processing, connection, embedding),
                        json, vectorStore), jobs, heartbeat);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (heartbeat != null) heartbeat.shutdownNow();
        if (es != null) es.close();
        if (server != null) server.stop(0);
    }

    @Test
    void runsGrobidEmbeddingBulkVerificationAndPublishesOnlyAfterSuccess() {
        processor.process(job);
        assertEquals(1, indexed.get());
        assertEquals(List.of("20:1:attempt-a:0"), ids);
        verify(jobs).complete(eq(job), argThat(p -> p.title().equals("Test Paper")), eq(1), eq(1800));
        verify(jobs).updateParsedMetadata(eq(job), argThat(p -> p.title().equals("Test Paper")));
        assertTemporaryFileDeleted();
    }

    @Test
    void partialBulkErrorDoesNotPublishReady() {
        failBulk.set(true);
        assertThrows(RuntimeException.class, () -> processor.process(job));
        verify(jobs, never()).complete(any(), any(), anyInt(), anyInt());
        verify(jobs).updateParsedMetadata(eq(job), argThat(p -> p.title().equals("Test Paper")));
        assertTemporaryFileDeleted();
    }

    @Test
    void createsIndexWithModelDimensionAndTextAnalyzers() {
        indexExists.set(false);
        processor.process(job);
        assertTrue(indexExists.get());
        verify(jobs).complete(eq(job), any(), eq(1), eq(1800));
    }

    @Test
    void missingIndexWithInitializationDisabledDoesNotWriteOrPublish() {
        indexExists.set(false);
        vectorStore.setInitializeSchema(false);
        assertThrows(IllegalStateException.class, () -> processor.process(job));
        assertFalse(indexExists.get());
        assertEquals(0, indexed.get());
        verify(jobs, never()).complete(any(), any(), anyInt(), anyInt());
    }

    @Test
    void wrongEmbeddingDimensionsDoNotWriteOrPublish() {
        wrongEmbeddingDimensions.set(true);
        assertThrows(IllegalStateException.class, () -> processor.process(job));
        assertEquals(0, indexed.get());
        verify(jobs, never()).complete(any(), any(), anyInt(), anyInt());
    }

    @Test
    void lostInitialLeaseDoesNotDownloadOrIndex() {
        when(jobs.renew(any(), anyInt())).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> processor.process(job));
        verifyNoInteractions(cos);
        assertEquals(0, indexed.get());
    }

    private void assertTemporaryFileDeleted() {
        ArgumentCaptor<Path> path = ArgumentCaptor.forClass(Path.class);
        verify(cos).downloadPaper(any(), anyLong(), path.capture());
        assertFalse(Files.exists(path.getValue()));
    }
}
