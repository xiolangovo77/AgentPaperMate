package com.ecnu.agentpapermate.agent.context;

import com.ecnu.agentpapermate.rag.model.Evidence;
import com.ecnu.agentpapermate.rag.model.EvidenceCandidate;
import com.ecnu.agentpapermate.rag.model.PaperScope;
import com.ecnu.agentpapermate.rag.model.RetrievalCandidates;
import com.ecnu.agentpapermate.rag.model.RetrievalResult;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 每个 Run 独享的候选与证据登记簿；由执行流程串行使用，不作为 Spring 单例。 */
public final class EvidenceRegistry {
    private final Map<Long, PaperScope> allowedScopes;
    /** 预算筛选前的候选并集，与下方已登记的原文证据分别保存。 */
    private final LinkedHashSet<String> retrievedChunkIds = new LinkedHashSet<>();
    private final Map<EvidenceKey, Entry> entries = new LinkedHashMap<>();
    private long nextEvidenceNumber = 1;

    public EvidenceRegistry(List<PaperScope> allowedScopes) {
        this.allowedScopes = allowedScopes.stream().collect(Collectors.toUnmodifiableMap(
                PaperScope::paperId, Function.identity()));
    }

    public Evidence register(EvidenceCandidate candidate) {
        return registerAll(List.of(candidate)).getFirst();
    }

    /** 累计全 Run 的检索候选，同时只返回本轮选中的证据。 */
    public RetrievalResult registerRetrieval(RetrievalCandidates retrieval) {
        Objects.requireNonNull(retrieval, "检索结果不能为空");
        // 原文登记失败时不把本轮候选计入成功登记的检索集合。
        var evidence = registerAll(retrieval.candidates());
        retrievedChunkIds.addAll(retrieval.retrievedChunkIds());
        return new RetrievalResult(getRetrievedChunkIds(), evidence);
    }

    /** 全 Run 的候选去重并集，返回不可修改的首次出现顺序快照。 */
    public List<String> getRetrievedChunkIds() {
        return List.copyOf(retrievedChunkIds);
    }

    /** 按首次出现顺序返回本批证据；重复来源复用原 ID，批内也去重。 */
    public List<Evidence> registerAll(List<EvidenceCandidate> candidates) {
        Objects.requireNonNull(candidates, "证据候选不能为空");
        Map<EvidenceKey, EvidenceCandidate> batch = new LinkedHashMap<>();
        // 先校验整批，避免后一条冲突时已为前面的候选分配编号。
        for (EvidenceCandidate candidate : candidates) {
            Objects.requireNonNull(candidate, "证据候选不能包含空元素");
            PaperScope scope = candidate.scope();
            if (!scope.equals(allowedScopes.get(scope.paperId()))) {
                throw new IllegalArgumentException("证据来源不属于本次运行的论文及版本快照");
            }
            EvidenceKey key = key(candidate);
            Entry existing = entries.get(key);
            EvidenceCandidate previous = batch.putIfAbsent(key, candidate);
            if ((existing != null && !existing.candidate().equals(candidate))
                    || (previous != null && !previous.equals(candidate))) {
                throw new IllegalStateException("同一证据来源的原文或定位发生冲突");
            }
        }
        long newCount = batch.keySet().stream().filter(key -> !entries.containsKey(key)).count();
        Math.addExact(nextEvidenceNumber, newCount);
        for (var item : batch.entrySet()) {
            if (entries.containsKey(item.getKey())) continue;
            EvidenceCandidate candidate = item.getValue();
            PaperScope scope = candidate.scope();
            Evidence evidence = new Evidence("E" + nextEvidenceNumber++, scope.paperId(), scope.title(),
                    scope.indexVersion(), scope.esIndexName(), candidate.chunkId(), candidate.pageStart(),
                    candidate.pageEnd(), candidate.quotedText(), scope.fileUrl());
            entries.put(item.getKey(), new Entry(candidate, evidence));
        }
        return batch.keySet().stream().map(key -> entries.get(key).evidence()).toList();
    }

    /** 全 Run 的登记快照，不等同于某一轮实际送入模型的证据子集。 */
    public List<Evidence> snapshot() {
        return entries.values().stream().map(Entry::evidence).toList();
    }

    public int size() {
        return entries.size();
    }

    private EvidenceKey key(EvidenceCandidate candidate) {
        PaperScope scope = candidate.scope();
        return new EvidenceKey(scope.paperId(), scope.esIndexName(), scope.indexVersion(),
                scope.attemptToken(), candidate.chunkId());
    }

    private record EvidenceKey(Long paperId, String esIndexName, int indexVersion,
                               String attemptToken, String chunkId) { }
    private record Entry(EvidenceCandidate candidate, Evidence evidence) { }
}
