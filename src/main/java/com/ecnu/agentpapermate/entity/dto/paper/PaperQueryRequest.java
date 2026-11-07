package com.ecnu.agentpapermate.entity.dto.paper;

import com.ecnu.agentpapermate.common.PageRequest;
import lombok.Getter;
import lombok.Setter;

/**
 * 当前用户的论文分页筛选条件，固定按创建时间、ID 倒序排列。
 */
@Getter
@Setter
public class PaperQueryRequest extends PageRequest {
    /**
     * 标题关键词，可选。
     */
    private String title;
    /**
     * 作者关键词，可选。
     */
    private String authors;
    /**
     * 主题关键词，可选。
     */
    private String topic;
    /**
     * 处理状态：uploaded、processing、ready、failed，可选。
     */
    private String status;
}
