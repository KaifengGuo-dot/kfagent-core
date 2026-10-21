package io.github.kfagent.core;

import io.github.kfagent.json.MiniJson;
import io.github.kfagent.llm.LLMClient;
import io.github.kfagent.llm.LLMResponse;
import io.github.kfagent.message.ChatMessage;
import io.github.kfagent.message.ChatMessage.ToolCall;
import io.github.kfagent.tool.Tool;
import io.github.kfagent.tool.ToolContext;
import io.github.kfagent.tool.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 循环的实现。普通用户不用直接碰这个类，走 {@link Agent} 门面即可。
 *
 * <p>循环伪代码：
 * <pre>
 * history = [system]
 * while round < maxRounds:
 *     resp = llm.chat(history + [user], tools)
 *     history += resp.assistant
 *     if not resp.hasToolCalls():
 *         return resp.content
 *     for call in resp.toolCalls:
 *         result = tool.execute(call.arguments)
 *         history += tool_message(call.id, result)
 * return "达到最大轮次"
 * </pre>
 */
public final class AgentRunner {

    private final LLMClient llm;
    private final List<Tool> tools;
    private final Map<String, Tool> toolIndex;
    private final AgentConfig config;
    private final AgentListener listener;
    private final ToolContext toolContext;

    public AgentRunner(LLMClient llm, List<Tool> tools, AgentConfig config,
                       AgentListener listener, ToolContext toolContext) {
        this.llm = llm;
        this.tools = List.copyOf(tools);
        this.config = config;
        this.listener = listener == null ? new AgentListener() {} : listener;
        this.toolContext = toolContext == null ? ToolContext.empty() : toolContext;
        Map<String, Tool> idx = new LinkedHashMap<>();
        for (Tool t : tools) idx.put(t.spec().name(), t);
        this.toolIndex = idx;
    }

    public AgentResult run(String userInput) throws Exception {
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.system(config.systemPrompt()));
        history.add(ChatMessage.user(userInput));

        List<ToolSpec> specs = new ArrayList<>();
        for (Tool t : tools) specs.add(t.spec());

        int round = 0;
        boolean maxed = false;

        while (round < config.maxRounds()) {
            round++;
            listener.onRoundStart(round);

            LLMResponse resp = llm.chat(history, specs);
            ChatMessage assistant = ChatMessage.assistant(resp.content(), resp.toolCalls());
            history.add(assistant);
            listener.onLLMResponse(round, assistant);

            if (!resp.hasToolCalls()) {
                listener.onFinish(resp.content(), round);
                return AgentResult.of(resp.content(), history, round, false);
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
                        @SuppressWarnings("unchecked")
                        Map<String, Object> args = call.argumentsJson() == null
                                || call.argumentsJson().isBlank()
                                ? Map.of()
                                : (Map<String, Object>) MiniJson.parse(call.argumentsJson());
                        result = tool.execute(args, toolContext);
                    }
                } catch (Exception e) {
                    result = "[error] " + e.getMessage();
                }
                listener.onToolCallEnd(round, call, result);
                history.add(ChatMessage.tool(call.id(), call.name(), result));
            }
        }

        maxed = true;
        String answer = "（已达到最大工具轮次 " + config.maxRounds() + "，未得到最终答案）";
        listener.onFinish(answer, round);
        return AgentResult.of(answer, history, round, maxed);
    }
}
