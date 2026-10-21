package io.github.kfagent.message;

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
            m.put("content", content.isEmpty() ? null : content);
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

    /** 一次待执行的工具调用。argumentsJson 是 LLM 吐出的原始 JSON 字符串。 */
    public record ToolCall(String id, String name, String argumentsJson) {}
}
