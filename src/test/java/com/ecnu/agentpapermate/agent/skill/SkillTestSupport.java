package com.ecnu.agentpapermate.agent.skill;

import com.ecnu.agentpapermate.entity.model.AgentRun;
import com.ecnu.agentpapermate.support.json.RagJson;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/** 本地测试使用生产加载器生成真实快照，避免手工伪造哈希绕过校验。 */
public final class SkillTestSupport {
    private SkillTestSupport() { }

    public static SkillRouter router() { return new SkillRouter(new SkillRegistry(new SkillLoader())); }

    public static void attach(AgentRun run, RagJson json, SkillSnapshot snapshot) {
        var config = (ObjectNode) json.tree(run.getModelConfig() == null ? "{}" : run.getModelConfig());
        config.set("skillSnapshot", json.tree(json.write(snapshot)));
        run.setModelConfig(config.toString());
        run.setSkillId(snapshot.id()); run.setSkillVersion(snapshot.version()); run.setSkillContentHash(snapshot.contentHash());
    }

    /** 从修改后的文件集合重新计算快照，供上下文超限及权限收缩的集成测试使用。 */
    public static SkillSnapshot replace(SkillSnapshot original, String path, String content) {
        var files = new java.util.HashMap<>(original.files()); files.put(path, content);
        var loader = new SkillLoader((id, name) -> files.get(name).getBytes(StandardCharsets.UTF_8));
        return loader.load(loader.metadata(original.id()));
    }
}
