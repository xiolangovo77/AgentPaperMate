package com.ecnu.agentpapermate.entity.dto.paper;

import lombok.Data;

/** 重新处理指定失败任务；jobId 防止重复或延迟请求再次创建任务。 */
@Data
public class PaperRetryRequest {
    private Long id;
    private Long jobId;
}
