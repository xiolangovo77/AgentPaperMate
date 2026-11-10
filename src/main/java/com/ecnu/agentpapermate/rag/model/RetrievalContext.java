package com.ecnu.agentpapermate.rag.model;

import java.util.HashSet;
import java.util.List;

/** 仅保存后端构建的用户和资料范围；运行时间预算由 AgentRunContext 单独持有。 */
public record RetrievalContext(Long userId, List<PaperScope> allowedScopes) {
    public RetrievalContext {
        if (userId == null || userId <= 0
                || allowedScopes == null || allowedScopes.isEmpty()) {
            throw new IllegalArgumentException("检索上下文缺少有效用户或资料范围");
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
