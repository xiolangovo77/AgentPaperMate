package com.ecnu.agentpapermate.entity.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * PDF解析与索引任务
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
@Getter
@Setter
@ToString
@TableName("paper_processing_job")
public class PaperProcessingJob implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 论文入库任务ID
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 处理的论文ID，关联paper.id
     */
    @TableField("paper_id")
    private Long paperId;

    /**
     * 本次处理版本，同一论文逐次递增
     */
    @TableField("index_version")
    private Integer indexVersion;

    /**
     * pending、running、completed、failed
     */
    @TableField("status")
    private String status;

    /**
     * queued、awaiting_processor、parsing、chunking、embedding、indexing、verifying、done
     */
    @TableField("current_step")
    private String currentStep;

    /**
     * PDF解析器及处理规则版本
     */
    @TableField("parser_version")
    private String parserVersion;

    /**
     * 切分策略版本
     */
    @TableField("chunking_version")
    private String chunkingVersion;

    /**
     * 片段大小、重叠等实际配置
     */
    @TableField("chunking_config")
    private String chunkingConfig;

    /**
     * Embedding模型标识
     */
    @TableField("embedding_model")
    private String embeddingModel;

    /**
     * Embedding模型版本或应用配置版本
     */
    @TableField("embedding_version")
    private String embeddingVersion;

    /**
     * 实际向量维度
     */
    @TableField("embedding_dimension")
    private Integer embeddingDimension;

    /**
     * 本次写入的ES物理索引
     */
    @TableField("es_index_name")
    private String esIndexName;

    /**
     * 本次应写入片段数
     */
    @TableField("total_chunks")
    private Integer totalChunks;

    /**
     * 本次执行已确认写入 Elasticsearch 的片段数，每次重试从 0 开始计数
     */
    @TableField("indexed_chunks")
    private Integer indexedChunks;

    /**
     * 已安排的额外重试次数
     */
    @TableField("retry_count")
    private Integer retryCount;

    /**
     * 允许的额外重试上限
     */
    @TableField("max_retries")
    private Integer maxRetries;

    /**
     * 允许首次执行或重试的时间UTC
     */
    @TableField("next_retry_time")
    private LocalDateTime nextRetryTime;

    /**
     * 本次领取的唯一令牌，防止旧执行者回写
     */
    @TableField("lease_token")
    private String leaseToken;

    /**
     * 执行者租约到期时间UTC，执行期间续约
     */
    @TableField("lease_until")
    private LocalDateTime leaseUntil;

    /**
     * 最近一次开始执行时间UTC
     */
    @TableField("start_time")
    private LocalDateTime startTime;

    /**
     * 完成或最终失败时间UTC
     */
    @TableField("end_time")
    private LocalDateTime endTime;

    /**
     * 最近错误代码
     */
    @TableField("error_code")
    private String errorCode;

    /**
     * 最近错误摘要，不含凭据
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 任务创建时间UTC
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 任务更新时间UTC
     */
    @TableField("update_time")
    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0正常、1删除
     */
    @TableLogic
    @TableField("is_delete")
    private Byte isDelete;
}
