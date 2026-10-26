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

    /**
     * 流式模式下每收到一段模型输出就会回调一次。非流式模式不会触发。
     *
     * <p>注意：中间的工具轮次如果模型先说了话再调工具，也会先回调这里的增量；
     * 想只展示最终答复，请按轮次区分，或者等 {@link #onFinish} 再渲染。
     */
    default void onContentDelta(int round, String delta) {}

    default void onLLMResponse(int round, ChatMessage assistant) {}

    default void onToolCallStart(int round, ToolCall call) {}

    default void onToolCallEnd(int round, ToolCall call, String resultOrError) {}

    default void onFinish(String finalAnswer, int roundsUsed) {}
}
