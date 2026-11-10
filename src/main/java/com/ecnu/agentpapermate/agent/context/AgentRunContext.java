package com.ecnu.agentpapermate.agent.context;

import com.ecnu.agentpapermate.entity.model.AgentRun;
import com.ecnu.agentpapermate.agent.AgentHarness;
import com.ecnu.agentpapermate.agent.skill.SkillSnapshot;
import com.ecnu.agentpapermate.rag.model.RetrievalContext;
import com.ecnu.agentpapermate.support.json.RagJson;
import lombok.Getter;

import java.time.LocalDateTime;

/** 固定 RAG 和 Agent 共用的运行上下文，每个 Run 创建一次，所有工具共享其证据注册器。 */
@Getter
public final class AgentRunContext {
    /** 本次运行的标识，用于状态检查和结果记录。 */
    private final Long runId;
    /** 所属会话的标识，用于读取当前问题之前的历史问题。 */
    private final Long sessionId;
    /** 用户提交的原始问题快照。 */
    private final String userQuery;
    /** 创建 Run 时确定的回答模型名称。 */
    private final String modelName;
    /** 创建 Run 时保存的模型参数和调用预算快照。 */
    private final String modelConfig;
    /** Run 开始执行时确定的总截止时间，由所有模型、检索和工具调用共用。 */
    private final LocalDateTime deadline;
    /** 服务端构建的用户和论文范围快照。 */
    private final RetrievalContext retrievalContext;
    /** 本次 Run 共享的证据编号与检索候选登记器。 */
    private final EvidenceRegistry evidenceRegistry;
    /** Agent 专用的不可变技能内容，固定 RAG 为 null；不从历史或工具结果构建。 */
    private final SkillSnapshot skill;

    private AgentRunContext(AgentRun run, RetrievalContext retrievalContext, SkillSnapshot skill) {
        if (run.getId() == null || run.getId() <= 0
                || run.getSessionId() == null || run.getSessionId() <= 0
                || run.getUserQuery() == null || run.getUserQuery().isBlank() || run.getDeadline() == null) {
            throw new IllegalArgumentException("运行上下文缺少有效 Run、会话、问题或截止时间");
        }
        this.runId = run.getId();
        this.sessionId = run.getSessionId();
        this.userQuery = run.getUserQuery();
        this.modelName = run.getModelName();
        this.modelConfig = run.getModelConfig();
        this.deadline = run.getDeadline();
        this.retrievalContext = retrievalContext;
        this.evidenceRegistry = new EvidenceRegistry(retrievalContext.allowedScopes());
        this.skill = skill;
    }

    /** 仅供后端分发入口使用；必须传入 RunStore.start 已提交的快照，不接受请求或工具参数。 */
    public static AgentRunContext fromClaimedRun(AgentRun run, RagJson json) {
        if (run == null || !"running".equals(run.getStatus()) || !"ready".equals(run.getExecutionPhase())) {
            throw new IllegalArgumentException("只能为刚领取的 Run 创建运行上下文");
        }
        // 只复制实际执行所需的不可变信息，不保留可修改的持久化实体或过期计数。
        SkillSnapshot skill = null;
        if ("agent".equals(run.getExecutorType())) {
            try {
                skill = json.skillSnapshot(run.getModelConfig());
                if (!skill.id().equals(run.getSkillId()) || !skill.version().equals(run.getSkillVersion())
                        || !skill.contentHash().equals(run.getSkillContentHash())) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw new AgentHarness.Rejected("SKILL_SNAPSHOT_INVALID", "任务缺少有效技能快照，请创建新任务");
            }
        }
        return new AgentRunContext(run, new RetrievalContext(run.getUserId(),
                json.scopes(run.getPapersSnapshot())), skill);
    }
}
