# DeepCoder Skill 系统设计 v1.2

> **状态**：设计稿（待评审）
> **范围**：定义 Skill 系统的能力边界、数据模型、工具执行回路协议、工具沙箱边界、MVP 分阶段计划
> **设计原则**：轻量起步、可演进；不引入编排状态机、不引入多专家调度；一个 skill = 一次"带工具箱的直通流式"
>
> **v1.2 变更**（相对 v1.1，落地 8 项深度优化决策）：
> 9. v1.0 只支持单 tool_call/assistant 消息（强制 tools 数组只声明 1 个工具），规避并行执行复杂性
> 10. 工具参数在 ToolExecutor 入口统一校验（按 ToolSpec.parameters JSON Schema），校验失败直接返回 Failure
> 11. 持久化的 tool 消息加载历史时合并进折叠卡片（与实时调用展示一致，所见即所得）
> 12. skill.systemPrompt 完全覆盖用户全局 systemPrompt（选中 skill 后全局 prompt 失效）
> 13. 工具结果做会话级内存缓存（LRU，按工具名+args 哈希），attached 文件按 mtime 失效
> 14. 支持 `@skill_name` 临时切换本条消息的 skill（不改 currentSkillId），实现轻量链式调用
> 15. 可观测性：AppLogger 日志 + 折叠卡片显示调用详情（name/args/结果/耗时）
> 16. 内置 skill 不版本化，skillId 是稳定标识，prompt 升级后旧会话按 id 查最新 prompt 渲染 chip
>
> **v1.1 变更**（相对 v1.0，落地 8 项评审决策）：
> 1. 会话记 `currentSkillId`，加载历史时动态过滤旧 system 消息（避免 system 堆积冲突）
> 2. tool + assistant(tool_calls) 消息完整持久化（保证多轮连续工具调用上下文不丢）
> 3. 回路中间 API 失败：保留已完成工具卡片 + 提供从失败点重试（不丢中间进度）
> 4. tool_call 硬阈值定 5 次（v1.0 保守值，Phase 3 后视实测调整）
> 5. 工具调用结果用折叠卡片展示（工具名 + 摘要，点击展开详情）
> 6. render_mermaid 进 Phase 1（explain_code 直接能出架构图，体验优先）
> 7. fetch_doc 用内置文档快照（assets Markdown，离线可用，随版本更新）
> 8. 会话内切换 skill 时消息打 skill tag（小 chip 标识，支持回看与按 skill 过滤）

---

## 0. 设计动机与教训

### 0.1 历史教训
- v1.1 ~ v1.4 的 Orchestrator + 警察专家系统在实际使用中**效果不达预期**：调用链长、API 开销大、状态机脆弱、回路过深易死锁
- 本设计**明确规避**：不引入 FSM、不引入专家调度、不引入多轮升级、不做"模型决策+我们编排"的复杂回路

### 0.2 核心定位
Skill = **能力声明**（system prompt + tools 契约 + 输出契约）+ **工具执行回路**（标准 function calling）。
- 模型自主决定何时调工具，App 只负责执行工具并把结果回传
- 比 Orchestrator 更轻、更标准（OpenAI/DeepSeek function calling 协议）
- 复用已有的 DTO/parser/model 底座（详见 §1.3）

### 0.3 与已删除系统的边界
| 维度 | 已删除 Orchestrator/警察 | Skill 系统 v1.0 |
|------|-------------------------|-----------------|
| 决策方 | App 编排（FSM 状态机） | 模型自主（function calling） |
| 调用粒度 | 多专家多步重组队 | 单 skill 单工具回路 |
| 回路深度 | 可升级重组队（最多 3 轮） | 硬阈值 5 次 tool_call |
| 状态管理 | EscalationTracker 多字段 | 无（仅历史消息 + tool_call_id） |

---

## 1. 技术底座核查

