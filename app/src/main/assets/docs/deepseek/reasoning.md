# 思考模式（Thinking Mode）

DeepSeek V4 模型支持思考模式：在输出最终回答之前，模型先输出一段思维链内容（`reasoning_content`），以提升最终答案的准确性。

## 模型

| 模型 ID | 说明 |
|---|---|
| `deepseek-v4-flash` | 高并发 / 快速响应，支持非思考与思考模式（默认开启） |
| `deepseek-v4-pro` | 更强推理，支持非思考与思考模式（默认开启） |

> 兼容期：旧模型名 `deepseek-chat`（→ flash 非思考）、`deepseek-reasoner`（→ flash 思考）将于北京时间 2026/07/24 23:59 弃用。**不存在 `deepseek-coder` 模型。**

## 控制参数（OpenAI 格式）

| 用途 | 参数 |
|---|---|
| 思考模式开关 | `"thinking": {"type": "enabled"}` 或 `{"type": "disabled"}` |
| 思考强度控制 | `"reasoning_effort": "high"` 或 `"max"` |

- 默认思考开关为 `enabled`
- 对普通请求，默认 effort 为 `high`；对复杂 Agent 类请求（Claude Code、OpenCode 等），effort 自动设置为 `max`
- 出于兼容考虑，`low`/`medium` 会被映射为 `high`，`xhigh` 会被映射为 `max`
- 使用 OpenAI SDK 时，`thinking` 需通过 `extra_body` 传入：

```python
response = client.chat.completions.create(
    model="deepseek-v4-pro",
    reasoning_effort="high",
    extra_body={"thinking": {"type": "enabled"}}
)
```

## 输入输出约束

- 思考模式**不支持** `temperature`、`top_p`、`presence_penalty`、`frequency_penalty`。设置不会报错，但也不会生效。
- 思维链内容通过 `reasoning_content` 字段返回，与 `content` 同级：

```json
{
  "message": {
    "role": "assistant",
    "content": "最终答案",
    "reasoning_content": "让我一步步分析…首先…然后…所以…"
  }
}
```

## 多轮对话拼接规则

- 两个 `user` 消息之间，若模型**未进行工具调用**，则中间 `assistant` 的 `reasoning_content` 无需参与上下文拼接（传入会被服务端忽略）。
- 两个 `user` 消息之间，若模型**进行了工具调用**，则中间 `assistant` 的 `reasoning_content` 需参与上下文拼接。
- 流式场景：delta 中会先推送 `reasoning_content` delta，推送完毕后再推送 `content` delta。

## 工具调用

从 DeepSeek-V3.2 起，思考模式支持工具调用。详见 `function-calling.md`。

## FIM 补全

FIM 补全（Beta）**仅在非思考模式支持**。思考模式下不可用 FIM。

## 适用场景

- 数学证明与计算
- 复杂逻辑推理
- 算法设计与复杂度分析
- 多步骤代码调试
- 不适用：简单问答、翻译、改写（关闭 thinking 更快更省）
