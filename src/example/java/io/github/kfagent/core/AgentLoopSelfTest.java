package io.github.kfagent.core;

import io.github.kfagent.conversation.ConversationCodec;
import io.github.kfagent.conversation.InMemoryConversationStore;
import io.github.kfagent.json.MiniJson;
import io.github.kfagent.llm.LLMClient;
import io.github.kfagent.llm.LLMResponse;
import io.github.kfagent.message.ChatMessage;
import io.github.kfagent.message.ChatMessage.ToolCall;
import io.github.kfagent.message.Role;
import io.github.kfagent.tool.MethodTool;
import io.github.kfagent.tool.SchemaBuilder;
import io.github.kfagent.tool.Tool;
import io.github.kfagent.tool.ToolContext;
import io.github.kfagent.tool.ToolMethod;
import io.github.kfagent.tool.ToolSpec;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 离线自测：不打真实 LLM，用 FakeLLMClient 覆盖主链路和已修复的边界情况。
 */
public class AgentLoopSelfTest {

    public static void main(String[] args) throws Exception {
        happyPath();
        maxRoundsForcesFinalAnswer();
        nullArgumentsBecomeEmptyMap();
        duplicateToolNamesRejected();
        miniJsonRobustness();
        methodToolUsesRealParameterNames();
        missingArgumentFailsToolCall();
        conversationPersistsAcrossRuns();
        conversationStoreValidation();
        incompleteTailSanitized();
        toolTranscriptPersists();
        conversationCodecRoundTrip();
        streamingDeltasReachListener();
        streamingFallsBackToChat();
        System.out.println("\n✅ ALL ASSERTIONS PASSED");
    }

    // ---------- 1. 主链路 ----------

