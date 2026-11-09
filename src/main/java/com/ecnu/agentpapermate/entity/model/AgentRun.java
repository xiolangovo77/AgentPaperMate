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
 * 单次问答执行任务
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Getter
@Setter
@ToString
@TableName("agent_run")
public class AgentRun implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 一次问答任务 ID，即 runId，由 MyBatis-Plus 分配雪花 ID。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属聊天室ID，关联chat_session.id
     */
    @TableField("session_id")
    private Long sessionId;

    /**
     * 发起用户ID，必须与会话所有者一致
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 客户端本次提交幂等键，重试保持不变
     */
    @TableField("request_key")
    private String requestKey;

    /**
     * 本次原始问题，用于执行和幂等请求核对
     */
    @TableField("user_query")
    private String userQuery;

    /**
     * rag固定流程、agent智能体
     */
    @TableField("executor_type")
    private String executorType;

    /**
     * pending、running、completed、failed、cancelled、timeout
     */
    @TableField("status")
    private String status;

    /**
     * 业务执行阶段：queued、ready、retrieving、evidence_ready、generating。
     * 独立于事件序号；进入终态时保留最后阶段。
     */
    @TableField("execution_phase")
    private String executionPhase;

    /**
     * 创建任务时的会话资料范围版本快照
     */
    @TableField("scope_version")
    private Integer scopeVersion;

    /**
     * 服务端生成的论文ID、索引版本、ES索引及模型配置快照数组
     */
    @TableField("papers_snapshot")
    private String papersSnapshot;

    /**
     * RRF 融合去重后、证据 Token 和数量筛选前的有序 Chunk ID 数组，不存向量。
     * null 表示未采集，JSON 空数组表示检索成功但没有候选。
     */
    @TableField("retrieval_snapshot")
    private String retrievalSnapshot;

    /**
     * 证据预算筛选后的最终证据清单，保存来源定位与原文摘录，不存向量
     */
    @TableField("evidence_snapshot")
    private String evidenceSnapshot;

    /**
     * 本次回答模型标识
     */
    @TableField("model_name")
    private String modelName;

    /**
     * 温度、输出限制等非敏感模型参数
     */
    @TableField("model_config")
    private String modelConfig;

    /**
     * 第二阶段所用技能，第一阶段为空
     */
    @TableField("skill_id")
    private String skillId;

    /**
     * 技能版本，第一阶段为空
     */
    @TableField("skill_version")
    private String skillVersion;

    /**
     * 加载技能及参考资料的内容哈希
     */
    @TableField("skill_content_hash")
    private String skillContentHash;

    /**
     * 本次累计输入Token
     */
    @TableField("input_tokens")
    private Long inputTokens;

    /**
     * 本次累计输出Token
     */
    @TableField("output_tokens")
    private Long outputTokens;

    /**
     * 已执行模型轮数
     */
    @TableField("step_count")
    private Integer stepCount;

    /**
     * 工具实际调用次数，包含重试
     */
    @TableField("tool_call_count")
    private Integer toolCallCount;

    /**
     * 已分配的最后一个进度事件序号，仅用于事件排序和补发，不代表业务阶段
     */
    @TableField("event_seq")
    private Long eventSeq;

    /**
     * 开始执行时间（上海时间）
     */
    @TableField("start_time")
    private LocalDateTime startTime;

    /**
     * 进入终态的时间（上海时间）
     */
    @TableField("end_time")
    private LocalDateTime endTime;

    /**
     * 开始执行时确定的截止时间（上海时间）
     */
    @TableField("deadline")
    private LocalDateTime deadline;

    /**
     * 失败或超时原因代码
     */
    @TableField("error_code")
    private String errorCode;

    /**
     * 可读错误摘要，不含凭据
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 新任务重试时关联原Run，不复用原幂等键
     */
    @TableField("retry_of_run_id")
    private Long retryOfRunId;

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
