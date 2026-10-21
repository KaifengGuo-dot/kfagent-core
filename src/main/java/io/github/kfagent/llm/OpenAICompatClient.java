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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 协议的客户端。
 *
 * <p>一份实现同时兼容 OpenAI、DeepSeek、Qwen-Max、Moonshot、通义、智谱、本地
 * Ollama (OpenAI 兼容端口) 等。只要改 baseUrl / apiKey / model 三个参数。
 *
 * <pre>{@code
 * LLMClient llm = OpenAICompatClient.builder()
 *     .baseUrl("https://api.deepseek.com/v1")
 *     .apiKey(System.getenv("DEEPSEEK_API_KEY"))
 *     .model("deepseek-chat")
 *     .temperature(0.2)
 *     .build();
 * }</pre>
 */
public final class OpenAICompatClient implements LLMClient {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int timeoutSeconds;
    private final HttpClient http;

    private OpenAICompatClient(Builder b) {
        this.baseUrl = b.baseUrl.endsWith("/")
                ? b.baseUrl.substring(0, b.baseUrl.length() - 1) : b.baseUrl;
        this.apiKey = b.apiKey;
        this.model = b.model;
        this.temperature = b.temperature;
        this.timeoutSeconds = b.timeoutSeconds;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public static Builder builder() { return new Builder(); }

    @Override
    @SuppressWarnings("unchecked")
    public LLMResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);

        List<Object> msgs = new ArrayList<>();
        for (ChatMessage m : messages) msgs.add(m.toWire());
        body.put("messages", msgs);

        if (tools != null && !tools.isEmpty()) {
            List<Object> t = new ArrayList<>();
            for (ToolSpec s : tools) t.add(s.toWire());
            body.put("tools", t);
            body.put("tool_choice", "auto");
        }

        String payload = MiniJson.stringify(body);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new LLMException("LLM HTTP " + resp.statusCode() + ": " + resp.body());
        }

        Map<String, Object> root = MiniJson.parseObject(resp.body());
        List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LLMException("LLM returned no choices: " + resp.body());
        }
        Map<String, Object> first = choices.get(0);
        Map<String, Object> msg = (Map<String, Object>) first.get("message");
        String content = msg.get("content") == null ? "" : String.valueOf(msg.get("content"));
        String finish = first.get("finish_reason") == null ? "" : String.valueOf(first.get("finish_reason"));

        List<ChatMessage.ToolCall> calls = new ArrayList<>();
        Object rawCalls = msg.get("tool_calls");
        if (rawCalls instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> c = (Map<String, Object>) o;
                Map<String, Object> fn = (Map<String, Object>) c.get("function");
                calls.add(new ChatMessage.ToolCall(
                        String.valueOf(c.get("id")),
                        String.valueOf(fn.get("name")),
                        String.valueOf(fn.getOrDefault("arguments", "{}"))
                ));
            }
        }
        return new LLMResponse(content, calls, finish);
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
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalStateException("apiKey is required");
            }
            return new OpenAICompatClient(this);
        }
    }

    public static final class LLMException extends RuntimeException {
        public LLMException(String msg) { super(msg); }
    }
}