    private static void happyPath() throws Exception {
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

        LLMClient fake = (messages, tools) -> {
            boolean hasToolResult = messages.stream()
                    .anyMatch(m -> m.role() == Role.TOOL);
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

        assertTrue(r.roundsUsed() == 2, "应当跑 2 轮");
        assertTrue(toolCalls.get() == 1, "工具应当被调用 1 次");
        assertTrue(toolArgsSeen.contains("北京"), "应当传入 city=北京");
        assertTrue(r.finalAnswer().contains("25"), "最终答案应当包含工具返回的 25°C");
        assertTrue(r.transcript().stream().anyMatch(m -> m.role() == Role.TOOL),
                "历史里应当有 tool message");
        assertTrue("stop".equals(r.finishReason()), "finishReason 应当透传");
    }

    // ---------- 2. 轮次耗尽后强制收尾 ----------

    private static void maxRoundsForcesFinalAnswer() throws Exception {
        System.out.println("=== maxRounds 耗尽后强制收尾 ===");
        AtomicInteger llmCalls = new AtomicInteger();
        LLMClient fake = (messages, tools) -> {
            llmCalls.incrementAndGet();
            if (tools.isEmpty()) {
                return new LLMResponse("基于已有结果的最终答案", List.of(), "stop");
            }
            return new LLMResponse("",
                    List.of(new ToolCall("call_" + llmCalls.get(), "noop", "{}")), "tool_calls");
        };

        AgentResult r = Agent.builder(fake)
                .maxRounds(2)
                .tool(noopTool("noop"))
                .build()
                .runWithTrace("hi");

        assertTrue(llmCalls.get() == 3, "应当 2 轮工具 + 1 次收尾，实际 " + llmCalls.get());
        assertTrue(r.roundsUsed() == 3, "roundsUsed 应当为 3");
        assertTrue(r.finishedByMaxRounds(), "应当标记为 maxRounds 结束");
        assertTrue(r.finalAnswer().contains("最终答案"), "收尾调用应当给出最终答案，而不是固定文案");
        assertTrue(r.transcript().get(r.transcript().size() - 1).role() == Role.ASSISTANT,
                "transcript 应当以 assistant 消息收尾，不能留下悬空的 tool 消息");
    }

    // ---------- 3. arguments=null ----------

    private static void nullArgumentsBecomeEmptyMap() throws Exception {
        System.out.println("=== arguments=null ===");
        AtomicInteger toolCalls = new AtomicInteger();
        Tool noop = new Tool() {
            @Override public ToolSpec spec() {
                return new ToolSpec("noop", "no-op", SchemaBuilder.object().build());
            }
            @Override public String execute(Map<String, Object> args, ToolContext ctx) {
                toolCalls.incrementAndGet();
                assertTrue(args != null, "工具参数不应为 null");
                return "ok";
            }
        };

        AtomicInteger calls = new AtomicInteger();
        LLMClient fake = (messages, tools) -> calls.incrementAndGet() == 1
                ? new LLMResponse("", List.of(new ToolCall("c1", "noop", null)), "tool_calls")
                : new LLMResponse("done", List.of(), "stop");

        AgentResult r = Agent.builder(fake).tool(noop).build().runWithTrace("hi");

        assertTrue(toolCalls.get() == 1, "工具应当被正常调用");
        assertTrue("done".equals(r.finalAnswer()), "应当拿到最终答案");
        assertTrue(r.transcript().stream().anyMatch(m ->
                m.role() == Role.TOOL && "ok".equals(m.content())), "工具消息应当是 ok");
    }

    // ---------- 4. 重名工具 ----------

    private static void duplicateToolNamesRejected() {
        System.out.println("=== 重名工具 ===");
        LLMClient fake = (messages, tools) -> new LLMResponse("", List.of(), "stop");
        try {
            Agent.builder(fake)
                    .tool(noopTool("same"))
                    .tool(noopTool("same"))
                    .build()
                    .runWithTrace("hi");
            throw new AssertionError("FAIL: 重名工具应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("same"),
                    "异常信息应当包含工具名: " + expected.getMessage());
        } catch (Exception e) {
            throw new AssertionError("FAIL: 预期 IllegalArgumentException，实际 " + e, e);
        }
    }

    // ---------- 5. MiniJson ----------

    private static void miniJsonRobustness() {
        System.out.println("=== MiniJson ===");
        assertTrue(MiniJson.parse("3") instanceof Integer, "整数应当解析为 Integer");
        assertTrue(MiniJson.parse("12345678901234567890") instanceof BigInteger,
                "超出 long 的整数应当解析为 BigInteger，保留精度");
        assertTrue(MiniJson.parse("1.5") instanceof Double, "小数应当解析为 Double");
        assertEquals("3", MiniJson.stringify(MiniJson.parse("3")), "整数序列化");
        assertEquals("12345678901234567890",
                MiniJson.stringify(MiniJson.parse("12345678901234567890")), "大整数序列化不丢精度");

        assertJsonException(() -> MiniJson.parse("\"abc\\"), "未闭合转义");
        assertJsonException(() -> MiniJson.parse("\"\\u12"), "截断的 unicode 转义");
        assertJsonException(() -> MiniJson.parse("{"), "对象未闭合");
        assertJsonException(() -> MiniJson.parse("[".repeat(1000) + "]".repeat(1000)), "嵌套过深");
        assertJsonException(() -> MiniJson.parse("1e999"), "数字溢出");
        assertJsonException(() -> MiniJson.stringify(Double.NaN), "NaN 不能序列化");
    }

    // ---------- 6. MethodTool 参数名 ----------

    private static void methodToolUsesRealParameterNames() throws Exception {
        System.out.println("=== MethodTool 参数名 ===");
        Tool weather = MethodTool.all(new WeatherTools()).get(0);
        String schema = MiniJson.stringify(weather.spec().parametersSchema());
        assertTrue(schema.contains("\"city\"") && schema.contains("\"days\""),
                "schema 应当使用真实参数名而不是 arg0/arg1: " + schema);

        assertEquals("北京:2", weather.execute(Map.of("city", "北京", "days", 2),
                ToolContext.empty()), "应当正确回填参数");
        assertEquals("上海:1", weather.execute(Map.of("city", "上海", "days", "1"),
                ToolContext.empty()), "字符串形式的数字应当被转换");
    }

    // ---------- 7. 缺参报错 ----------

    private static void missingArgumentFailsToolCall() throws Exception {
        System.out.println("=== 缺参报错 ===");
        Tool weather = MethodTool.all(new WeatherTools()).get(0);
        try {
            weather.execute(Map.of("city", "北京"), ToolContext.empty());
            throw new AssertionError("FAIL: 缺参应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("days"),
                    "异常信息应当指出缺少 days: " + expected.getMessage());
        }
    }

    // ---------- 8. 会话持久化 ----------

    private static void conversationPersistsAcrossRuns() throws Exception {
        System.out.println("=== 会话持久化 ===");
        InMemoryConversationStore store = new InMemoryConversationStore();
        List<List<ChatMessage>> seen = new ArrayList<>();

        LLMClient fake = (messages, tools) -> {
            seen.add(new ArrayList<>(messages));
            long userCount = messages.stream().filter(m -> m.role() == Role.USER).count();
            return new LLMResponse("answer#" + userCount, List.of(), "stop");
        };

        Agent agent = Agent.builder(fake)
                .systemPrompt("SYS")
                .conversationStore(store)
                .build();

        assertEquals("answer#1", agent.run("c1", "hello"), "第一次运行");
        assertEquals("answer#2", agent.run("c1", "again"), "第二次应当带上历史");
        assertEquals("answer#1", agent.run("c2", "other"), "不同 conversationId 互不影响");

        List<ChatMessage> saved = store.load("c1");
        assertEquals(4, saved.size(), "c1 应当保存 2 轮共 4 条消息");
        assertTrue(saved.stream().noneMatch(m -> m.role() == Role.SYSTEM),
                "system 不应持久化");
        assertTrue(saved.stream().anyMatch(m ->
                        m.role() == Role.USER && "hello".equals(m.content())),
                "历史 user 应当落库");
        assertTrue(saved.stream().anyMatch(m ->
                        m.role() == Role.ASSISTANT && "answer#1".equals(m.content())),
                "历史 assistant 应当落库");

        List<ChatMessage> secondCall = seen.get(1);
        assertEquals(4, secondCall.size(), "第二次调用应当收到 system + 历史 + 当前输入");
        assertEquals(Role.SYSTEM, secondCall.get(0).role(), "每次运行都重新拼 system");
        assertEquals("SYS", secondCall.get(0).content(), "system 应当是当前配置");
        assertEquals("hello", secondCall.get(1).content(), "历史 user");
        assertEquals("answer#1", secondCall.get(2).content(), "历史 assistant");
        assertEquals("again", secondCall.get(3).content(), "当前 user");
    }

    private static void conversationStoreValidation() throws Exception {
        System.out.println("=== conversationId 校验 ===");
        LLMClient fake = (messages, tools) -> new LLMResponse("ok", List.of(), "stop");

        try {
            Agent.builder(fake).build().run("c1", "hi");
            throw new AssertionError("FAIL: 没配 store 却传 conversationId 应当报错");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("ConversationStore"),
                    "异常信息应当提示配置 store: " + expected.getMessage());
        }

        InMemoryConversationStore store = new InMemoryConversationStore();
        Agent agent = Agent.builder(fake).conversationStore(store).build();
        try {
            agent.run("   ", "hi");
            throw new AssertionError("FAIL: 空白 conversationId 应当报错");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("conversationId"),
                    "异常信息应当指出 conversationId: " + expected.getMessage());
        }

