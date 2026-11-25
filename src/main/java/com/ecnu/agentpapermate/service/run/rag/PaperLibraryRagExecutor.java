package com.ecnu.agentpapermate.service.run.rag;

import com.ecnu.agentpapermate.agent.context.AgentRunContext;
import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.rag.PaperLibraryRetriever;
import com.ecnu.agentpapermate.rag.RagAnswerGenerator;
import com.ecnu.agentpapermate.rag.RagQueryRewriter;
import com.ecnu.agentpapermate.rag.model.Answer;
import com.ecnu.agentpapermate.rag.model.RetrievalRequest;
import com.ecnu.agentpapermate.service.run.RunExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 论文库问答先定位可能相关的论文，再从这些论文中检索回答证据。 */
@Component
@RequiredArgsConstructor
public class PaperLibraryRagExecutor implements RunExecutor {
    /** 论文库问答提交与执行时使用的执行器标识。 */
    public static final String TYPE = "paper_library";

    /** 保存检索结果和回答生成阶段，并检查任务是否可以继续。 */
    private final RunStore runStore;
    /** 读取本轮问答可用的近期对话。 */
    private final RunQueries runQueries;
    /** 根据近期对话和当前问题构造独立的检索句。 */
    private final RagQueryRewriter ragQueryRewriter;
    /** 执行论文级粗召和候选论文范围内的段落检索。 */
    private final PaperLibraryRetriever paperLibraryRetriever;
    /** 基于本轮检索证据生成回答并校验引用。 */
    private final RagAnswerGenerator ragAnswerGenerator;
    /** 提供段落检索中每路召回的候选分块数量上限。 */
    private final RagProperties ragProperties;

    @Override
    public String executorType() {
        return TYPE;
    }

    @Override
    public Optional<Answer> execute(AgentRunContext context) {
        //1、进入检索阶段，并结合近期对话构造本轮检索请求
        Long runId = context.getRunId();
        if (!runStore.retrievalStarted(runId)) return Optional.empty();
        var history = runQueries.recentMessages(context);
        var request = new RetrievalRequest(ragQueryRewriter.rewrite(context, history), null, ragProperties.getRetrievalTopK());

        //2、先从会话中召回相关论文，再检索段落并登记符合证据预算的原文
        var candidates = paperLibraryRetriever.retrieve(request, context.getRetrievalContext(), context.getDeadline());
        var retrieval = context.getEvidenceRegistry().registerRetrieval(candidates);
        if (!runStore.retrieved(runId, retrieval)) return Optional.empty();

        //3、有可引用的证据时生成回答，否则给出无可靠证据的提示
        var evidence = retrieval.evidence();
        if (evidence.isEmpty()) {
            return Optional.of(new Answer("无法从当前资料中找到具有可靠页码的有效证据，暂时无法确认该问题。请调整问题或检查论文解析结果。",
                    List.of()));
        }
        if (!runStore.answerGenerationStarted(runId)) return Optional.empty();
        return Optional.of(ragAnswerGenerator.generate(context, evidence, history));
    }
}
