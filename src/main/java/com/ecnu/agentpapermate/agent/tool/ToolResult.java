package com.ecnu.agentpapermate.agent.tool;

import com.ecnu.agentpapermate.rag.model.Evidence;

import java.util.List;
import java.util.Map;

/** 工具统一结果：业务数据和可引用原文分开，失败只包含受控摘要。 */
public record ToolResult(String status, String code, boolean retryable, Object data, List<Evidence> evidence) {
    public ToolResult {
        evidence = List.copyOf(evidence);
    }

    /** 成功数据可以包含缺失的元数据字段，不为其伪造证据。 */
    public static ToolResult success(Object data, List<Evidence> evidence) {
        return new ToolResult("ok", "OK", false, data, evidence);
    }

    /** 当前没有自动重试，拒绝结果不会携带异常文本或论文原文。 */
    public static ToolResult rejected(String code, String message) {
        return new ToolResult("error", code, false, Map.of("message", message), List.of());
    }
}
