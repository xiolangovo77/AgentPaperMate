package com.ecnu.agentpapermate.entity.dto.sessionPaper;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

/**
 * 完整替换聊天室论文范围的请求。
 */
@Getter
@Setter
public class SessionPaperScopeRequest {
    /**
     * 聊天室标识。
     */
    private Long sessionId;
    /**
     * 完整目标论文集合，空数组表示清空，重复 ID 自动去重。
     */
    private List<Long> paperIds;
    /**
     * 客户端读取的范围版本，必须与服务端当前版本一致。
     */
    private Integer scopeVersion;
}
