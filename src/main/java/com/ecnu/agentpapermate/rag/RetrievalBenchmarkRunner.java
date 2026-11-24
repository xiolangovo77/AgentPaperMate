package com.ecnu.agentpapermate.rag;

import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.ecnu.agentpapermate.rag.model.PaperScope;
import com.ecnu.agentpapermate.rag.model.RetrievalContext;
import com.ecnu.agentpapermate.rag.model.RetrievalPlan;
import com.ecnu.agentpapermate.service.PaperProcessingJobService;
import com.ecnu.agentpapermate.service.PaperService;
import com.ecnu.agentpapermate.support.ai.RagCalls;
import com.ecnu.agentpapermate.support.json.RagJson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** 显式启用的本地 JSONL 入口，每次运行一套策略。 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "bench.retrieval.enabled", havingValue = "true")
class RetrievalBenchmarkRunner implements ApplicationRunner {
    private static final String PREFIX = "bench.retrieval.";
    private final Environment environment;
    private final RetrievalBenchmark benchmark;
    private final PaperService papers;
    private final PaperProcessingJobService jobs;
    private final RagProperties properties;
    private final Clock ragClock;
    private final RagJson json;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var input = Path.of(environment.getRequiredProperty(PREFIX + "input"));
        var output = Path.of(environment.getRequiredProperty(PREFIX + "output"));
        long ownerId = environment.getRequiredProperty(PREFIX + "owner-id", Long.class);
        if (ownerId <= 0) throw new IllegalArgumentException("评测必须指定有效的论文所有者 ID");
        var plan = new RetrievalPlan(environment.getRequiredProperty(PREFIX + "strategy", RetrievalPlan.Strategy.class),
                parameter("top-k", 20), parameter("num-candidates", 100), parameter("rrf-k", 60), parameter("rerank-input-size", 20));

        Map<Long, RetrievalContext> scopes = new HashMap<>();
        int rows = 0;
        int failures = 0;
        try (var reader = Files.newBufferedReader(input); var writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                var row = evaluate(line, ++rows, ownerId, plan, scopes);
                if (!"ok".equals(row.get("status"))) failures++;
                writer.write(json.write(row));
                writer.newLine();
            }
        }
        log.info("检索评测完成，策略={}，题数={}，失败数={}，输出={}", plan.strategy(), rows, failures, output);
    }

    private Map<String, Object> evaluate(String line, int rowNumber, long ownerId, RetrievalPlan plan,
                                         Map<Long, RetrievalContext> scopes) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("row", rowNumber);
        row.put("plan", plan);
        try {
            var input = json.tree(line);
            String questionId = input.path("question_id").asText("");
            if (questionId.isBlank()) throw new IllegalArgumentException("缺少 question_id");
            row.put("question_id", questionId);
            row.put("paper_id", input.path("paper_id").asText(""));
            long paperId = Long.parseLong(input.path("library_paper_id").asText());
            if (paperId <= 0) throw new IllegalArgumentException("无效的 library_paper_id");
            row.put("library_paper_id", Long.toString(paperId));
            var context = scopes.computeIfAbsent(paperId, id -> ownedScope(id, ownerId));
            var scope = context.allowedScopes().getFirst();
            row.put("index_name", scope.esIndexName());
            row.put("index_version", scope.indexVersion());
            row.put("embedding_model", scope.embeddingModel());
            row.put("embedding_version", scope.embeddingVersion());
            var deadline = LocalDateTime.now(ragClock).plusSeconds(properties.getRunTimeoutSeconds());
            row.put("result", benchmark.evaluate(input.path("question").asText(""), context, plan, deadline));
            row.put("status", "ok");
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            row.put("status", failure instanceof RagCalls.RequestTimeout ? "timeout" : "error");
            row.put("error_type", failure.getClass().getSimpleName());
        }
        return row;
    }

    private RetrievalContext ownedScope(long id, long ownerId) {
        var paper = papers.requireOwnedPaper(id, ownerId);
        Integer indexVersion = paper.getActiveIndexVersion();
        String attempt = paper.getActiveIndexAttempt();
        if (indexVersion == null || attempt == null || attempt.isBlank()) throw new IllegalArgumentException("论文索引尚未就绪");
        var job = jobs.lambdaQuery().eq(PaperProcessingJob::getPaperId, id)
                .eq(PaperProcessingJob::getIndexVersion, indexVersion).eq(PaperProcessingJob::getStatus, "completed").one();
        if (job == null || job.getEmbeddingDimension() == null) throw new IllegalArgumentException("缺少已完成的论文索引任务");
        var scope = new PaperScope(id, paper.getTitle(), indexVersion, attempt, job.getEsIndexName(), job.getEmbeddingModel(),
                job.getEmbeddingVersion(), job.getEmbeddingDimension(), paper.getFileUrl());
        return new RetrievalContext(ownerId, List.of(scope));
    }

    private int parameter(String name, int fallback) {
        return environment.getProperty(PREFIX + name, Integer.class, fallback);
    }
}
