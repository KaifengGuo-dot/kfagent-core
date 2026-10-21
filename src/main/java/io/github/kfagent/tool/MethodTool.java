package io.github.kfagent.tool;

import io.github.kfagent.json.MiniJson;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一个普通 Java 对象上标了 {@link ToolMethod} 的方法反射包装成 {@link Tool}。
 *
 * <pre>{@code
 * class MyTools {
 *     @ToolMethod(description = "查询给定城市的天气")
 *     public String weather(String city, String unit) { ... }
 * }
 *
 * Tool t = MethodTool.of(new MyTools(), "weather");
 * }</pre>
 *
 * <p>参数类型目前支持 String / Integer / Long / Double / Boolean（够用了，
 * 真要复杂嵌套类型请直接实现 Tool 接口）。
 */
public final class MethodTool implements Tool {

    private final Object target;
    private final Method method;
    private final ToolSpec spec;

    private MethodTool(Object target, Method method, ToolSpec spec) {
        this.target = target;
        this.method = method;
        this.spec = spec;
    }

    /** 扫描 target 上所有 @ToolMethod，返回工具列表。 */
    public static List<Tool> all(Object target) {
        List<Tool> out = new ArrayList<>();
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(ToolMethod.class)) {
                out.add(wrap(target, m));
            }
        }
        return out;
    }

    /** 包装指定方法名。 */
    public static Tool of(Object target, String methodName) {
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.isAnnotationPresent(ToolMethod.class)) {
                return wrap(target, m);
            }
        }
        throw new IllegalArgumentException("No @ToolMethod named '" + methodName
                + "' on " + target.getClass());
    }

    private static Tool wrap(Object target, Method m) {
        m.setAccessible(true);
        ToolMethod ann = m.getAnnotation(ToolMethod.class);
        String name = ann.name().isEmpty() ? m.getName() : ann.name();

        SchemaBuilder sb = SchemaBuilder.object();
        Map<String, Object> props = new LinkedHashMap<>();
        for (Parameter p : m.getParameters()) {
            String pname = p.getName();
            // 没开 -parameters 编译时，参数名是 arg0/arg1，这里就按顺序塞 required 提示
            props.put(pname, javaTypeToSchema(p.getType()));
            sb.required(pname);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> propMap = (Map<String, Object>)
                sb.build().getOrDefault("properties", new LinkedHashMap<>());
        propMap.putAll(props);
        sb.build().put("properties", propMap);

        return new MethodTool(target, m, new ToolSpec(name, ann.description(), sb.build()));
    }

    private static Map<String, Object> javaTypeToSchema(Class<?> t) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (t == String.class) m.put("type", "string");
        else if (t == int.class || t == Integer.class) m.put("type", "integer");
        else if (t == long.class || t == Long.class) m.put("type", "integer");
        else if (t == double.class || t == Double.class || t == float.class || t == Float.class)
            m.put("type", "number");
        else if (t == boolean.class || t == Boolean.class) m.put("type", "boolean");
        else m.put("type", "string");
        return m;
    }

    @Override
    public ToolSpec spec() { return spec; }

    @Override
    public String execute(Map<String, Object> args, ToolContext ctx) throws Exception {
        Parameter[] ps = method.getParameters();
        Object[] realArgs = new Object[ps.length];
        for (int i = 0; i < ps.length; i++) {
            String pname = ps[i].getName();
            Object v = args.get(pname);
            realArgs[i] = coerce(v, ps[i].getType());
        }
        try {
            Object r = method.invoke(target, realArgs);
            return r == null ? "" : r.toString();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new Exception("Tool " + spec.name() + " failed: " + cause.getMessage(), cause);
        }
    }

    private static Object coerce(Object v, Class<?> t) {
        if (v == null) {
            if (t == int.class || t == long.class) return 0;
            if (t == double.class || t == float.class) return 0d;
            if (t == boolean.class) return false;
            return null;
        }
        if (t == String.class) return v.toString();
        if (v instanceof Number n) {
            if (t == int.class || t == Integer.class) return n.intValue();
            if (t == long.class || t == Long.class) return n.longValue();
            if (t == double.class || t == Double.class) return n.doubleValue();
            if (t == float.class || t == Float.class) return n.floatValue();
        }
        if (t == boolean.class || t == Boolean.class) {
            if (v instanceof Boolean b) return b;
            return Boolean.parseBoolean(v.toString());
        }
        return v;
    }
}
