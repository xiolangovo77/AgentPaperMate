package com.ecnu.agentpapermate.rag.model;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;

/** 仅由后端根据 Run 快照构建；不能将前端或模型参数直接绑定到此上下文。 */
public record RetrievalContext(Long userId, List<PaperScope> allowedScopes, LocalDateTime deadline) {
    public RetrievalContext {
        if (userId == null || userId <= 0 || deadline == null
                || allowedScopes == null || allowedScopes.isEmpty()) {
            throw new IllegalArgumentException("检索上下文缺少有效用户、资料范围或截止时间");
        }
        var ids = new HashSet<Long>();
        for (PaperScope scope : allowedScopes) {
            if (scope == null || scope.paperId() == null || scope.paperId() <= 0 || !ids.add(scope.paperId())) {
                throw new IllegalArgumentException("检索上下文含无效或重复论文");
            }
        }
        allowedScopes = List.copyOf(allowedScopes);
    }
}
