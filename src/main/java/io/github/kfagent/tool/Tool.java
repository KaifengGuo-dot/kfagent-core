package io.github.kfagent.tool;

import java.util.Map;

/**
 * 一个可被 Agent 调用的工具。
 *
 * <p>用户最简单的实现方式：
 * <pre>{@code
 * Tool weather = new Tool() {
 *     public ToolSpec spec() {
 *         return new ToolSpec("weather", "查询天气",
 *             SchemaBuilder.object().required("city")
 *                 .property("city", SchemaBuilder.string("城市名")).build());
 *     }
 *     public String execute(Map<String, Object> args, ToolContext ctx) {
 *         return "晴, 25度";
 *     }
 * };
 * }</pre>
 *
 * <p>或者用 {@link io.github.kfagent.tool.MethodTool} 包装一个普通 Java 方法。
 */
public interface Tool {

    ToolSpec spec();

    /**
     * 执行工具。返回值会以 tool message 形式回灌给 LLM，建议是简短、信息密度高的字符串。
     * 抛出的异常会被框架捕获，异常 message 会作为工具错误结果返回给 LLM。
     */
    String execute(Map<String, Object> args, ToolContext ctx) throws Exception;

    /** 默认便捷方法：无上下文版本。 */
    default String execute(Map<String, Object> args) throws Exception {
        return execute(args, ToolContext.empty());
    }
}
