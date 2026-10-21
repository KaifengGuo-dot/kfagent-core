# Contributing

感谢你愿意为 kfagent-core 提 issue 或 PR。

## 本地开发

```bash
# JDK 17+
mvn -DskipTests package

# 跑离线自测（不需要 API key）
mvn -DskipTests install
java -cp "target/classes:target/example-classes" io.github.kfagent.core.AgentLoopSelfTest
```

## 提 PR 前

1. 确认 `mvn -DskipTests package` 通过。
2. 不引入新的第三方依赖——这是这个项目的核心约束。
3. 新功能要在 README 里加最小示例。
4. 保持 API 简洁：能加一个 builder 参数就不要加新类。

## Issue 怎么写

- 想加新工具类型？说清楚场景。
- 报 bug：贴最小复现、JDK 版本、模型服务商。
- 别上来就问"能不能接 LangChain4j"——这是有意不做的事。
