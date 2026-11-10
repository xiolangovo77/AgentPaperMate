package com.ecnu.agentpapermate.rag;

import com.ecnu.agentpapermate.rag.model.EvidenceCandidate;
import com.ecnu.agentpapermate.rag.model.PaperScope;
import com.ecnu.agentpapermate.rag.model.RetrievalCandidates;
import com.ecnu.agentpapermate.rag.model.RetrievalRequest;
import com.ecnu.agentpapermate.rag.model.RetrievalContext;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.ecnu.agentpapermate.config.paper.PaperProcessingProperties;
import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.service.PaperService;
import com.ecnu.agentpapermate.support.ai.RagCalls;
import com.ecnu.agentpapermate.support.json.RagJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

/** 自行发送 BM25 和带前置过滤的 kNN 请求，在 Java 中按 RRF 合并证据。 */
@Component
public class RagRetriever {
    /**
     * 论文索引专用的 Elasticsearch REST 客户端，用于发送文本及向量检索请求。
     */
    private final RestClient client;
    /**
     * 默认使用 query 模式的问题向量模型，与论文入库的 document 模型实例分开。
     */
    private final EmbeddingModel embedding;
    /**
     * 检索配置，提供候选数量、证据数量及原文 Token 预算。
     */
    private final RagProperties properties;
    /**
     * 当前向量模型及索引版本配置，用于核对任务快照的兼容性。
     */
    private final PaperProcessingProperties processing;
    /**
     * JSON 转换工具，用于转换 ES 请求、响应。
     */
    private final RagJson json;
    /**
     * 外部调用执行器，同时约束单次请求超时与任务剩余运行预算。
     */
    private final RagCalls calls;
    /** 每次访问前核对当前论文归属，Run 快照不能绕过删除或撤权。 */
    private final PaperService papers;
    /**
     * cl100k_base 编码器，用于估算证据文本的 Token 数量并控制上下文预算。
     */
    private final Encoding encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    public RagRetriever(@Qualifier("paperElasticsearchClient") RestClient client,
                        @Qualifier("ragQueryEmbeddingModel") EmbeddingModel embedding, RagProperties properties,
                        PaperProcessingProperties processing, RagJson json, RagCalls calls, PaperService papers) {
        this.client = client; this.embedding = embedding; this.properties = properties;
        this.processing = processing; this.json = json; this.calls = calls;
        this.papers = papers;
    }

    /** deadline 由后端 AgentRunContext 提供，与检索范围分开传递。 */
    public RetrievalCandidates retrieve(RetrievalRequest request, RetrievalContext context, LocalDateTime deadline) {
        if (request == null || context == null || deadline == null) {
            throw new IllegalArgumentException("检索请求、范围上下文和截止时间不能为空");
        }
        // 固定 RAG 与 Agent 共用配置上限，越界请求在访问外部服务前拒绝。
        if (request.topK() > properties.getRetrievalTopK()) {
            throw new IllegalArgumentException("每路检索候选数量不能超过配置上限：" + properties.getRetrievalTopK());
        }
        //1、用 query 模式生成问题向量，不能沿用入库时的 document 模式
        List<PaperScope> scopes = resolveScopes(request, context);
        for (PaperScope scope : scopes) {
            if (!Objects.equals(scope.embeddingModel(), processing.getEmbeddingModel())
                    || !Objects.equals(scope.embeddingVersion(), processing.getEmbeddingVersion())
                    || scope.embeddingDimension() != processing.getEmbeddingDimension()) {
                throw new IllegalStateException("任务向量配置已变化，请重新提交任务");
            }
        }
        float[] vector = calls.call(deadline, () -> {
            verifyAccess(context.userId(), scopes);
            var options = DashScopeEmbeddingOptions.builder().withModel(scopes.getFirst().embeddingModel())
                    .withTextType("query").build();
            var response = embedding.call(new EmbeddingRequest(List.of(request.query()), options));
            if (response.getResults().size() != 1) throw new IllegalStateException("问题向量数量错误");
            return response.getResults().getFirst().getOutput();
        });
        if (vector.length != scopes.getFirst().embeddingDimension()) throw new IllegalStateException("问题向量维度错误");
        for (float number : vector) if (!Float.isFinite(number)) throw new IllegalStateException("问题向量含无效数值");

        //2、两路检索使用相同权限与快照约束，未知页码片段不作为可发布引用
        Object filter = filter(context.userId(), scopes);
        int k = request.topK();
        Map<String, Object> lexical = Map.of("size", k, "_source", List.of("content", "metadata"),
                "query", Map.of("bool", Map.of("filter", filter, "must", Map.of("multi_match", Map.of(
                        "query", request.query(), "fields", List.of("content", "content.en", "content.cjk"))))));
        Map<String, Object> semantic = Map.of("size", k, "_source", List.of("content", "metadata"), "knn", Map.of(
                "field", "embedding", "query_vector", vector, "k", k, "num_candidates", Math.max(100, k * 5), "filter", filter));
        String path = searchPath(scopes);
        JsonNode bm25 = calls.call(deadline, () -> {
            verifyAccess(context.userId(), scopes);
            return search(path, lexical);
        });
        JsonNode knn = calls.call(deadline, () -> {
            verifyAccess(context.userId(), scopes);
            return search(path, semantic);
        });

        //3、按排序名次融合而非直接相加不同量纲的分数，统一去重并限制证据预算
        return fuse(context.userId(), scopes, bm25, knn);
    }

