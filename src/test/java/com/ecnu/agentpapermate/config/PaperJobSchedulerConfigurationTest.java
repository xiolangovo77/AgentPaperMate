package com.ecnu.agentpapermate.config;

import com.ecnu.agentpapermate.config.paper.PaperJobSchedulerConfiguration;
import com.ecnu.agentpapermate.config.paper.PaperJobSchedulerProperties;
import com.ecnu.agentpapermate.scheduler.PaperJobScheduler;
import com.ecnu.agentpapermate.service.PaperJobClaimService;
import com.ecnu.agentpapermate.service.PaperJobProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PaperJobSchedulerConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PaperJobSchedulerConfiguration.class, PaperJobScheduler.class,
                    PaperJobSchedulerProperties.class)
            .withBean(PaperJobClaimService.class, () -> mock(PaperJobClaimService.class))
            .withBean(PaperJobProcessor.class, () -> mock(PaperJobProcessor.class));

    @Test
    void switchDisablesSchedulerAndExecutor() {
        runner.withPropertyValues("paper.processing.scheduler.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PaperJobScheduler.class);
            assertThat(context).doesNotHaveBean(ExecutorService.class);
            verifyNoInteractions(context.getBean(PaperJobClaimService.class));
        });
    }

    @Test
    void invalidConcurrencyFailsStartup() {
        runner.withPropertyValues("paper.processing.scheduler.max-concurrent-jobs=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }
}
