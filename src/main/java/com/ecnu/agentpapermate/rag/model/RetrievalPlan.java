package com.ecnu.agentpapermate.rag.model;

/** 单次检索实验的策略和参数快照。 */
public record RetrievalPlan(Strategy strategy, int topK, int numCandidates, int rrfK, int rerankInputSize) {
    public RetrievalPlan {
        if (strategy == null || topK < 1 || topK > 100 || numCandidates < topK || numCandidates > 10000
                || rrfK < 1 || rrfK > 10000 || rerankInputSize < 1 || rerankInputSize > 200) {
            throw new IllegalArgumentException("检索策略、候选深度或排序参数无效");
        }
    }

    public enum Strategy {
        R0, // BM25
        R1, // kNN
        R2, // BM25 / kNN 交替合并
        R3, // RRF
        R4, // RRF + 精排
        R5; // 查询改写 + RRF + 精排

        public boolean usesReranker() {
            return this == R4 || this == R5;
        }
    }
}
