package io.github.kfagent.core;

/**
 * Agent 运行时配置。
 */
public final class AgentConfig {

    /** 系统提示词。 */
    private final String systemPrompt;
    /**
     * 最大工具轮次（防止 LLM 死循环调工具）。达到上限后会再做一次
     * 不带工具的收尾调用，让模型基于已有结果给出答案。
     */
    private final int maxRounds;
    /** 是否用流式协议调用 LLM，并把增量内容通过 {@link AgentListener#onContentDelta} 抛出。 */
    private final boolean streaming;

    private AgentConfig(Builder b) {
        this.systemPrompt = b.systemPrompt;
        this.maxRounds = b.maxRounds;
        this.streaming = b.streaming;
    }

    public static Builder builder() { return new Builder(); }

    public String systemPrompt() { return systemPrompt; }
    public int maxRounds() { return maxRounds; }
    public boolean streaming() { return streaming; }

    public static final class Builder {
        String systemPrompt = "You are a helpful assistant.";
        int maxRounds = 10;
        boolean streaming = false;

        public Builder systemPrompt(String v) { this.systemPrompt = v; return this; }
        public Builder maxRounds(int v) { this.maxRounds = v; return this; }
        public Builder streaming(boolean v) { this.streaming = v; return this; }

        public AgentConfig build() {
            if (maxRounds < 1) {
                throw new IllegalArgumentException("maxRounds must be >= 1, got " + maxRounds);
            }
            return new AgentConfig(this);
        }
    }
}
