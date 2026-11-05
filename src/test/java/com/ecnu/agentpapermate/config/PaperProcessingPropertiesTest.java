package com.ecnu.agentpapermate.config;

import com.ecnu.agentpapermate.config.paper.PaperProcessingProperties;
import com.ecnu.agentpapermate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证处理配置在 Spring 完成属性绑定后、Bean 初始化时自动校验。
 */
class PaperProcessingPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "paper.processing.parser-version=parser-v1",
                    "paper.processing.chunking-version=chunk-v1",
                    "paper.processing.embedding-version=model-v1",
                    "paper.processing.embedding-dimension=1024",
                    "paper.processing.es-index-name=paper_chunks_v1");

    @Test
    void validBoundConfigurationStartsSuccessfully() {
        contextRunner.withPropertyValues("paper.processing.embedding-model=test-model")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PaperProcessingProperties config = context.getBean(PaperProcessingProperties.class);
                    assertThat(config.getEmbeddingModel()).isEqualTo("test-model");
                    assertThat(config.getEmbeddingDimension()).isEqualTo(1024);
                });
    }

    @Test
    void missingRequiredPropertyPreventsStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BusinessException.class)
                    .hasStackTraceContaining("paper.processing.embedding-model");
        });
    }

    @Test
    void invalidChunkingParametersPreventStartup() {
        for (String invalid : new String[]{"paper.processing.chunk-overlap=1000",
                "paper.processing.chunk-overlap=596", "paper.processing.max-chunk-size=599",
                "paper.processing.chunk-unit=characters"}) {
            contextRunner.withPropertyValues(
                        "paper.processing.embedding-model=test-model",
                        invalid)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BusinessException.class)
                            .hasStackTraceContaining("论文处理配置无效");
                });
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaperProcessingProperties.class)
    static class TestConfiguration {
    }
}
