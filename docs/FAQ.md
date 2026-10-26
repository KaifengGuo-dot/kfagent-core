# FAQ

## 为什么不用 LangChain4j / Spring AI？

不是它们不好，是场景不同。它们解决的是“全家桶”问题：多模型、RAG、向量库、
工具、链编排、可观测性。这个库只做“模型 + 工具 + 循环 + 会话”，而且坚持零依赖。

如果你要 RAG、向量库、几十种模型、复杂工作流，直接用它们更省事。
如果你只是想在业务代码里加一个会调 Java 方法的助手，这个库的接入成本更低。

## 为什么不用 Jackson / Gson？

零依赖是设计前提，不是临时偷懒。库一旦带 Jackson，用户的 Spring Boot 版本、
Jackson 版本、模块注册都可能和它打架。详见
[docs/ARCHITECTURE.md](ARCHITECTURE.md) 的 4.1 节。

## 怎么接 DeepSeek / Qwen / Moonshot / 本地 Ollama？

它们都提供 OpenAI 兼容端点，换三个参数就行：

```java
LLMClient llm = OpenAICompatClient.builder()
    .baseUrl("https://api.deepseek.com/v1")  // 换成对应服务商的地址
    .apiKey(System.getenv("LLM_API_KEY"))
    .model("deepseek-chat")
    .build();
```

本地 Ollama：

```java
OpenAICompatClient.builder()
    .baseUrl("http://localhost:11434/v1")   // 注意 /v1 后缀
    .apiKey("")                             // 留空就不发 Authorization
    .model("qwen2.5")
    .build();
```

部分网关的路径不是 `/v1/chat/completions`，`baseUrl` 填到 `/chat/completions` 之前即可。

## 为什么 `@ToolMethod` 报 "has no parameter names"？

`javac` 默认不把参数名写进 class 文件。Maven 加：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <parameters>true</parameters>
    </configuration>
</plugin>
```

Gradle 加：

```groovy
tasks.withType(JavaCompile).configureEach {
    options.compilerArgs << '-parameters'
}
```

IDEA 如果没把构建委托给 Maven / Gradle，也可能丢参数名。打开
Settings -> Build, Execution, Deployment -> Build Tools -> Maven -> Runner，
勾选 “Delegate IDE build/run actions to Maven”。

## 模型返回的 tool arguments 是字符串还是对象？

协议要求是 JSON 字符串。少数兼容网关会直接返回对象，`OpenAICompatClient` 会重新
序列化成一个字符串。你自己实现 `LLMClient` 时，`ToolCall.argumentsJson()` 请始终放字符串。

## 同一轮的多个工具能并行执行吗？

当前版本是串行。这样顺序、日志、错误处理都确定，工具之间也可以有依赖。
要并行，可以在单个工具内部用线程池，或者自己包一层 `Tool` 做 fan-out。
Roadmap 里有“可开关的并发执行”，但默认行为不会变。

## 怎么控制上下文长度？

库不替你做。两种常见做法：

1. 按轮数裁剪：从后往前找第 N 个 `user` 消息，从那里截断。README 里有
   `keepRecentTurns` 示例。
2. 摘要压缩：把摘要存在自己的业务字段里，构造 Agent 时拼进 system prompt。

不要往消息数组里塞一条“摘要 user 消息”，那会破坏 user / assistant 的交替结构，
加载时会被清洗掉。也不要把 `assistant(tool_calls)` 和后面的 `tool` 消息拆散。

## 怎么加重试 / 超时？

超时用 `timeoutSeconds`：

```java
OpenAICompatClient.builder()
    .timeoutSeconds(300)
    .build();
```

重试不要改库，包一层 `LLMClient`：

```java
final class RetryingLLM implements LLMClient {

    private final LLMClient delegate;

    RetryingLLM(LLMClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public LLMResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) throws Exception {
        // 只重试 429 / 5xx / IOException，指数退避，注意总耗时
        return delegate.chat(messages, tools);
    }

    @Override
    public LLMResponse chatStream(List<ChatMessage> messages, List<ToolSpec> tools,
                                  Consumer<String> onDelta) throws Exception {
        // 流式已经回调出去的 delta 收不回来，重试会导致前端重复显示。
        // 真要做，只在建立连接阶段重试。
        return delegate.chatStream(messages, tools, onDelta);
    }
}
```

## 同一个 conversationId 并发跑两次会怎样？

后保存的覆盖先保存的，可能丢一轮对话。库不做分布式锁，常见做法：

- 按 `conversationId` 加本地锁（单实例够用）；
- Redis `SET NX` 锁（多实例）；
- 业务层保证一个会话同一时间只有一个请求。

## 怎么接 RAG？

检索是你的事，拼 prompt 是库的事。两种做法：

1. 检索完把片段拼进 system 或 user 消息，再调 `agent.run(...)`；
2. 把检索做成一个 `Tool`，让模型自己决定查什么、查几次。

第一种简单可控，第二种更“Agent”，但要注意工具调用的轮次和延迟。

## 和 Spring Boot 3 怎么集成？

JDK 17 刚好对齐，直接 `@Bean`，不需要 Starter。示例见 README 的
[和 Spring Boot 一起用](../README.md#和-spring-boot-一起用)。

## 支持哪些模型？

所有 OpenAI Chat Completions 兼容端点理论上都能接。不同网关在 `tool_calls`
分片、`finish_reason`、错误格式上会有差异，遇到兼容问题请提 issue，附上请求和响应
（记得打码）。

## 能上生产吗？

可以用，但目前还没有大规模生产验证。建议至少做这几件事：

- 锁定版本，别用 `LATEST`；
- 工具内部做权限校验和参数校验，高危操作加人工确认；
- 给 `LLMClient` 包一层重试和超时；
- 给会话存储配 TTL 和容量上限；
- 用 `AgentListener` 接日志 / 指标 / tracing；
- 不要把 Agent 接口直接暴露给公网而不加鉴权。

## 为什么没有 JUnit？

零依赖约束也适用于构建期。离线自测是普通 `main` 类，CI 直接 `java -cp` 跑，
不需要测试框架。不是反对 JUnit，是保持构建轻。如果你要加，先开 issue 讨论。

## 为什么 README 里的依赖下载不到？

1.0.0 还在准备发 Maven Central。发布前先本地 `mvn install`，或者用 JitPack。
具体步骤和命名空间验证的坑见 [docs/RELEASING.md](RELEASING.md)。
