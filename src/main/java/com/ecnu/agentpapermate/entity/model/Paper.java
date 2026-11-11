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
 * 论文及原始文件信息
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
@Getter
@Setter
@ToString
@TableName("paper")
public class Paper implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 论文ID
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属用户ID，关联app_user.id
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 论文标题，未解析前可先用文件名
     */
    @TableField("title")
    private String title;

    /**
     * 作者展示文本，第一阶段不单独建作者表
     */
    @TableField("authors")
    private String authors;

    /**
     * 主题，第一阶段每篇一个主题
     */
    @TableField("topic")
    private String topic;

    /**
     * 摘要，未提取到则为空
     */
    @TableField("abstract_text")
    private String abstractText;

    /**
     * 上传时的文件名，仅用于展示
     */
    @TableField("original_filename")
    private String originalFilename;

    /**
     * 原始文件SHA-256十六进制值
     */
    @TableField("file_hash")
    private String fileHash;

    /**
     * PDF字节数
     */
    @TableField("file_size")
    private Long fileSize;

    /**
     * PDF的COS固定访问URL，不含临时签名参数
     */
    @TableField("file_url")
    private String fileUrl;

    /**
     * PDF物理页数，解析前为空
     */
    @TableField("page_count")
    private Integer pageCount;

    /**
     * uploaded、processing、ready、failed
     */
    @TableField("status")
    private String status;

    /**
     * 当前可用于检索的处理版本，未就绪为空
     */
    @TableField("active_index_version")
    private Integer activeIndexVersion;

    /**
     * 有效索引的执行尝试令牌，隔离旧执行者写入
     */
    @TableField("active_index_attempt")
    private String activeIndexAttempt;

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
     * 回收站状态：0正常、1已回收，不影响旧会话读取。
     */
    @TableField("is_recycled")
    private Byte isRecycled;

    /**
     * 逻辑删除：0正常、1删除。
     */
    @TableLogic
    @TableField("is_delete")
    private Byte isDelete;
}
