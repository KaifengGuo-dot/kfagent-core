package io.github.kfagent.llm;

import io.github.kfagent.message.ChatMessage;
import io.github.kfagent.tool.ToolSpec;

import java.util.List;

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
}
