package io.github.kfagent.conversation;

import io.github.kfagent.message.ChatMessage;

import java.util.List;

/**
 * 会话持久化 SPI。库本身不带 Redis / MongoDB 依赖，你用自己的客户端实现这两个方法即可。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code conversationId} 由调用方生成并保证唯一，库只负责透传。</li>
 *   <li>{@code load} 返回 {@code null} 或空列表都表示“还没有历史”。</li>
 *   <li>{@code save} 收到的是已经清洗过的历史：不含 system 消息，且一定以不带
 *       tool_calls 的 assistant 消息收尾。实现方原样存即可，不要改写消息内容。</li>
 *   <li>只有一轮对话完整跑完（拿到最终答案）才会调用 {@code save}；LLM 或网络异常时
 *       不会落库，避免把半截对话写进上下文。</li>
 *   <li>同一个 {@code conversationId} 的并发调用需要调用方自己串行化（例如按 id 加锁），
 *       库不提供分布式锁。</li>
 * </ul>
 *
 * <p>Redis 实现提示：{@code SET key [json] EX ttl}，json 由
 * {@link ConversationCodec#toJson(List)} 生成。
 * MongoDB 实现提示：把 {@link ConversationCodec#toWire(List)} 的结果存进文档的
 * {@code messages} 字段。两个示例都写在 README 里。
 */
public interface ConversationStore {

    /**
     * 读取历史消息。返回 null 或空列表表示没有历史。
     *
     * @param conversationId 会话 id
     */
    List<ChatMessage> load(String conversationId) throws Exception;

    /**
     * 覆盖保存整个会话。实现方不需要做增量合并。
     *
     * @param conversationId 会话 id
     * @param messages       不含 system 的完整历史
     */
    void save(String conversationId, List<ChatMessage> messages) throws Exception;
}
