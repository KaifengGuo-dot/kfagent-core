package io.github.kfagent.llm;

import io.github.kfagent.json.MiniJson;
import io.github.kfagent.message.ChatMessage;
import io.github.kfagent.tool.ToolSpec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OpenAI Chat Completions 协议的客户端。
 *
 * <p>一份实现同时兼容 OpenAI、DeepSeek、Qwen-Max、Moonshot、通义、智谱、本地
 * Ollama (OpenAI 兼容端口) 等。只要改 baseUrl / apiKey / model 三个参数；
 * 本地 Ollama 不需要 key，apiKey 留空即可（此时不发送 Authorization 头）。
 *
 * <pre>{@code
 * LLMClient llm = OpenAICompatClient.builder()
 *     .baseUrl("https://api.deepseek.com/v1")
 *     .apiKey(System.getenv("DEEPSEEK_API_KEY"))
 *     .model("deepseek-chat")
 *     .temperature(0.2)
 *     .build();
 * }</pre>
 *
 * <p>流式模式使用标准 SSE（{@code text/event-stream}），会按 {@code index} 聚合
 * 工具调用的 id / name / arguments 分片。若服务端忽略了 {@code stream=true}
 * 直接返回一整段 JSON，会自动降级解析。
 */
public final class OpenAICompatClient implements LLMClient {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int timeoutSeconds;
    private final HttpClient http;

