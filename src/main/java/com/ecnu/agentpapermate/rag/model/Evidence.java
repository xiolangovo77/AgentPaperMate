package com.ecnu.agentpapermate.rag.model;

/** 已登记的原文证据，页码和来源只接受检索返回的元数据。 */
public record Evidence(String evidenceId, Long paperId, String paperTitle, int indexVersion,
                       String esIndexName, String chunkId, int pageStart, int pageEnd,
                       String quotedText, String fileUrl) { }
