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
        //1、校验回答大小与引用集合，不允许未知证据或重复引用 ID
        if (answer == null || answer.content() == null || answer.content().isBlank() || answer.content().length() > 100000
                || answer.evidenceIds() == null) {
            throw new InvalidAnswer(Failure.INVALID_STRUCTURE);
        }
        Set<String> allowed = new HashSet<>();
        evidence.forEach(item -> allowed.add(item.evidenceId()));
        Set<String> used = new HashSet<>(answer.evidenceIds());
        if (!allowed.containsAll(used)) throw new InvalidAnswer(Failure.UNKNOWN_EVIDENCE);
        if (used.size() != answer.evidenceIds().size()) throw new InvalidAnswer(Failure.DUPLICATE_EVIDENCE);
        if (!evidence.isEmpty() && used.isEmpty()) throw new InvalidAnswer(Failure.MISSING_CITATION);
        //2、正文引用必须与结构化引用完全一致，阻止只在正文伪造来源
        Set<String> markers = new HashSet<>();
        MARKER.matcher(answer.content()).results().forEach(match -> markers.add(match.group(1)));
        if (!markers.equals(used)) throw new InvalidAnswer(Failure.CITATION_MISMATCH);
    }

    /** 固定诊断原因，不包含模型原文、论文内容或上游异常。 */
    public enum Failure {
        INVALID_JSON("回答不是完整的 JSON 对象"),
        INVALID_STRUCTURE("回答须包含非空 answer 字符串和 evidenceIds 字符串数组，且不能超长"),
        UNKNOWN_EVIDENCE("引用了本次未提供的证据 ID"),
        DUPLICATE_EVIDENCE("evidenceIds 中存在重复 ID"),
        MISSING_CITATION("已提供原文证据，但回答没有引用依据"),
        CITATION_MISMATCH("正文 [E编号] 标记与 evidenceIds 不一致");

        private final String description;
        Failure(String description) { this.description = description; }
        public String description() { return description; }
    }

    public static class InvalidAnswer extends RuntimeException {
        private final Failure failure;
        public InvalidAnswer() { this(Failure.INVALID_STRUCTURE); }
        public InvalidAnswer(Failure failure) {
            super(failure.description());
            this.failure = failure;
        }
        public Failure failure() { return failure; }
    }
}
