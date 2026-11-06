package com.ecnu.agentpapermate.validator;

import com.ecnu.agentpapermate.rag.model.Answer;
import com.ecnu.agentpapermate.rag.model.Evidence;
import java.util.*;
import java.util.regex.Pattern;

/** 检查引用身份与正文标记；此检查不等同于自动证明回答的语义正确性。 */
public final class AnswerValidator {
    /**
     * 正文证据标记的匹配规则，用于核对 [E1] 等标记与结构化引用 ID 是否一致。
     */
    private static final Pattern MARKER = Pattern.compile("\\[(E[0-9]+)\\]");
    private AnswerValidator() { }

    public static void validate(Answer answer, List<Evidence> evidence) {
        //1、校验回答大小、用量与引用集合，不允许未知证据或重复引用 ID
        if (answer == null || answer.content() == null || answer.content().isBlank() || answer.content().length() > 100000
                || answer.evidenceIds() == null || answer.inputTokens() < 0 || answer.outputTokens() < 0) {
            throw new InvalidAnswer();
        }
        Set<String> allowed = new HashSet<>();
        evidence.forEach(item -> allowed.add(item.evidenceId()));
        Set<String> used = new HashSet<>(answer.evidenceIds());
        if (!allowed.containsAll(used) || used.size() != answer.evidenceIds().size()
                || (!evidence.isEmpty() && used.isEmpty())) throw new InvalidAnswer();
        //2、正文引用必须与结构化引用完全一致，阻止只在正文伪造来源
        Set<String> markers = new HashSet<>();
        MARKER.matcher(answer.content()).results().forEach(match -> markers.add(match.group(1)));
        if (!markers.equals(used)) throw new InvalidAnswer();
    }

    public static class InvalidAnswer extends RuntimeException {
        public InvalidAnswer() { super("模型回答格式或引用校验失败"); }
    }
}
