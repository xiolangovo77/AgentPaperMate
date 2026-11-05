package com.ecnu.agentpapermate.entity.vo.readingnote;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 阅读笔记详情。
 */
@Data
public class ReadingNoteVO {

    /**
     * 笔记 ID。
     */
    private Long id;

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

    /**
     * 笔记版本。
     */
    private Integer version;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    private LocalDateTime updateTime;
}
