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
 * <p>
 * 聊天室当前论文关联
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Getter
@Setter
@ToString
@TableName("session_paper")
public class SessionPaper implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 资料关联ID
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 聊天室ID，关联chat_session.id
     */
    @TableField("session_id")
    private Long sessionId;

    /**
     * 论文ID，关联paper.id
     */
    @TableField("paper_id")
    private Long paperId;

    /**
     * 关联首次创建时间（上海时间）
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 关联更新时间（上海时间）
     */
    @TableField("update_time")
    private LocalDateTime updateTime;

    /**
     * 0当前在会话中、1已移出
     */
    @TableLogic
    @TableField("is_delete")
    private Byte isDelete;
}
