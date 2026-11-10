package com.ecnu.agentpapermate.support.json;

import com.ecnu.agentpapermate.agent.skill.SkillLoader;
import com.ecnu.agentpapermate.agent.skill.SkillSnapshot;
import com.ecnu.agentpapermate.rag.model.Evidence;
import com.ecnu.agentpapermate.rag.model.PaperScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;

/** 统一读取任务快照，禁止将反序列化异常当作空证据处理。 */
@Component
@RequiredArgsConstructor
public class RagJson {
    /**
     * 应用统一的 JSON 映射器，沿用已配置的 ID 和时间序列化规则。
     */
    private final ObjectMapper mapper;

    /** 只解析服务端保存的技能快照，重新计算引用闭包及哈希，拒绝损坏的内容。 */
    public SkillSnapshot skillSnapshot(String config) {
        try {
            var node = tree(config).get("skillSnapshot");
            if (node == null || !node.isObject()) throw new IllegalArgumentException("缺少技能快照");
            return SkillLoader.verify(mapper.treeToValue(node, SkillSnapshot.class));
        } catch (java.io.IOException | RuntimeException e) {
            throw new IllegalArgumentException("任务技能快照无效", e);
        }
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("任务数据序列化失败", e);
        }
    }

    public JsonNode tree(String value) {
        try {
            return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("任务 JSON 数据无效", e);
        }
    }

    public List<PaperScope> scopes(String value) {
        try {
            return mapper.readValue(value, new TypeReference<>() { });
        } catch (java.io.IOException e) {
            throw new IllegalStateException("任务论文快照无效", e);
        }
    }

    public List<Evidence> evidence(String value) {
        try {
            return mapper.readValue(value, new TypeReference<>() { });
        } catch (java.io.IOException e) {
            throw new IllegalStateException("任务证据快照无效", e);
        }
    }

    /** 严格读取有序候选，不将损坏的数据、数字或 JSON null 当作空检索。 */
    public List<String> retrievedChunkIds(String value) {
        JsonNode root = tree(value);
        if (root == null || !root.isArray()) throw new IllegalStateException("任务检索快照必须是数组");
        List<String> result = new ArrayList<>();
        var seen = new HashSet<String>();
        for (JsonNode id : root) {
            if (!id.isTextual() || id.asText().isBlank() || !seen.add(id.asText())) {
                throw new IllegalStateException("任务检索快照包含无效或重复 Chunk ID");
            }
            result.add(id.asText());
        }
        return List.copyOf(result);
    }
}
