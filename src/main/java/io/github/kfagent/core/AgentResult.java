package io.github.kfagent.core;

import io.github.kfagent.message.ChatMessage;
import io.github.kfagent.message.ChatMessage.ToolCall;

import java.util.List;

/**
 * 运行结果。除了最终答案，也把整段对话历史带出来，方便用户做持久化 / 调试。
 */
public record AgentResult(String finalAnswer, List<ChatMessage> transcript,
                         int roundsUsed, boolean finishedByMaxRounds) {

    public static AgentResult of(String answer, List<ChatMessage> transcript,
                                 int rounds, boolean maxed) {
        return new AgentResult(answer, List.copyOf(transcript), rounds, maxed);
    }
}
