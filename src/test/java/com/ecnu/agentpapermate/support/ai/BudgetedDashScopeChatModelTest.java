package com.ecnu.agentpapermate.support.ai;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实 SDK 的 HTTP 路径、消息格式和响应解析，不调用外部模型。 */
class BudgetedDashScopeChatModelTest {
    @ParameterizedTest
    @CsvSource({"qwen3.7-plus,true", "qwen3.7-plus-2026-05-26,true", "qwen-plus,false"})
    void routesTaskModelAndPreservesOutputBudget(String modelName, boolean multimodal) throws Exception {
        var mapper = new ObjectMapper();
        var request = new AtomicReference<JsonNode>();
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            count.incrementAndGet();
            JsonNode body = mapper.readTree(exchange.getRequestBody());
            request.set(body);
            String expectedPath = "/api/v1/services/aigc/"
                    + (multimodal ? "multimodal-generation" : "text-generation") + "/generation";
            boolean valid = expectedPath.equals(exchange.getRequestURI().getPath());
            for (JsonNode message : body.at("/input/messages")) {
                valid &= multimodal ? message.path("content").isArray()
                        && message.at("/content/0/text").isTextual() : message.path("content").isTextual();
            }
            Object response = valid
                    ? Map.of("request_id", "test", "output", Map.of("choices", List.of(Map.of(
                            "finish_reason", "stop", "message", Map.of("role", "assistant", "content",
                                    multimodal ? List.of(Map.of("text", "论文标题[E1]")) : "论文标题[E1]")))),
                            "usage", Map.of("input_tokens", 10, "output_tokens", 20, "total_tokens", 30))
                    : Map.of("code", "InvalidParameter", "message", "url or content error");
            byte[] bytes = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(valid ? 200 : 400, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var connection = new DashScopeConnectionProperties();
            connection.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            connection.setApiKey("fake-test-key");
            var properties = new RagProperties();
            properties.setModel("qwen3.7-plus");
            properties.setMaxOutputTokens(512);
            var model = new BudgetedDashScopeChatModel(connection, properties);
            var options = DashScopeChatOptions.builder().withModel(modelName).withMaxToken(2048)
                    .withTemperature(0.2).withEnableSearch(false).build();
            var prompt = new Prompt(List.of(new SystemMessage("只根据证据回答"), new UserMessage("论文叫什么")), options);
            var response = model.call(prompt);

            assertEquals("论文标题[E1]", response.getResult().getOutput().getText());
            assertEquals(1, count.get());
            assertEquals(modelName, request.get().path("model").asText());
            assertEquals(2048, request.get().at("/parameters/max_tokens").asInt());
            assertEquals(0.2, request.get().at("/parameters/temperature").asDouble());
            assertFalse(request.get().at("/parameters/enable_search").asBoolean());
            assertEquals("system", request.get().at("/input/messages/0/role").asText());
            String contentPath = "/input/messages/1/content" + (multimodal ? "/0/text" : "");
            assertEquals("论文叫什么", request.get().at(contentPath).asText());
            assertFalse(options.getMultiModel(), "不得修改调用方的选项");
            assertEquals(2048, options.setMaxTokens());
        } finally {
            server.stop(0);
        }
    }
}
