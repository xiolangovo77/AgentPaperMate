package com.ecnu.agentpapermate.rag.model;

/** 尚未编号的原文证据；携带后端来源快照，登记时核对版本、尝试令牌和原文。 */
public record EvidenceCandidate(PaperScope scope, String chunkId, int pageStart, int pageEnd,
                                String quotedText) {
    public EvidenceCandidate {
        if (scope == null || chunkId == null || chunkId.isBlank() || chunkId.length() > 128
                || pageStart < 1 || pageEnd < pageStart || quotedText == null || quotedText.isBlank()) {
            throw new IllegalArgumentException("证据候选缺少有效来源、页码或原文");
        }
    }
}