### 1.1 已就绪（无需新建）
| 层 | 位置 | 状态 |
|----|------|------|
| 请求 DTO | `ChatCompletionRequest.tools: List<ToolDto>?` | ✅ 就绪 |
| 响应 DTO | `ChatMessageDto.toolCalls` / `ChoiceDto.toolCalls` | ✅ 就绪 |
| 流式 DTO | `ChunkDeltaDto.toolCalls` / `ChunkFunctionCallDto` | ✅ 就绪 |
| 领域模型 | `ToolCall(id, name, arguments)` | ✅ 就绪 |
| 流式事件 | `ChatStreamEvent.ToolCallDelta(index, nameDelta, argsDelta)` | ✅ 就绪 |
| SSE 解析 | `SseFlowParser` 已解析 `tool_calls` delta | ✅ 就绪 |
| finish_reason | 已支持 `tool_calls` 判断 | ✅ 就绪 |

### 1.2 关键缺口（需补全）
1. **ToolCall 流式聚合**：当前 `ToolCallDelta` 只把 `argsDelta` 当文本追加到 assistant 文本，未聚合成完整 `ToolCall` 对象
2. **工具执行回路**：无"解析完整 ToolCall → 本地执行 → 以 tool 角色回传 → 再次调 API"的循环
3. **Tool 角色消息**：领域模型 `ChatRole` 与 DTO 可能缺 `TOOL` 角色，需核查
4. **Skill 数据模型**：无 Skill / ToolSpec 抽象
5. **Skill 注册与选择**：无内置 skill 库与 UI 选择器

### 1.3 DeepSeek function calling 协议要点
- `tools` 字段：`[{type:"function", function:{name, description, parameters: JSONSchema}}]`
- 响应 `tool_calls`：`[{id, type:"function", function:{name, arguments: JSON字符串}}]`
- 工具结果回传：`{role:"tool", tool_call_id, content}`
- 流式：`delta.tool_calls[].function.arguments` 分片到达，需按 index 聚合
- 模型继续生成直到 `finish_reason: stop`

---

## 2. Skill 数据模型

### 2.1 核心结构
```kotlin
data class Skill(
    val id: String,                    // 唯一标识，如 "code_review"
    val name: String,                  // 显示名，如 "代码审查"
    val description: String,           // 一句话描述
    val icon: String,                  // 图标 key（Material Icon 名）
    val category: SkillCategory,       // 分类
    val systemPrompt: String,          // 角色 system prompt
    val tools: List<ToolSpec>,         // 声明可用工具（可空，纯 prompt skill 无工具）
    val outputContract: OutputContract,// 输出格式约束
    val enabled: Boolean = true,       // UI 开关
    val builtIn: Boolean = true        // 内置不可删（用户自定义可删）
)

enum class SkillCategory {
    CODE_MODIFY,    // 代码改动类
    CODE_UNDERSTAND,// 理解产出类
    CODE_GENERATE,  // 新生成类
    QA_ASSIST       // 问答辅助类
}

data class ToolSpec(
    val name: String,                  // 工具名，如 "fetch_doc"
    val description: String,           // 工具描述（给模型看）
    val parameters: JsonSchema         // 参数 JSON Schema
)

data class OutputContract(
    val format: OutputFormat,          // MARKDOWN / DIFF / JSON / MERMAID
    val requiredFields: List<String>,  // 必填字段（如审查的 severity）
    val styleHints: String             // 风格提示（如"必须先思路后代码"）
)

enum class OutputFormat { MARKDOWN, DIFF, JSON, MERMAID }
```

### 2.2 设计要点
- **Skill 与工具解耦**：一个工具可被多个 skill 声明使用（如 `save_snippet` 可被 gen_function / refactor 共用）
- **tools 可空**：纯 prompt skill（如 explain_code）不带工具，退化为 v1.5 的直通流式 + 强化 system prompt
- **outputContract 不强制**：只是 prompt 提示，不做事后校验（吸取警察层 L1 硬规则过度复杂的教训）
- **builtIn 标记**：内置 skill 不可删但可禁用；用户自定义 skill 可删

