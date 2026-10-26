package io.github.kfagent.llm;

import io.github.kfagent.message.ChatMessage;
import io.github.kfagent.tool.ToolSpec;

import java.util.List;
import java.util.function.Consumer;

/**
 * LLM 客户端抽象。默认提供 {@link OpenAICompatClient}（兼容 OpenAI / DeepSeek /
 * Qwen / Moonshot / 通义等所有 OpenAI Chat Completions 协议的服务）。
 *
 * <p>要接别的模型，自己实现这个接口即可，Agent 循环不关心背后是谁。
 */
public interface LLMClient {

    /**
     * @param messages 完整对话历史（含 system / user / assistant / tool）
     * @param tools    当前可用工具列表，可能为空
     */
    LLMResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) throws Exception;

    /**
     * 流式调用。每收到一段文本就回调 {@code onDelta}，返回值仍然是聚合后的完整响应。
     *
     * <p>默认实现退化成一次普通 {@link #chat}，然后把完整内容作为单个 delta 抛出去。
     * 自己的客户端如果支持真正的流式协议，覆写它即可。
     *
     * @param onDelta 增量文本回调，可能为 null
     */
    default LLMResponse chatStream(List<ChatMessage> messages, List<ToolSpec> tools,
                                   Consumer<String> onDelta) throws Exception {
        LLMResponse resp = chat(messages, tools);
        if (onDelta != null && resp.content() != null && !resp.content().isEmpty()) {
            onDelta.accept(resp.content());
        }
        return resp;
    }
}
