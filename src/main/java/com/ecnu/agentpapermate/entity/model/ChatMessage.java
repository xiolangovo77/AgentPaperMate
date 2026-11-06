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
 * 聊天室消息
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Getter
@Setter
@ToString
@TableName("chat_message")
public class ChatMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息 ID，由 MyBatis-Plus 分配雪花 ID。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属聊天室ID，关联chat_session.id
     */
    @TableField("session_id")
    private Long sessionId;

    /**
     * 所属问答任务，资料变更消息为空
     */
    @TableField("run_id")
    private Long runId;

    /**
     * user、assistant、system
     */
    @TableField("role")
    private String role;

    /**
     * text聊天正文、scope_change资料变更
     */
    @TableField("message_type")
    private String messageType;

    /**
     * 消息正文，助手正文仅保存最终校验结果
     */
    @TableField("content")
    private String content;

    /**
     * 资料变更前后ID等补充信息
     */
    @TableField("extra_data")
    private String extraData;

    /**
     * 创建时间（上海时间）
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间（上海时间）
     */
    @TableField("update_time")
    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0正常、1删除
     */
    @TableLogic
    @TableField("is_delete")
    private Byte isDelete;
}
