package com.ecnu.agentpapermate.entity.vo.run;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;

import java.io.IOException;
import java.util.List;

/** 评测采集数据；只返回运行结果，不暴露内部模型配置、权限快照或凭据。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RunEvaluationView(
        String runId, String status, String question,
        List<String> retrievedChunkIds, List<String> finalEvidenceChunkIds,
        List<FinalEvidence> finalEvidence, String answer, List<Citation> citations,
        @JsonSerialize(using = MetricSerializer.class) Long latencyMs,
        @JsonSerialize(using = MetricSerializer.class) Long inputTokens,
        @JsonSerialize(using = MetricSerializer.class) Long outputTokens,
        String errorCode, String errorMessage) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FinalEvidence(String evidenceId, String paperId, String chunkId, String text) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Citation(String evidenceId, String paperId, String chunkId, Integer pageStart, Integer pageEnd) { }

    /** 覆盖应用全局 Long 转字符串规则，让评测数值仍为 JSON 数字；ID 显式使用字符串。 */
    public static class MetricSerializer extends StdScalarSerializer<Long> {
        public MetricSerializer() { super(Long.class); }

        @Override
        public void serialize(Long value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeNumber(value);
        }
    }
}
