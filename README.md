# kfagent-core

> 零依赖、纯 Java 17 的 Agent 循环库。加一行依赖，就能在自己项目里跑带工具调用的 LLM Agent。

[![CI](https://github.com/KaifengGuo-dot/kfagent-core/actions/workflows/ci.yml/badge.svg)](https://github.com/KaifengGuo-dot/kfagent-core/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Zero Dependencies](https://img.shields.io/badge/dependencies-0-brightgreen.svg)](#)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

如果你只想要“模型 + 工具 + 循环 + 会话”，不想为了一个助手把 Spring AI / LangChain4j 的整棵依赖树搬进业务项目，这个库就是干这个的。

当前版本 1.0.1：核心 API 基本稳定，但还没有大规模生产验证。适合中小型业务助手、内部工具，以及想读源码自己改的场景。

## 目录

- [它解决什么问题](#它解决什么问题)
- [特性](#特性)
- [安装](#安装)
- [三行代码跑起来](#三行代码跑起来)
- [定义一个工具](#定义一个工具)
- [观察运行过程](#观察运行过程)
- [流式输出](#流式输出)
- [多轮对话与上下文存储](#多轮对话与上下文存储)
- [和 Spring Boot 一起用](#和-spring-boot-一起用)
- [已知限制](#已知限制)
- [包结构](#包结构)
- [构建与自测](#构建与自测)
- [Roadmap](#roadmap)
- [常见问题](#常见问题)
- [参与贡献](#参与贡献)
- [License](#license)

## 它解决什么问题

很多 Agent 框架解决的是“全家桶”问题：几十种模型、RAG、向量库、工作流编排、可视化。功能确实全，但接进一个已有的 Spring Boot 业务系统时，往往要先接受一整套抽象和一堆传递依赖。

我的需求很窄：

- 业务代码里能直接 `new` 一个 Agent，传一句话，拿回答案；
- 模型能调我写好的 Java 方法，参数和返回值都是普通类型；
- 支持流式输出，前端能逐字显示；
- 多轮对话的历史我自己想存在哪儿就存在哪儿，定义了一个很薄的 SPI ， 库只定义接口，Redis、MongoDB、MySQL、文件、内存都能接，用哪个由我自行决定。
- 不引入任何第三方依赖。

于是有了 kfagent-core。核心 20 个类、1900 多行，只做上面这几件事。

明确不做的：

- 不做 RAG、向量库、文档解析；
- 不做工作流 / 多 Agent 编排（如果想做的话可以把这个作为一个子agent）；
- 不做模型路由和 API 网关；
- 不内置上下文压缩、摘要、限流、重试；
- 不封装 Spring Boot Starter —— 一个 `@Bean` 就够了，见下文。

## 特性

- **零外部依赖**：只靠 JDK 自带的 `java.net.http.HttpClient`，JSON 是手写的迷你解析器。引入后不会有传递依赖冲突。
- **OpenAI 兼容协议**：一份客户端同时对接 OpenAI / DeepSeek / Qwen / Moonshot / 通义 / 智谱 / 本地 Ollama 的 OpenAI 兼容端口。本地 Ollama 不需要 key，`apiKey` 留空即可。
- **标准 Agent 循环**：`LLM → tool_calls → 执行工具 → 把结果喂回 LLM → 直到出最终答案`，带最大轮次防死循环。
- **流式输出**：SSE 解析，工具调用分片按 `index` 聚合，增量文本通过 `AgentListener.onContentDelta` 回调。
- **会话持久化 SPI**：`ConversationStore` 只定义 `load/save`，自带进程内实现；Redis / MongoDB 用你自己的客户端接，库不带任何存储依赖。
- **工具两种写法**：实现 `Tool` 接口，或给普通方法加 `@ToolMethod` 注解自动反射包装。
- **可观测**：`AgentListener` 钩子暴露每一轮、每次工具调用的开始/结束，以及流式增量。
- **JDK 17**：用了 record / switch expression / var。

## 安装

JDK 17+。当前通过 JitPack 发布，Maven 需要先加仓库：

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.KaifengGuo-dot</groupId>
    <artifactId>kfagent-core</artifactId>
    <version>v1.0.1</version>
</dependency>
```

Gradle：

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.KaifengGuo-dot:kfagent-core:v1.0.1'
}
```

> JitPack 首次拉取会按 tag 现场构建，通常需要几分钟，之后走缓存。
> 如果不想依赖 JitPack，也可以 clone 后本地安装：
>
> ```bash
> git clone https://github.com/KaifengGuo-dot/kfagent-core.git
> cd kfagent-core
> mvn -DskipTests install
> ```
>
> Maven Central 发布在准备中，坐标确定后会更新这里。

## 三行代码跑起来

```java
LLMClient llm = OpenAICompatClient.builder()
    .baseUrl("https://api.deepseek.com/v1")
    .apiKey(System.getenv("DEEPSEEK_API_KEY"))
    .model("deepseek-chat")
    .build();

Agent agent = Agent.builder(llm)
    .systemPrompt("你是一个有用的助手")
    .tool(myWeatherTool)
    .build();

String answer = agent.run("北京今天天气怎么样？");
```

完整可运行版本见 [`HelloAgent.java`](src/example/java/io/github/kfagent/example/HelloAgent.java)。

## 定义一个工具

### 方式一：实现 `Tool` 接口

```java
Tool weather = new Tool() {
    public ToolSpec spec() {
        return new ToolSpec("get_weather", "查询指定城市天气",
            SchemaBuilder.object()
                .required("city")
                .property("city", SchemaBuilder.string("城市名"))
                .build());
    }
    public String execute(Map<String, Object> args, ToolContext ctx) {
        return args.get("city") + ": 晴, 25°C";
    }
};
```

### 方式二：注解 + 反射

```java
class MyTools {
    @ToolMethod(description = "查询给定城市的天气")
    public String weather(String city) {
        return city + ": 晴, 25°C";
    }
}

List<Tool> tools = MethodTool.all(new MyTools());
```

> 注解方式依赖 class 文件里的参数名。Maven 项目需要在 `maven-compiler-plugin` 里配
> `<parameters>true</parameters>`（等价于 `javac -parameters`），否则 `MethodTool`
> 会在启动时直接报错，而不是生成 `arg0/arg1` 这种模型看不懂的 schema。

## 观察运行过程

```java
Agent agent = Agent.builder(llm)
    .listener(new AgentListener() {
        @Override public void onToolCallStart(int round, ChatMessage.ToolCall call) {
            System.out.println("[round " + round + "] call " + call.name()
                + " args=" + call.argumentsJson());
        }
        @Override public void onToolCallEnd(int round, ChatMessage.ToolCall call, String result) {
            System.out.println("[round " + round + "] -> " + result);
        }
    })
    .build();
```

## 流式输出

打开 `streaming(true)` 后，Agent 每一轮都用 SSE 调模型，增量文本通过
`AgentListener.onContentDelta` 回调；`run` 的返回值仍然是聚合后的完整答案。

```java
Agent agent = Agent.builder(llm)
    .streaming(true)
    .listener(new AgentListener() {
        @Override public void onContentDelta(int round, String delta) {
            System.out.print(delta);
            System.out.flush();
        }
        @Override public void onToolCallStart(int round, ChatMessage.ToolCall call) {
            System.out.println("\n[tool] " + call.name() + " " + call.argumentsJson());
        }
        @Override public void onFinish(String answer, int roundsUsed) {
            System.out.println("\n[done in " + roundsUsed + " rounds]");
        }
    })
    .build();

String answer = agent.run("北京天气怎么样？");
```

说明：

- 中间的工具轮次如果模型先说了话再调工具，也会回调 `onContentDelta`。前端只想展示
  最终答复时，按 `round` 区分，或者等 `onFinish` 再定稿。
- 自定义 `LLMClient` 只需要实现 `chat`；`chatStream` 默认会退化成一次普通调用，把完整
  内容作为单个 delta 抛出。要真流式就覆写 `chatStream`。
- `OpenAICompatClient` 会按 `tool_calls[].index` 聚合 id / name / arguments 分片；
  如果网关忽略 `stream=true` 直接返回整段 JSON，会自动降级解析。
- 流式请求同样受 `timeoutSeconds`（默认 120s）限制，长回答或慢模型记得调大。
- 流式只改变输出方式，不影响 Agent 循环、工具调用和会话存储。两者可以同时开启；
  流式增量只用于展示，落库的始终是完整 transcript。

## 多轮对话与上下文存储

### 快速接入

```java
ConversationStore store = new InMemoryConversationStore(); // 可以根据需求接入存储方式

Agent agent = Agent.builder(llm)
    .systemPrompt("你是一个助手")
    .conversationStore(store)
    .build();

String a1 = agent.run("user-42", "我叫小明，住在杭州");
String a2 = agent.run("user-42", "我住在哪？");   // 自动带上上一轮历史
```

`conversationId` 是你自己的业务会话 id（用户 id、聊天窗口 id 都可以）。不传 id 的
`agent.run(input)` 仍然是无状态的一次性调用；传了 id 但没配 `conversationStore`
会直接抛 `IllegalStateException`，避免“以为记住了其实没记”。

### 存储语义（很重要）

- **不存 system prompt**。每次运行都用当前 Agent 配置重新拼 system，线上改提示词立即生效。
- **只保存 user / assistant / tool 消息**。工具 schema 也不存，每次运行以当前 `tool(...)` 为准。
- **只有完整跑完才保存**。LLM 抛异常或网络中断时不落库，避免把半截对话写进上下文。
- **加载时会清洗不完整尾巴**。未回答的 user、缺 tool 结果的 assistant、半个工具链，都会被
  截断到最后一个完整 assistant；残留的 system 会被丢弃。
- **同一个 conversationId 的并发请求要自己串行化**。库不做分布式锁；同一会话并发跑两次
  会互相覆盖。常见做法是按 id 加本地锁 / Redis 锁，或者一个会话同一时间只允许一个请求。
- **历史会无限增长**。上下文窗口、摘要压缩、按轮数裁剪都是业务策略，库不替你做。

### MongoDB 实现

把对话存成一条文档，`messages` 是数组：

```java
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import io.github.kfagent.conversation.ConversationCodec;
import io.github.kfagent.conversation.ConversationStore;
import io.github.kfagent.message.ChatMessage;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;

public final class MongoConversationStore implements ConversationStore {

    private final MongoCollection<Document> collection;

    public MongoConversationStore(MongoCollection<Document> collection) {
        this.collection = collection;
    }

    @Override
    public List<ChatMessage> load(String conversationId) {
        Document doc = collection.find(eq("_id", conversationId)).first();
        if (doc == null) return List.of();
        return ConversationCodec.fromWire(doc.getList("messages", Document.class));
    }

    @Override
    public void save(String conversationId, List<ChatMessage> messages) {
        List<Document> docs = new ArrayList<>();
        for (Map<String, Object> wire : ConversationCodec.toWire(messages)) {
            docs.add(new Document(wire));
        }
        collection.updateOne(
                eq("_id", conversationId),
                combine(set("messages", docs), set("updatedAt", new Date())),
                new UpdateOptions().upsert(true));
    }
}
```

> MongoDB 单文档上限 16 MB。对话特别长时，改成“一条消息一条文档”（按
> `conversationId + seq` 存），或者先裁剪 / 摘要再写入。

### 按轮数裁剪（可选）

```java
// import io.github.kfagent.message.Role;

static List<ChatMessage> keepRecentTurns(List<ChatMessage> messages, int maxTurns) {
    int seen = 0;
    for (int i = messages.size() - 1; i >= 0; i--) {
        if (messages.get(i).role() == Role.USER && ++seen == maxTurns) {
            return new ArrayList<>(messages.subList(i, messages.size()));
        }
    }
    return new ArrayList<>(messages);
}
```

在自定义 store 的 `save` 里先裁剪再落库。从 `user` 处开始截断，可以保证不会把
`assistant(tool_calls)` 和后续 `tool` 消息拆散。

如果要做摘要压缩，建议把摘要存在自己的业务字段里，构造 Agent 时拼进 system prompt；
不要直接往消息数组里塞一条“摘要 user 消息”，那会破坏 user / assistant 的交替结构，
加载时会被清洗掉。

## 和 Spring Boot 一起用

不需要 Starter。Spring Boot 3 要求 JDK 17，正好是同一个下限：

```java
@Configuration
class AgentBeans {

    @Bean
    LLMClient llmClient(@Value("${agent.api-key}") String apiKey) {
        return OpenAICompatClient.builder()
                .baseUrl("https://api.deepseek.com/v1")
                .apiKey(apiKey)
                .model("deepseek-chat")
                .build();
    }

    @Bean
    Agent agent(LLMClient llm, OrderTools orderTools) {
        return Agent.builder(llm)
                .systemPrompt("你是订单助手，需要查数据时调用工具，不要编造。")
                .tools(MethodTool.all(orderTools))
                .conversationStore(new InMemoryConversationStore()) // 单机 demo；生产换 Redis
                .build();
    }
}
```

`Agent` 本身不持有每次运行的中间状态，`run()` 里会新建 `AgentRunner`，可以直接做成单例 Bean。
真正需要你保证的是同一个 `conversationId` 不被并发写，原因见上面的存储语义。

## 已知限制

- 同一轮 LLM 返回多个 `tool_calls` 时，工具是**串行**执行的，没有并发。
- 不内置重试 / 退避。网络抖动要不要重试、重试几次，交给 HTTP 层或业务层决定。
- 不做上下文窗口管理。历史太长会顶到模型上限，需要自己裁剪或摘要。
- MiniJson 只覆盖 Chat Completions 需要的 JSON 子集，不支持注解、日期类型、复杂泛型。
- 没有内置 metrics / tracing。需要的话用 `AgentListener` 接 Micrometer / OpenTelemetry。
- 同一个 `conversationId` 的并发写会互相覆盖，库不提供分布式锁。
- 不是 RAG 框架，也不打算做。要接知识库，检索完把片段拼进 prompt 就行。

## 包结构

```
io.github.kfagent
├── core          Agent / AgentRunner / AgentConfig / AgentResult / AgentListener
├── llm           LLMClient / OpenAICompatClient（含 SSE 流式解析）
├── tool          Tool / ToolSpec / ToolContext / SchemaBuilder / MethodTool / @ToolMethod
├── message       ChatMessage / Role
├── conversation  ConversationStore / InMemoryConversationStore / ConversationCodec
└── json          MiniJson（手写的迷你 JSON，别学它，够用就行）
```

更细的设计取舍见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 构建与自测

```bash
mvn -DskipTests package
```

产物是 `target/kfagent-core-1.0.1.jar`（默认还会带一个 sources jar）。本地装到自己的 m2：

```bash
mvn -DskipTests install
```

需要 sources / javadoc jar（发布用）：

```bash
mvn -DskipTests -Prelease-artifacts package
```

离线自测（Fake LLM / 本地 HttpServer，不联网）随 `package` 一起编译，可直接运行：

```bash
java -cp "target/test-classes:target/classes" io.github.kfagent.core.AgentLoopSelfTest
java -cp "target/test-classes:target/classes" io.github.kfagent.llm.StreamingSelfTest
```

Windows 下 classpath 分隔符用的是 `;`。

## Roadmap

- [x] Agent 循环 / 工具调用 / 最大轮次收尾
- [x] OpenAI 兼容客户端 + SSE 流式
- [x] 会话持久化 SPI + 内存实现
- [x] `@ToolMethod` 注解包装
- [ ] 同一轮工具并发执行（可开关）
- [ ] 结构化输出（`response_format`）
- [ ] 更多可运行示例：Spring Boot、RAG、Redis 会话
- [ ] 可选的 JSON 后端 SPI（默认仍然零依赖）

有想要的特性先开 issue 聊场景，别直接提一个大 PR。

## 常见问题

为什么不用 Jackson？为什么工具参数名变成了 `arg0`？怎么接 Ollama？能不能上生产？
这些都整理在 [docs/FAQ.md](docs/FAQ.md)。

## 参与贡献

提 PR 前先看 [CONTRIBUTING.md](CONTRIBUTING.md)。这个项目最硬的约束是**不引入第三方依赖**，
新功能尽量在现有 API 上加参数，而不是再加一层抽象。

- Bug / 需求：[Issues](https://github.com/KaifengGuo-dot/kfagent-core/issues)
- 使用问题：[Discussions](https://github.com/KaifengGuo-dot/kfagent-core/discussions)
- 版本变化：[CHANGELOG.md](CHANGELOG.md)
- 安全问题：不要公开提 issue，走 [SECURITY.md](SECURITY.md)

## License

[MIT](LICENSE) © 2026 郭凯峰
