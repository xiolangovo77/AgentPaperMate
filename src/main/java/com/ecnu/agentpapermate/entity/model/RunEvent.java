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
 * 问答运行进度及SSE补发事件
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Getter
@Setter
@ToString
@TableName("run_event")
public class RunEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 事件记录主键，由 MyBatis-Plus 分配雪花 ID，不是 SSE 序号。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属问答任务ID，关联agent_run.id
     */
    @TableField("run_id")
    private Long runId;

    /**
     * 同一Run内递增的事件序号，从1开始，对应SSE id
     */
    @TableField("event_seq")
    private Long eventSeq;

    /**
     * run_started、retrieval_started、retrieval_completed及终态等
     */
    @TableField("event_type")
    private String eventType;

    /**
     * 事件数据，终态可携带消息ID、最终正文及引用
     */
    @TableField("payload")
    private String payload;

    /**
     * 事件创建时间（上海时间）
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 事件更新时间（上海时间）
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
