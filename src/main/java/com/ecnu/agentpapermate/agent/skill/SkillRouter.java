package com.ecnu.agentpapermate.agent.skill;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 只使用当前问题进行保守规则路由；历史消息和论文正文不能选择技能或路径。 */
@Component
@RequiredArgsConstructor
public class SkillRouter {
    /** 只匹配明确的总结命令；无法确定的问句、混合任务统一回退问答。 */
    private static final Pattern SUMMARY = Pattern.compile(
            "^(?:(?:请|帮我|请帮我)\\s*)?(?:总结|概括|生成(?:一份)?(?:结构化)?总结|summarize\\b|summarise\\b|summary\\b).*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 所有规则结果都必须经过服务端注册表的启用校验。 */
    private final SkillRegistry registry;

    /** 返回值来自注册表，永远不是模型或用户传入的任意路径。 */
    public String route(String question) {
        return registry.resolve(question != null && SUMMARY.matcher(question.strip()).matches()
                ? "paper-summary" : "paper-qa");
    }

    /** 在创建事务中取得完整不可变内容，用于持久化并供随后执行。 */
    public SkillSnapshot select(String question) { return registry.load(route(question)); }
}
