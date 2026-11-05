package com.ecnu.agentpapermate.config.paper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 在任务调度启用时开启定时扫描，并提供论文任务执行器
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "paper.processing.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PaperJobSchedulerConfiguration {
    /**
     * 为每个任务创建独立虚拟线程，由调度器的信号量限制实际并发数
     */
    // 关闭时不再接受新任务；进程被终止时，未完成任务由租约回收。
    @Bean(destroyMethod = "shutdown")
    public ExecutorService paperJobExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("paper-job-", 0).factory());
    }
}
