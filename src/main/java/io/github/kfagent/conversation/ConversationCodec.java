package io.github.kfagent.conversation;

import io.github.kfagent.json.MiniJson;
import io.github.kfagent.message.ChatMessage;
import io.github.kfagent.message.Role;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话消息与可持久化结构之间的编解码。
 *
 * <ul>
 *   <li>Redis 这类 KV：{@link #toJson(List)} / {@link #fromJson(String)}</li>
 *   <li>MongoDB 这类文档库：{@link #toWire(List)} / {@link #fromWire(List)}，
 *       直接把结果放进文档的数组字段即可</li>
 * </ul>
 *
 * <p>持久化结构比 {@link ChatMessage#toWire()} 多带一个 {@code name} 字段（仅 TOOL 消息），
 * 便于排查历史；回放时即使没有这个字段也能正常还原。
 */
public final class ConversationCodec {

    private ConversationCodec() {}

    public static List<Map<String, Object>> toWire(List<ChatMessage> messages) {
        if (messages == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            Map<String, Object> wire = new LinkedHashMap<>(m.toWire());
            if (m.role() == Role.TOOL && m.toolName() != null && !m.toolName().isBlank()) {
                wire.put("name", m.toolName());
            }
            out.add(wire);
        }
        return out;
    }

    public static List<ChatMessage> fromWire(List<?> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        List<ChatMessage> out = new ArrayList<>(messages.size());
        for (Object raw : messages) {
            if (!(raw instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("message must be a JSON object, got: " + raw);
            }
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                typed.put(String.valueOf(e.getKey()), e.getValue());
            }
            out.add(ChatMessage.fromWire(typed));
        }
        return List.copyOf(out);
    }

    public static String toJson(List<ChatMessage> messages) {
        return MiniJson.stringify(toWire(messages));
    }

    public static List<ChatMessage> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        Object parsed = MiniJson.parse(json);
        if (!(parsed instanceof List<?> list)) {
            throw new IllegalArgumentException("conversation JSON must be an array, got: "
                    + (parsed == null ? "null" : parsed.getClass().getSimpleName()));
        }
        return fromWire(list);
    }
}
