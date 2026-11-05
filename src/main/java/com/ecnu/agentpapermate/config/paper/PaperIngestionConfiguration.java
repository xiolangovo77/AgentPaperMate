package com.ecnu.agentpapermate.config.paper;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingProperties;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.autoconfigure.vectorstore.elasticsearch.ElasticsearchVectorStoreProperties;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 配置论文入库使用的向量模型、Elasticsearch 客户端和租约心跳线程池
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DashScopeConnectionProperties.class, DashScopeEmbeddingProperties.class,
        ElasticsearchProperties.class, ElasticsearchVectorStoreProperties.class})
public class PaperIngestionConfiguration {
    /**
     * 创建文档向量模型，校验启用时的服务配置，并由任务层负责失败重试
     */
    @Bean
    public DashScopeEmbeddingModel paperEmbeddingModel(
            PaperIngestionProperties ingestionProperties, PaperProcessingProperties processingProperties,
            DashScopeConnectionProperties connection, DashScopeEmbeddingProperties embedding) {
        String baseUrl = StringUtils.hasText(embedding.getBaseUrl()) ? embedding.getBaseUrl() : connection.getBaseUrl();
        String apiKey = StringUtils.hasText(embedding.getApiKey()) ? embedding.getApiKey() : connection.getApiKey();
        String workspaceId = StringUtils.hasText(embedding.getWorkspaceId())
                ? embedding.getWorkspaceId() : connection.getWorkspaceId();
        if (!"document".equals(embedding.getOptions().getTextType())) {
            throw new IllegalArgumentException("论文入库的 DashScope text-type 必须为 document");
        }
        if (ingestionProperties.isEnabled() && (!StringUtils.hasText(baseUrl) || baseUrl.contains(".invalid")
                || baseUrl.contains("/compatible-mode") || !StringUtils.hasText(apiKey)
                || apiKey.startsWith("placeholder"))) {
            throw new IllegalArgumentException("开启入库前请替换 DashScope 占位地址及密钥");
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(ingestionProperties.getConnectTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(ingestionProperties.getRequestTimeoutSeconds()));
        var api = new DashScopeApi(baseUrl, apiKey, workspaceId,
                org.springframework.web.client.RestClient.builder().requestFactory(factory),
                org.springframework.web.reactive.function.client.WebClient.builder(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
        return new DashScopeEmbeddingModel(api, MetadataMode.NONE,
                DashScopeEmbeddingOptions.builder().withModel(embedding.getOptions().getModel())
                        .withTextType(embedding.getOptions().getTextType()).build(),
                RetryTemplate.builder().maxAttempts(1).build()) {
            /**
             * 校验返回的向量数量及维度与请求和当前处理配置一致
             */
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                var response = super.call(request);
                // M6.1 不发送 dimension 参数，校验服务端默认维度后才能写入 ES。
                if (response.getResults().size() != request.getInstructions().size()
                        || response.getResults().stream().anyMatch(result ->
                        result.getOutput().length != processingProperties.getEmbeddingDimension())) {
                    throw new IllegalStateException("DashScope 返回的向量数量或维度与任务配置不一致");
                }
                return response;
            }
        };
    }

    /**
     * 创建论文索引专用的 Elasticsearch 客户端，配置认证、请求超时及关闭时的资源释放
     */
    @Bean(destroyMethod = "close")
    public RestClient paperElasticsearchClient(PaperIngestionProperties ingestionProperties, ElasticsearchProperties elasticsearch) {
        var credentials = new BasicCredentialsProvider();
        if (StringUtils.hasText(elasticsearch.getUsername())) {
            credentials.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(
                    elasticsearch.getUsername(), elasticsearch.getPassword()));
        }
        return RestClient.builder(elasticsearch.getUris().stream().map(HttpHost::create).toArray(HttpHost[]::new))
                .setRequestConfigCallback(c -> c.setConnectTimeout(ingestionProperties.getConnectTimeoutSeconds() * 1000)
                        .setSocketTimeout(ingestionProperties.getRequestTimeoutSeconds() * 1000)
                        .setConnectionRequestTimeout(ingestionProperties.getConnectTimeoutSeconds() * 1000))
                .setHttpClientConfigCallback(c -> c.setDefaultCredentialsProvider(credentials)).build();
    }

    /**
     * 创建两个守护线程组成的定时线程池，用于论文任务租约续期
     */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService paperLeaseHeartbeatExecutor() {
        return Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("paper-lease-", 0).factory());
    }
}
