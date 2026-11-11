package com.ecnu.agentpapermate.entity.vo.paper;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 论文列表展示信息，不暴露存储地址、文件哈希或内部执行令牌。
 */
@Getter
@Setter
public class PaperSummaryVO {
    /**
     * 回收站状态：0正常、1已回收。
     */
    private Byte isRecycled;

    /**
     * 论文标识。
     */
    private Long id;
    /**
     * 论文标题。
     */
    private String title;
    /**
     * 作者展示文本。
     */
    private String authors;
    /**
     * 论文主题。
     */
    private String topic;
    /**
     * 原始文件名。
     */
    private String originalFilename;
    /**
     * PDF 物理页数，解析前为空。
     */
    private Integer pageCount;
    /**
     * PDF 文件字节数。
     */
    private Long fileSize;
    /**
     * 论文处理状态。
     */
    private String status;
    /**
     * 创建时间（上海时间）。
     */
    private LocalDateTime createTime;
    /**
     * 更新时间（上海时间）。
     */
    private LocalDateTime updateTime;
}
