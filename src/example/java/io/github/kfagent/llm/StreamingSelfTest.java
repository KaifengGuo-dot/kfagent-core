package io.github.kfagent.llm;

import com.sun.net.httpserver.HttpServer;
import io.github.kfagent.message.ChatMessage;
import io.github.kfagent.tool.ToolSpec;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 离线自测流式解析：直接喂 SSE 文本，另外用 JDK 自带 HttpServer 做一次端到端验证。
 */
public class StreamingSelfTest {

    public static void main(String[] args) throws Exception {
        sseContent();
        sseToolCalls();
        sseErrors();
        httpStreamingAndFallback();
        System.out.println("\n✅ STREAMING ASSERTIONS PASSED");
    }

    private static void sseContent() {
        System.out.println("=== SSE 文本聚合 ===");
        List<String> lines = List.of(
                ": keep-alive",
                "",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                        + "\"content\":\"北\"},\"finish_reason\":null}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"京\"},"
                        + "\"finish_reason\":\"stop\"}]}",
                "data: [DONE]",
                "data: {\"choices\":[{\"delta\":{\"content\":\"不应出现\"}}]}"
        );

        List<String> deltas = new ArrayList<>();
        LLMResponse r = OpenAICompatClient.parseSse(lines.iterator(), deltas::add);

        assertEquals("北京", r.content(), "SSE 内容应当拼接完整");
        assertEquals(List.of("北", "京"), deltas, "增量应当按顺序回调");
        assertEquals("stop", r.finishReason(), "finish_reason 应当透传");
        assertTrue(r.toolCalls().isEmpty(), "纯文本响应不应有工具调用");
    }

    private static void sseToolCalls() {
        System.out.println("=== SSE 工具调用分片 ===");
        List<String> lines = List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
                        + "\"arguments\":\"\"}}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,"
                        + "\"id\":\"call_2\",\"type\":\"function\",\"function\":{\"name\":\"get_time\","
                        + "\"arguments\":\"{\\\"tz\\\":\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"{\\\"city\\\":\\\"北京\\\"}\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,"
                        + "\"function\":{\"arguments\":\"\\\"UTC\\\"}\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "data: [DONE]"
        );

        LLMResponse r = OpenAICompatClient.parseSse(lines.iterator(), null);

        assertEquals(2, r.toolCalls().size(), "应当聚合出两个工具调用");
        assertEquals("call_1", r.toolCalls().get(0).id(), "第一个调用 id");
        assertEquals("get_weather", r.toolCalls().get(0).name(), "第一个调用 name");
        assertEquals("{\"city\":\"北京\"}", r.toolCalls().get(0).argumentsJson(),
                "arguments 分片应当拼接完整");
        assertEquals("call_2", r.toolCalls().get(1).id(), "第二个调用 id");
        assertEquals("get_time", r.toolCalls().get(1).name(), "第二个调用 name");
        assertEquals("{\"tz\":\"UTC\"}", r.toolCalls().get(1).argumentsJson(),
                "交错的 arguments 分片应当按 index 归位");
        assertEquals("tool_calls", r.finishReason(), "finish_reason");
    }

    private static void sseErrors() {
        System.out.println("=== SSE 异常 ===");
        assertLlmException(() -> OpenAICompatClient.parseSse(
                        List.of("data: {\"error\":{\"message\":\"bad key\"}}").iterator(), null),
                "bad key", "服务端 error chunk 应当抛异常");
        assertLlmException(() -> OpenAICompatClient.parseSse(
                        List.of("data: {oops").iterator(), null),
                "malformed chunk", "坏 JSON 应当抛异常");
        assertLlmException(() -> OpenAICompatClient.parseSse(
                        List.of("").iterator(), null),
                "no data chunks", "空流应当抛异常");
        assertLlmException(() -> OpenAICompatClient.parseSse(
                        List.of("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                                + "\"id\":\"c1\",\"function\":{\"arguments\":\"{}\"}}]}}]}").iterator(), null),
                "incomplete tool_call", "缺少 name 的工具调用应当抛异常");
    }

    private static void httpStreamingAndFallback() throws Exception {
        System.out.println("=== HTTP 流式端到端 ===");
        AtomicInteger hits = new AtomicInteger();
        List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            int hit = hits.incrementAndGet();
            if (hit == 1) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    write(out, "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n");
                    write(out, "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},"
                            + "\"finish_reason\":\"stop\"}]}\n\n");
                    write(out, "data: [DONE]\n\n");
                }
            } else if (hit == 4) {
                byte[] body = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } else {
                byte[] body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"降级答案\"},\"finish_reason\":\"stop\"}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            OpenAICompatClient client = OpenAICompatClient.builder()
                    .baseUrl("http://127.0.0.1:" + port + "/v1")
                    .model("test-model")
                    .build();

            List<String> deltas = new ArrayList<>();
            LLMResponse r1 = client.chatStream(List.of(ChatMessage.user("hi")),
                    List.of(), deltas::add);
            assertEquals("你好", r1.content(), "SSE 内容聚合");
            assertEquals(List.of("你", "好"), deltas, "SSE 增量顺序");
            assertEquals("stop", r1.finishReason(), "SSE finish_reason");

            List<String> fallbackDeltas = new ArrayList<>();
            LLMResponse r2 = client.chatStream(List.of(ChatMessage.user("hi")),
                    List.of(), fallbackDeltas::add);
            assertEquals("降级答案", r2.content(), "非 SSE 响应应当降级解析 JSON");
            assertEquals(List.of("降级答案"), fallbackDeltas,
                    "降级时完整内容作为一次 delta");

            LLMResponse r3 = client.chat(List.of(ChatMessage.user("hi")), List.of());
            assertEquals("降级答案", r3.content(), "普通 chat 解析");

            try {
                client.chatStream(List.of(ChatMessage.user("hi")), List.of(), deltas::add);
                throw new AssertionError("FAIL: HTTP 500 应当抛 LLMException");
            } catch (OpenAICompatClient.LLMException expected) {
                assertTrue(expected.getMessage().contains("500")
                                && expected.getMessage().contains("boom"),
                        "异常信息应当包含状态码和响应体: " + expected.getMessage());
            }

            assertEquals(4, hits.get(), "应当命中 4 次");
            assertTrue(requestBodies.get(0).contains("\"stream\":true"),
                    "流式请求应当带 stream:true");
            assertTrue(requestBodies.get(1).contains("\"stream\":true"),
                    "降级路径的请求也应当带 stream:true");
            assertTrue(!requestBodies.get(2).contains("\"stream\":true"),
                    "普通 chat 不应带 stream");
            assertTrue(!requestBodies.get(0).contains("Authorization"),
                    "apiKey 为空时不应发送 Authorization");
        } finally {
            server.stop(0);
        }
    }

    // ---------- helpers ----------

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void assertLlmException(Runnable action, String fragment, String msg) {
        try {
            action.run();
        } catch (OpenAICompatClient.LLMException e) {
            assertTrue(e.getMessage().contains(fragment),
                    msg + "，实际信息: " + e.getMessage());
            return;
        }
        throw new AssertionError("FAIL: " + msg);
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (!expected.equals(actual)) {
            throw new AssertionError("FAIL: " + msg + "，expected=" + expected + "，actual=" + actual);
        }
    }
}
