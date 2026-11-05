package com.ecnu.agentpapermate.config;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingProperties;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.ecnu.agentpapermate.config.paper.PaperIngestionConfiguration;
import com.ecnu.agentpapermate.config.paper.PaperIngestionProperties;
import com.ecnu.agentpapermate.config.paper.PaperProcessingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.autoconfigure.vectorstore.elasticsearch.ElasticsearchVectorStoreProperties;
import org.springframework.ai.vectorstore.elasticsearch.SimilarityFunction;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DashScopeConfigurationTest {
    @Test
    void applicationYamlBindsNativeModelAndCreatesOnlyOneEmbeddingBean() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DashScopeAutoConfiguration.class))
                .withUserConfiguration(PaperIngestionConfiguration.class, PaperIngestionProperties.class,
                        PaperProcessingProperties.class)
                .withPropertyValues("spring.profiles.active=test",
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
                            .doesNotHaveBean("dashScopeImageModel").doesNotHaveBean("dashscopeRerankModel")
                            .doesNotHaveBean("dashScopeSpeechSynthesisModel")
                            .doesNotHaveBean("dashScopeAudioTranscriptionModel");
                });
    }
}
