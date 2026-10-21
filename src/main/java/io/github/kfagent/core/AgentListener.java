package io.github.kfagent.core;

import io.github.kfagent.message.ChatMessage;
import io.github.kfagent.message.ChatMessage.ToolCall;

/**
 * 观察 Agent 运行过程的钩子。所有方法都有默认空实现，用户只覆写关心的事件。
 *
 * <p>典型用途：打印每一轮 LLM 返回、工具入参出参，做 trace / 调试。
 */
public interface AgentListener {

    default void onRoundStart(int round) {}

    default void onLLMResponse(int round, ChatMessage assistant) {}

    default void onToolCallStart(int round, ToolCall call) {}

    default void onToolCallEnd(int round, ToolCall call, String resultOrError) {}

    default void onFinish(String finalAnswer, int roundsUsed) {}
}