        assertEquals("ok", agent.run("ephemeral-without-id"), "不传 id 时按无状态运行");
        assertEquals(0, store.size(), "无 conversationId 时不应落库");
    }

    // ---------- 9. 加载历史时清洗不完整尾巴 ----------

    private static void incompleteTailSanitized() throws Exception {
        System.out.println("=== 清洗不完整历史 ===");
        InMemoryConversationStore store = new InMemoryConversationStore();
        store.save("c", List.of(
                ChatMessage.system("OLD SYSTEM"),
                ChatMessage.user("q1"),
                ChatMessage.assistant("a1", List.of()),
                ChatMessage.user("q2"),
                ChatMessage.assistant("", List.of(new ToolCall("t1", "noop", "{}"))),
                ChatMessage.tool("t1", "noop", "ok")
        ));

        List<ChatMessage> seen = new ArrayList<>();
        LLMClient fake = (messages, tools) -> {
            seen.addAll(messages);
            return new LLMResponse("done", List.of(), "stop");
        };

        Agent.builder(fake).systemPrompt("SYS").conversationStore(store)
                .build().run("c", "q3");

        assertEquals(4, seen.size(), "应当只保留 system + q1 + a1 + 当前问题");
        assertEquals("SYS", seen.get(0).content(), "旧 system 不应进入历史");
        assertEquals("q1", seen.get(1).content(), "完整轮次应当保留");
        assertEquals("a1", seen.get(2).content(), "完整轮次应当保留");
        assertEquals("q3", seen.get(3).content(), "当前问题应当追加在最后");
        assertTrue(seen.stream().noneMatch(m -> "q2".equals(m.content())),
                "未回答的旧 user 应当被丢弃");
        assertTrue(seen.stream().noneMatch(m -> m.role() == Role.TOOL),
                "悬空的工具链应当被丢弃");

        InMemoryConversationStore partial = new InMemoryConversationStore();
        partial.save("p", List.of(
                ChatMessage.user("x1"),
                ChatMessage.assistant("x1-answer", List.of()),
                ChatMessage.user("x2"),
                ChatMessage.assistant("", List.of(new ToolCall("t2", "noop", "{}"))),
                ChatMessage.tool("t2", "noop", "ok")
        ));
        List<ChatMessage> seenPartial = new ArrayList<>();
        LLMClient fake2 = (messages, tools) -> {
            seenPartial.addAll(messages);
            return new LLMResponse("done2", List.of(), "stop");
        };
        Agent.builder(fake2).systemPrompt("SYS").conversationStore(partial)
                .build().run("p", "x3");

        assertEquals(4, seenPartial.size(), "半截工具链应当整轮丢弃");
        assertEquals("x1-answer", seenPartial.get(2).content(), "上一个完整轮次应当保留");
        assertEquals("x3", seenPartial.get(3).content(), "当前问题应当追加在最后");
    }

    // ---------- 10. 完整工具链持久化 ----------

    private static void toolTranscriptPersists() throws Exception {
        System.out.println("=== 工具链持久化 ===");
        InMemoryConversationStore store = new InMemoryConversationStore();
        List<List<ChatMessage>> snapshots = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger();

        Tool weather = new Tool() {
            @Override public ToolSpec spec() {
                return new ToolSpec("get_weather", "查询天气",
                        SchemaBuilder.object().required("city")
                                .property("city", SchemaBuilder.string()).build());
            }
            @Override public String execute(Map<String, Object> args, ToolContext ctx) {
                return "北京: 晴, 25°C";
            }
        };

        LLMClient fake = (messages, tools) -> {
            snapshots.add(new ArrayList<>(messages));
            boolean hasToolResult = messages.stream().anyMatch(m -> m.role() == Role.TOOL);
            if (!hasToolResult) {
                return new LLMResponse("", List.of(new ToolCall("call_" + seq.incrementAndGet(),
                        "get_weather", "{\"city\":\"北京\"}")), "tool_calls");
            }
            return new LLMResponse("北京晴", List.of(), "stop");
        };

        Agent agent = Agent.builder(fake)
                .systemPrompt("SYS")
                .tool(weather)
                .conversationStore(store)
                .build();

        agent.run("c", "北京天气?");
        agent.run("c", "刚才的结果是什么?");

        List<ChatMessage> secondRun = snapshots.get(2);
        assertEquals(6, secondRun.size(), "第二次运行应当看到完整工具链和两轮用户输入");
        assertEquals(Role.TOOL, secondRun.get(3).role(), "tool 消息应当保留");
        assertEquals("call_1", secondRun.get(3).toolCallId(), "tool_call_id 应当保留");
        assertEquals("get_weather", secondRun.get(3).toolName(), "tool name 应当保留");
        assertEquals(Role.ASSISTANT, secondRun.get(4).role(), "工具结果后的最终答复应当保留");
        assertEquals("刚才的结果是什么?", secondRun.get(5).content(), "当前问题应当追加在最后");

        List<ChatMessage> saved = store.load("c");
        assertEquals(6, saved.size(), "工具链也应当落库");
        assertTrue(saved.stream().anyMatch(m ->
                m.role() == Role.TOOL && "call_1".equals(m.toolCallId())), "落库的 tool 消息");
    }

    // ---------- 11. 持久化编解码 ----------

    private static void conversationCodecRoundTrip() {
        System.out.println("=== ConversationCodec 往返 ===");
        List<ChatMessage> original = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("你好"),
                ChatMessage.assistant("", List.of(
                        new ToolCall("call_1", "get_weather", "{\"city\":\"北京\"}"))),
                ChatMessage.tool("call_1", "get_weather", "晴"),
                ChatMessage.assistant("北京晴", List.of())
        );

        List<ChatMessage> back = ConversationCodec.fromJson(ConversationCodec.toJson(original));
        assertEquals(original.size(), back.size(), "消息条数");
        assertEquals(Role.TOOL, back.get(3).role(), "tool 角色");
        assertEquals("call_1", back.get(3).toolCallId(), "tool_call_id");
        assertEquals("get_weather", back.get(3).toolName(), "tool name 应当保留");
        assertEquals("{\"city\":\"北京\"}", back.get(2).toolCalls().get(0).argumentsJson(),
                "arguments 字符串应当原样保留");

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "get_weather");
        function.put("arguments", Map.of("city", "上海"));
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "c2");
        call.put("type", "function");
        call.put("function", function);
        Map<String, Object> assistantWire = new LinkedHashMap<>();
        assistantWire.put("role", "assistant");
        assistantWire.put("content", "");
        assistantWire.put("tool_calls", List.of(call));

        List<ChatMessage> parsed = ConversationCodec.fromWire(List.of(assistantWire));
        assertEquals("{\"city\":\"上海\"}", parsed.get(0).toolCalls().get(0).argumentsJson(),
                "对象形式的 arguments 应当序列化为 JSON 字符串");

        assertTrue(ConversationCodec.fromJson(null).isEmpty(), "null 应当当作空会话");
        assertTrue(ConversationCodec.fromJson("").isEmpty(), "空串应当当作空会话");
        try {
            ConversationCodec.fromJson("{}");
            throw new AssertionError("FAIL: 非数组 JSON 应当报错");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("array"),
                    "异常信息应当说明需要数组: " + expected.getMessage());
        }
    }

    // ---------- 12. 流式回调 ----------

    private static void streamingDeltasReachListener() throws Exception {
        System.out.println("=== 流式输出 ===");
        List<String> deltas = new ArrayList<>();
        AtomicInteger streamCalls = new AtomicInteger();

        LLMClient streamingFake = new LLMClient() {
            @Override
            public LLMResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
                throw new AssertionError("streaming=true 时不应调用 chat()");
            }

            @Override
            public LLMResponse chatStream(List<ChatMessage> messages, List<ToolSpec> tools,
                                          Consumer<String> onDelta) {
                streamCalls.incrementAndGet();
                onDelta.accept("你");
                onDelta.accept("好");
                return new LLMResponse("你好", List.of(), "stop");
            }
        };

        AgentResult r = Agent.builder(streamingFake)
                .streaming(true)
                .listener(new AgentListener() {
                    @Override public void onContentDelta(int round, String delta) {
                        deltas.add(round + ":" + delta);
                    }
                })
                .build()
                .runWithTrace("hi");

        assertEquals(1, streamCalls.get(), "应当只发起一次流式调用");
        assertEquals(List.of("1:你", "1:好"), deltas, "增量应当按顺序透传给 listener");
        assertEquals("你好", r.finalAnswer(), "返回值仍然是聚合后的完整答案");
    }

    private static void streamingFallsBackToChat() throws Exception {
        System.out.println("=== 流式默认降级 ===");
        List<String> deltas = new ArrayList<>();
        LLMClient chatOnly = (messages, tools) -> new LLMResponse("完整答案", List.of(), "stop");

        AgentResult r = Agent.builder(chatOnly)
                .streaming(true)
                .listener(new AgentListener() {
                    @Override public void onContentDelta(int round, String delta) {
                        deltas.add(delta);
                    }
                })
                .build()
                .runWithTrace("hi");

        assertEquals(List.of("完整答案"), deltas,
                "未覆写 chatStream 的客户端应当把完整内容作为一次 delta");
        assertEquals("完整答案", r.finalAnswer(), "最终答案不变");
    }

    // ---------- helpers ----------

    static class WeatherTools {
        @ToolMethod(description = "查询给定城市的天气")
        public String weather(String city, int days) {
            return city + ":" + days;
        }
    }

    private static Tool noopTool(String name) {
        return new Tool() {
            @Override public ToolSpec spec() {
                return new ToolSpec(name, "no-op", SchemaBuilder.object().build());
            }
            @Override public String execute(Map<String, Object> args, ToolContext ctx) {
                return "ok";
            }
        };
    }

    private static void assertJsonException(Runnable action, String msg) {
        try {
            action.run();
        } catch (MiniJson.JsonException expected) {
            return;
        }
        throw new AssertionError("FAIL: 应当抛 JsonException: " + msg);
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (!expected.equals(actual)) {
            throw new AssertionError("FAIL: " + msg + "，expected=" + expected + "，actual=" + actual);
        }
    }
}
