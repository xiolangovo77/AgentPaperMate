package com.ecnu.agentpapermate.config.paper;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据库任务调度参数，单位显式写在属性名中。
 */
@Data
@Component
@ConfigurationProperties(prefix = "paper.processing.scheduler")
public class PaperJobSchedulerProperties {

    /**
     * 是否启用数据库任务调度，默认开启
     */
    private boolean enabled = true;

    /**
     * 每次定时扫描结束到下一次扫描开始的间隔（毫秒）
     */
    private long pollIntervalMs = 2000;

    /**
     * 当前调度器实例同时执行论文任务的数量上限
     */
    private int maxConcurrentJobs = 4;

    /**
     * 单轮扫描最多尝试领取的任务数，同时作为过期租约回收的批量上限
     */
    private int batchSize = 16;

    /**
     * 每次领取或续期设置的租约有效时长（秒）
     */
    private int leaseSeconds = 120;

    /**
     * 失败任务重新进入待处理状态后的重试等待时间（秒）
     */
    private int retryDelaySeconds = 30;

    /**
     * 启动时校验所有调度间隔、批次数量及并发参数均为正数
     */
    @PostConstruct
    public void validate() {
        if (pollIntervalMs <= 0 || maxConcurrentJobs <= 0 || batchSize <= 0
                || leaseSeconds <= 0 || retryDelaySeconds <= 0) {
            throw new IllegalArgumentException("paper.processing.scheduler 所有间隔、批次和并发参数必须大于 0");
        }
    }
}
