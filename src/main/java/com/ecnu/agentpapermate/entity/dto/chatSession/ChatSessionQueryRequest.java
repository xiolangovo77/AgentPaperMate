package com.ecnu.agentpapermate.entity.dto.chatSession;

import com.ecnu.agentpapermate.common.PageRequest;
import lombok.Getter;
import lombok.Setter;

/**
 * 查询当前用户的聊天室，固定按最近活动时间、ID 降序排列。
 */
@Getter
@Setter
public class ChatSessionQueryRequest extends PageRequest {
    /**
     * 聊天室名称关键字，可选。
     */
    private String name;
}
