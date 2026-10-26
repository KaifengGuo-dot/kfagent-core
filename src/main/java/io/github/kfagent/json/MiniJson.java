package io.github.kfagent.json;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析与序列化。
 *
 * <p>这个库刻意不引入 Jackson / Gson / org.json，就是为了让用户加一行依赖后
 * 不会被传递依赖炸到。这里只覆盖 OpenAI Chat Completions 请求/响应所需要的
 * 那一小部分 JSON 子集，足够用，且行为可预测。
 *
 * <p>解析结果：JSON object -> {@link LinkedHashMap}，array -> {@link ArrayList}，
 * 整数 -> {@link Integer} / {@link Long} / {@link BigInteger}（按大小），
 * 小数或指数 -> {@link Double}，string -> {@link String}，true/false -> {@link Boolean}，
 * null -> {@code null}。
 */
public final class MiniJson {

    /** 嵌套深度上限，防止畸形输入把解析器递归到 StackOverflowError。 */
    private static final int MAX_DEPTH = 512;

    private MiniJson() {}

    // ---------- parse ----------

    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("Input is null");
        }
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (!p.atEnd()) {
            throw new JsonException("Unexpected trailing character at index " + p.pos);
        }
        return v;
    }

    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("Expected JSON object, got: "
                    + (v == null ? "null" : v.getClass()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    // ---------- stringify ----------

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (v instanceof Number n) {
            if (v instanceof Double || v instanceof Float) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new JsonException("Non-finite number cannot be serialized: " + n);
                }
                if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                    sb.append((long) d);
                } else {
                    sb.append(n.toString());
                }
            } else {
                // Integer / Long / BigInteger / BigDecimal 等按原样输出，不丢精度
                sb.append(n.toString());
            }
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object o : list) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, o);
            }
            sb.append(']');
        } else {
            // 兜底：把任意对象当字符串
            writeString(sb, v.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    // 控制字符和代理项必须转义，否则可能产生非法 JSON / 非法 UTF-8
                    if (c < 0x20 || Character.isSurrogate(c)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------- parser ----------

    private static final class Parser {
        final String s;
        int pos = 0;
        int depth = 0;

        Parser(String s) { this.s = s; }

        boolean atEnd() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object readValue() {
            skipWs();
            if (atEnd()) throw new JsonException("Unexpected end of input");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            enter();
            try {
                expect('{');
                Map<String, Object> map = new LinkedHashMap<>();
                skipWs();
                if (peek() == '}') { pos++; return map; }
                while (true) {
                    skipWs();
                    String key = readString();
                    skipWs();
                    expect(':');
                    Object v = readValue();
                    map.put(key, v);
                    skipWs();
                    char c = next();
                    if (c == '}') break;
                    if (c != ',') throw new JsonException("Expected ',' or '}' at " + pos);
                }
                return map;
            } finally {
                depth--;
            }
        }

        private List<Object> readArray() {
            enter();
            try {
                expect('[');
                List<Object> list = new ArrayList<>();
                skipWs();
                if (peek() == ']') { pos++; return list; }
                while (true) {
                    list.add(readValue());
                    skipWs();
                    char c = next();
                    if (c == ']') break;
                    if (c != ',') throw new JsonException("Expected ',' or ']' at " + pos);
                }
                return list;
            } finally {
                depth--;
            }
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw new JsonException("Unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    if (atEnd()) throw new JsonException("Unterminated escape sequence");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> sb.append(readUnicodeEscape());
                        default -> throw new JsonException("Bad escape: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private char readUnicodeEscape() {
            if (pos + 4 > s.length()) {
                throw new JsonException("Truncated unicode escape at " + pos);
            }
            int code = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(s.charAt(pos + i), 16);
                if (digit < 0) {
                    throw new JsonException("Bad unicode escape at " + pos);
                }
                code = code * 16 + digit;
            }
            pos += 4;
            return (char) code;
        }

        private Number readNumber() {
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else break;
            }
            String token = s.substring(start, pos);
            if (token.isEmpty()) {
                throw new JsonException("Unexpected character '" + s.charAt(start)
                        + "' at " + start);
            }
            boolean integral = token.indexOf('.') < 0
                    && token.indexOf('e') < 0 && token.indexOf('E') < 0;
            if (integral) {
                try {
                    long value = Long.parseLong(token);
                    if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                        return (int) value;
                    }
                    return value;
                } catch (NumberFormatException overflow) {
                    try {
                        return new BigInteger(token);
                    } catch (NumberFormatException e) {
                        throw new JsonException("Bad number: " + token);
                    }
                }
            }
            try {
                double d = Double.parseDouble(token);
                if (Double.isInfinite(d)) {
                    throw new JsonException("Number out of range: " + token);
                }
                return d;
            } catch (NumberFormatException e) {
                throw new JsonException("Bad number: " + token);
            }
        }

        private Object readLiteral(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw new JsonException("Expected '" + word + "' at " + pos);
            }
            pos += word.length();
            return value;
        }

        private void enter() {
            if (++depth > MAX_DEPTH) {
                throw new JsonException("JSON nesting exceeds " + MAX_DEPTH + " levels");
            }
        }

        private char peek() {
            if (atEnd()) throw new JsonException("Unexpected end of input");
            return s.charAt(pos);
        }

        private char next() {
            if (atEnd()) throw new JsonException("Unexpected end");
            return s.charAt(pos++);
        }

        private void expect(char c) {
            if (atEnd() || s.charAt(pos) != c) {
                throw new JsonException("Expected '" + c + "' at " + pos);
            }
            pos++;
        }
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String msg) { super(msg); }
    }
}