    private List<PaperScope> resolveScopes(RetrievalRequest request, RetrievalContext context) {
        List<PaperScope> allowed = context.allowedScopes();
        if (allowed.size() > properties.getMaxPapers()) throw new IllegalArgumentException("检索论文范围超过上限");
        if (request.requestedPaperIds() == null) return allowed;
        Set<Long> requested = new HashSet<>(request.requestedPaperIds());
        Set<Long> allowedIds = new HashSet<>();
        allowed.forEach(scope -> allowedIds.add(scope.paperId()));
        if (!allowedIds.containsAll(requested)) throw new IllegalArgumentException("指定论文超出本次运行的资料范围");
        // 始终保留后端快照的索引、版本和尝试令牌，不从论文当前索引重建范围。
        return allowed.stream().filter(scope -> requested.contains(scope.paperId())).toList();
    }

    private void verifyAccess(Long userId, List<PaperScope> scopes) {
        for (PaperScope scope : scopes) papers.requireOwnedPaper(scope.paperId(), userId);
    }

    Object filter(Long userId, List<PaperScope> scopes) {
        List<Object> allowed = new ArrayList<>();
        for (PaperScope scope : scopes) {
            allowed.add(Map.of("bool", Map.of("filter", List.of(
                    term("_index", scope.esIndexName()), term("metadata.paperId", scope.paperId().toString()),
                    term("metadata.indexVersion", scope.indexVersion()), term("metadata.attemptToken", scope.attemptToken()),
                    term("metadata.embeddingModel", scope.embeddingModel()), term("metadata.embeddingVersion", scope.embeddingVersion())))));
        }
        return Map.of("bool", Map.of("filter", List.of(term("metadata.ownerId", userId.toString()),
                term("metadata.pageKnown", true), term("metadata.lowConfidence", false),
                Map.of("bool", Map.of("should", allowed, "minimum_should_match", 1)))));
    }

