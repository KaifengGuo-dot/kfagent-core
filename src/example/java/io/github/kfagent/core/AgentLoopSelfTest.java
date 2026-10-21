package io.github.kfagent.core;

import io.github.kfagent.llm.LLMClient;
import io.github.kfagent.llm.LLMResponse;
import io.github.kfagent.message.ChatMessage;
import io.github.kfagent.message.ChatMessage.ToolCall;
import io.github.kfagent.tool.Tool;
import io.github.kfagent.tool.ToolContext;
import io.github.kfagent.tool.ToolSpec;
import io.github.kfagent.tool.SchemaBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 离线自测：不打真实 LLM，用一个 FakeLLMClient 模拟两轮：
 *   第 1 轮：返回 tool_call -> 查天气
 *   第 2 轮：看到 tool 结果后，返回最终答案
 *
 * 同时验证：
 *   - 工具被真的调用了
 *   - tool message 被加回历史
 *   - 最终答案正确
 */
public class AgentLoopSelfTest {

    public static void main(String[] args) throws Exception {
        AtomicInteger toolCalls = new AtomicInteger();
        List<String> toolArgsSeen = new ArrayList<>();

        Tool weather = new Tool() {
            @Override public ToolSpec spec() {
                return new ToolSpec("get_weather", "查询天气",
                    SchemaBuilder.object().required("city")
                        .property("city", SchemaBuilder.string()).build());
            }
            @Override public String execute(Map<String, Object> args, ToolContext ctx) {
                toolCalls.incrementAndGet();
                toolArgsSeen.add(String.valueOf(args.get("city")));
                return "北京: 晴, 25°C";
            }
        };

        // Fake LLM: 第 1 次返回 tool_call, 第 2 次返回最终答案
        LLMClient fake = (messages, tools) -> {
            boolean hasToolResult = messages.stream()
                    .anyMatch(m -> m.role() == io.github.kfagent.message.Role.TOOL);
            if (!hasToolResult) {
                return new LLMResponse("",
                    List.of(new ToolCall("call_1", "get_weather", "{\"city\":\"北京\"}")),
                    "tool_calls");
            }
            return new LLMResponse("北京今天晴，25°C。", List.of(), "stop");
        };

        List<String> trace = new ArrayList<>();
        Agent agent = Agent.builder(fake)
                .systemPrompt("test")
                .tool(weather)
                .listener(new AgentListener() {
                    @Override public void onRoundStart(int r) { trace.add("round" + r); }
                    @Override public void onToolCallStart(int r, ToolCall c) { trace.add("call:" + c.name()); }
                    @Override public void onToolCallEnd(int r, ToolCall c, String res) { trace.add("result:" + res); }
                })
                .build();

        AgentResult r = agent.runWithTrace("北京天气?");

        System.out.println("=== trace ===");
        trace.forEach(System.out::println);
        System.out.println("=== finalAnswer ===");
        System.out.println(r.finalAnswer());
        System.out.println("=== roundsUsed = " + r.roundsUsed() + " ===");
        System.out.println("=== toolCalls = " + toolCalls.get() + " ===");
        System.out.println("=== toolArgsSeen = " + toolArgsSeen + " ===");

        // 断言
        assertTrue(r.roundsUsed() == 2, "应当跑 2 轮");
        assertTrue(toolCalls.get() == 1, "工具应当被调用 1 次");
        assertTrue(toolArgsSeen.contains("北京"), "应当传入 city=北京");
        assertTrue(r.finalAnswer().contains("25"), "最终答案应当包含工具返回的 25°C");
        assertTrue(r.transcript().stream().anyMatch(m -> m.role() == io.github.kfagent.message.Role.TOOL),
                "历史里应当有 tool message");

        System.out.println("\n✅ ALL ASSERTIONS PASSED");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
    }
}
