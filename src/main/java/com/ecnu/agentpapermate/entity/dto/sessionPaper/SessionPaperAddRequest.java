package com.ecnu.agentpapermate.entity.dto.sessionPaper;

import lombok.Getter;
import lombok.Setter;

/**
 * 向聊天室添加论文，重复添加返回原关联ID。
 */
@Getter
@Setter
public class SessionPaperAddRequest {
    /**
     * 聊天室ID。
     */
    private Long sessionId;

    /**
     * 当前用户拥有的论文ID。
     */
    private Long paperId;
}
