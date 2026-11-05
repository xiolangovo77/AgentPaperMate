package com.ecnu.agentpapermate.config.paper;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingProperties;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 配置论文入库使用的向量模型和租约心跳线程池，并引入论文索引的连接配置。
 */
@Configuration(proxyBeanMethods = false)
@Import(PaperElasticsearchConfiguration.class)
@EnableConfigurationProperties({DashScopeConnectionProperties.class, DashScopeEmbeddingProperties.class})
public class PaperIngestionConfiguration {
    /**
     * 创建文档向量模型，校验启用时的服务配置，并由任务层负责失败重试
     */
    @Bean
    public DashScopeEmbeddingModel paperEmbeddingModel(
            PaperIngestionProperties ingestionProperties, PaperProcessingProperties processingProperties,
            DashScopeConnectionProperties connection, DashScopeEmbeddingProperties embedding) {
        // 1、优先读取向量模型专用的连接配置，缺省项使用 DashScope 公共配置
        String baseUrl = StringUtils.hasText(embedding.getBaseUrl()) ? embedding.getBaseUrl() : connection.getBaseUrl();
        String apiKey = StringUtils.hasText(embedding.getApiKey()) ? embedding.getApiKey() : connection.getApiKey();
        String workspaceId = StringUtils.hasText(embedding.getWorkspaceId())
                ? embedding.getWorkspaceId() : connection.getWorkspaceId();

        // 2、校验文档向量模式，启用入库时再校验服务地址和密钥
        if (!"document".equals(embedding.getOptions().getTextType())) {
            throw new IllegalArgumentException("论文入库的 DashScope text-type 必须为 document");
        }
        if (ingestionProperties.isEnabled() && (!StringUtils.hasText(baseUrl) || baseUrl.contains(".invalid")
                || baseUrl.contains("/compatible-mode") || !StringUtils.hasText(apiKey)
                || apiKey.startsWith("placeholder"))) {
            throw new IllegalArgumentException("开启入库前请替换 DashScope 占位地址及密钥");
        }

        // 3、配置连接和读取超时，创建调用 DashScope 的 API 客户端
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(ingestionProperties.getConnectTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(ingestionProperties.getRequestTimeoutSeconds()));
        var api = new DashScopeApi(baseUrl, apiKey, workspaceId,
                org.springframework.web.client.RestClient.builder().requestFactory(factory),
                org.springframework.web.reactive.function.client.WebClient.builder(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);

        // 4、创建文档向量模型，每个请求只尝试一次，失败重试交给入库任务
        return new DashScopeEmbeddingModel(api, MetadataMode.NONE,
                DashScopeEmbeddingOptions.builder().withModel(embedding.getOptions().getModel())
                        .withTextType(embedding.getOptions().getTextType()).build(),
                RetryTemplate.builder().maxAttempts(1).build()) {
            /**
             * 校验返回的向量数量及维度与请求和当前处理配置一致
             */
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                // 1、调用 DashScope 获取当前批次的文档向量
                var response = super.call(request);

                // 2、M6.1 使用服务端默认维度，写入索引前校验向量数量和维度
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
     * 创建两个守护线程组成的定时线程池，用于论文任务租约续期
     */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService paperLeaseHeartbeatExecutor() {
        return Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("paper-lease-", 0).factory());
    }
}
