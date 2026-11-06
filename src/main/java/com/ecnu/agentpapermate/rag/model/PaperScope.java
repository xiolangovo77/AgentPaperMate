package com.ecnu.agentpapermate.rag.model;

/** 论文检索范围快照，包含物理索引、有效尝试令牌和向量配置。 */
public record PaperScope(Long paperId, String title, int indexVersion, String attemptToken,
                         String esIndexName, String embeddingModel, String embeddingVersion,
                         int embeddingDimension, String fileUrl) { }
