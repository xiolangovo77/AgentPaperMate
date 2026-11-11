package com.ecnu.agentpapermate.agent.skill;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/** 服务端显式注册技能，部署目录中额外出现的文件不会自动获得执行资格。 */
@Component
public class SkillRegistry {
    /** 本批运行策略的工具上限；未来注册新工具不会自动扩大权限。 */
    public static final Set<String> RUN_TOOLS = Set.of("get_paper_metadata", "search_paper_chunks");
    /** 注册阶段只保留元数据，选中后才读取参考内容。 */
    private final Map<String, SkillLoader.Metadata> registered;
    /** 选定技能后才读取正文及参考文件的受控加载器。 */
    private final SkillLoader loader;

    @Autowired
    public SkillRegistry(SkillLoader loader) {
        this(loader, List.of("paper-qa", "paper-summary", "paper-comparison", "research-analysis"));
    }

    SkillRegistry(SkillLoader loader, List<String> ids) {
        this.loader = loader;
        var entries = new LinkedHashMap<String, SkillLoader.Metadata>();
        for (String id : ids) {
            var metadata = loader.metadata(id);
            if (!RUN_TOOLS.containsAll(metadata.allowedTools()) || entries.putIfAbsent(id, metadata) != null)
                throw new IllegalArgumentException("技能注册重复或声明了未授权工具");
        }
        this.registered = Map.copyOf(entries);
        if (!enabled("paper-qa")) throw new IllegalArgumentException("默认问答技能必须注册并启用");
    }

    /** 执行前再次检查当前部署是否允许该技能；不会用新指令替换历史快照。 */
    public boolean enabled(String id) { return id != null && registered.containsKey(id) && registered.get(id).enabled(); }

    /** 路由结果只能选择已注册且启用的 ID；未知或停用项安全回退。 */
    public String resolve(String candidate) { return enabled(candidate) ? candidate : "paper-qa"; }

    /** 仅从已注册启用项加载；内容损坏直接失败，不能静默替换已选技能。 */
    public SkillSnapshot load(String candidate) { return loader.load(registered.get(resolve(candidate))); }
}
