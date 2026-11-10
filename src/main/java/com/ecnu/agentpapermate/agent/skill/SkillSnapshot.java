package com.ecnu.agentpapermate.agent.skill;

import java.util.Map;
import java.util.Set;

/**
 * 创建 Run 时持久化的技能内容；执行时只使用此快照，不再读取部署文件。
 * @param id 服务端注册的技能名称
 * @param version SKILL.md 声明的语义版本
 * @param contentHash 原始文件路径及字节内容的 SHA-256
 * @param allowedTools 技能声明的工具上限，还需与系统和运行权限取交集
 * @param instructions 展开参考资料后的实际模型指令
 * @param files 实际加载的原始 UTF-8 文件闭包，用于独立重建和验证
 */
public record SkillSnapshot(String id, String version, String contentHash, Set<String> allowedTools,
                            String instructions, Map<String, String> files) {
    public SkillSnapshot {
        allowedTools = Set.copyOf(allowedTools);
        files = Map.copyOf(files);
    }
}
