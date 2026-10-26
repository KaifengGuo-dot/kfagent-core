package io.github.kfagent.tool;

import java.util.Map;

/**
 * 上下文：工具执行时能拿到的东西。先放最简，后续要加 tracing / 取消信号再扩。
 */
public final class ToolContext {

    private final Map<String, Object> attributes;

    public ToolContext(Map<String, Object> attributes) {
        this.attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }

    public Object get(String key) { return attributes.get(key); }

    public <T> T get(String key, Class<T> type) { return type.cast(attributes.get(key)); }

    public static ToolContext empty() { return new ToolContext(Map.of()); }
}
