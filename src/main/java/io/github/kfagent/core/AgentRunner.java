package io.github.kfagent.core;

import io.github.kfagent.conversation.ConversationStore;
import io.github.kfagent.json.MiniJson;
import io.github.kfagent.llm.LLMClient;
import io.github.kfagent.llm.LLMResponse;
import io.github.kfagent.message.ChatMessage;
import io.github.kfagent.message.ChatMessage.ToolCall;
import io.github.kfagent.message.Role;
import io.github.kfagent.tool.Tool;
import io.github.kfagent.tool.ToolContext;
import io.github.kfagent.tool.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 循环的实现。普通用户不用直接碰这个类，走 {@link Agent} 门面即可。
 *
 * <p>循环伪代码：
 * <pre>
 * history = [system] + 已持久化的历史 + [user]
 * while round &lt; maxRounds:
 *     resp = llm.chat(history, tools)
 *     history += resp.assistant
 *     if not resp.hasToolCalls():
 *         return resp.content
 *     for call in resp.toolCalls:
 *         result = tool.execute(call.arguments)
 *         history += tool_message(call.id, result)
 * # 工具轮次耗尽：去掉工具再请求一次，强制模型基于已有结果收尾
 * return llm.chat(history, no_tools).content
 * # 拿到最终答案后，run() 统一 save(history)
 * </pre>
 */
public final class AgentRunner {

    private final LLMClient llm;
    private final List<Tool> tools;
    private final Map<String, Tool> toolIndex;
    private final AgentConfig config;
    private final AgentListener listener;
    private final ToolContext toolContext;
    private final ConversationStore conversationStore;

