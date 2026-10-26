# Contributing

感谢你愿意为 kfagent-core 提 issue 或 PR。

## 本地开发

要求 JDK 17+、Maven 3.8+。

```bash
git clone https://github.com/KaifengGuo-dot/kfagent-core.git
cd kfagent-core
mvn -B -DskipTests package
```

跑离线自测（不需要 API key，不联网）：

```bash
# Linux / macOS
java -cp "target/test-classes:target/classes" io.github.kfagent.core.AgentLoopSelfTest
java -cp "target/test-classes:target/classes" io.github.kfagent.llm.StreamingSelfTest

# Windows
java -cp "target/test-classes;target/classes" io.github.kfagent.core.AgentLoopSelfTest
java -cp "target/test-classes;target/classes" io.github.kfagent.llm.StreamingSelfTest
```

要连真实模型时，用环境变量传 key，不要写进代码。

## 提 PR 前

1. `mvn -B -DskipTests package` 通过。
2. 两个离线自测都通过。
3. 新功能在 README 里加了最小示例。
4. 没有引入第三方依赖。
5. 没有提交 API Key、内网地址、真实用户数据。
6. public API 有 Javadoc，行为变化写进 `CHANGELOG.md`。

PR 描述里写清楚：改了什么、为什么改、怎么验证的，附上截图或日志。

## Issue 怎么写

- 想加新工具类型？说清楚业务场景，不要只给一个 API 设计。
- 报 bug：贴最小复现、JDK 版本、模型服务商、完整异常堆栈。
- LangChain4j 集成不在范围内，原因见 [FAQ](docs/FAQ.md#为什么不用-langchain4j--spring-ai)。
- 安全问题不要公开提 issue，走 [SECURITY.md](SECURITY.md)。

## 行为准则

参与讨论即表示同意 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。
