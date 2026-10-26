package io.github.kfagent.message;

import io.github.kfagent.json.MiniJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一条聊天消息。不可变；工具调用通过 toolCalls 字段携带，
 * 工具返回通过 toolCallId 关联。
 *
 * <p>设计为 record 太受限（需要嵌套结构），用普通 final 类 + 静态工厂更顺手。
 */
public final class ChatMessage {

    private final Role role;
    private final String content;
    private final List<ToolCall> toolCalls;   // 仅 ASSISTANT 用
    private final String toolCallId;          // 仅 TOOL 用
    private final String toolName;            // 仅 TOOL 用

    private ChatMessage(Role role, String content, List<ToolCall> toolCalls,
                        String toolCallId, String toolName) {
        this.role = role;
        this.content = content == null ? "" : content;
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        this.toolCallId = toolCallId;
        this.toolName = toolName;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, null, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, content, toolCalls, null, null);
    }

    public static ChatMessage tool(String toolCallId, String toolName, String result) {
        return new ChatMessage(Role.TOOL, result, null, toolCallId, toolName);
    }

    public Role role() { return role; }
    public String content() { return content; }
    public List<ToolCall> toolCalls() { return toolCalls; }
    public String toolCallId() { return toolCallId; }
    public String toolName() { return toolName; }

    /** 把消息转成 OpenAI Chat Completions 接口需要的 JSON map。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toWire() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("role", role.wire());
        if (role == Role.TOOL) {
            m.put("tool_call_id", toolCallId);
            m.put("content", content);
        } else if (role == Role.ASSISTANT && !toolCalls.isEmpty()) {
            // 空字符串比 null 兼容性更好：部分 OpenAI 兼容网关不接受 content=null
            m.put("content", content);
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolCall c : toolCalls) {
                Map<String, Object> cm = new java.util.LinkedHashMap<>();
                cm.put("id", c.id());
                cm.put("type", "function");
                Map<String, Object> fn = new java.util.LinkedHashMap<>();
                fn.put("name", c.name());
                fn.put("arguments", c.argumentsJson());
                cm.put("function", fn);
                calls.add(cm);
            }
            m.put("tool_calls", calls);
        } else {
            m.put("content", content);
        }
        return m;
    }

    /**
     * 从 wire 结构还原一条消息，用于读取持久化的会话。
     *
     * <p>对常见的历史数据做了容错：缺 content 视为空串，缺 tool_calls 视为没有工具调用，
     * arguments 如果是对象（MongoDB 里常见）会重新序列化成 JSON 字符串。
     * role / tool_call_id / function.name 这些关键字段缺失时直接报错。
     */
    public static ChatMessage fromWire(Map<String, Object> wire) {
        if (wire == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        Object rawRole = wire.get("role");
        if (!(rawRole instanceof String roleText)) {
            throw new IllegalArgumentException("message.role must be a string");
        }
        Role role = Role.fromWire(roleText);
        Object rawContent = wire.get("content");
        String content = rawContent == null ? ""
                : rawContent instanceof String s ? s : MiniJson.stringify(rawContent);

        if (role == Role.TOOL) {
            Object rawId = wire.get("tool_call_id");
            if (!(rawId instanceof String id) || id.isBlank()) {
                throw new IllegalArgumentException("tool message requires a non-blank tool_call_id");
            }
            String name = wire.get("name") instanceof String s ? s : null;
            return tool(id, name, content);
        }

        if (role == Role.ASSISTANT) {
            List<ToolCall> calls = new ArrayList<>();
            Object rawCalls = wire.get("tool_calls");
            if (rawCalls instanceof List<?> list) {
                for (Object raw : list) {
                    if (!(raw instanceof Map<?, ?> call)) {
                        throw new IllegalArgumentException("tool_call must be a JSON object: " + raw);
                    }
                    Object rawId = call.get("id");
                    if (!(rawId instanceof String id) || id.isBlank()) {
                        throw new IllegalArgumentException("tool_call.id is required: " + raw);
                    }
                    Object rawFn = call.get("function");
                    if (!(rawFn instanceof Map<?, ?> fn)) {
                        throw new IllegalArgumentException("tool_call.function must be an object: " + raw);
                    }
                    Object rawName = fn.get("name");
                    if (!(rawName instanceof String name) || name.isBlank()) {
                        throw new IllegalArgumentException("tool_call.function.name is required: " + raw);
                    }
                    Object rawArgs = fn.get("arguments");
                    String args;
                    if (rawArgs == null) {
                        args = "{}";
                    } else if (rawArgs instanceof String s) {
                        args = s.isBlank() ? "{}" : s;
                    } else {
                        args = MiniJson.stringify(rawArgs);
                    }
                    calls.add(new ToolCall(id, name, args));
                }
            }
            return assistant(content, calls);
        }

        return new ChatMessage(role, content, null, null, null);
    }

    /** 一次待执行的工具调用。argumentsJson 是 LLM 吐出的原始 JSON 字符串。 */
    public record ToolCall(String id, String name, String argumentsJson) {}
}
