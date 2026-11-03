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
 * 可靠投递到RabbitMQ的事件
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
@Getter
@Setter
@ToString
@TableName("outbox_event")
public class OutboxEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Outbox事件ID，也作为MQ消息标识
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 要派发的入库任务，关联paper_processing_job.id
     */
    @TableField("job_id")
    private Long jobId;

    /**
     * 业务派发幂等键，例如job:123:dispatch:0
     */
    @TableField("event_key")
    private String eventKey;

    /**
     * 业务事件类型
     */
    @TableField("event_type")
    private String eventType;

    /**
     * 目标RabbitMQ交换机
     */
    @TableField("exchange_name")
    private String exchangeName;

    /**
     * RabbitMQ路由键
     */
    @TableField("routing_key")
    private String routingKey;

    /**
     * 消息载荷，包含jobId、paperId和处理版本，不包含PDF或凭据
     */
    @TableField("payload")
    private String payload;

    /**
     * pending、publishing、sent、failed
     */
    @TableField("status")
    private String status;

    /**
     * 实际投递尝试次数
     */
    @TableField("publish_attempts")
    private Integer publishAttempts;

    /**
     * 自动投递尝试上限
     */
    @TableField("max_attempts")
    private Integer maxAttempts;

    /**
     * 下次允许投递时间UTC
     */
    @TableField("next_attempt_time")
    private LocalDateTime nextAttemptTime;

    /**
     * 投递领取令牌
     */
    @TableField("lease_token")
    private String leaseToken;

    /**
     * 投递租约到期时间UTC
     */
    @TableField("lease_until")
    private LocalDateTime leaseUntil;

    /**
     * 确认已成功投递时间UTC
     */
    @TableField("sent_time")
    private LocalDateTime sentTime;

    /**
     * 最近投递错误摘要
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 创建时间UTC
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间UTC
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
