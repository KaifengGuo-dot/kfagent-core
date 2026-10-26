package io.github.kfagent.tool;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
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
 * <p>参数类型目前支持 String / Integer / Long / Double / Float / Boolean 及其基本类型。
 * 参数名来自 class 文件，必须用 {@code javac -parameters} 编译（Maven 下在
 * maven-compiler-plugin 里配置 {@code <parameters>true</parameters>}），否则
 * {@link #all(Object)} / {@link #of(Object, String)} 会直接报错，避免生成
 * {@code arg0/arg1} 这种 LLM 看不懂的 schema。
 *
 * <p>真要复杂嵌套类型请直接实现 {@link Tool} 接口。
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

        SchemaBuilder schema = SchemaBuilder.object();
        for (Parameter p : m.getParameters()) {
            if (!p.isNamePresent()) {
                throw new IllegalStateException("@ToolMethod " + target.getClass().getName()
                        + "#" + m.getName() + " has no parameter names in the class file; "
                        + "compile with -parameters (Maven: <parameters>true</parameters> "
                        + "for maven-compiler-plugin)");
            }
            schema.required(p.getName())
                    .property(p.getName(), schemaFor(target, m, p.getType()));
        }
        return new MethodTool(target, m, new ToolSpec(name, ann.description(), schema.build()));
    }

    private static SchemaBuilder schemaFor(Object target, Method m, Class<?> type) {
        if (type == String.class) return SchemaBuilder.string();
        if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class) return SchemaBuilder.integer();
        if (type == double.class || type == Double.class
                || type == float.class || type == Float.class) return SchemaBuilder.number();
        if (type == boolean.class || type == Boolean.class) return SchemaBuilder.bool();
        throw new IllegalArgumentException("@ToolMethod " + target.getClass().getName()
                + "#" + m.getName() + " has unsupported parameter type " + type.getName()
                + "; use String/number/boolean or implement Tool directly");
    }

    @Override
    public ToolSpec spec() { return spec; }

    @Override
    public String execute(Map<String, Object> args, ToolContext ctx) throws Exception {
        Map<String, Object> actual = args == null ? Map.of() : args;
        Parameter[] ps = method.getParameters();
        Object[] realArgs = new Object[ps.length];
        for (int i = 0; i < ps.length; i++) {
            String pname = ps[i].getName();
            if (!actual.containsKey(pname) || actual.get(pname) == null) {
                throw new IllegalArgumentException("missing required argument '" + pname
                        + "' for tool " + spec.name());
            }
            realArgs[i] = coerce(actual.get(pname), ps[i].getType());
        }
        try {
            Object r = method.invoke(target, realArgs);
            return r == null ? "" : r.toString();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new Exception("Tool " + spec.name() + " failed: " + cause.getMessage(), cause);
        }
    }

    private static Object coerce(Object v, Class<?> type) {
        if (type == String.class) return v.toString();
        if (type == boolean.class || type == Boolean.class) {
            return v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString().trim());
        }
        if (v instanceof Number n) {
            if (type == int.class || type == Integer.class) return n.intValue();
            if (type == long.class || type == Long.class) return n.longValue();
            if (type == float.class || type == Float.class) return n.floatValue();
            return n.doubleValue();
        }
        // 模型偶尔会把数字写成字符串，这里宽松转换
        String text = v.toString().trim();
        if (type == int.class || type == Integer.class) return Integer.parseInt(text);
        if (type == long.class || type == Long.class) return Long.parseLong(text);
        if (type == float.class || type == Float.class) return Float.parseFloat(text);
        return Double.parseDouble(text);
    }
}
