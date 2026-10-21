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
}