    public AgentRunner(LLMClient llm, List<Tool> tools, AgentConfig config,
                       AgentListener listener, ToolContext toolContext,
                       ConversationStore conversationStore) {
        this.llm = llm;
        this.tools = List.copyOf(tools);
        this.config = config;
        this.listener = listener == null ? new AgentListener() {} : listener;
        this.toolContext = toolContext == null ? ToolContext.empty() : toolContext;
        this.conversationStore = conversationStore;
        Map<String, Tool> idx = new LinkedHashMap<>();
        for (Tool t : this.tools) {
            Tool previous = idx.putIfAbsent(t.spec().name(), t);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + t.spec().name());
            }
        }
        this.toolIndex = idx;
    }

    /** 不读写会话存储的一次运行。 */
    public AgentResult run(String userInput) throws Exception {
        return run(null, userInput);
    }

    /**
     * 带会话 id 运行：先加载历史，跑完后再保存。
     * {@code conversationId == null} 表示无状态的一次性运行。
     */
    public AgentResult run(String conversationId, String userInput) throws Exception {
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.system(config.systemPrompt()));

        if (conversationId != null) {
            requireConversationId(conversationId);
            if (conversationStore == null) {
                throw new IllegalStateException("conversationId was provided but no ConversationStore is "
                        + "configured; use Agent.builder(llm).conversationStore(...)");
            }
            history.addAll(sanitizeHistory(conversationStore.load(conversationId)));
        }
        history.add(ChatMessage.user(userInput));

        AgentResult result = runLoop(history);

        if (conversationId != null) {
            persist(conversationId, history);
        }
        listener.onFinish(result.finalAnswer(), result.roundsUsed());
        return result;
    }

    private AgentResult runLoop(List<ChatMessage> history) throws Exception {
        List<ToolSpec> specs = new ArrayList<>();
        for (Tool t : tools) specs.add(t.spec());

        int round = 0;

        while (round < config.maxRounds()) {
            round++;
            LLMResponse resp = callLLM(round, history, specs, true);

            if (!resp.hasToolCalls()) {
                String answer = resp.content() == null ? "" : resp.content();
                return AgentResult.of(answer, history, round, false, resp.finishReason());
            }

            // 执行所有工具调用，结果以 tool message 回灌
            for (ToolCall call : resp.toolCalls()) {
                listener.onToolCallStart(round, call);
                String result;
                try {
                    Tool tool = toolIndex.get(call.name());
                    if (tool == null) {
                        result = "[error] no such tool: " + call.name();
                    } else {
                        result = tool.execute(parseArguments(call), toolContext);
                    }
                } catch (Exception e) {
                    result = "[error] " + (e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage());
                }
                listener.onToolCallEnd(round, call, result);
                history.add(ChatMessage.tool(call.id(), call.name(), result));
            }
        }

        // 工具轮次耗尽：不再提供工具，强制模型基于已有工具结果给出最终答案
        round++;
        LLMResponse finalResp = callLLM(round, history, List.of(), false);
        String answer = finalResp.content() == null ? "" : finalResp.content();
        if (answer.isBlank()) {
            answer = "（已达到最大工具轮次 " + config.maxRounds() + "，未得到最终答案）";
        }
        return AgentResult.of(answer, history, round, true, finalResp.finishReason());
    }

    /**
     * 调一次 LLM 并把 assistant 消息写入历史。
     * keepToolCalls=false 时丢弃响应里的 tool_calls（收尾调用不会再有工具结果回灌，
     * 保留会留下悬空的 tool_call）。
     */
    private LLMResponse callLLM(int round, List<ChatMessage> history,
                                List<ToolSpec> specs, boolean keepToolCalls) throws Exception {
        listener.onRoundStart(round);
        LLMResponse resp = config.streaming()
                ? llm.chatStream(history, specs, delta -> listener.onContentDelta(round, delta))
                : llm.chat(history, specs);
        ChatMessage assistant = ChatMessage.assistant(resp.content(),
                keepToolCalls ? resp.toolCalls() : List.of());
        history.add(assistant);
        listener.onLLMResponse(round, assistant);
        return resp;
    }

    /**
     * 加载历史时只保留“完整轮次”。
     *
     * <p>合法序列是：user → assistant（可能带 tool_calls）→ tool... → assistant ...，
     * 最终以不带 tool_calls 的 assistant 收尾。system 一律丢弃（运行时重新拼当前配置的
     * system prompt）；未回答的尾部、缺 tool 结果的 assistant、半个工具链，全部截断到
     * 最后一个完整 assistant 为止。
     */
    private static List<ChatMessage> sanitizeHistory(List<ChatMessage> loaded) {
        if (loaded == null || loaded.isEmpty()) return List.of();

        List<ChatMessage> clean = new ArrayList<>();
        int checkpoint = 0;
        TurnState state = TurnState.NEED_USER;
        Set<String> pendingToolCalls = new LinkedHashSet<>();

        for (ChatMessage m : loaded) {
            if (m == null || m.role() == Role.SYSTEM) continue;

            switch (state) {
                case NEED_USER -> {
                    if (m.role() != Role.USER) return copyPrefix(clean, checkpoint);
                    clean.add(m);
                    state = TurnState.NEED_ASSISTANT;
                }
                case NEED_ASSISTANT -> {
                    if (m.role() != Role.ASSISTANT) return copyPrefix(clean, checkpoint);
                    if (m.toolCalls().isEmpty()) {
                        clean.add(m);
                        checkpoint = clean.size();
                        state = TurnState.NEED_USER;
                    } else {
                        pendingToolCalls.clear();
                        for (ToolCall call : m.toolCalls()) {
                            if (call.id() == null || !pendingToolCalls.add(call.id())) {
                                return copyPrefix(clean, checkpoint);
                            }
                        }
                        clean.add(m);
                        state = TurnState.NEED_TOOL;
                    }
                }
                case NEED_TOOL -> {
                    if (m.role() != Role.TOOL || m.toolCallId() == null
                            || !pendingToolCalls.remove(m.toolCallId())) {
                        return copyPrefix(clean, checkpoint);
                    }
                    clean.add(m);
                    if (pendingToolCalls.isEmpty()) state = TurnState.NEED_ASSISTANT;
                }
            }
        }
        return copyPrefix(clean, checkpoint);
    }

    private static List<ChatMessage> copyPrefix(List<ChatMessage> messages, int end) {
        return new ArrayList<>(messages.subList(0, end));
    }

    private void persist(String conversationId, List<ChatMessage> history) throws Exception {
        List<ChatMessage> persisted = new ArrayList<>(history.size());
        for (ChatMessage m : history) {
            if (m.role() != Role.SYSTEM) persisted.add(m);
        }
        try {
            conversationStore.save(conversationId, List.copyOf(persisted));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save conversation: " + conversationId, e);
        }
    }

    private static void requireConversationId(String conversationId) {
        if (conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArguments(ToolCall call) {
        String json = call.argumentsJson();
        if (json == null || json.isBlank()) return Map.of();
        Object parsed = MiniJson.parse(json);
        if (parsed == null) return Map.of();
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("tool arguments must be a JSON object, got "
                    + parsed.getClass().getSimpleName() + ": " + json);
        }
        return (Map<String, Object>) parsed;
    }

    private enum TurnState {
        NEED_USER, NEED_ASSISTANT, NEED_TOOL
    }
}
