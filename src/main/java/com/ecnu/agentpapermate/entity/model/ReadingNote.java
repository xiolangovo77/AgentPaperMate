package com.ecnu.agentpapermate.entity.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 论文阅读笔记。
 */
@Getter
@Setter
@ToString
@TableName("reading_note")
public class ReadingNote implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 笔记 ID。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 笔记所有者。
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 所属论文 ID。
     */
    @TableField("paper_id")
    private Long paperId;

    /**
     * 研究问题。
     */
    @TableField("research_question")
    private String researchQuestion;

    /**
     * 技术方法。
     */
    @TableField("methods")
    private String methods;

    /**
     * 核心创新。
     */
    @TableField("innovations")
    private String innovations;

    /**
     * 实验结果。
     */
    @TableField("experiment_results")
    private String experimentResults;

    /**
     * 局限与疑问。
     */
    @TableField("limitations_questions")
    private String limitationsQuestions;

    /**
     * 个人思考。
     */
    @TableField("personal_thoughts")
    private String personalThoughts;

    /**
     * 笔记版本。
     */
    @TableField("version")
    private Integer version;

    /**
     * 创建时间。
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    @TableField("update_time")
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标记。
     */
    @TableLogic
    @TableField("is_delete")
    private Byte isDelete;
}
