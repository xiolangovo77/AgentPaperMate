package com.ecnu.agentpapermate.rag;

import com.alibaba.cloud.ai.dashscope.metadata.DashScopeAiUsage;
import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.rag.model.RetrievalContext;
import com.ecnu.agentpapermate.rag.model.RetrievalPlan;
import com.ecnu.agentpapermate.rag.model.RetrievalRequest;
import com.ecnu.agentpapermate.support.ai.RagCalls;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** 独立检索评测入口，接受由后端构造的论文范围。 */
@Service
public class RetrievalBenchmark {
    private final RagRetriever retriever;
    private final RagQueryRewriter rewriter;
    private final ChatModel chatModel;
    private final RagCalls calls;
    private final RagProperties properties;

    public RetrievalBenchmark(RagRetriever retriever, RagQueryRewriter rewriter,
                              @Qualifier("ragChatModel") ChatModel chatModel, RagCalls calls, RagProperties properties) {
        this.retriever = retriever;
        this.rewriter = rewriter;
        this.chatModel = chatModel;
        this.calls = calls;
        this.properties = properties;
    }

    public Result evaluate(String question, RetrievalContext context, RetrievalPlan plan, LocalDateTime deadline) {
        long start = System.nanoTime();
        var request = new RetrievalRequest(question, null, plan.topK());
        Map<String, Long> stageMillis = new LinkedHashMap<>();
        Rewrite rewrite = null;
        if (plan.strategy() == RetrievalPlan.Strategy.R5) {
            long rewriteStart = System.nanoTime();
            rewrite = rewrite(question, deadline);
            stageMillis.put("query_rewrite", elapsedMillis(rewriteStart));
            request = new RetrievalRequest(rewrite.query(), null, plan.topK());
        }
        var ranking = retriever.evaluateRanking(request, context, deadline, plan, question);
        stageMillis.putAll(ranking.stageMillis());
        return new Result(question, request.query(), plan.strategy().usesReranker() ? question : null, rewrite,
                ranking.bm25Ids(), ranking.knnIds(), ranking.candidateIds(), ranking.rankedIds(),
                ranking.rerankInputCount(), Map.copyOf(stageMillis), elapsedMillis(start));
    }

    private Rewrite rewrite(String original, LocalDateTime deadline) {
        String model = properties.getModel();
        var prompt = rewriter.prompt(original, List.of(), model, Math.min(512, properties.getMaxOutputTokens()));
        ChatResponse response;
        try {
            response = calls.call(deadline, () -> chatModel.call(prompt));
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            String status = failure instanceof RagCalls.RequestTimeout ? "timeout_fallback" : "provider_fallback";
            return new Rewrite(original, model, RagQueryRewriter.PROMPT_VERSION, status, null, null);
        }

        String query = rewriter.parseQuery(response);
        var usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        Number input = usage == null ? null : usage.getPromptTokens();
        Number output = usage instanceof DashScopeAiUsage nativeUsage ? nativeUsage.getGenerationTokens()
                : usage == null ? null : usage.getCompletionTokens();
        String status = query == null ? "invalid_fallback" : original.equals(query) ? "unchanged" : "rewritten";
        return new Rewrite(query == null ? original : query, model, RagQueryRewriter.PROMPT_VERSION, status, input, output);
    }

    private static long elapsedMillis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Result(String originalQuery, String recallQuery, String rerankQuery, Rewrite rewrite,
                         List<String> bm25ChunkIds, List<String> knnChunkIds, List<String> candidateChunkIds,
                         List<String> rankedChunkIds, int rerankInputCount, Map<String, Long> stageMillis, long latencyMs) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Rewrite(String query, String model, String promptVersion, String status, Number inputTokens,
                          Number outputTokens) { }
}
