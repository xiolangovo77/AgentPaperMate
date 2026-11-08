package com.ecnu.agentpapermate.rag.model;

import java.util.HashSet;
import java.util.List;

/** 同一次检索的预算筛选前有序候选及筛选后的最终证据。 */
public record RetrievalResult(List<String> retrievedChunkIds, List<Evidence> evidence) {
    public RetrievalResult {
        retrievedChunkIds = List.copyOf(retrievedChunkIds);
        evidence = List.copyOf(evidence);
        var candidates = new HashSet<>(retrievedChunkIds);
        if (candidates.size() != retrievedChunkIds.size()
                || retrievedChunkIds.stream().anyMatch(String::isBlank)
                || evidence.stream().anyMatch(item -> !candidates.contains(item.chunkId()))) {
            throw new IllegalArgumentException("检索候选必须有序去重，最终证据必须来自候选");
        }
    }
}
