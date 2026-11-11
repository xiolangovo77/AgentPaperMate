package com.ecnu.agentpapermate.entity.vo.paper;

import lombok.Data;

/**
 * 对外返回的论文保存结果，包含论文 ID、处理任务 ID 和论文状态。
 */
@Data
public class PaperAddVO {
    /**
     * 是否为已存在的论文：true表示返回已有记录，未重复创建论文及处理任务。
     */
    private boolean alreadyExists;

    /**
     * 论文 ID。
     */
    private Long paperId;

    /**
     * 论文处理任务 ID。
     */
    private Long jobId;

    /**
     * 论文状态：uploaded、processing、ready、failed。
     */
    private String status;
}
