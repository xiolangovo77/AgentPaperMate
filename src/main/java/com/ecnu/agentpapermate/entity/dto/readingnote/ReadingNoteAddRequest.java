package com.ecnu.agentpapermate.entity.dto.readingnote;

import lombok.Data;

/**
 * 新增阅读笔记请求。
 */
@Data
public class ReadingNoteAddRequest {

    /**
     * 所属论文 ID。
     */
    private Long paperId;

    /**
     * 研究问题。
     */
    private String researchQuestion;

    /**
     * 技术方法。
     */
    private String methods;

    /**
     * 核心创新。
     */
    private String innovations;

    /**
     * 实验结果。
     */
    private String experimentResults;

    /**
     * 局限与疑问。
     */
    private String limitationsQuestions;

    /**
     * 个人思考。
     */
    private String personalThoughts;
}
