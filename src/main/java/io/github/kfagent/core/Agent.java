package io.github.kfagent.core;

import io.github.kfagent.llm.LLMClient;
import io.github.kfagent.tool.Tool;
import io.github.kfagent.tool.ToolContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 门面。用户正常使用就只碰这一个类。
 *
 * <pre>{@code
 * // 1. 配好 LLM（OpenAI 兼容协议，DeepSeek/Qwen/Moonshot/本地 Ollama 都能用）
 * LLMClient llm = OpenAICompatClient.builder()
 *     .baseUrl("https://api.deepseek.com/v1")
 *     .apiKey(System.getenv("DEEPSEEK_API_KEY"))
 *     .model("deepseek-chat")
 *     .build();
 *
 * // 2. 声明工具
 * Tool weather = ...;
 *
 * // 3. 一行起一个 agent
 * Agent agent = Agent.builder(llm)
 *     .systemPrompt("你是一个天气助手")
 *     .tool(weather)
 *     .build();
 *
 * // 4. 跑
 * String answer = agent.run("北京今天天气怎么样？");
 * System.out.println(answer);
 * }</pre>
 */
public final class Agent {

    private final LLMClient llm;
    private final List<Tool> tools;
    private final AgentConfig config;
    private final AgentListener listener;
    private final ToolContext toolContext;

    private Agent(Builder b) {
        this.llm = b.llm;
        this.tools = List.copyOf(b.tools);
        this.config = b.configBuilder.build();
        this.listener = b.listener;
        this.toolContext = b.toolContext;
    }

    public static Builder builder(LLMClient llm) {
        return new Builder(llm);
    }

    /** 同步跑一次，直接拿最终答案字符串。 */
    public String run(String userInput) throws Exception {
        return runWithTrace(userInput).finalAnswer();
    }

    /** 跑一次，拿到完整结果（含 transcript）。 */
    public AgentResult runWithTrace(String userInput) throws Exception {
        AgentRunner runner = new AgentRunner(llm, tools, config, listener, toolContext);
        return runner.run(userInput);
    }

    // ---------- Builder ----------

    public static final class Builder {
        private final LLMClient llm;
        private final List<Tool> tools = new ArrayList<>();
        private final AgentConfig.Builder configBuilder = AgentConfig.builder();
        private AgentListener listener;
        private ToolContext toolContext;

        private Builder(LLMClient llm) {
            this.llm = llm;
        }

        public Builder systemPrompt(String v) { configBuilder.systemPrompt(v); return this; }
        public Builder maxRounds(int v) { configBuilder.maxRounds(v); return this; }

        public Builder tool(Tool t) { tools.add(t); return this; }
        public Builder tools(List<Tool> ts) { tools.addAll(ts); return this; }

        public Builder listener(AgentListener l) { this.listener = l; return this; }
        public Builder toolContext(ToolContext c) { this.toolContext = c; return this; }

        public Agent build() {
            return new Agent(this);
        }
    }
}
