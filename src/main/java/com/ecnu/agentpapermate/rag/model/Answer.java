package com.ecnu.agentpapermate.rag.model;

import java.util.List;

/** 已校验的最终回答及用量，引用 ID 必须属于当前任务的证据集合。 */
public record Answer(String content, List<String> evidenceIds, long inputTokens, long outputTokens) { }