### 2.3 序列化与持久化
- 内置 skill：编译期常量（Kotlin object），不持久化
- 用户自定义 skill：DataStore JSON 存储（Phase 4）
- 当前会话选中的 skill id：存入 ChatSession（会话级），字段名 `currentSkillId`
- 每条 ChatMessage 增加 `skillId: String?` 字段（v1.1 决策 8）：记录生成该消息的 skill id，UI 渲染小 chip；加载历史时可按 skill 过滤

### 2.4 会话级 skill 切换协议（v1.1 决策 1）
- `ChatSession.currentSkillId` 随用户切换实时更新并持久化
- 加载会话历史时，**动态过滤掉所有 `role=SYSTEM` 的历史消息**，只在请求头部插入当前 `currentSkillId` 对应 skill 的 system prompt
- 好处：历史里堆积多少旧 system 都不影响当前请求；切换 skill 后旧 system 自动失效
- 实现点：`buildRequest` 时构造 messages = [当前 skill system] + [过滤 system 后的历史] + [新 user]

### 2.5 system prompt 合并规则（v1.2 决策 12）
- 选中 skill 后，**skill.systemPrompt 完全覆盖**用户在设置页配置的全局 systemPrompt
- 即：请求头部 system 消息内容 = `currentSkill.systemPrompt`（用户全局 prompt 不参与拼接）
- 例外：`default_chat` skill 的 systemPrompt = 用户全局 systemPrompt（default_chat 本就代表"通用对话"，应尊重用户配置）
- 切换回 default_chat → 用户全局 prompt 恢复生效
- 好处：专用 skill 的指令不被用户全局 prompt 稀释；default_chat 保留用户自定义能力

### 2.6 @skill 临时切换（v1.2 决策 14：轻量链式调用）
- 用户在输入框首位输入 `@skill_name` → 本条消息临时使用该 skill（不改变会话 currentSkillId）
- 解析规则：正则 `^@([a-z_]+)\s` 匹配，如 `@code_review 帮我看这个文件`
- 匹配失败（skill 不存在）→ 按普通文本处理，用当前 currentSkillId
- 本条消息的 assistant 回复 skillId 标记为临时 skill id（chip 显示临时 skill）
- 下一条消息仍用 currentSkillId（临时切换只生效一次）
- 用途：轻量实现"先 review 再 refactor"——用户 `@code_review` 看结果后，`@refactor` 继续，无需进选择器切换

### 2.7 内置 skill 版本策略（v1.2 决策 16）
- 内置 skill **不版本化**，skillId 是稳定标识（如 `code_review` 永远指向最新版）
- prompt 升级后，旧会话历史消息仍按 skillId 查**当前最新** prompt 渲染 chip（不回溯旧 prompt）
- 好处：无需维护版本历史，代码简单；用户看到的 skill 信息始终最新
- 取舍：旧会话的 system prompt 已被 §2.4 过滤掉不进请求，所以 prompt 升级不影响旧会话的后续对话；只影响 chip 显示名/图标（用最新版即可）

---

## 3. 工具执行回路协议（核心）

### 3.1 回路主流程
```
[用户消息 + skill.systemPrompt + skill.tools]
        ↓
   API(stream) ──→ finish_reason?
        ↓
   ├─ stop ─────────────────→ 输出最终回复，结束
   ├─ tool_calls ──→ 聚合完整 ToolCall(name, args)
        ↓
   本地执行工具（沙箱内）
        ↓
   追加 {role:tool, tool_call_id, content:结果} 到历史
        ↓
   再次调 API(stream) ←─┐
        ↓                 │
   (循环) ──────────────┘
        ↓
   [硬阈值：tool_call 次数 ≥ 5 → 强制结束，提示用户]
```

### 3.2 ToolCall 流式聚合规则
- `ToolCallDelta` 按 `index` 累积 `nameDelta` / `argsDelta`
- 当 `finish_reason=tool_calls` 时，聚合出完整 `List<ToolCall>`
- **v1.2 决策 9：v1.0 只支持单 tool_call/assistant 消息**——若模型返回多个 tool_call，只执行第一个，其余忽略并在日志记录（避免并行执行复杂性；Phase 3+ 视实测放开）
- **不流式展示 tool_call 的 arguments 给用户**（避免显示半截 JSON），而是在 UI 显示"正在调用工具 X..."

