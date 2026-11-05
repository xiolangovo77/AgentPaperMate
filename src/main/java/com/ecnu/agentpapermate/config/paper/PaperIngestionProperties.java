package com.ecnu.agentpapermate.config.paper;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 论文入库流水线配置，对应 paper.processing.pipeline 配置项
 */
@Data
@Component
@ConfigurationProperties(prefix = "paper.processing.pipeline")
public class PaperIngestionProperties {

    /**
     * 是否启用论文入库流水线，默认关闭
     */
    private boolean enabled = false;

    /**
     * GROBID 解析服务的基础地址
     */
    private String grobidUrl = "http://localhost:8070";

    /**
     * 外部服务连接超时时间（秒）
     */
    private int connectTimeoutSeconds = 10;

    /**
     * 外部服务响应读取超时时间（秒）
     */
    private int requestTimeoutSeconds = 90;

    /**
     * 任务租约续期的心跳间隔（秒）
     */
    private int heartbeatSeconds = 20;

    /**
     * 单次任务执行的最长允许时长（秒）
     */
    private int maxDurationSeconds = 1800;

    /**
     * 每批向量化并写入索引的分块数量，允许范围为 1 至 10
     */
    private int embeddingBatchSize = 10;

    /**
     * 启动时校验超时、心跳及批次配置，最大执行时长必须大于心跳间隔
     */
    @PostConstruct
    public void validate() {
        if (connectTimeoutSeconds <= 0 || requestTimeoutSeconds <= 0 || heartbeatSeconds <= 0
                || maxDurationSeconds <= heartbeatSeconds || embeddingBatchSize <= 0 || embeddingBatchSize > 10) {
            throw new IllegalArgumentException("论文入库超时、心跳和批次配置无效");
        }
    }
}
