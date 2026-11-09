package com.ecnu.agentpapermate.rag.model;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

/** 单次检索参数，不携带用户身份、索引名称或版本；null 论文集合表示完整 Run 范围。 */
public record RetrievalRequest(String query, List<Long> requestedPaperIds, int topK) {
    public RetrievalRequest {
        if (query == null || query.isBlank() || query.getBytes(StandardCharsets.UTF_8).length > 16000) {
            throw new IllegalArgumentException("检索问题必须非空且不超过16000字节");
        }
        if (topK < 1 || topK > 100) {
            throw new IllegalArgumentException("每路检索候选数量必须为1至100");
        }
        if (requestedPaperIds != null) {
            if (requestedPaperIds.isEmpty() || requestedPaperIds.size() > 100
                    || requestedPaperIds.stream().anyMatch(id -> id == null || id <= 0)
                    || new HashSet<>(requestedPaperIds).size() != requestedPaperIds.size()) {
                throw new IllegalArgumentException("指定论文集合必须包含1至100个不重复的有效ID");
            }
            requestedPaperIds = List.copyOf(requestedPaperIds);
        }
    }
}
