package com.ecnu.agentpapermate.rag.model;

import java.util.HashSet;
import java.util.List;

/** 检索器输出：预算筛选前的完整候选 ID，以及筛选后尚未分配证据 ID 的原文。 */
public record RetrievalCandidates(List<String> retrievedChunkIds, List<EvidenceCandidate> candidates) {
    public RetrievalCandidates {
        retrievedChunkIds = List.copyOf(retrievedChunkIds);
        candidates = List.copyOf(candidates);
        var ids = new HashSet<>(retrievedChunkIds);
        if (ids.size() != retrievedChunkIds.size() || retrievedChunkIds.stream().anyMatch(String::isBlank)
                || candidates.stream().anyMatch(item -> !ids.contains(item.chunkId()))) {
            throw new IllegalArgumentException("检索候选必须有序去重，证据候选必须来自检索结果");
        }
    }
}
