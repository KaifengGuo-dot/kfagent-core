package io.github.kfagent.message;

/**
 * 消息角色。只覆盖 Agent 循环里真正用到的四种。
 */
public enum Role {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String wire;

    Role(String wire) { this.wire = wire; }

    public String wire() { return wire; }

    /** 从 wire 字符串还原角色，未知值直接报错，避免静默丢消息。 */
    public static Role fromWire(String wire) {
        for (Role r : values()) {
            if (r.wire.equals(wire)) return r;
        }
        throw new IllegalArgumentException("Unknown role: " + wire);
    }
}
