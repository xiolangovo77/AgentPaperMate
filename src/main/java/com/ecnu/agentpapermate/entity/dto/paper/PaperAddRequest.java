package com.ecnu.agentpapermate.entity.dto.paper;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * 论文保存请求，文件名、大小和哈希由后端读取 COS，用户身份由登录态取得。
 */
@Data
public class PaperAddRequest {

    /**
     * 上传接口返回的固定文件 URL，兼容请求字段 file_url。
     */
    @JsonAlias("file_url")
    private String fileUrl;
}