### 3.3 工具参数校验（v1.2 决策 10：入口统一校验）
- 在 `ToolExecutor.execute` 入口处，按 `ToolSpec.parameters`（JSON Schema）校验 `args`
- 校验内容：必填字段存在性、类型匹配、枚举值合法
- 校验失败 → 直接返回 `Failure("参数校验失败：<具体错误>")`，不调实际工具实现
- 模型收到 Failure 后自行决策（修正参数重试 / 换工具 / 直接回答）
- 实现：轻量 JSON Schema 校验器（不引入完整 JSON Schema 库，只校验 type/required/enum 三种约束即可覆盖 v1.0 工具）

### 3.4 工具结果缓存（v1.2 决策 13：会话级内存缓存）
- 缓存 key = `工具名 + args 的稳定哈希`（如 `read_attached_file:path=/a/b.kt`）
- 缓存 value = `ToolResult`
- 缓存范围：**会话级内存 LRU**（容量 16，会话结束清理；不跨会话）
- 失效策略：
  - `read_attached_file`：按文件 mtime 失效（mtime 变了重新读）
  - `render_mermaid`：code 不变即命中（mermaid 渲染结果稳定）
  - `fetch_doc` / `search_history`：会话内不失效（文档快照/历史不变）
  - `save_snippet`：不缓存（写入操作，重复执行无害但应避免）
- 命中缓存 → 直接返回结果，日志标记 `[cache hit]`，UI 卡片也标注"（缓存）"

### 3.5 工具结果回传格式
```json
{
  "role": "tool",
  "tool_call_id": "<原 ToolCall.id>",
  "content": "<工具执行结果，字符串>"
}
```
- content 必须是字符串（JSON 字符串化）
- 失败时 content 写错误信息，让模型自行决策（如换工具或直接回答）

### 3.6 硬阈值（唯一保留的"硬规则"）
- 单次会话内 tool_call 总次数 ≥ **5**（v1.1 决策 4）→ 强制结束，UI 提示"工具调用次数过多，已停止"
- **不做** attempted_approaches 去重、不做 escalation_count、不做相似度检测
- 这是吸取警察层过度规则的教训：只留一个最简单的防失控阈值
- Phase 3 工具丰富后视实测调整（可能调到 8）

### 3.7 回路终止条件
- `finish_reason = stop` → 正常结束
- tool_call 次数 ≥ 5 → 强制结束
- API 失败 → 进入失败处理流程（见 §3.9），不自动重试回路
- 用户主动取消 → cancel streamingJob

### 3.8 tool 消息持久化（v1.1 决策 2）
回路中产生的消息**完整持久化**到会话历史，保证多轮连续工具调用上下文不丢：
- assistant 消息（含 `tool_calls` 字段）→ 持久化，`skillId` 标记当前 skill
- tool 角色消息（`{role:tool, tool_call_id, content}`）→ 持久化
- 下次加载会话时，这些消息原样进入请求 messages，模型能看到完整工具调用上下文
- 持久化格式：扩展 ChatMessage 支持 `role=TOOL` + `toolCallId` 字段（领域模型 + Room 实体 + DTO 三层同步）

### 3.9 回路中间 API 失败处理（v1.1 决策 3）
回路已执行 N 次工具后第 N+1 次 API 调用失败时：
- **保留**已完成 N 次工具调用的折叠卡片（不丢弃中间进度）
- 最终回复区显示错误："工具调用中断：网络错误（已完成 N/计划 5 次工具调用）"
- 提供"重试"按钮：从失败点续调（重建 messages = [当前 skill system] + [过滤 system 的历史含已完成 tool 消息] + [原 user]，重新发起 API 调用）
- **不自动重试**：避免循环放大，由用户主动点重试
- 重试时重置该次 API 调用的 streamingJob，但不重置 tool_call 计数器（继续累计）

