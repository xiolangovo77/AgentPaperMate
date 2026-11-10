package com.ecnu.agentpapermate.service.run.rag;

import com.ecnu.agentpapermate.agent.context.AgentRunContext;
import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.rag.RagAnswerGenerator;
import com.ecnu.agentpapermate.rag.RagRetriever;
import com.ecnu.agentpapermate.rag.model.Answer;
import com.ecnu.agentpapermate.rag.model.RetrievalRequest;
import com.ecnu.agentpapermate.service.run.RunExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 固定单次检索问答流程；阶段事务与外部调用分开，终态统一交由分发器提交。 */
@Component
@RequiredArgsConstructor
public class FixedRagExecutor implements RunExecutor {
    /** 任务事务服务，检查运行状态并保存检索和回答生成阶段。 */
    private final RunStore store;
    /** 任务查询服务，读取有长度预算的历史用户问题以理解追问。 */
    private final RunQueries queries;
    /** 论文检索器，在本次 Run 的资料范围内检索和融合候选。 */
    private final RagRetriever retriever;
    /** 回答生成器，根据本次证据生成回答并校验引用。 */
    private final RagAnswerGenerator generator;
    /** 固定 RAG 配置，提供本次检索的候选数量上限。 */
    private final RagProperties properties;

    @Override
    public String executorType() {
        return "rag";
    }

    @Override
    public Optional<Answer> execute(AgentRunContext context) {
        Long runId = context.getRunId();
        if (!store.retrievalStarted(runId)) return Optional.empty();
        var request = new RetrievalRequest(context.getUserQuery(), null, properties.getRetrievalTopK());
        var candidates = retriever.retrieve(request, context.getRetrievalContext(), context.getDeadline());
        var retrieval = context.getEvidenceRegistry().registerRetrieval(candidates);
        if (!store.retrieved(runId, retrieval)) return Optional.empty();

        var evidence = retrieval.evidence();
        if (evidence.isEmpty()) {
            return Optional.of(new Answer("无法从当前资料中找到具有可靠页码的有效证据，暂时无法确认该问题。请调整问题或检查论文解析结果。",
                    List.of()));
        }
        if (!store.answerGenerationStarted(runId)) return Optional.empty();
        return Optional.of(generator.generate(context, evidence, queries.previousQuestions(context)));
    }
}
