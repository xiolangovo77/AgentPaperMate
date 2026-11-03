package com.ecnu.agentpapermate.config;

import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.exception.ThrowUtils;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 论文处理配置，创建任务时保存配置快照，实际模型和索引由部署环境提供。
 */
@Data
@Component
@ConfigurationProperties(prefix = "paper.processing")
public class PaperProcessingConfig {

    /**
     * PDF 解析器及处理规则版本。
     */
    private String parserVersion;

    /**
     * 文本切分策略版本。
     */
    private String chunkingVersion;

    /**
     * 单个片段的大小，计量单位由 chunkUnit 指定。
     */
    private int chunkSize = 1000;

    /**
     * 相邻片段的重叠长度，必须小于片段大小。
     */
    private int chunkOverlap = 150;

    /**
     * 切分计量单位：characters 表示字符，tokens 表示 Token。
     */
    private String chunkUnit = "characters";

    /**
     * Embedding 模型名称。
     */
    private String embeddingModel;

    /**
     * Embedding 模型版本或应用配置版本。
     */
    private String embeddingVersion;

    /**
     * 模型实际输出的向量维度。
     */
    private Integer embeddingDimension;

    /**
     * 任务写入的 Elasticsearch 物理索引名。
     */
    private String esIndexName;

    /**
     * 任务派发使用的 RabbitMQ 交换机名称。
     */
    private String exchangeName = "paper.processing";

    /**
     * 任务派发使用的 RabbitMQ 路由键。
     */
    private String routingKey = "paper.processing.requested";

    /**
     * 任务失败后允许的额外重试次数 - 消费者。
     */
    private int maxRetries = 3;

    /**
     * Outbox 事件自动投递的最大尝试次数 - 生产者。
     */
    private int maxPublishAttempts = 10;

    /**
     * 配置绑定完成后，在 Bean 初始化时检查必填配置、字段长度及参数范围。
     * 配置无效时阻止应用启动。
     */
    @PostConstruct
    public void init() {
        requireText(parserVersion, 64, "parser-version");
        requireText(chunkingVersion, 64, "chunking-version");
        requireText(embeddingModel, 128, "embedding-model");
        requireText(embeddingVersion, 64, "embedding-version");
        requireText(esIndexName, 128, "es-index-name");
        requireText(exchangeName, 128, "exchange-name");
        requireText(routingKey, 128, "routing-key");
        ThrowUtils.throwIf(embeddingDimension == null || embeddingDimension <= 0,
                ErrorCode.SYSTEM_ERROR, "请配置paper.processing.embedding-dimension");
        ThrowUtils.throwIf(chunkSize <= 0 || chunkOverlap < 0 || chunkOverlap >= chunkSize
                        || !("characters".equals(chunkUnit) || "tokens".equals(chunkUnit))
                        || maxRetries < 0 || maxPublishAttempts <= 0
                        || !esIndexName.matches("[a-z0-9][a-z0-9._-]*"),
                ErrorCode.SYSTEM_ERROR, "论文处理配置无效，请检查切分、重试和索引配置");
    }

    /**
     * 校验文本配置非空且未超过数据库字段长度。
     */
    private void requireText(String value, int maxLength, String property) {
        ThrowUtils.throwIf(!StringUtils.hasText(value) || value.length() > maxLength,
                ErrorCode.SYSTEM_ERROR, "请正确配置paper.processing." + property);
    }
}