---

## 4. 工具沙箱边界

### 4.1 设计原则
Android 端**不能**像桌面端任意读文件/跑命令。工具集严格限制在 App 沙箱能力范围内，**不做**：
- 任意文件系统读写（权限 + 安全风险）
- shell 命令执行（同上）
- 网络任意请求（除官方文档 fetch）

### 4.2 内置工具清单（v1.0）
| 工具名 | 用途 | 参数 | 输出 | 使用 skill |
|--------|------|------|------|-----------|
| `read_attached_file` | 读用户导入到 App 沙箱的文件 | `{path: String}` | 文件内容字符串 | explain/refactor/review |
| `search_history` | 在历史会话语义搜索 | `{query: String, limit: Int}` | 匹配片段列表 | qa_assist |
| `fetch_doc` | 拉 DeepSeek API 官方文档片段 | `{topic: String}` | 文档片段 | api_qa |
| `render_mermaid` | mermaid 代码渲染成图 | `{code: String}` | 图片路径 | explain/architecture |
| `save_snippet` | 生成代码存入 App 片段库 | `{title, language, code}` | 保存确认 | gen_function/refactor |

> **v1.1 决策 7**：`fetch_doc` 用**内置文档快照**实现，不实时抓取。文档以 Markdown 形式打包进 `app/src/main/assets/docs/deepseek/`，随 App 版本更新。工具执行时按 topic 关键词匹配返回相关片段。优点：离线可用、无网络依赖、无反爬风险；缺点：可能滞后官方文档（通过版本更新缓解）。

### 4.3 工具执行抽象
```kotlin
interface ToolExecutor {
    suspend fun execute(name: String, args: JsonObject): ToolResult
}

sealed class ToolResult {
    data class Success(val content: String) : ToolResult()
    data class Failure(val error: String) : ToolResult()
    data class NeedsUserAction(val prompt: String) : ToolResult()  // 如需用户确认
}
```

### 4.4 工具权限模型
- 所有工具执行**默认在后台线程**，不阻塞 UI
- `read_attached_file` 只能读 App 沙箱目录（`filesDir/attached/`），路径穿越（`../`）拒绝
- `fetch_doc` 走固定白名单域名（`api-docs.deepseek.com`）
- 不引入运行时权限弹窗（Phase 1 工具均无需危险权限）

---

## 5. 内置 Skill 清单（全场景覆盖）

### 5.1 代码改动类
| skill id | 名 | tools | 输出契约 |
|----------|----|----|---------|
| `code_review` | 代码审查 | read_attached_file | MARKDOWN + severity 字段 |
| `refactor` | 重构 | read_attached_file | DIFF 格式 |
| `fix_bug` | Bug 修复 | read_attached_file, search_history | MARKDOWN + 修复说明 |
| `write_test` | 写测试 | read_attached_file | MARKDOWN + 测试代码 |

### 5.2 理解产出类
| skill id | 名 | tools | 输出契约 |
|----------|----|----|---------|
| `explain_code` | 解释代码 | read_attached_file, render_mermaid | MARKDOWN + 可选图 |
| `translate_lang` | 转语言 | read_attached_file | MARKDOWN + 目标代码 |
| `write_docs` | 写文档 | read_attached_file | MARKDOWN |
| `commit_msg` | 生成 commit | read_attached_file | 纯文本 |

### 5.3 新生成类
| skill id | 名 | tools | 输出契约 |
|----------|----|----|---------|
| `gen_function` | 生成函数 | save_snippet | MARKDOWN + 代码 |
| `gen_class` | 生成类 | save_snippet | MARKDOWN + 代码 |
| `scaffold` | 脚手架 | save_snippet | MARKDOWN + 多文件 |

### 5.4 问答辅助类
| skill id | 名 | tools | 输出契约 |
|----------|----|----|---------|
| `api_qa` | API 问答 | fetch_doc | MARKDOWN |
| `error_explain` | 报错解读 | search_history | MARKDOWN + 原因+解决 |
| `dep_advice` | 依赖选型 | fetch_doc | MARKDOWN + 对比表 |