    RetrievalCandidates fuse(Long userId, List<PaperScope> scopes, JsonNode... responses) {
        Map<String, JsonNode> hits = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        for (JsonNode response : responses) {
            Set<String> seen = new HashSet<>();
            int rank = 0;
            for (JsonNode hit : response.at("/hits/hits")) {
                String key = hit.path("_index").asText() + "/" + hit.path("_id").asText();
                if (!seen.add(key)) continue;
                hits.putIfAbsent(key, hit);
                scores.merge(key, 1.0 / (60 + ++rank), Double::sum);
            }
        }
        List<String> ordered = scores.keySet().stream().sorted(Comparator
                .<String>comparingDouble(scores::get).reversed().thenComparing(Comparator.naturalOrder())).toList();
        List<Candidate> candidates = new ArrayList<>();
        Set<String> chunkIds = new HashSet<>();
        for (String key : ordered) {
            JsonNode hit = hits.get(key);
            JsonNode meta = hit.at("/_source/metadata");
            PaperScope scope = scopes.stream().filter(item -> item.paperId().toString().equals(meta.path("paperId").asText())
                    && item.esIndexName().equals(hit.path("_index").asText())
                    && item.indexVersion() == meta.path("indexVersion").asInt()
                    && item.attemptToken().equals(meta.path("attemptToken").asText())).findFirst().orElse(null);
            //4、再次核验返回来源，拒绝越权、错误版本或伪造页码的响应
            if (scope == null || !userId.toString().equals(meta.path("ownerId").asText())
                    || !scope.embeddingModel().equals(meta.path("embeddingModel").asText())
                    || !scope.embeddingVersion().equals(meta.path("embeddingVersion").asText())) {
                throw new IllegalStateException("ES 返回了范围外的证据");
            }
            int start = meta.path("pageStart").asInt();
            int end = meta.path("pageEnd").asInt();
            if (!meta.path("pageKnown").asBoolean() || meta.path("lowConfidence").asBoolean()
                    || start < 1 || end < start) continue;
            String chunkId = meta.path("chunkId").asText();
            String content = hit.at("/_source/content").asText();
            //5、现有分块以标题和章节作为前缀，引用仅保存其后的提取正文
            int boundary = content.indexOf("\n\n");
            String quote = (boundary >= 0 ? content.substring(boundary + 2) : content).strip();
            if (quote.isBlank() || chunkId.isBlank() || chunkId.length() > 128) continue;
            if (chunkIds.add(chunkId)) candidates.add(new Candidate(scope, chunkId, start, end, quote));
        }

        //6、先收集完整候选，再做原文去重、Token 和数量筛选，避免 break 截短候选快照
        List<EvidenceCandidate> result = new ArrayList<>();
        int tokens = 0;
        Set<String> contents = new HashSet<>();
        for (Candidate candidate : candidates) {
            PaperScope scope = candidate.scope();
            int cost = encoding.countTokens(candidate.quote());
            if (tokens + cost > properties.getEvidenceTokenBudget()
                    || !contents.add(scope.paperId() + "\n" + candidate.quote())) continue;
            result.add(new EvidenceCandidate(scope, candidate.chunkId(), candidate.start(), candidate.end(), candidate.quote()));
            tokens += cost;
            if (result.size() >= properties.getMaxEvidence()) break;
        }
        return new RetrievalCandidates(candidates.stream().map(Candidate::chunkId).toList(), result);
    }

    private record Candidate(PaperScope scope, String chunkId, int start, int end, String quote) { }

    private JsonNode search(String path, Object body) {
        //1、显式拒绝分片失败和超时的部分结果，避免把不完整检索标为完成
        Request request = new Request("POST", path);
        request.addParameter("allow_partial_search_results", "false");
        request.setJsonEntity(json.write(body));
        try (var input = client.performRequest(request).getEntity().getContent()) {
            JsonNode result = json.tree(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            if (result.path("timed_out").asBoolean()) throw new RagCalls.RequestTimeout();
            if (result.at("/_shards/failed").asInt() > 0 || !result.at("/hits/hits").isArray()) {
                throw new IllegalStateException("ES 检索结果不完整");
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("ES 检索请求失败", e);
        }
    }

    private String searchPath(List<PaperScope> scopes) {
        for (PaperScope scope : scopes) {
            if (!scope.esIndexName().matches("[a-z0-9][a-z0-9._-]*")) throw new IllegalStateException("索引名称无效");
        }
        return "/" + String.join(",", scopes.stream().map(PaperScope::esIndexName).distinct().toList()) + "/_search";
    }

    private Object term(String field, Object value) { return Map.of("term", Map.of(field, value)); }
}
