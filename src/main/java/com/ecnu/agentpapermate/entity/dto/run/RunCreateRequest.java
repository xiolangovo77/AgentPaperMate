package com.ecnu.agentpapermate.entity.dto.run;

import lombok.Data;

/** 提交问题的请求；身份、模型和资料快照均由服务端确定。 */
@Data
public class RunCreateRequest {
    /** 聊天室 ID。 */
    private Long sessionId;
    /** 本次提交的幂等键，网络重发时保持不变。 */
    private String requestKey;
    /** 用户原始问题。 */
    private String question;
    /** 主动重新执行时关联旧任务，新任务必须使用新幂等键。 */
    private Long retryOfRunId;
}