### 5.5 默认 Skill
- `default_chat`：无 tools，通用对话（等价于当前 v1.5 直通流式）
- App 启动时默认选中 `default_chat`，用户可切换

---

## 6. UI 设计

### 6.1 Skill 选择器
- 顶部下拉/Chip 组：显示当前 skill + 切换入口
- 长按 skill → 查看详情（prompt/tools/契约）
- 设置页：skill 管理列表（启用/禁用、查看、Phase 4 自定义）

### 6.2 工具调用展示（v1.1 决策 5：折叠卡片 + v1.2 决策 11/15）
- 流式中：不显示 tool_call arguments 原文，显示状态条"🔧 正在调用 read_attached_file..."
- 工具执行完：折叠卡片显示工具名 + 结果摘要（如"read_attached_file · 1.2KB"），点击展开详情
- **v1.2 决策 15：卡片展开显示调用详情**——工具名 / 参数 / 结果（截断）/ 耗时 / 是否缓存命中
- **v1.2 决策 11：历史会话加载时，持久化的 tool 消息合并进对应 assistant 消息的折叠卡片**（与实时调用展示一致，所见即所得；不单独渲染 tool 消息行）
- 多个工具调用：垂直堆叠多个折叠卡片
- 最终回复：正常流式渲染，位于工具卡片下方
- 回路失败时：已完成工具卡片保留，下方显示错误 + 重试按钮（见 §3.9）

### 6.3 Skill tag 展示（v1.1 决策 8）
- 每条 assistant 消息左下角渲染小 chip：图标 + skill 名（如"🔍 explain_code"）
- default_chat 不显示 chip（避免噪音）
- chip 颜色按 SkillCategory 区分：改动类红/理解类蓝/生成类绿/问答类紫
- 点击 chip → 展开该 skill 详情（prompt/tools/契约）

### 6.4 交互约束
- 切换 skill 时不清空历史（但后续消息用新 skill 的 system prompt，加载时过滤旧 system 见 §2.4）
- 一个会话内可多次切换 skill
- 历史 skill 调用记录在消息 skillId 字段（便于回看与按 skill 过滤）

---

## 7. MVP 分阶段计划

### Phase 1：工具执行回路 + 3 个 skill + mermaid（验证回路通）
- 补全 ToolCall 流式聚合（按 index 累积，finish_reason=tool_calls 时落盘；v1.2 决策 9：只执行首个 tool_call）
- 实现 ToolExecutor 抽象 + 硬阈值 5
- 实现 ToolExecutor 入口参数校验（v1.2 决策 10：轻量 JSON Schema 校验 type/required/enum）
- 实现工具结果缓存（v1.2 决策 13：会话级 LRU 容量 16）
- 扩展 ChatMessage 支持 role=TOOL + toolCallId + skillId（三层同步）
- 实现 `default_chat`（无工具）/ `explain_code`（带 render_mermaid）/ `gen_function`（无工具）
- 实现 `render_mermaid` 工具（v1.1 决策 6：提前到 Phase 1）
- Skill 选择器 UI（最小可用）+ skill tag chip
- 会话 currentSkillId 持久化 + 加载时过滤旧 system（v1.1 决策 1）
- system prompt 覆盖规则（v1.2 决策 12：skill 覆盖全局，default_chat 用全局）
- **验收**：选 explain_code → 输出含 mermaid 图 → 渲染正常；切换 default_chat → 通用对话正常

### Phase 2：1 个带文件工具的 skill（验证 function calling 端到端）
- 实现 `read_attached_file` 工具（沙箱目录 + 路径穿越防护）
- 实现 `code_review` skill（带工具）
- 工具调用折叠卡片 UI（含 v1.2 决策 15：展开显示调用详情）+ 回路失败重试按钮
- tool + assistant(tool_calls) 消息持久化 + 历史加载合并进折叠卡片（v1.2 决策 11）
- @skill 临时切换（v1.2 决策 14：输入框 @skill_name 解析）
- **验收**：上传文件 → code_review → 模型调 read_attached_file → 返回审查结果；中断后重试能续调；@refactor 临时切换生效

