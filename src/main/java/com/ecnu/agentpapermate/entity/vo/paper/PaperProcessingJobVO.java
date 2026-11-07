package com.ecnu.agentpapermate.entity.vo.paper;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 论文处理进度，不暴露租约令牌和内部索引配置。
 */
@Getter
@Setter
public class PaperProcessingJobVO {
    /**
     * 处理任务标识，对应上传后返回的 jobId。
     */
    private Long id;
    /**
     * 所属论文标识。
     */
    private Long paperId;
    /**
     * 任务状态：pending、running、completed、failed。
     */
    private String status;
    /**
     * 当前处理步骤。
     */
    private String currentStep;
    /**
     * 应写入的总片段数。
     */
    private Integer totalChunks;
    /**
     * 已确认写入的片段数。
     */
    private Integer indexedChunks;
    /**
     * 已安排的额外重试次数。
     */
    private Integer retryCount;
    /**
     * 最近错误代码。
     */
    private String errorCode;
    /**
     * 最近错误原因摘要。
     */
    private String errorMessage;
    /**
     * 更新时间（上海时间）。
     */
    private LocalDateTime updateTime;
}
