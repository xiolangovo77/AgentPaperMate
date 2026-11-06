package com.ecnu.agentpapermate.entity.dto.chatSession;

import lombok.Getter;
import lombok.Setter;

/**
 * 创建研究聊天室的请求。
 */
@Getter
@Setter
public class ChatSessionAddRequest {
    /**
     * 聊天室名称，未提供时使用“新研究会话”。
     */
    private String name;

    /**
     * 尚未发送的输入草稿。
     */
    private String inputDraft;
}
