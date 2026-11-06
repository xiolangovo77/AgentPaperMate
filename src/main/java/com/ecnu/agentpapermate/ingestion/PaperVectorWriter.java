package com.ecnu.agentpapermate.ingestion;

import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import org.springframework.ai.autoconfigure.vectorstore.elasticsearch.ElasticsearchVectorStoreProperties;
import org.springframework.ai.vectorstore.elasticsearch.SimilarityFunction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 管理论文分块索引，负责索引兼容性校验、向量写入及本次尝试的分块数量验证
 */
@Component
public class PaperVectorWriter {
    /**
     * 论文索引专用的 Elasticsearch REST 客户端
     */
    private final RestClient client;
    /**
     * 将论文分块文本转换为向量的模型
     */
    private final EmbeddingModel embedding;
    /**
     * 用于读取索引模板及序列化 Elasticsearch 请求和响应
     */
    private final ObjectMapper json;
    /**
     * 向量存储配置，包含相似度算法和是否允许自动建索引
     */
    private final ElasticsearchVectorStoreProperties properties;

    /**
     * 注入向量写入依赖，并校验索引使用余弦相似度
     */
    public PaperVectorWriter(@Qualifier("paperElasticsearchClient") RestClient client,
                             @Qualifier("paperEmbeddingModel") EmbeddingModel embedding, ObjectMapper json, ElasticsearchVectorStoreProperties properties) {
        this.client = client;
        this.embedding = embedding;
        this.json = json;
        this.properties = properties;
        if (properties.getSimilarity() != SimilarityFunction.cosine) {
            throw new IllegalArgumentException("当前论文索引仅支持 cosine 相似度");
        }
    }

    /**
     * 按配置创建缺失索引，并校验维度、模型版本及尝试隔离和来源追踪字段
     */
    public void ensureIndex(PaperProcessingJob job) {
        // 1、按任务中的索引名称检查索引是否存在
        String path = indexPath(job);
        try {
            // 低级 REST 客户端对 HEAD 404 返回响应，不抛 ResponseException。
            if (client.performRequest(new Request("HEAD", path)).getStatusLine().getStatusCode() == 404) {
                // 2、索引缺失时检查建索引开关，按任务的向量维度和模型版本填充模板
                if (!properties.isInitializeSchema()) {
                    throw new IllegalStateException("ES 索引不存在且 initialize-schema=false");
                }
                try (var input = new ClassPathResource("elasticsearch/paper-chunks.json").getInputStream()) {
                    var mapping = (ObjectNode) json.readTree(input);
                    ((ObjectNode) mapping.at("/mappings/properties/embedding")).put("dims", job.getEmbeddingDimension());
                    ((ObjectNode) mapping.at("/mappings/_meta")).put("embeddingModel", job.getEmbeddingModel())
                            .put("embeddingVersion", job.getEmbeddingVersion());
                    try {
                        request("PUT", path, mapping);
                    } catch (ResponseException race) {
                        // 其他任务抢先建好同名索引时，继续校验实际映射
                        if (race.getResponse().getStatusLine().getStatusCode() != 400
                                || !new String(race.getResponse().getEntity().getContent().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                                .contains("resource_already_exists_exception")) {
                            throw race;
                        }
                    }
                }
            }
            // 3、读取实际映射，核对向量配置、执行令牌和来源字段
            JsonNode mapping = request("GET", path + "/_mapping", null).path(job.getEsIndexName()).path("mappings");
            if (mapping.at("/properties/embedding/dims").asInt() != job.getEmbeddingDimension()
                    || !properties.getSimilarity().name().equals(mapping.at("/properties/embedding/similarity").asText())
                    || !job.getEmbeddingModel().equals(mapping.at("/_meta/embeddingModel").asText())
                    || !job.getEmbeddingVersion().equals(mapping.at("/_meta/embeddingVersion").asText())
                    || !"keyword".equals(mapping.at("/properties/metadata/properties/attemptToken/type").asText())
                    || !"keyword".equals(mapping.at("/properties/metadata/properties/sourceBlockIds/type").asText())) {
                throw new IllegalStateException("ES 索引与模型、维度或尝试隔离字段不兼容，请使用新索引");
            }
        } catch (IOException e) {
            throw new IllegalStateException("ES 索引初始化失败", e);
        }
    }

    /**
     * 将一批论文文档向量化并写入任务指定的索引
     */
    public void write(PaperProcessingJob job, List<Document> documents) {
        // 1、使用任务快照指定的索引名称和向量维度
        var options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(job.getEsIndexName());
        options.setDimensions(job.getEmbeddingDimension());
        // 2、将本批文档向量化，写入已校验的索引
        ElasticsearchVectorStore.builder(client, embedding).options(options).initializeSchema(false).build().add(documents);
    }

    /**
     * 刷新索引并检查分片结果，确认当前论文、索引版本及尝试令牌对应的分块数量符合预期
     */
    public void verify(PaperProcessingJob job, int expected) {
        try {
            // 1、刷新索引使新分块可检索，并检查是否有分片刷新失败
            JsonNode refresh = request("POST", indexPath(job) + "/_refresh", null);
            if (refresh.at("/_shards/failed").asInt() != 0) {
                throw new IllegalStateException("ES 刷新失败");
            }
            // 2、按论文 ID、索引版本和租约令牌限定本次写入的分块
            var filters = List.of(Map.of("term", Map.of("metadata.paperId", job.getPaperId().toString())),
                    Map.of("term", Map.of("metadata.indexVersion", job.getIndexVersion())),
                    Map.of("term", Map.of("metadata.attemptToken", job.getLeaseToken())));
            // 3、统计匹配分块，确认分片查询成功且数量符合预期
            JsonNode count = request("POST", indexPath(job) + "/_count", Map.of("query", Map.of("bool", Map.of("filter", filters))));
            if (count.path("count").asInt(-1) != expected || count.at("/_shards/failed").asInt() != 0) {
                throw new IllegalStateException("ES 可检索分块数量与预期不一致");
            }
        } catch (IOException e) {
            throw new IllegalStateException("ES 索引验证失败", e);
        }
    }

    /**
     * 校验索引名称仅包含允许的字符，并构造索引请求路径
     */
    private String indexPath(PaperProcessingJob job) {
        if (!job.getEsIndexName().matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("无效索引名");
        }
        return "/" + job.getEsIndexName();
    }

    /**
     * 发送可选 JSON 请求体的 Elasticsearch 请求，并读取 JSON 响应
     */
    private JsonNode request(String method, String path, Object body) throws IOException {
        // 1、构造索引请求，有请求体时序列化为 JSON
        Request request = new Request(method, path);
        if (body != null) {
            request.setJsonEntity(json.writeValueAsString(body));
        }
        // 2、发送请求并读取 JSON 响应，读取完毕后关闭响应流
        try (var input = client.performRequest(request).getEntity().getContent()) {
            return json.readTree(input);
        }
    }
}
