package io.github.kfagent.core;

/**
 * 框架级异常包装。
 */
public final class AgentException extends RuntimeException {
    public AgentException(String msg) { super(msg); }
    public AgentException(String msg, Throwable cause) { super(msg, cause); }
}
