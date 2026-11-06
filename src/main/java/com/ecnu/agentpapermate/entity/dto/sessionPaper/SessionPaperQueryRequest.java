package com.ecnu.agentpapermate.entity.dto.sessionPaper;

import com.ecnu.agentpapermate.common.PageRequest;
import lombok.Getter;
import lombok.Setter;

/**
 * 分页查询聊天室内的论文关联，固定按创建时间、ID 降序排列。
 */
@Getter
@Setter
public class SessionPaperQueryRequest extends PageRequest {
    /**
     * 聊天室ID，必填。
     */
    private Long sessionId;

    /**
     * 论文ID，可选。
     */
    private Long paperId;
}
