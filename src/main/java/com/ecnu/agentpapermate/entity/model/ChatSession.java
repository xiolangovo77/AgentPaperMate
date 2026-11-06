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
 * 研究聊天室
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Getter
@Setter
@ToString
@TableName("chat_session")
public class ChatSession implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 聊天室ID
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属用户ID，关联app_user.id
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 聊天室名称
     */
    @TableField("name")
    private String name;

    /**
     * 尚未发送的输入草稿
     */
    @TableField("input_draft")
    private String inputDraft;

    /**
     * 论文范围版本，集合实际变化时加1
     */
    @TableField("scope_version")
    private Integer scopeVersion;

    /**
     * 活动任务ID，关联agent_run.id，无活动任务时为空
     */
    @TableField("active_run_id")
    private Long activeRunId;

    /**
     * 最近聊天或资料变更时间，不受保存草稿影响
     */
    @TableField("last_message_time")
    private LocalDateTime lastMessageTime;

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