    private OpenAICompatClient(Builder b) {
        String url = b.baseUrl == null ? "" : b.baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.baseUrl = url;
        this.apiKey = b.apiKey == null ? "" : b.apiKey.trim();
        this.model = b.model;
        this.temperature = b.temperature;
        this.timeoutSeconds = b.timeoutSeconds;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public static Builder builder() { return new Builder(); }

    @Override
    public LLMResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) throws Exception {
        String payload = MiniJson.stringify(buildBody(messages, tools, false));
        HttpResponse<String> resp = http.send(buildRequest(payload, false),
                HttpResponse.BodyHandlers.ofString());
        if (!isSuccess(resp.statusCode())) {
            throw new LLMException("LLM HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return parseResponse(resp.body());
    }

    @Override
    public LLMResponse chatStream(List<ChatMessage> messages, List<ToolSpec> tools,
                                  Consumer<String> onDelta) throws Exception {
        String payload = MiniJson.stringify(buildBody(messages, tools, true));
        HttpResponse<Stream<String>> resp = http.send(buildRequest(payload, true),
                HttpResponse.BodyHandlers.ofLines());

        if (!isSuccess(resp.statusCode())) {
            String error;
            try (Stream<String> lines = resp.body()) {
                error = lines.collect(Collectors.joining("\n"));
            }
            throw new LLMException("LLM HTTP " + resp.statusCode() + ": " + error);
        }

        String contentType = resp.headers().firstValue("Content-Type").orElse("")
                .toLowerCase(Locale.ROOT);
        if (contentType.contains("text/event-stream")) {
            try (Stream<String> lines = resp.body()) {
                return parseSse(lines.iterator(), onDelta);
            }
        }

        // 有些兼容网关会忽略 stream=true，直接返回整段 JSON，这里兜底
        String raw;
        try (Stream<String> lines = resp.body()) {
            raw = lines.collect(Collectors.joining("\n"));
        }
        if (looksLikeSse(raw)) {
            return parseSse(raw.lines().iterator(), onDelta);
        }

        LLMResponse parsed = parseResponse(raw);
        if (onDelta != null && !parsed.content().isEmpty()) {
            onDelta.accept(parsed.content());
        }
        return parsed;
    }

    private Map<String, Object> buildBody(List<ChatMessage> messages, List<ToolSpec> tools,
                                          boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);
        if (stream) {
            body.put("stream", true);
        }

        List<Object> msgs = new ArrayList<>();
        for (ChatMessage m : messages) msgs.add(m.toWire());
        body.put("messages", msgs);

        if (tools != null && !tools.isEmpty()) {
            List<Object> t = new ArrayList<>();
            for (ToolSpec s : tools) t.add(s.toWire());
            body.put("tools", t);
            body.put("tool_choice", "auto");
        }
        return body;
    }

    private HttpRequest buildRequest(String payload, boolean stream) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (stream) {
            request.header("Accept", "text/event-stream");
        }
        if (!apiKey.isEmpty()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        return request.build();
    }

    static LLMResponse parseResponse(String body) {
        Map<String, Object> root;
        try {
            root = MiniJson.parseObject(body);
        } catch (RuntimeException e) {
            throw new LLMException("LLM returned invalid JSON: " + body, e);
        }

        Object rawChoices = root.get("choices");
        if (!(rawChoices instanceof List<?> choices) || choices.isEmpty()) {
            throw new LLMException("LLM returned no choices: " + body);
        }
        if (!(choices.get(0) instanceof Map<?, ?> first)) {
            throw new LLMException("LLM returned malformed choice: " + body);
        }

        Object rawMsg = first.get("message");
        if (!(rawMsg instanceof Map<?, ?> msg)) {
            throw new LLMException("LLM returned no message: " + body);
        }

        String content = msg.get("content") == null ? "" : String.valueOf(msg.get("content"));
        String finish = first.get("finish_reason") == null
                ? "" : String.valueOf(first.get("finish_reason"));

        List<ChatMessage.ToolCall> calls = new ArrayList<>();
        Object rawCalls = msg.get("tool_calls");
        if (rawCalls instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> call) || !(call.get("function") instanceof Map<?, ?> fn)) {
                    throw new LLMException("LLM returned malformed tool_call: " + o);
                }
                String id = call.get("id") == null ? "" : String.valueOf(call.get("id"));
                String name = fn.get("name") == null ? "" : String.valueOf(fn.get("name"));
                if (id.isEmpty() || name.isEmpty()) {
                    throw new LLMException("LLM returned tool_call without id/name: " + o);
                }
                Object rawArgs = fn.get("arguments");
                String arguments;
                if (rawArgs == null) {
                    arguments = "{}";
                } else if (rawArgs instanceof String s) {
                    arguments = s.isBlank() ? "{}" : s;
                } else {
                    // 少数兼容网关会直接返回 JSON 对象而不是字符串
                    arguments = MiniJson.stringify(rawArgs);
                }
                calls.add(new ChatMessage.ToolCall(id, name, arguments));
            }
        }
        return new LLMResponse(content, calls, finish);
    }

    /**
     * 解析 OpenAI 风格的 SSE 流。按 {@code choices[0].delta} 聚合文本，并按
     * {@code tool_calls[].index} 聚合工具调用分片。
     *
     * <p>package-private 是为了让离线自测能直接喂字符串，不需要起网络。
     */
    static LLMResponse parseSse(Iterator<String> lines, Consumer<String> onDelta) {
        StringBuilder content = new StringBuilder();
        TreeMap<Integer, ToolCallBuilder> calls = new TreeMap<>();
        String finish = "";
        boolean sawChunk = false;

        while (lines.hasNext()) {
            String line = lines.next();
            if (line == null) continue;
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith(":")) continue;
            if (!trimmed.startsWith("data:")) continue;

            String data = trimmed.substring(5).trim();
            if (data.isEmpty()) continue;
            if ("[DONE]".equals(data)) break;

            sawChunk = true;
            Map<String, Object> chunk;
            try {
                chunk = MiniJson.parseObject(data);
            } catch (RuntimeException e) {
                throw new LLMException("LLM stream returned malformed chunk: " + data, e);
            }

            if (chunk.get("error") != null) {
                throw new LLMException("LLM stream error: " + MiniJson.stringify(chunk.get("error")));
            }

            Object rawChoices = chunk.get("choices");
            if (!(rawChoices instanceof List<?> choices) || choices.isEmpty()) continue;
            if (!(choices.get(0) instanceof Map<?, ?> choice)) continue;

            if (choice.get("finish_reason") != null) {
                finish = String.valueOf(choice.get("finish_reason"));
            }

            Object rawDelta = choice.get("delta");
            if (!(rawDelta instanceof Map<?, ?> delta)) continue;

            Object rawContent = delta.get("content");
            if (rawContent instanceof String text && !text.isEmpty()) {
                content.append(text);
                if (onDelta != null) onDelta.accept(text);
            }

            Object rawCalls = delta.get("tool_calls");
            if (rawCalls instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> tc)) continue;
                    int index = tc.get("index") instanceof Number n
                            ? n.intValue()
                            : (calls.isEmpty() ? 0 : calls.lastKey());
                    ToolCallBuilder builder = calls.computeIfAbsent(index, k -> new ToolCallBuilder());

                    if (builder.id.isEmpty() && tc.get("id") instanceof String id && !id.isEmpty()) {
                        builder.id = id;
                    }
                    if (tc.get("function") instanceof Map<?, ?> fn) {
                        if (builder.name.isEmpty() && fn.get("name") instanceof String name
                                && !name.isEmpty()) {
                            builder.name = name;
                        }
                        Object args = fn.get("arguments");
                        if (args instanceof String fragment) {
                            builder.arguments.append(fragment);
                        } else if (args instanceof Map<?, ?> || args instanceof List<?>) {
                            builder.arguments.append(MiniJson.stringify(args));
                        }
                    }
                }
            }
        }

        if (!sawChunk) {
            throw new LLMException("LLM stream returned no data chunks");
        }

        List<ChatMessage.ToolCall> toolCalls = new ArrayList<>(calls.size());
        for (Map.Entry<Integer, ToolCallBuilder> e : calls.entrySet()) {
            ToolCallBuilder b = e.getValue();
            if (b.id.isEmpty() || b.name.isEmpty()) {
                throw new LLMException("LLM stream returned incomplete tool_call at index "
                        + e.getKey() + " (id=" + b.id + ", name=" + b.name + ")");
            }
            toolCalls.add(new ChatMessage.ToolCall(b.id, b.name,
                    b.arguments.length() == 0 ? "{}" : b.arguments.toString()));
        }
        return new LLMResponse(content.toString(), toolCalls, finish);
    }

    private static boolean looksLikeSse(String raw) {
        String trimmed = raw.stripLeading();
        return trimmed.startsWith("data:")
                || trimmed.contains("\ndata:")
                || trimmed.contains("\r\ndata:");
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode / 100 == 2;
    }

    private static final class ToolCallBuilder {
        String id = "";
        String name = "";
        final StringBuilder arguments = new StringBuilder();
    }

    // ---------- Builder ----------

    public static final class Builder {
        String baseUrl = "https://api.openai.com/v1";
        String apiKey = "";
        String model = "gpt-4o-mini";
        double temperature = 0.2;
        int timeoutSeconds = 120;

        public Builder baseUrl(String v) { this.baseUrl = v; return this; }
        public Builder apiKey(String v) { this.apiKey = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder temperature(double v) { this.temperature = v; return this; }
        public Builder timeoutSeconds(int v) { this.timeoutSeconds = v; return this; }

        public OpenAICompatClient build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException("baseUrl is required");
            }
            if (model == null || model.isBlank()) {
                throw new IllegalStateException("model is required");
            }
            if (timeoutSeconds < 1) {
                throw new IllegalStateException("timeoutSeconds must be >= 1");
            }
            return new OpenAICompatClient(this);
        }
    }

    public static final class LLMException extends RuntimeException {
        public LLMException(String msg) { super(msg); }
        public LLMException(String msg, Throwable cause) { super(msg, cause); }
    }
}
