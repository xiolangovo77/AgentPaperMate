package com.ecnu.agentpapermate.agent.context;

import com.ecnu.agentpapermate.rag.model.RetrievalContext;
import lombok.Getter;

/** 固定 RAG 和 Agent 共用的运行上下文，每个 Run 创建一次，所有工具共享其证据注册器。 */
@Getter
public final class AgentRunContext {
    private final Long runId;
    private final RetrievalContext retrievalContext;
    private final EvidenceRegistry evidenceRegistry;

    public AgentRunContext(Long runId, RetrievalContext retrievalContext) {
        if (runId == null || runId <= 0 || retrievalContext == null) {
            throw new IllegalArgumentException("运行上下文缺少有效 Run ID 或检索上下文");
        }
        this.runId = runId;
        this.retrievalContext = retrievalContext;
        this.evidenceRegistry = new EvidenceRegistry(retrievalContext.allowedScopes());
    }

}
