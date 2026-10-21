# kfagent-core

> 零依赖、纯 Java 17 的 Agent 循环库。别人加一行依赖，就能在自己项目里跑带工具调用的 LLM Agent。

## 特性

- **零外部依赖**：只靠 JDK 自带的 `java.net.http.HttpClient`，JSON 是手写的迷你解析器。引入后不会有传递依赖冲突。
- **OpenAI 兼容协议**：一份客户端同时对接 OpenAI / DeepSeek / Qwen / Moonshot / 通义 / 智谱 / 本地 Ollama 的 OpenAI 兼容端口。
- **标准 Agent 循环**：`LLM → tool_calls → 执行工具 → 把结果喂回 LLM → 直到出最终答案`，带最大轮次防死循环。
- **工具两种写法**：实现 `Tool` 接口，或给普通方法加 `@ToolMethod` 注解自动反射包装。
- **可观测**：`AgentListener` 钩子暴露每一轮、每次工具调用的开始/结束。
- **JDK 17**：用了 record / switch expression / var。

## 用户怎么接入（一行依赖）

```xml
<dependency>
    <groupId>io.github.kfagent</groupId>
    <artifactId>kfagent-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

Gradle：

```groovy
implementation 'io.github.kfagent:kfagent-core:1.0.0'
```

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

## 包结构

```
io.github.kfagent
├── core        Agent / AgentRunner / AgentConfig / AgentResult / AgentListener
├── llm         LLMClient / OpenAICompatClient
├── tool        Tool / ToolSpec / ToolContext / SchemaBuilder / MethodTool / @ToolMethod
├── message     ChatMessage / Role
└── json        MiniJson（手写的迷你 JSON，别学它，够用就行）
```

## 构建

```bash
mvn -DskipTests package
```

产物是 `target/kfagent-core-1.0.0.jar`。本地装到自己的 m2：

```bash
mvn -DskipTests install
```
