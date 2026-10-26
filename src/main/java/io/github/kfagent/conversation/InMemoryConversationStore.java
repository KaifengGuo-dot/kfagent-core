package io.github.kfagent.conversation;

import io.github.kfagent.message.ChatMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内实现，适合单机demo、单元测试，或者“重启就丢”的场景。
 *
 * <p>需要跨实例、跨重启保存时，按 {@link ConversationStore} 接 Redis / MongoDB。
 */
public final class InMemoryConversationStore implements ConversationStore {

    private final Map<String, List<ChatMessage>> conversations = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> load(String conversationId) {
        requireId(conversationId);
        List<ChatMessage> messages = conversations.get(conversationId);
        return messages == null ? List.of() : messages;
    }

    @Override
    public void save(String conversationId, List<ChatMessage> messages) {
        requireId(conversationId);
        conversations.put(conversationId, List.copyOf(messages));
    }

    public void delete(String conversationId) {
        requireId(conversationId);
        conversations.remove(conversationId);
    }

    public int size() {
        return conversations.size();
    }

    private static void requireId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
    }
}
