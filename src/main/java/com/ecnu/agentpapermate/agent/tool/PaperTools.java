package com.ecnu.agentpapermate.agent.tool;

import com.ecnu.agentpapermate.agent.context.AgentRunContext;
import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.rag.RagRetriever;
import com.ecnu.agentpapermate.rag.model.RetrievalRequest;
import com.ecnu.agentpapermate.service.PaperService;
import com.ecnu.agentpapermate.service.run.rag.RunStore;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** 两个论文只读工具的定义与实现，统一由 ToolRegistry 注册、ToolExecutor 执行。 */
@Component
@RequiredArgsConstructor
public class PaperTools {
    /** 核对论文当前归属并读取元数据，不将持久化实体整体交给模型。 */
    private final PaperService papers;
    /** 复用受范围、索引版本和 topK 限制的检索实现。 */
    private final RagRetriever retriever;
    /** 检查 Run 存活状态并保存检索快照。 */
    private final RunStore store;
    /** 提供缺省 topK，最终上限仍由检索器强制检查。 */
    private final RagProperties properties;

    /** 只描述可接受的参数，用户身份、索引配置和截止时间均不对模型开放。 */
    public List<ToolDefinition> definitions() {
        return List.of(
                ToolDefinition.builder().name("get_paper_metadata")
                        .description("读取当前 Run 范围内一篇论文的元数据；缺失字段返回 null，不作为原文引用证据。")
                        .inputSchema("""
                                {"type":"object","properties":{"paperId":{"type":"string","pattern":"^[1-9][0-9]*$"}},
                                 "required":["paperId"],"additionalProperties":false}
                                """).build(),
                ToolDefinition.builder().name("search_paper_chunks")
                        .description("在当前 Run 论文和索引快照内检索原文，返回可引用证据。省略 requestedPaperIds 使用完整范围；topK 不得超过服务端上限。")
                        .inputSchema("""
                                {"type":"object","properties":{
                                  "query":{"type":"string","minLength":1,"maxLength":16000},
                                  "requestedPaperIds":{"type":"array","minItems":1,"maxItems":100,"uniqueItems":true,
                                    "items":{"type":"string","pattern":"^[1-9][0-9]*$"}},
                                  "topK":{"type":"integer","minimum":1,"maximum":%d}},
                                 "required":["query"],"additionalProperties":false}
                                """.formatted(properties.getRetrievalTopK())).build());
    }

    /** 定义和处理函数一起注册，不再通过字符串 switch 分派。 */
    public List<ToolRegistry.RegisteredTool> registeredTools() {
        var definitions = definitions();
        return List.of(new ToolRegistry.RegisteredTool(definitions.get(0), this::metadata),
                new ToolRegistry.RegisteredTool(definitions.get(1), this::search));
    }

    private ToolResult metadata(AgentRunContext context, JsonNode args) {
        fields(args, Set.of("paperId"));
        Long id = paperId(args.path("paperId"));
        var scope = context.getRetrievalContext().allowedScopes().stream()
                .filter(item -> item.paperId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("论文超出本次 Run 范围"));
        var paper = papers.requireOwnedPaper(id, context.getRetrievalContext().userId());
        var data = new LinkedHashMap<String, Object>();
        data.put("paperId", id.toString());
        data.put("title", scope.title());
        data.put("authors", paper.getAuthors());
        data.put("topic", paper.getTopic());
        data.put("pageCount", paper.getPageCount());
        data.put("indexVersion", scope.indexVersion());
        // 不返回全文摘要或文件地址，保持最小且有界；可引用原文由检索工具提供。
        return ToolResult.success(data, List.of());
    }

    private ToolResult search(AgentRunContext context, JsonNode args) {
        fields(args, Set.of("query", "requestedPaperIds", "topK"));
        if (!args.path("query").isTextual()) throw new IllegalArgumentException("query 必须是字符串");
        List<Long> requested = null;
        if (args.has("requestedPaperIds")) {
            var ids = args.get("requestedPaperIds");
            if (!ids.isArray() || ids.isEmpty() || ids.size() > 100) {
                throw new IllegalArgumentException("requestedPaperIds 必须是非空且有界的数组");
            }
            requested = new ArrayList<>();
            for (var id : ids) requested.add(paperId(id));
        }
        int topK = properties.getRetrievalTopK();
        if (args.has("topK")) {
            var value = args.get("topK");
            if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("topK 必须是整数");
            topK = value.intValue();
        }
        var request = new RetrievalRequest(args.get("query").asText(), requested, topK);
        if (topK > properties.getRetrievalTopK()) throw new IllegalArgumentException("topK 超过配置上限");
        if (requested != null) {
            var allowed = context.getRetrievalContext().allowedScopes().stream().map(item -> item.paperId()).toList();
            if (!allowed.containsAll(requested)) throw new IllegalArgumentException("论文超出本次 Run 范围");
        }
        if (!store.retrievalStarted(context.getRunId())) throw new CancellationException("Run 已停止");
        var candidates = retriever.retrieve(request, context.getRetrievalContext(), context.getDeadline());
        requireActive(context);
        var result = context.getEvidenceRegistry().registerRetrieval(candidates);
        if (!store.retrieved(context.getRunId(), result)) throw new CancellationException("Run 已停止");
        return ToolResult.success(java.util.Map.of("retrievedChunkIds", result.retrievedChunkIds()), result.evidence());
    }

    private void requireActive(AgentRunContext context) {
        if (Thread.currentThread().isInterrupted() || !store.executionAllowed(context.getRunId())) {
            throw new CancellationException("Run 已停止");
        }
    }

    private void fields(JsonNode args, Set<String> allowed) {
        args.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new IllegalArgumentException("工具参数包含未知字段");
        });
    }

    private Long paperId(JsonNode value) {
        if (!value.isTextual() || !value.asText().matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("论文 ID 必须是正整数字符串");
        }
        return Long.valueOf(value.asText());
    }
}
