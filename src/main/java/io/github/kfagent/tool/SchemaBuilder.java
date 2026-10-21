package io.github.kfagent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON Schema 构造器。让用户不用手写裸 map 就能描述工具参数。
 *
 * <pre>{@code
 * ToolSpec spec = new ToolSpec("weather", "查询天气",
 *     SchemaBuilder.object()
 *         .required("city")
 *         .property("city", SchemaBuilder.string("城市名"))
 *         .property("unit", SchemaBuilder.string("单位: c 或 f"))
 *         .build());
 * }</pre>
 */
public final class SchemaBuilder {

    private final Map<String, Object> backing = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private SchemaBuilder(String type) { backing.put("type", type); }

    public static SchemaBuilder object() { return new SchemaBuilder("object"); }
    public static SchemaBuilder string() { return new SchemaBuilder("string"); }
    public static SchemaBuilder string(String description) {
        SchemaBuilder s = new SchemaBuilder("string");
        s.backing.put("description", description);
        return s;
    }
    public static SchemaBuilder integer() { return new SchemaBuilder("integer"); }
    public static SchemaBuilder number() { return new SchemaBuilder("number"); }
    public static SchemaBuilder bool() { return new SchemaBuilder("boolean"); }
    public static SchemaBuilder array(SchemaBuilder item) {
        SchemaBuilder a = new SchemaBuilder("array");
        a.backing.put("items", item.backing);
        return a;
    }

    public SchemaBuilder description(String d) {
        backing.put("description", d);
        return this;
    }

    public SchemaBuilder required(String... keys) {
        for (String k : keys) required.add(k);
        return this;
    }

    public SchemaBuilder property(String name, SchemaBuilder sub) {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>)
                backing.computeIfAbsent("properties", k -> new LinkedHashMap<>());
        props.put(name, sub.backing);
        return this;
    }

    public Map<String, Object> build() {
        if (!required.isEmpty()) {
            backing.put("required", List.copyOf(required));
        }
        return backing;
    }
}
