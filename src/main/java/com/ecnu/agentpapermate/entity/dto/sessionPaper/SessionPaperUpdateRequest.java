package com.ecnu.agentpapermate.entity.dto.sessionPaper;

import lombok.Getter;
import lombok.Setter;

/**
 * 更新论文关联状态，聊天室和论文ID不可修改。
 */
@Getter
@Setter
public class SessionPaperUpdateRequest {
    /**
     * 资料关联ID。
     */
    private Long id;

    /**
     * 0恢复到会话、1移出会话。
     */
    private Byte isDelete;
}
