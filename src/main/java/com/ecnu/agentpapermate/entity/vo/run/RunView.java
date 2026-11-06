package com.ecnu.agentpapermate.entity.vo.run;

import com.ecnu.agentpapermate.entity.model.ChatMessage;
import com.ecnu.agentpapermate.entity.model.MessageCitation;
import java.time.LocalDateTime;
import java.util.List;

/** 前端任务详情；不暴露模型密钥、完整内部快照。 */
public record RunView(Long runId, Long sessionId, String status, Long eventSeq,
                      LocalDateTime startTime, LocalDateTime endTime, LocalDateTime deadline,
                      String errorCode, String errorMessage, ChatMessage answer,
                      List<MessageCitation> citations) { }
