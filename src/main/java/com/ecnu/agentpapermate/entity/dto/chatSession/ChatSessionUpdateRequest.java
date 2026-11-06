package com.ecnu.agentpapermate.entity.dto.chatSession;

import lombok.Getter;
import lombok.Setter;

/**
 * 更新聊天室，null 字段保持原值。
 */
@Getter
@Setter
public class ChatSessionUpdateRequest {
    /**
     * 聊天室ID。
     */
    private Long id;

    /**
     * 聊天室名称。
     */
    private String name;

    /**
     * 输入草稿，空字符串表示清空。
     */
    private String inputDraft;
}
