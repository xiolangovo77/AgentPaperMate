package com.ecnu.agentpapermate.config;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingProperties;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.ecnu.agentpapermate.config.paper.PaperIngestionConfiguration;
import com.ecnu.agentpapermate.config.paper.PaperIngestionProperties;
import com.ecnu.agentpapermate.config.paper.PaperProcessingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.autoconfigure.vectorstore.elasticsearch.ElasticsearchVectorStoreProperties;
import org.springframework.ai.vectorstore.elasticsearch.SimilarityFunction;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DashScopeConfigurationTest {
    @Test
    void applicationYamlConfiguresEmbeddingAndNativeRerankRequest() {
        var client = RestClient.builder();
        var server = MockRestServiceServer.bindTo(client).build();
        new ApplicationContextRunner()
                .withBean(RestClient.Builder.class, () -> client)
                .withBean(WebClient.Builder.class, WebClient::builder)
                .withBean(RetryTemplate.class, () -> RetryTemplate.builder().maxAttempts(1).build())
                .withBean(ResponseErrorHandler.class, () -> RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DashScopeAutoConfiguration.class))
                .withUserConfiguration(PaperIngestionConfiguration.class, PaperIngestionProperties.class,
                        PaperProcessingProperties.class)
                .withPropertyValues("spring.profiles.active=test",
                        "spring.ai.dashscope.image.enabled=false",
                        "spring.ai.dashscope.audio.synthesis.enabled=false",
                        "spring.ai.dashscope.audio.transcription.enabled=false",
                        "spring.ai.dashscope.api-key=fake-test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(EmbeddingModel.class);
                    assertThat(context.getBean(EmbeddingModel.class)).isInstanceOf(DashScopeEmbeddingModel.class);
                    var pipeline = context.getBean(PaperIngestionProperties.class);
                    var processing = context.getBean(PaperProcessingProperties.class);
                    var connection = context.getBean(DashScopeConnectionProperties.class);
                    var embedding = context.getBean(DashScopeEmbeddingProperties.class);
                    var vectorStore = context.getBean(ElasticsearchVectorStoreProperties.class);
                    var elasticsearch = context.getBean(ElasticsearchProperties.class);
                    assertThat(connection.getBaseUrl()).isEqualTo("https://dashscope.aliyuncs.com");
                    assertThat(connection.getApiKey()).isEqualTo("fake-test-key");
                    assertThat(embedding.getOptions().getTextType()).isEqualTo("document");
                    assertThat(pipeline.getEmbeddingBatchSize()).isEqualTo(10);
                    assertThat(vectorStore.isInitializeSchema()).isTrue();
                    assertThat(vectorStore.getSimilarity()).isEqualTo(SimilarityFunction.cosine);
                    assertThat(elasticsearch.getUris()).containsExactly("http://localhost:9200");
                    assertThat(processing.getEmbeddingModel()).isEqualTo("qwen3.7-text-embedding");
                    assertThat(processing.getEmbeddingDimension()).isEqualTo(1024);
                    assertThat(processing.getEsIndexName()).isEqualTo("agentpapermate-paper-chunks-qwen37-1024-v1");
                    assertThat(context).doesNotHaveBean("dashscopeChatModel")
                            .doesNotHaveBean("dashScopeImageModel").hasSingleBean(RerankModel.class)
                            .doesNotHaveBean("dashScopeSpeechSynthesisModel")
                            .doesNotHaveBean("dashScopeAudioTranscriptionModel");
                    server.expect(requestTo(connection.getBaseUrl() + "/api/v1/services/rerank/text-rerank/text-rerank"))
                            .andExpect(method(HttpMethod.POST))
                            .andExpect(header("Authorization", "Bearer fake-test-key"))
                            .andExpect(content().json("""
                                    {"model":"qwen3.7-text-rerank",
                                     "input":{"query":"文本排序","documents":["量子计算","排序模型"]},
                                     "parameters":{"top_n":5,"return_documents":false}}
                                    """))
                            .andRespond(withSuccess("""
                                    {"request_id":"test","output":{"results":[
                                      {"index":1,"relevance_score":0.99},
                                      {"index":0,"relevance_score":0.01}]},
                                     "usage":{"total_tokens":10}}
                                    """, MediaType.APPLICATION_JSON));
                    var documents = List.of(new Document("量子计算"), new Document("排序模型"));
                    var model = context.getBean(RerankModel.class);
                    var ranked = model.call(new RerankRequest("文本排序", documents));
                    assertThat(ranked.getResults()).extracting(result -> result.getOutput())
                            .containsExactly(documents.get(1), documents.get(0));
                    server.verify();

                    server.reset();
                    server.expect(requestTo(connection.getBaseUrl() + "/api/v1/services/rerank/text-rerank/text-rerank"))
                            .andExpect(content().json("""
                                    {"model":"qwen3.7-text-rerank","parameters":{"top_n":12,"return_documents":false}}
                                    """))
                            .andRespond(withSuccess("""
                                    {"request_id":"test","output":{"results":[{"index":0,"relevance_score":0.9}]},
                                     "usage":{"total_tokens":10}}
                                    """, MediaType.APPLICATION_JSON));
                    var evaluationDocuments = java.util.stream.IntStream.range(0, 12)
                            .mapToObj(i -> new Document("评测片段" + i)).toList();
                    model.call(new RerankRequest("文本排序", evaluationDocuments,
                            DashScopeRerankOptions.builder().withModel(null).withReturnDocuments(null)
                                    .withTopN(evaluationDocuments.size()).build()));
                    server.verify();
                });
    }
}
