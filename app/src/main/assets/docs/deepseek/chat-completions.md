# Chat Completions

DeepSeek Chat Completions API，兼容 OpenAI 格式。

## 端点

```
POST https://api.deepseek.com/chat/completions
```

- BASE URL（OpenAI 格式）：`https://api.deepseek.com`
- BASE URL（Anthropic 格式）：`https://api.deepseek.com/anthropic`

## 模型

| 模型 ID | 上下文 | 最大输出 | 并发 |
|---|---|---|---|
| `deepseek-v4-flash` | 1M | 384K | 2500 |
| `deepseek-v4-pro` | 1M | 384K | 500 |

> 旧模型名 `deepseek-chat` / `deepseek-reasoner` 将于 2026/07/24 23:59 弃用，分别对应 `deepseek-v4-flash` 的非思考与思考模式。**不存在 `deepseek-coder` 模型。**

## 请求参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| model | string | 是 | `deepseek-v4-flash` 或 `deepseek-v4-pro` |
| messages | array | 是 | 消息数组，每条含 `role`（system/user/assistant/tool）和 `content` |
| temperature | number | 否 | 采样温度，0-2，默认 1。**思考模式不支持** |
| top_p | number | 否 | nucleus sampling，0-1，默认 1。**思考模式不支持** |
| max_tokens | integer | 否 | 最大生成 token 数 |
| stream | boolean | 否 | 是否流式返回，默认 false |
| stream_options | object | 否 | 流式选项，`{"include_usage": true}` 在末块返回用量 |
| thinking | object | 否 | 思考模式开关，`{"type":"enabled"}` 或 `{"type":"disabled"}`，默认 enabled |
| reasoning_effort | string | 否 | 思考强度，`"high"` 或 `"max"`（`low`/`medium`→`high`，`xhigh`→`max`） |
| response_format | object | 否 | `{"type":"json_object"}` 启用 JSON Output |
| tools | array | 否 | 工具定义，见 `function-calling.md` |
| tool_choice | string/object | 否 | 工具选择策略，`"auto"`/`"none"`/指定函数 |
| stop | string/array | 否 | 停止序列，最多 16 个 |
| seed | integer | 否 | 随机种子 |

> `frequency_penalty` / `presence_penalty` 已不再支持（FIM 端点已废弃，Chat 端点未列入支持）。

## 响应

```json
{
  "id": "chatcmpl-xxx",
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "content": "...",
      "reasoning_content": "..." 
    },
    "finish_reason": "stop"
  }],
  "usage": {
    "prompt_tokens": 10,
    "completion_tokens": 20,
    "total_tokens": 30,
    "prompt_cache_hit_tokens": 0,
    "prompt_cache_miss_tokens": 10,
    "completion_tokens_details": {"reasoning_tokens": 0}
  }
}
```

## finish_reason 取值

- `stop`：正常结束或遇到 stop 序列
- `length`：达到 max_tokens 或上下文长度截断
- `tool_calls`：模型请求调用工具
- `content_filter`：内容过滤触发
- `insufficient_system_resource`：后端推理资源受限，请求被打断

## 用量字段

- `prompt_tokens` = `prompt_cache_hit_tokens` + `prompt_cache_miss_tokens`
- `completion_tokens_details.reasoning_tokens`：思考模式产生的思维链 token 数

## JSON Output

设置 `response_format: {"type":"json_object"}`，并在 system 或 user prompt 中包含 `json` 字样及格式样例。有概率返回空 content，需合理设置 `max_tokens` 防截断。
