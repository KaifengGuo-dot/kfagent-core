# Changelog

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [1.0.0] - 2026-10-23

第一个公开版本。

### Added

- `Agent` 门面与 `AgentRunner` 循环：LLM 调用、工具执行、结果回灌，支持最大轮次。
- `OpenAICompatClient`：兼容 OpenAI Chat Completions 协议，支持 OpenAI / DeepSeek /
  Qwen / Moonshot / 智谱 / 本地 Ollama。
- SSE 流式输出：文本增量回调，`tool_calls` 按 `index` 聚合，网关忽略 `stream`
  时自动降级。
- 工具两种写法：实现 `Tool` 接口，或用 `@ToolMethod` + `MethodTool` 反射包装。
- `SchemaBuilder`：用链式调用拼 JSON Schema。
- 会话持久化 SPI：`ConversationStore`、`InMemoryConversationStore`、
  `ConversationCodec`（JSON / wire 两种格式）。
- `AgentListener`：轮次、LLM 响应、工具开始/结束、流式增量、结束回调。
- 历史清洗：加载时截断不完整轮次，丢弃残留 system 消息。
- 离线自测：`AgentLoopSelfTest`、`StreamingSelfTest`，不联网、不需要 API key。
- GitHub Actions CI（JDK 17 / 21）、CodeQL、Release 工作流。

### Notes

- 工具在同一轮内串行执行。
- 不内置重试、上下文裁剪、RAG、分布式锁。
- `MiniJson` 只覆盖 Chat Completions 所需的 JSON 子集。

[Unreleased]: https://github.com/KaifengGuo-dot/kfagent-core/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/KaifengGuo-dot/kfagent-core/releases/tag/v1.0.0
