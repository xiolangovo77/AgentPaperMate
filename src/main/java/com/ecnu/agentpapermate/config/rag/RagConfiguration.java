package com.ecnu.agentpapermate.config.rag;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingProperties;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.ecnu.agentpapermate.config.paper.PaperProcessingProperties;
import org.springframework.ai.document.MetadataMode;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.ecnu.agentpapermate.support.ai.BudgetedDashScopeChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 配置问答任务和事件订阅的执行资源。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({DashScopeConnectionProperties.class, DashScopeEmbeddingProperties.class})
public class RagConfiguration {
    /** M6.1 忽略单次请求的 textType，因此创建默认模式为 query 的独立客户端。 */
    @Bean
    public DashScopeEmbeddingModel ragQueryEmbeddingModel(RagProperties properties, PaperProcessingProperties processing,
            DashScopeConnectionProperties connection, DashScopeEmbeddingProperties embedding) {
        //1、沿用文档向量模型的专用连接配置，未指定的字段回退至公共连接
        String baseUrl = org.springframework.util.StringUtils.hasText(embedding.getBaseUrl()) ? embedding.getBaseUrl() : connection.getBaseUrl();
        String apiKey = org.springframework.util.StringUtils.hasText(embedding.getApiKey()) ? embedding.getApiKey() : connection.getApiKey();
        String workspace = org.springframework.util.StringUtils.hasText(embedding.getWorkspaceId()) ? embedding.getWorkspaceId() : connection.getWorkspaceId();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()));
        var api = new DashScopeApi(baseUrl, apiKey, workspace,
                org.springframework.web.client.RestClient.builder().requestFactory(factory),
                org.springframework.web.reactive.function.client.WebClient.builder(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
        //2、不改变入库客户端的默认配置，避免并发问答污染 document 模式
        return new DashScopeEmbeddingModel(api, MetadataMode.NONE,
                DashScopeEmbeddingOptions.builder().withModel(processing.getEmbeddingModel()).withTextType("query").build(),
                RetryTemplate.builder().maxAttempts(1).build());
    }

    /** 沿用原生 DashScope 地址与凭据，单次调用不自动重试。 */
    @Bean
    public ChatModel ragChatModel(RagProperties properties, DashScopeConnectionProperties connection) {
        //1、启用时检查原生接口配置，禁止误用兼容接口路径
        if (properties.isEnabled() && (connection.getBaseUrl() == null || connection.getApiKey() == null
                || connection.getApiKey().isBlank() || connection.getBaseUrl().contains("/compatible-mode"))) {
            throw new IllegalArgumentException("请配置现有 spring.ai.dashscope 的原生 base-url 和 api-key");
        }
        //2、兼容当前 SDK 的输出预算缺陷，实际运行总预算由问答执行器进一步限制
        return new BudgetedDashScopeChatModel(connection, properties);
    }

    @Bean
    public Clock ragClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService ragExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rag-run-", 0).factory());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService ragSseExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rag-sse-", 0).factory());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService ragCallExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rag-call-", 0).factory());
    }
}
