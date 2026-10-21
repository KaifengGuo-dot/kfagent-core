package io.github.kfagent.core;

/**
 * Agent 运行时配置。
 */
public final class AgentConfig {

    /** 系统提示词。 */
    private final String systemPrompt;
    /** 最大工具轮次（防止 LLM 死循环调工具）。 */
    private final int maxRounds;

    private AgentConfig(Builder b) {
        this.systemPrompt = b.systemPrompt;
        this.maxRounds = b.maxRounds;
    }

    public static Builder builder() { return new Builder(); }

    public String systemPrompt() { return systemPrompt; }
    public int maxRounds() { return maxRounds; }

    public static final class Builder {
        String systemPrompt = "You are a helpful assistant.";
        int maxRounds = 10;

        public Builder systemPrompt(String v) { this.systemPrompt = v; return this; }
        public Builder maxRounds(int v) { this.maxRounds = v; return this; }
        public AgentConfig build() { return new AgentConfig(this); }
    }
}
