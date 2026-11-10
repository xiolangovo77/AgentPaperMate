package com.ecnu.agentpapermate.agent.tool;

import com.ecnu.agentpapermate.agent.context.AgentRunContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BiFunction;

/** 系统工具定义与实现的只读注册表；模型只能从后端允许集合中选择名称。 */
@Component
public class ToolRegistry {
    /** 应用启动时确定的工具映射，重复名称会阻止启动。 */
    private final Map<String, RegisteredTool> tools;

    @Autowired
    public ToolRegistry(PaperTools papers) {
        this(papers.registeredTools());
    }

    /** 接受后端注册项，允许独立构造注册表进行验证。 */
    public ToolRegistry(List<RegisteredTool> registered) {
        var indexed = new LinkedHashMap<String, RegisteredTool>();
        for (var tool : registered) {
            if (tool == null || tool.definition() == null || tool.handler() == null
                    || tool.definition().name() == null || !tool.definition().name().matches("[a-z][a-z0-9_]{0,63}")
                    || indexed.putIfAbsent(tool.definition().name(), tool) != null) {
                throw new IllegalArgumentException("工具注册项无效或名称重复");
            }
        }
        this.tools = Collections.unmodifiableMap(indexed);
    }

    /** 返回系统与后端允许集合的交集，后续 Skill 只能进一步缩小该集合。 */
    public List<ToolDefinition> definitions(Set<String> allowedTools) {
        return tools.entrySet().stream().filter(item -> allowedTools.contains(item.getKey()))
                .map(item -> item.getValue().definition()).toList();
    }

    /** 未知工具返回空，不解析任意类名、方法名或文件路径。 */
    public Optional<RegisteredTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 系统当前已注册的名称快照；调用者可以取子集作为本次执行白名单。 */
    public Set<String> names() {
        return tools.keySet();
    }

    /** Schema 与受控实现一起注册；实现负责 Schema 对应的类型约束及业务范围校验。 */
    public record RegisteredTool(ToolDefinition definition,
                                 BiFunction<AgentRunContext, JsonNode, ToolResult> handler) { }
}
