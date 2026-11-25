package com.ecnu.agentpapermate.rag;

import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.rag.model.RetrievalCandidates;
import com.ecnu.agentpapermate.rag.model.RetrievalContext;
import com.ecnu.agentpapermate.rag.model.RetrievalRequest;
import com.ecnu.agentpapermate.support.ai.RagCalls;
import com.ecnu.agentpapermate.support.json.RagJson;
import com.fasterxml.jackson.databind.JsonNode;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 按标题和摘要召回论文，沿用会话快照中的段落索引及 R4 检索配置。 */
@Component
public class PaperLibraryRetriever {
    /** 保存论文标题、摘要和索引版本的 Elasticsearch 索引名称，与回填工具共用。 */
    public static final String INDEX_NAME = "agentpapermate-paper-library-v1";

    /** 论文专用客户端，用于查询论文级 Elasticsearch 索引。 */
    private final RestClient restClient;
    /** JSON 转换工具，用于写入搜索请求及解析搜索响应。 */
    private final RagJson ragJson;
    /** 外部调用执行器，按每次请求和本次任务的剩余预算限制等待时间。 */
    private final RagCalls ragCalls;
    /** 检索配置，用于读取论文级粗召的数量上限。 */
    private final RagProperties ragProperties;
    /** 段落检索器，复用会话范围校验及段落召回和精排。 */
    private final RagRetriever ragRetriever;

    public PaperLibraryRetriever(@Qualifier("paperElasticsearchClient") RestClient restClient, RagJson ragJson,
                                 RagCalls ragCalls, RagProperties ragProperties, RagRetriever ragRetriever) {
        this.restClient = restClient;
        this.ragJson = ragJson;
        this.ragCalls = ragCalls;
        this.ragProperties = ragProperties;
        this.ragRetriever = ragRetriever;
    }

    public RetrievalCandidates retrieve(RetrievalRequest request, RetrievalContext context, LocalDateTime deadline) {
        //1、先从本次问答的论文范围中召回相关论文
        var ids = selectPaperIds(request, context, deadline);
        if (ids.isEmpty()) return new RetrievalCandidates(List.of(), List.of());
        //2、在命中论文的有效索引版本中检索可引用的段落
        return ragRetriever.retrieve(new RetrievalRequest(request.query(), ids, request.topK()), context, deadline);
    }

    public List<Long> selectPaperIds(RetrievalRequest request, RetrievalContext context, LocalDateTime deadline) {
        //1、从问答快照取得获准检索的论文及其有效索引版本
        var scopes = ragRetriever.resolveScopes(request, context);
        var paperIds = scopes.stream().collect(Collectors.toMap(scope -> scope.paperId().toString(), scope -> scope.paperId()));
        var scopeKeys = scopes.stream()
                .map(scope -> scope.paperId() + ":" + scope.indexVersion() + ":" + scope.attemptToken()).toList();
        //2、按标题和摘要打分，同时统计零分文档以核对索引覆盖率
        var body = Map.of("size", Math.min(ragProperties.getPaperLibraryTopP(), scopes.size()),
                "track_total_hits", true, "_source", false,
                "sort", List.of(Map.of("_score", "desc"), Map.of("paperId", "asc")),
                "query", Map.of("bool", Map.of(
                        "filter", List.of(Map.of("term", Map.of("ownerId", context.userId().toString())),
                                Map.of("terms", Map.of("scopeKey", scopeKeys))),
                        "should", List.of(Map.of("multi_match", Map.of("query", request.query(), "fields",
                                List.of("title^3", "title.en^3", "title.cjk^3", "abstractText", "abstractText.en", "abstractText.cjk")))),
                        "minimum_should_match", 0)));
        JsonNode response = ragCalls.call(deadline, () -> search(body));
        //3、确认范围内所有论文的元数据均已回填，并按粗召顺序保留正分命中
        if (response.path("timed_out").asBoolean() || response.at("/_shards/failed").asInt(-1) != 0) {
            throw new IllegalStateException("论文索引查询超时或分片失败");
        }
        if (response.at("/hits/total/value").asInt(-1) != scopes.size()) {
            throw new IllegalStateException("论文级索引缺少当前会话的论文版本，请先回填索引");
        }
        var hits = response.at("/hits/hits");
        if (!hits.isArray()) throw new IllegalStateException("论文索引查询结果无效");
        List<Long> result = new ArrayList<>();
        for (JsonNode hit : hits) {
            if (hit.path("_score").asDouble() <= 0) break;
            Long id = paperIds.get(hit.path("_id").asText());
            if (id == null) throw new IllegalStateException("论文索引返回了范围外的论文");
            result.add(id);
        }
        return List.copyOf(result);
    }

    private JsonNode search(Object body) {
        //1、发送请求并拒绝分片失败时返回部分命中
        var request = new Request("POST", "/" + INDEX_NAME + "/_search");
        request.addParameter("allow_partial_search_results", "false");
        request.setJsonEntity(ragJson.write(body));
        //2、读取应答并交给调用方核对论文覆盖率
        try (var input = restClient.performRequest(request).getEntity().getContent()) {
            return ragJson.tree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("论文索引查询失败", e);
        }
    }
}
