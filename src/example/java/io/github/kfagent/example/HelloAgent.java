package io.github.kfagent.example;

import io.github.kfagent.core.Agent;
import io.github.kfagent.core.AgentResult;
import io.github.kfagent.llm.OpenAICompatClient;
import io.github.kfagent.tool.SchemaBuilder;
import io.github.kfagent.tool.Tool;
import io.github.kfagent.tool.ToolSpec;

import java.util.Map;

/**
 * 最小可运行示例。
 *
 * <p>运行前设置环境变量：
 * <pre>
 * export OPENAI_API_KEY=sk-xxx
 * # 或者用 DeepSeek:
 * export OPENAI_BASE_URL=https://api.deepseek.com/v1
 * export OPENAI_MODEL=deepseek-chat
 * </pre>
 */
public class HelloAgent {

    public static void main(String[] args) throws Exception {

        // 1. 一个工具：查天气（这里写死返回值，真要接 API 自己换实现）
        Tool weather = new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(
                        "get_weather",
                        "查询指定城市当前的天气",
                        SchemaBuilder.object()
                                .required("city")
                                .property("city", SchemaBuilder.string("城市名，例如 北京"))
                                .build()
                );
            }

            @Override
            public String execute(Map<String, Object> args,
                                  io.github.kfagent.tool.ToolContext ctx) {
                String city = String.valueOf(args.get("city"));
                return city + ": 晴, 25°C, 东南风 2 级";
            }
        };

        // 2. 组装 LLM 客户端（兼容 OpenAI / DeepSeek / Qwen / Moonshot / 本地 Ollama）
        var llm = OpenAICompatClient.builder()
                .baseUrl(System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1"))
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .model(System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini"))
                .build();

        // 3. 一行起 Agent
        Agent agent = Agent.builder(llm)
                .systemPrompt("你是一个天气助手，需要查天气时调用工具，不要编造。")
                .tool(weather)
                .build();

        // 4. 跑
        AgentResult r = agent.runWithTrace("我下周要去上海出差，那边现在天气怎么样？");
        System.out.println("=== 最终答案 ===");
        System.out.println(r.finalAnswer());
        System.out.println("=== 共跑了 " + r.roundsUsed() + " 轮 ===");
    }
}
