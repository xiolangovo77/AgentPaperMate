package com.ecnu.agentpapermate.entity.vo.run;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;

/** SSE 事件，eventId 与 SSE id 相同，在一个任务内递增。 */
public record EventView(Long runId, Long eventId, String type, LocalDateTime timestamp, JsonNode payload) { }
