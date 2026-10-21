package io.github.kfagent.tool;

/**
 * 工具的规格（name / description / JSON Schema 参数）。
 * 这部分会原样塞进 LLM 请求的 tools 字段。
 */
public record ToolSpec(String name, String description,
                       java.util.Map<String, Object> parametersSchema) {

    /** 生成 OpenAI tools 数组里的一个元素。 */
    public java.util.Map<String, Object> toWire() {
        java.util.Map<String, Object> fn = new java.util.LinkedHashMap<>();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", parametersSchema == null
                ? emptyObject() : parametersSchema);
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("type", "function");
        m.put("function", fn);
        return m;
    }

    private static java.util.Map<String, Object> emptyObject() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", new java.util.LinkedHashMap<>());
        return m;
    }
}
