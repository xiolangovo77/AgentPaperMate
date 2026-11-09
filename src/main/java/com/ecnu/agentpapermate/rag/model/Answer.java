package com.ecnu.agentpapermate.rag.model;

import java.util.List;

/** 已校验的最终回答，引用 ID 必须属于当前任务的证据集合；用量独立累计到 Run。 */
public record Answer(String content, List<String> evidenceIds) { }
