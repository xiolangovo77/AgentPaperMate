package com.ecnu.agentpapermate.rag.model;

import java.util.HashSet;
import java.util.List;

/** 执行层登记后的结果：全 Run 候选去重并集（首次出现顺序）及本轮已登记的证据子集。 */
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
