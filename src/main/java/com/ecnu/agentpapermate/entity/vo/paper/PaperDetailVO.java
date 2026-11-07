package com.ecnu.agentpapermate.entity.vo.paper;

import lombok.Getter;
import lombok.Setter;

/**
 * 论文详情及当前索引的问答可用性。
 */
@Getter
@Setter
public class PaperDetailVO extends PaperSummaryVO {
    /**
     * 论文摘要，尚未解析时为空。
     */
    private String abstractText;
    /**
     * 论文是否具备与当前向量模型兼容的已完成索引，不代表问答功能总开关。
     */
    private boolean canAsk;
    /**
     * 最新处理任务标识，无任务时为空。
     */
    private Long jobId;
    /**
     * 最新处理任务的错误代码。
     */
    private String errorCode;
    /**
     * 最新处理任务的错误原因摘要。
     */
    private String errorMessage;
}
