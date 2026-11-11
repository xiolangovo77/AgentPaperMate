package com.ecnu.agentpapermate.agent.skill;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 只使用当前问题进行保守规则路由；历史消息和论文正文不能选择技能或路径。 */
@Component
@RequiredArgsConstructor
public class SkillRouter {
    /** 总结命令规则；所有未命中规则的问题回退问答。 */
    private static final Pattern SUMMARY = Pattern.compile(
            "^(?:(?:请|帮我|请帮我)\\s*)?(?:总结|概括|生成(?:一份)?(?:结构化)?总结|summarize\\b|summarise\\b|summary\\b).*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 所有规则结果都必须经过服务端注册表的启用校验。 */
    private final SkillRegistry registry;

    private static final String PREFIX = "^(?:(?:请帮我|帮我|请)\\s*)?";
    private static final Pattern COMPARISON = Pattern.compile(PREFIX
            + "(?:(?:比较|对比).*(?:论文|文献).*|多论文(?:比较|对比).*|paper-comparison\\b.*|compare\\b.*\\bpapers?\\b.*)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern RESEARCH = Pattern.compile(PREFIX
            + "(?:(?:分析|梳理|探索|提出|推荐|生成).*(?:研究思路|研究方向|研究选题).*|研究(?:思路|方向|选题)分析.*|research-analysis\\b.*|research analysis\\b.*)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 返回值来自注册表，永远不是模型或用户传入的任意路径。 */
    public String route(String question) {
        String text = question == null ? "" : question.strip();
        if (RESEARCH.matcher(text).matches()) return registry.resolve("research-analysis");
        if (COMPARISON.matcher(text).matches()) return registry.resolve("paper-comparison");
        return registry.resolve(SUMMARY.matcher(text).matches()
                ? "paper-summary" : "paper-qa");
    }

    /** 在创建事务中取得完整不可变内容，用于持久化并供随后执行。 */
    public SkillSnapshot select(String question) { return registry.load(route(question)); }
}