### Phase 3：补齐全场景 skill + 内置文档快照
- 实现 `search_history` / `save_snippet` 工具
- 实现 `fetch_doc` 工具（v1.1 决策 7：内置文档快照，assets Markdown，随版本更新）
- 补齐 13 个内置 skill（覆盖四类全场景）
- skill 管理页（启用/禁用）
- **验收**：四类场景全覆盖；api_qa 能查内置文档快照

### Phase 4：用户自定义 skill
- DataStore 存储 + 编辑 UI
- 导入/导出 JSON
- **验收**：用户可创建/编辑/删除自定义 skill

---

## 8. 风险与规避

| 风险 | 规避 |
|------|------|
| 工具回路死循环 | 硬阈值 5 次 + API 失败不重试 |
| tool_call arguments 流式聚合错乱 | 按 index 严格累积，finish_reason=tool_calls 时才落盘 |
| Android 沙箱权限问题 | 工具仅限 App 沙箱目录 + 白名单域名 |
| function calling 兼容性 | DeepSeek 兼容 OpenAI 协议，但需实测流式 tool_call 行为 |
| 复杂度回潮 | 严格守住"不做编排"底线，任何 FSM/专家调度提案一律拒绝 |

---

## 9. 评审决策点（v1.2 全部已定）

| # | 决策点 | v1.x 定论 | 章节 |
|---|--------|----------|------|
| 1 | 切换 skill 历史 system 冲突 | 会话记 currentSkillId，加载时动态过滤旧 system | §2.4 |
| 2 | tool 消息持久化 | 完整持久化 tool + assistant(tool_calls) | §3.8 |
| 3 | 回路中间 API 失败 UI | 保留已完成工具卡片 + 提供重试 | §3.9 |
| 4 | tool_call 硬阈值 | 5 次（Phase 3 后视实测调整） | §3.6 |
| 5 | 工具结果展示 | 折叠卡片（工具名 + 摘要，点击展开） | §6.2 |
| 6 | render_mermaid 时机 | Phase 1 就引入 | §7 Phase 1 |
| 7 | fetch_doc 实现 | 内置文档快照（assets Markdown） | §4.2 |
| 8 | 会话内切换 skill tag | 消息打 skill tag chip | §6.3 |
| 9 | 并行工具调用 | v1.0 只支持单 tool_call，多 call 只执行第一个 | §3.2 |
| 10 | 工具参数校验 | ToolExecutor 入口按 JSON Schema 统一校验 | §3.3 |
| 11 | tool 消息历史渲染 | 合并进对应 assistant 的折叠卡片 | §6.2 |
| 12 | system prompt 合并 | skill 覆盖全局（default_chat 例外，用全局） | §2.5 |
| 13 | 工具结果缓存 | 会话级内存 LRU（容量 16），attached 按 mtime 失效 | §3.4 |
| 14 | skill 链式调用 | @skill_name 临时切换本条消息 | §2.6 |
| 15 | 工具可观测性 | AppLogger 日志 + 卡片展开显示调用详情 | §6.2 |
| 16 | skill 版本迁移 | 不版本化，按 id 查最新 prompt 渲染 chip | §2.7 |

---

## 10. 下一步

文档评审通过后 → 进 Phase 1 开发：
1. 扩展 ChatMessage（role=TOOL / toolCallId / skillId）三层同步
2. 补全 ToolCall 流式聚合（单 tool_call 约束）
3. 实现 ToolExecutor 抽象 + 入口参数校验 + 会话级 LRU 缓存
4. 实现 render_mermaid 工具
5. 实现 default_chat / explain_code / gen_function 三个 skill
6. Skill 选择器 UI + skill tag chip
7. 会话 currentSkillId 持久化 + 加载过滤旧 system + skill 覆盖全局 system prompt
