package io.github.kfagent.core;

import io.github.kfagent.message.ChatMessage;

import java.util.List;

/**
 * 运行结果。除了最终答案，也把整段对话历史带出来，方便用户做持久化 / 调试。
 *
 * <p>{@code finishReason} 是最后一次 LLM 调用的 finish_reason：
 * {@code stop} / {@code length} / {@code content_filter} 等。{@code length}
 * 表示输出被 max_tokens 截断，此时 finalAnswer 可能不完整。
 */
public record AgentResult(String finalAnswer, List<ChatMessage> transcript,
                          int roundsUsed, boolean finishedByMaxRounds,
                          String finishReason) {

    public AgentResult {
        transcript = transcript == null ? List.of() : List.copyOf(transcript);
        finalAnswer = finalAnswer == null ? "" : finalAnswer;
        finishReason = finishReason == null ? "" : finishReason;
    }

    public static AgentResult of(String answer, List<ChatMessage> transcript,
                                 int rounds, boolean maxed) {
        return of(answer, transcript, rounds, maxed, "");
    }

    public static AgentResult of(String answer, List<ChatMessage> transcript,
                                 int rounds, boolean maxed, String finishReason) {
        return new AgentResult(answer, transcript, rounds, maxed, finishReason);
    }
}
