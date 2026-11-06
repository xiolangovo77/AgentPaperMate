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
 * 回答原文引用快照
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Getter
@Setter
@ToString
@TableName("message_citation")
public class MessageCitation implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 引用 ID，由 MyBatis-Plus 分配雪花 ID。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 助手消息ID，关联chat_message.id
     */
    @TableField("message_id")
    private Long messageId;

    /**
     * 本次运行中的证据标识，如E1
     */
    @TableField("evidence_id")
    private String evidenceId;

    /**
     * 来源论文ID，关联paper.id
     */
    @TableField("paper_id")
    private Long paperId;

    /**
     * 引用时的论文标题快照
     */
    @TableField("paper_title")
    private String paperTitle;

    /**
     * 引用时的处理索引版本
     */
    @TableField("index_version")
    private Integer indexVersion;

    /**
     * 来源ES物理索引名
     */
    @TableField("es_index_name")
    private String esIndexName;

    /**
     * 来源片段ID；页面读取时使用已登记的证据片段ID
     */
    @TableField("chunk_id")
    private String chunkId;

    /**
     * PDF起始物理页码，从1开始
     */
    @TableField("page_start")
    private Integer pageStart;

    /**
     * PDF结束物理页码，单页时等于起始页
     */
    @TableField("page_end")
    private Integer pageEnd;

    /**
     * 实际原文摘录快照，不能用模型改写文本冒充
     */
    @TableField("quoted_text")
    private String quotedText;

    /**
     * 可选段落偏移或坐标等定位信息，第一阶段可为空
     */
    @TableField("source_location")
    private String sourceLocation;

    /**
     * 引用时PDF固定访问URL快照，不含临时签名参数
     */
    @TableField("file_url")
    private String fileUrl;

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
