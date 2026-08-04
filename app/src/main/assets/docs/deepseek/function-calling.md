# Tool Calls（工具调用 / Function Calling）

DeepSeek 支持与 OpenAI 兼容的 Tool Calls，让模型调用外部工具。从 DeepSeek-V3.2 起在思考模式下也支持工具调用。

## 工具定义

在请求 `tools` 字段声明工具，每个工具含 `type`（固定 `function`）和 `function`：

```json
{
  "tools": [{
    "type": "function",
    "function": {
      "name": "render_mermaid",
      "description": "渲染 mermaid 代码为图",
      "parameters": {
        "type": "object",
        "properties": { "code": { "type": "string" } },
        "required": ["code"]
      }
    }
  }]
}
```

## 调用流程

1. 首次请求带 `tools`，模型返回 `finish_reason: "tool_calls"`，`message.tool_calls` 含要调用的工具名与参数
2. 执行工具，把结果以 `role: "tool"` 消息回传（带 `tool_call_id`）
3. 再次请求带完整历史，模型基于工具结果生成最终回复

## tool_choice

- `"auto"`（默认）：模型自行决定是否调工具
- `"none"`：禁止调工具
- `{"type":"function","function":{"name":"xxx"}}`：强制调用指定工具

## strict 模式（Beta）

strict 模式下，模型严格遵循 Function 的 JSON Schema 输出。开启方式：

1. 设置 `base_url="https://api.deepseek.com/beta"`
2. `tools` 列表中所有 `function` 均设置 `"strict": true`
3. `parameters` 中 `object` 的所有属性均需在 `required` 中，且 `additionalProperties: false`

```json
{
  "type": "function",
  "function": {
    "name": "get_weather",
    "strict": true,
    "description": "Get weather of a location.",
    "parameters": {
      "type": "object",
      "properties": {
        "location": {"type": "string"}
      },
      "required": ["location"],
      "additionalProperties": false
    }
  }
}
```

### strict 模式支持的 JSON Schema 类型

- `object` / `string` / `number` / `integer` / `boolean` / `array` / `enum` / `anyOf`
- `string` 支持 `pattern`、`format`（email/hostname/ipv4/ipv6/uuid），不支持 `minLength`/`maxLength`
- `number`/`integer` 支持 `const`/`default`/`minimum`/`maximum`/`exclusiveMinimum`/`exclusiveMaximum`/`multipleOf`
- `array` 不支持 `minItems`/`maxItems`
- 支持 `$ref` + `$def` 引用与递归结构

## 注意事项

- **参数 JSON**：`tool_calls[i].function.arguments` 是 JSON 字符串，需解析
- **流式聚合**：流式响应中 tool_call 的 name/arguments 会分多个 delta 到达，需按 index 累积
- **思考模式下的拼接**：两个 user 之间若 assistant 做了工具调用，其 `reasoning_content` 需参与上下文拼接
- **失败处理**：工具执行失败应回传错误信息让模型自行调整，或由上层中断回路
- **单轮单工具**：本项目 v1.0 实现约束每轮只处理第一个 tool_call，避免并发复杂度
