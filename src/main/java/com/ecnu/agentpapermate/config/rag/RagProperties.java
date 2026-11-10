package com.ecnu.agentpapermate.config.rag;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 固定 RAG 的执行预算与模型选项；连接和密钥沿用原生 DashScope 配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {
    /** 是否接受新问答任务，配置模型后开启。 */
    private boolean enabled;
    /** 回答模型名称，创建任务时保存快照。 */
    private String model = "qwen3.7-plus";
    /** 模型输出上限。 */
    private int maxOutputTokens = 2048;
    /** 每个 Run 最多发起的聊天模型调用次数，包含失败请求和显式重试。 */
    private int maxModelCalls = 8;
    /** Agent 每轮输入及预留输出的估算 Token 上限，超限失败，不静默丢弃证据。 */
    private int agentContextTokenBudget = 24000;
    /** 每个 Run 最多尝试执行的工具次数，拒绝重复 ID 不消耗次数。 */
    private int maxToolCalls = 12;
    /** 单工具最长等待秒数，同时受整个 Run 的截止时间约束。 */
    private int toolTimeoutSeconds = 20;
    /** 单次外部请求最长等待秒数。 */
    private int requestTimeoutSeconds = 60;
    /** 开始执行后的总预算秒数。 */
    private int runTimeoutSeconds = 180;
    /** 排队任务最长等待秒数。 */
    private int queueTimeoutSeconds = 300;
    /** 本实例同时执行的任务数量。 */
    private int maxConcurrentRuns = 4;
    /** 固定 RAG 每路检索的候选数量，同时作为所有检索请求（含 Agent）的 topK 上限。 */
    private int retrievalTopK = 20;
    /** 融合后允许提供给模型的证据数量。 */
    private int maxEvidence = 8;
    /** 证据文本总预算，使用 cl100k_base 估算。 */
    private int evidenceTokenBudget = 6000;
    /** 单个会话最多参与一次检索的论文数量。 */
    private int maxPapers = 50;
    /** 本实例 SSE 连接数量上限。 */
    private int maxSseConnections = 100;

    @PostConstruct
    public void validate() {
        //1、约束执行、检索和连接预算，避免无界排队或请求
        if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > 300 || runTimeoutSeconds < 1
                || runTimeoutSeconds > 3600 || queueTimeoutSeconds < 1 || queueTimeoutSeconds > 3600
                || maxConcurrentRuns < 1 || maxConcurrentRuns > 64 || retrievalTopK < 1 || retrievalTopK > 100
                || maxEvidence < 1 || maxEvidence > retrievalTopK || evidenceTokenBudget < 128
                || evidenceTokenBudget > 32000 || maxPapers < 1 || maxPapers > 100
                || maxOutputTokens < 1 || maxOutputTokens > 16000 || maxModelCalls < 1 || maxModelCalls > 100
                || agentContextTokenBudget < 1024 || agentContextTokenBudget > 128000
                || maxToolCalls < 1 || maxToolCalls > 100 || toolTimeoutSeconds < 1 || toolTimeoutSeconds > 300
                || maxSseConnections < 1 || maxSseConnections > 1000) {
            throw new IllegalArgumentException("rag 执行预算配置无效");
        }
        //2、仅在开启问答时强制校验模型名称，不影响已存在的论文入库服务
        if (enabled) {
            if (model == null || model.isBlank() || model.length() > 128) {
                throw new IllegalArgumentException("开启 RAG 前请配置 rag.model");
            }
        }
    }
}
