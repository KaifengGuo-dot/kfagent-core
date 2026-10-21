package io.github.kfagent.llm;

import io.github.kfagent.message.ChatMessage;
import io.github.kfagent.tool.ToolSpec;

import java.util.List;

/**
 * 一次 LLM 调用的返回。
 */
public record LLMResponse(String content, List<ChatMessage.ToolCall> toolCalls,
                         String finishReason) {

    public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
}
