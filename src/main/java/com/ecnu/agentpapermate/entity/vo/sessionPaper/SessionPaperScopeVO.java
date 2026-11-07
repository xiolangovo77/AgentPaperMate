package com.ecnu.agentpapermate.entity.vo.sessionPaper;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

/**
 * 事务更新后的完整论文范围与版本。
 */
@Getter
@Setter
public class SessionPaperScopeVO {
    /**
     * 聊天室标识。
     */
    private Long sessionId;
    /**
     * 按 ID 升序返回的完整论文集合。
     */
    private List<Long> paperIds;
    /**
     * 最新范围版本，集合未变化时保持原值。
     */
    private Integer scopeVersion;
}
