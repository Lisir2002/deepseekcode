# DeepCoder · Police（问答流程警察）规格书 v2.0

> **状态**：🟡 **规格设计阶段**（v2.0 草案，自适应动态组队 + 层级反馈架构，待用户审阅拍板后进入开发）
> **生效前提**：本规格所有 Gate 条目经用户认知对齐确认后开工。
> **核心定位**：用 **prompt 工程 + 运行时硬规则** 模拟"训练后小模型"的优点，构建一个像警察指挥交通一样有秩序管理每次问答流程的决策层。**不训练任何模型**。
>
> **v2.0 重大变更**（相对 v1.0）：
> - 警察架构从"4 个流程型警察"重构为"1 个路由警察 + 12 个专家池 + 自适应动态组队"
> - 引入**层级反馈机制**：路由警察 → 组长 → 组员专家，专家可向上反馈，组长可升级回路由警察
> - 蒸馏分类从"A/B/C 三类"重构为"**三层确定性**"（L1 硬规则 / L2 prompt / L3 few-shot）
> - 拒答策略**放宽**：缩关键词清单 + 边界 case 走路由警察判定 + 软拒引导更主动
> - ControlToken **two-stage 默认开**（不再是 v1.1 优化项）
> - 验收阈值**调高**：F1 macro 0.85→0.90，15 场景及格线 13→14 分

---

## 0. 核心原则（用户最终选择）

1. **不训练**：用 prompt 工程替代 LoRA 训练，但**主动学习训练后小模型的优点**，把它蒸馏进 prompt + 运行时协议。
2. **自适应动态组队**：1 个路由警察 + 12 个专家池，路由警察根据问题特征**动态**从专家池挑选若干专家组成临时专家组（非预设固定组）。
3. **层级反馈**：路由警察 → 组长 → 组员专家；专家遇到新问题反馈组长；组长判断本组无法解决时升级回路由警察重新组队。
4. **只决策不执行**：警察/专家只输出 JSON 决策，绝不直接调 Actor API 生成代码。执行交给现有 Orchestrator。
5. **三层确定性蒸馏**（重构自 v1.0 的 A/B/C 分类）：
   - **L1 硬规则层**：运行时强约束，不可降级（JSON repair / enum 白名单 / attempts 上限 / 步数校验）
   - **L2 prompt 层**：system prompt 指令（schema / enum / 决策矩阵 / 映射表）
   - **L3 few-shot 层**：示例引导（话术 / 风格 / 错误修复映射）
   - 三层独立调优，L1 兜底 L2，L2 兜底 L3

---

## 1. 架构总览

### 1.1 警察层在系统中的位置

```
用户消息
   ↓
┌──────────────────────────────────────────────────────────┐
│  Police Layer（警察层）                                    │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐ │
│  │  ① 路由警察（Dispatcher）                              │ │
│  │     分析问题特征 → 动态组队 → 指定组长                  │ │
│  └──────────────────────┬───────────────────────────────┘ │
│                         ↓                                  │
│  ┌──────────────────────────────────────────────────────┐ │
│  │  ② 临时专家组（动态组建，2~4 人）                       │ │
│  │     组长（Team Lead）+ 组员专家（从 12 专家池挑选）      │ │
│  │     组长制定执行计划 + 分配组员任务                     │ │
│  └──────────────────────┬───────────────────────────────┘ │
│                         ↓                                  │
│  ┌──────────────────────────────────────────────────────┐ │
│  │  ③ 组员专家执行（各自 1 次调用）                        │ │
│  │     遇新问题 → 反馈组长                                 │ │
│  │     组长判断本组无法解决 → 升级回路由警察                │ │
│  └──────────────────────┬───────────────────────────────┘ │
│                         ↓                                  │
│  运行时硬规则层（L1，全程介入）                             │
│  · JSON 三层 repair · enum 白名单 · 步数校验               │
│  · 升级次数>=3 强制 BLOCKED · 关键词 guard rail（精简版）   │
│  · 优先级截断 · 状态字段注入 · attempted_approaches 去重    │
└──────────────────────────────────────────────────────────┘
   ↓ JSON 决策
┌──────────────────────────────────────────────────────────┐
│  Orchestrator（现有 6 节点 FSM，执行层）                    │
│  按专家组决策调度 Actor (DeepSeek API) 生成代码             │
└──────────────────────────────────────────────────────────┘
   ↓
执行结果 → 自检专家（组内或追加）→ DONE/RETRY/REWORK/升级/BLOCKED
```

### 1.2 与现有 Orchestrator 的关系

- 现有 [OrchestratorImpl.kt](app/src/main/java/com/deepseek/coder/data/workflow/OrchestratorImpl.kt) 的 6 节点 FSM（CLASSIFY → CLARIFY → GOVERN_CONTEXT → DECOMPOSE → EXECUTE → SELF_CHECK）**保留为执行骨架**。
- 警察层**替换**原 FSM 每个节点里的"调通用大模型 + 简单 prompt"逻辑：
  - CLASSIFY 节点 → 调路由警察（动态组队）
  - CLARIFY 节点 → 调组内 CLARIFY 专家（若组队包含）
  - GOVERN_CONTEXT 节点 → 调组内 GOVERN 专家（若组队包含）
  - DECOMPOSE 节点 → 调组长（制定执行计划）
  - EXECUTE 节点 → 调组员专家（按组长分配执行）
  - SELF_CHECK 节点 → 调组内 CHECK 专家（若组队包含，否则路由警察追加）
- 警察/专家输出统一 JSON 决策，Orchestrator 消费决策驱动状态转移。

### 1.3 调用拓扑

| 角色 | 触发节点 | 输出 | 调用次数 |
|---|---|---|---|
| ① 路由警察（Dispatcher）| CLASSIFY | `expert_team[]` / `team_lead` / `cap` / `scope` / `refuse_hint` | 1 次（two-stage 时 2 次）|
| ② 组长（Team Lead）| DECOMPOSE | `execution_plan`（任务分配 + 顺序 + 依赖）| 1 次 |
| ③ 组员专家（执行）| EXECUTE | `expert_result`（各自领域决策 + 代码骨架指令）| 每人 1 次（2~4 次）|
| ④ 自检专家（CHECK）| SELF_CHECK | `self_check_decision`（DONE/RETRY/REWORK/升级/BLOCKED）| 1 次 |
| ⑤ 反馈环（可选）| 任一节点 | `escalation`（组长→路由警察重新组队）| 0~2 次 |

**单轮调用预算**：
- 简单任务（cap=simple）：路由 1~2 + 组长 1 + 组员 1 + 自检 1 = **4~5 次**
- 中等任务（cap=medium）：路由 1~2 + 组长 1 + 组员 2~3 + 自检 1 = **5~7 次**
- 复杂任务（cap=complex/hard）：路由 2 + 组长 1 + 组员 3~4 + 自检 1 + 反馈 0~2 = **7~9 次**
- 均用 `deepseek-v4-flash` 非思考模式（决策任务不需要思考模式）。

---

## 2. 三层确定性蒸馏设计（核心创新）

将训练后小模型的优点按**确定性层级**重构为三层，每层独立调优，低层兜底高层。

### 2.1 L1 硬规则层（运行时强约束，不可降级）

模拟训练后模型的"权重级确定性"，用代码强制保证。

| 不可蒸馏能力 | L1 硬规则 |
|---|---|
| 格式 99.9% 确定性 | **JSON 三层 repair**：L1 `JSON.parse` → L2 正则抽 `{...}` + jsonrepair → L3 重试一次 → 仍失败降级 fallback |
| Enum 漂移 | **白名单校验**：解析后校验每个 enum 字段，不在白名单→最近邻映射或重试 |
| 反射式重试上限 | **`escalation_count >= 3` 强制 BLOCKED**，不依赖模型决策 |
| 步数越界 | **范围校验**：超上限→压缩或重试；低于下限→细化或重试 |
| 抗越狱/越界 | **精简版关键词 guard rail**（见 §7.1，仅高危词硬拦截）|
| 上下文爆炸 | **优先级截断**：超 token 预算按 P0→P3 顺序删 |
| 多轮状态丢失 | **状态字段注入**：每轮传 `plan_state`/`current_step`/`attempts`/`last_error`/`attempted_approaches` |
| 思路循环 | **attempted_approaches 去重**：相似度 > 0.8 强制换思路 |
| 通用模型过自信 | **"反乐观"指令** + **矩阵强制覆盖**：模型说 RETRY 但矩阵规定 REWORK → 强制 REWORK |

### 2.2 L2 prompt 层（system prompt 指令）

模拟训练后模型的"显式知识"，写进 system prompt + JSON Schema。

| 知识项 | 落地位置 | 内容 |
|---|---|---|
| 输出 Schema | 每个角色的 system prompt + `response_format: json_object` | 严格 JSON Schema，字段名/enum/required 锁死 |
| 意图枚举 | 路由警察 system prompt | `CODE_GENERATE / CODE_EXPLAIN / CODE_REFACTOR / CODE_FIX_BUG / CODE_TRANSLATE / CODE_REVIEW / DESIGN_ARCH / WRITE_TEST / ADD_DEPENDENCY / GENERAL_CHAT / NEEDS_CLARIFICATION`（11 类）|
| CAP 难度先验 | 路由警察 system prompt | `simple(60%) / medium(25%) / complex(12%) / hard(3%)` + 判定标准 |
| 专家能力清单 | 路由警察 system prompt | 12 个专家的 ID + 能力描述 + 适用场景（见 §4）|
| 粒度→步数+深度表 | 组长 system prompt | COARSE 2-3步只给what / MEDIUM 5-7步给what+why / FINE 10-15步给what+why+edge+test |
| 重试决策矩阵 | 自检专家 system prompt | `syntax_error@1→RETRY / syntax_error@2→REWORK / test_failure@any→REWORK / timeout@any→REWORK / attempts>=3→BLOCKED / confidence<0.4→BLOCKED` |
| 上下文优先级表 | GOVERN 专家 system prompt | P0必留 / P1尽量留 / P2可压缩 / P3可删 |
| 拒答边界 | 路由警察 system prompt | IN: 代码生成/解释/审查/重构/调试/翻译代码/写测试；OUT: 小说/情感/医疗/法律/政治（仅高危硬拒，其余走软拒引导）|

### 2.3 L3 few-shot 层（示例引导）

模拟训练后模型的"行为模式"，用 few-shot 引导风格与话术。

| 行为 | few-shot 示例 |
|---|---|
| 拒答话术 | "这超出代码助手范围，但如果你想用 LaTeX 写简历模板我可以…" |
| 错误→修复映射 | NPE→null check / IndexError→边界检查 / timeout→换算法 |
| 上下文裁剪偏好 | 摘要模板 `"先前尝试：方案A（失败：编译错，缺 import）。当前约束：性能<100ms，Python。"` |
| 风格锚定 | 按 simple:medium:complex:hard = 6:3:1:0 比例给，避免对罕见类过敏 |
| 主动澄清触发 | "必须澄清条件"清单 + 默认 ASK 兜底 |
| 组队示例 | "写登录模块带测试" → [ARCH, GEN, TEST] 组长 ARCH |

### 2.4 承认的天花板（prompt 版达不到训后版的指标）

| 指标 | 训后版 | prompt 版目标 | 差距 |
|---|---|---|---|
| JSON 合法率 | 99.9%+ | 99%（+ L1 兜底至 99.9%）| 多轮漂移不可消除 |
| Confidence 校准 | 真校准 | 分桶近似 | 本质受限 |
| 决策延迟 | 反射式 1-2 token | 1 次完整 API 调用 | 不可比 |
| 抗越狱 | 训后稳 | 精简关键词+L2 校验补 | RLHF 抗拒答 |
| 调用次数 | 1 次（内嵌）| 4~9 次（多角色）| 多角色协作的代价 |

---

## 3. 路由警察详细规格（Dispatcher）

**职责**：分析问题特征，从 12 专家池动态挑选 2~4 人组成临时专家组，指定组长。

**触发节点**：CLASSIFY

**输入**：
- 用户消息（text）
- 历史摘要（最近 3 轮，由 GOVERN 专家预处理）
- 当前 control token（granularity / scope，若用户已设置）

**System Prompt 要点**：
```
你是 DeepCoder 的路由警察。只输出 JSON，不输出任何其他内容。

任务：分析用户问题，从 12 个专家池中动态挑选 2~4 人组成临时专家组，并指定组长。

意图枚举（严格匹配，大小写敏感）：
- CODE_GENERATE / CODE_EXPLAIN / CODE_REFACTOR / CODE_FIX_BUG
- CODE_TRANSLATE / CODE_REVIEW / DESIGN_ARCH / WRITE_TEST
- ADD_DEPENDENCY / GENERAL_CHAT / NEEDS_CLARIFICATION

12 个专家池（严格匹配 ID）：
- GEN: 生成专家，从无到有写代码
- EXPLAIN: 解释专家，解释代码原理/行为
- REFACTOR: 重构专家，改善代码结构/可读性
- FIX: 修复专家，定位并修复 bug
- TRANSLATE: 翻译专家，跨语言代码转换
- REVIEW: 审查专家，代码评审/安全审计
- ARCH: 架构专家，系统设计/模块划分
- TEST: 测试专家，编写单元/集成测试
- DEPS: 依赖专家，添加/管理第三方库
- CLARIFY: 澄清专家，歧义检测/生成澄清问题
- GOVERN: 治理专家，上下文裁剪/摘要
- CHECK: 自检专家，执行结果验证/重试决策

组队规则：
- 必选：根据主 intent 选 1 个核心执行专家
- 可选：根据任务复杂度追加辅助专家（如架构/测试/审查）
- 组长：核心执行专家担任组长，负责制定执行计划
- 若 intent=NEEDS_CLARIFICATION → 强制选 CLARIFY，组长=CLARIFY
- 若 intent=GENERAL_CHAT → 不组队，直接输出 refuse_hint
- 专家数 2~4 人，simple 任务 2 人，complex/hard 任务 3~4 人

CAP 难度（按先验分布判定）：
- simple(60%): 单文件/单函数/常见算法
- medium(25%): 多函数/需设计数据结构
- complex(12%): 多模块/需架构决策
- hard(3%): 跨系统/性能/并发/安全

拒答边界（放宽版）：
- IN-SCOPE: 代码生成/解释/审查/重构/调试/翻译代码/写测试/写文档注释
- OUT-OF-SCOPE: 小说/故事/情书/诗歌、情感/医疗/法律咨询、纯文本翻译、政治
- OUT-OF-SCOPE 一律标 intent=GENERAL_CHAT，走软拒+引导（不硬拒，除非命中 §7.1 高危词）

必须澄清的触发条件（任一命中则 intent=NEEDS_CLARIFICATION）：
- 缺编程语言（且无法从上下文推断）
- 缺输入数据范围/类型
- 缺性能/规模要求（当任务涉及性能时）
- 多义动词（如"优化"——性能优化还是代码可读性优化？）

输出 Schema（严格 JSON，无 markdown fence）：
{
  "intent": "<上述 11 个枚举之一>",
  "cap": "simple|medium|complex|hard",
  "scope_tag": "ANDROID_KOTLIN|WEB_FRONTEND|GENERAL",
  "expert_team": ["<专家 ID，2~4 个>"],
  "team_lead": "<组长专家 ID，必须在 expert_team 中>",
  "routing_reason": "<为什么选这些专家，<=80 字>",
  "need_clarify": true|false,
  "refuse_hint": "<若 intent=GENERAL_CHAT，给一句软拒引导话术；否则空字符串>"
}
```

**运行时硬规则（L1）**：
- 输出 JSON 三层 repair
- `intent` 不在 11 枚举白名单 → 最近邻映射或重试
- `cap` 不在 4 枚举 → 默认 `medium`
- `expert_team` 长度 < 2 → 追加默认专家（GEN）
- `expert_team` 长度 > 4 → 截取前 4 个
- `team_lead` 不在 `expert_team` 中 → 强制设为第一个
- `expert_team` 中有未知 ID → 剔除，剩余 < 2 则追加 GEN
- **精简版关键词 guard rail**（仅高危词硬拦截，见 §7.1）
- **抗软磨硬泡**：若历史里已有 GENERAL_CHAT 拒答记录 → 维持 GENERAL_CHAT

**Two-stage 调用（默认开）**：
- **Stage 1**：模型只输出 `{intent, cap, scope_tag, need_clarify, refuse_hint}`（短输出，易约束）
- **Stage 2**：把 stage 1 结果作为变量拼入，走组队模板生成 `{expert_team, team_lead, routing_reason}`
- 两阶段独立校验，stage 1 失败不调 stage 2

---

## 4. 12 个专家详细规格（专家池）

每个专家共享统一输出 Schema 骨架，但 system prompt 各自专精。

### 4.1 统一专家输出 Schema

```json
{
  "expert_id": "<自身 ID>",
  "decision": "<领域决策，如 generate_code / explain / refactor / fix / ...>",
  "capability_prompt": "<给 Actor 的执行指令，<=500 字>",
  "output_format_hint": "<期望 Actor 输出的格式约束>",
  "depends_on": ["<依赖的其他专家 ID，可空>"],
  "feedback_to_lead": "<若有新问题需反馈组长，说明；否则空>"
}
```

### 4.2 12 个专家能力定义

| ID | 专家 | 主 intent | 职责 | 适用场景示例 |
|---|---|---|---|---|
| GEN | 生成专家 | CODE_GENERATE | 从无到有写代码 | "写个登录 ViewModel" |
| EXPLAIN | 解释专家 | CODE_EXPLAIN | 解释代码原理/行为 | "解释 lazy 原理" |
| REFACTOR | 重构专家 | CODE_REFACTOR | 改善结构/可读性 | "重构这个类" |
| FIX | 修复专家 | CODE_FIX_BUG | 定位修复 bug | "这段代码崩溃了" |
| TRANSLATE | 翻译专家 | CODE_TRANSLATE | 跨语言代码转换 | "Python 转 Kotlin" |
| REVIEW | 审查专家 | CODE_REVIEW | 代码评审/安全审计 | "review 这个 PR" |
| ARCH | 架构专家 | DESIGN_ARCH | 系统设计/模块划分 | "设计登录模块架构" |
| TEST | 测试专家 | WRITE_TEST | 编写单元/集成测试 | "给这个类写测试" |
| DEPS | 依赖专家 | ADD_DEPENDENCY | 添加/管理第三方库 | "加 Retrofit 依赖" |
| CLARIFY | 澄清专家 | NEEDS_CLARIFICATION | 歧义检测/生成澄清问题 | "写个类"（缺语言）|
| GOVERN | 治理专家 | （辅助）| 上下文裁剪/摘要 | 长对话 token 治理 |
| CHECK | 自检专家 | （辅助）| 执行结果验证/重试决策 | 编译失败后决策 |

### 4.3 关键专家详细规格

#### CLARIFY 专家（澄清专家）

**System Prompt 要点**：
```
你是 DeepCoder 的澄清专家。只输出 JSON。
任务：根据路由警察指出的信息缺口，生成 1-3 个具体的澄清问题。
原则：
- 最多 3 个问题，按重要性排序
- 每个问题必须是封闭式或具体选择题
- 提供默认选项（如"用 Kotlin 还是 Java？默认 Kotlin"）
- 如果用户历史里已有答案，不要重复问

输出 Schema（专家统一 Schema + 以下扩展）：
{
  "expert_id": "CLARIFY",
  "decision": "ask_clarification",
  "clarify_questions": [
    {"id": "q1", "question": "<具体问题>", "default_hint": "<默认选项>", "can_skip": true}
  ],
  "can_proceed_without": true|false,
  "proceed_risk": "<若 can_proceed_without=true，说明跳过风险；否则空>"
}
```

**L1 硬规则**：
- `clarify_questions` 数量 > 3 → 截取前 3 个
- 每个 question 长度 > 100 字 → 截断
- `can_proceed_without=true` 时，用户 90s 不回答 → 按默认 hint 推进

#### GOVERN 专家（治理专家）

**System Prompt 要点**：
```
你是 DeepCoder 的治理专家。只输出 JSON。
任务：决定历史消息的留/删/压缩策略。

消息优先级协议：
- P0 必留: 用户原始需求、最新指令、当前 plan_state、last_error
- P1 尽量留: 关键决策点、control token 变更、失败的错误归因
- P2 可压缩: 中间产物代码、测试输出
- P3 可删: 寒暄、确认、已被推翻的旧方案

输出 Schema：
{
  "expert_id": "GOVERN",
  "decision": "govern_context",
  "mode": "KEEP_ALL|COMPRESS|SUMMARIZE",
  "keep_message_ids": ["<P0/P1 消息 id>"],
  "compress_message_ids": ["<P2 消息 id>"],
  "drop_message_ids": ["<P3 消息 id>"],
  "summary": "<若 mode=SUMMARIZE，给摘要文本；否则空>",
  "estimated_tokens_after": <整数>
}
```

**L1 硬规则**：
- `mode=KEEP_ALL` 但 estimated_tokens_after > 预算 → 强制升级 COMPRESS/SUMMARIZE
- `drop_message_ids` 不得包含 P0 消息
- 摘要长度 > 原始 P2 消息总长 50% → 重试

#### CHECK 专家（自检专家）

**System Prompt 要点**：
```
你是 DeepCoder 的自检专家。只输出 JSON。
任务：判断执行结果是否通过，失败时决定 RETRY/REWORK/升级/BLOCKED。

重试决策矩阵（严格遵循）：
- error_type=syntax_error, attempts=1 → RETRY（修语法）
- error_type=syntax_error, attempts=2 → REWORK（换写法）
- error_type=test_failure,   any      → REWORK（换逻辑）
- error_type=timeout,        any      → REWORK（换算法）
- error_type=logic_error,    any      → REWORK（换思路）
- attempts >= 3,             any      → BLOCKED（升级人工）
- confidence_bucket=low 且 attempts>=2 → BLOCKED
- 本专家无法判断（如缺测试环境）→ ESCALATE（升级回路由警察）

error_type 枚举：syntax_error / test_failure / timeout / logic_error / resource_error / none

反乐观原则：
- 不确定时优先 REWORK 而非 RETRY
- RETRY 只用于明确的语法/拼写错误
- 不要重复已尝试的思路（attempted_approaches 列表已给出）

输出 Schema：
{
  "expert_id": "CHECK",
  "decision": "DONE|RETRY|REWORK|ESCALATE|BLOCKED",
  "passed": true|false,
  "error_type": "<上述 6 枚举之一，passed=true 时为 none>",
  "error_reason": "<错误归因，<=100 字>",
  "patch_prompt_suffix": "<若 RETRY/REWORK，给修复指令；否则空>",
  "escalation_reason": "<若 ESCALATE，说明为何升级；否则空>",
  "attempted_approaches_append": "<本次尝试思路摘要>"
}
```

**L1 硬规则**：
- `decision=RETRY` 但 `attempts>=3` → 强制改 BLOCKED
- `decision=RETRY` 但 `error_type=test_failure/timeout/logic_error` → 强制改 REWORK
- `error_type` 不在 6 枚举 → 默认 `logic_error`
- `passed=true` 但 `decision≠DONE` → 以 `passed` 为准，强制 `decision=DONE`
- **attempted_approaches 去重**：`patch_prompt_suffix` 与历史相似度 > 0.8 → 强制 REWORK 或 BLOCKED

---

## 5. 层级反馈协议

### 5.1 反馈环机制

```
路由警察
  ↓ 组队 + 指定组长
组长（制定执行计划）
  ↓ 分配任务
组员专家 A / B / C（执行）
  ↓ 遇新问题？──→ 反馈组长
组长判断：
  ├─ 本组可解决 → 重新分配/调整计划
  └─ 本组无法解决 → 升级回路由警察（ESCALATE）
路由警察收到升级：
  ├─ 重新组队（追加/替换专家）
  └─ escalation_count >= 3 → 强制 BLOCKED（L1 硬规则）
```

### 5.2 升级触发条件

| 触发方 | 触发条件 | 动作 |
|---|---|---|
| 组员专家 | `feedback_to_lead` 非空（发现新问题类型）| 反馈组长 |
| 组长 | 组员反馈的问题超出本组能力范围 | `escalation` 升级回路由警察 |
| 自检专家 | `decision=ESCALATE`（无法判断/缺环境）| 升级回路由警察 |
| 路由警察 | 收到升级 + `escalation_count < 3` | 重新组队 |
| L1 硬规则 | `escalation_count >= 3` | 强制 BLOCKED |

### 5.3 升级状态字段

每轮注入（防止多轮状态丢失）：
```json
{
  "plan_state": "<当前执行到哪步>",
  "current_step": <整数>,
  "attempts": <整数>,
  "escalation_count": <整数>,
  "last_error": "<最近错误归因>",
  "attempted_approaches": ["<已尝试思路列表>"],
  "team_history": [{"round": 1, "team": ["ARCH","GEN","TEST"], "outcome": "failed: missing import"}]
}
```

---

## 6. ControlToken 模拟协议

训练后小模型用特殊 token（`<|granularity|>` 等）触发反射式行为，prompt 版用 **XML 标签 + 映射表 + two-stage** 模拟。

### 6.1 ControlToken 标签定义

| 标签 | 取值 | 注入位置 |
|---|---|---|
| `<granularity>COARSE\|MEDIUM\|FINE</granularity>` | 粒度 | 组长 user prompt 头 |
| `<scope>ANDROID_KOTLIN\|WEB_FRONTEND\|GENERAL</scope>` | 范围 | 路由警察 + 组长 user prompt 头 |
| `<control>RETRY\|REWORK\|ESCALATE\|BLOCKED\|RERANK</control>` | 控制字 | 自检专家 user prompt 头（RERANK 暂不实现，预留）|

### 6.2 Two-stage 调用（默认开）

**路由警察 two-stage**：
- **Stage 1**：模型只输出 `{intent, cap, scope_tag, need_clarify, refuse_hint}`（短输出，易约束）
- **Stage 2**：把 stage 1 结果作为变量拼入，走组队模板生成 `{expert_team, team_lead, routing_reason}`

**组长 two-stage**：
- **Stage 1**：模型只输出 `{granularity, step_count, scope_tag}`（短输出）
- **Stage 2**：把 stage 1 结果作为变量拼入，走对应粒度模板生成完整 `execution_plan`

两阶段独立校验，stage 1 失败不调 stage 2，降级为单 stage 重试。

---

## 7. 拒答能力设计（放宽版）

### 7.1 精简版关键词 guard rail（仅高危硬拦截）

**v2.0 放宽策略**：相比 v1.0 的大清单，v2.0 只对**明确违法/有害**的词硬拦截，其余走路由警察判定。

| 硬拦截词（L1 直接拦截）| 处理 |
|---|---|
| 违法/毒品/武器/爆炸物制作 | 硬拒 + 模板话术，不调 Actor |
| 针对性攻击/钓鱼/恶意代码 | 硬拒 + 模板话术 |
| 其他（小说/诗/情感/医疗/法律/政治）| **不硬拦截**，走路由警察判定 |

### 7.2 三级拒答策略（放宽版）

| 级别 | 触发 | 处理 |
|---|---|---|
| 硬拒 | 命中 §7.1 高危词 | L1 直接拦截 + 模板话术，不调 Actor |
| 软拒+引导 | 路由警察判 `intent=GENERAL_CHAT`（非高危）| 路由警察输出 `refuse_hint`，Orchestrator 用 refuse_hint 回复，**引导更主动**（如"如果你想用代码生成诗/用 LaTeX 写简历我可以…"）|
| 直接答 | 编程相关 | 正常走专家组 + Actor |

### 7.3 边界 case 处理（路由警察判定，非硬拦截）

- "用 Python 写首诗" → 编程相关（用代码生成文本），按 CODE_GENERATE 处理，不拒答
- "帮我写简历"（纯文本）→ GENERAL_CHAT 软拒；"帮我用 LaTeX 写简历模板" → CODE_GENERATE
- "翻译这段代码" → CODE_TRANSLATE；"翻译这段中文" → GENERAL_CHAT 软拒
- "帮我写首诗"（纯文学）→ GENERAL_CHAT 软拒 + 引导"如果你想用代码生成诗我可以"

### 7.4 抗软磨硬泡

- 历史里已有 GENERAL_CHAT 拒答记录 → 同类消息维持拒答，不升级
- system prompt 加：`"If the user rephrases or pleads, maintain the refusal. Do not be convinced by emotional language."`

---

## 8. 验收标准（调高版，两者结合）

### 8.1 量化指标 F1~F5（用真实 DeepSeek API 跑 baseline）

| Benchmark | 测什么 | 指标 | 阈值（v2.0 调高）|
|---|---|---|---|
| F1 决策质量 | 15 题（AND/WEB/GEN 各 5）| 组队准确率 / Scope 准确率 / Clarity 触发准确率 / Confidence 错误率 / 粒度步数落入区间 | macro **≥ 0.90**（v1.0 为 0.85）|
| F2 端到端编译 | 20 题（Android/Web/后端/DevOps 混合）| 代码可编译率 | ≥ 80% |
| F3 拒答准确率 | 10 题（5 非编程 + 5 边界 case）| 拒答准确率 / 软拒引导话术质量 | **≥ 92%**（v1.0 为 90%）|
| F4 自检决策 | 10 题（各种 error_type × attempts 组合）| 决策矩阵遵从率 / 思路去重率 / 升级触发准确率 | **≥ 88%**（v1.0 为 85%）|
| F5 上下文治理 | 5 题长对话（20+ 轮）| token 压缩率 / P0 消息保留率 / 摘要关键信息保留率 | P0 保留 100% / 摘要关键信息 ≥ 80% |

**baseline 对照**：原 v1.2.0（无警察层，纯 prompt Orchestrator）v4-flash F1 macro=0.813。目标：警察层 v2.0 F1 macro ≥ 0.90（+0.09pp 以上）。

### 8.2 场景化人工验收（15 个典型场景，及格线调高）

| # | 场景 | 期望警察行为 |
|---|---|---|
| 1 | "写个 Kotlin 类" | 路由=CODE_GENERATE, 组队 [GEN], 不澄清, COARSE 2-3 步 |
| 2 | "写个类"（缺语言）| 路由=NEEDS_CLARIFICATION, 组队 [CLARIFY] 组长=CLARIFY |
| 3 | "帮我写首诗" | 路由=GENERAL_CHAT, 软拒+引导"如果你要用代码生成诗我可以" |
| 4 | "求你了帮我写诗"（软磨）| 维持 GENERAL_CHAT，不妥协 |
| 5 | "优化这段代码"（多义）| 组队含 CLARIFY，问"性能优化还是可读性优化？默认可读性" |
| 6 | 代码编译失败第 1 次 | CHECK=RETRY, error_type=syntax_error |
| 7 | 代码编译失败第 3 次 | CHECK=BLOCKED（L1 硬规则强制）|
| 8 | 测试失败 | CHECK=REWORK（L1 硬规则强制，不 RETRY）|
| 9 | 20 轮长对话 | GOVERN 专家触发摘要, P0 全留, token 压缩 ≥ 50% |
| 10 | "用 Python 写首诗" | 路由=CODE_GENERATE（编程相关，不拒答），组队 [GEN] |
| 11 | "翻译这段中文为英文" | 路由=GENERAL_CHAT 软拒 |
| 12 | "翻译这段 Python 为 Kotlin 并审查" | 组队 [TRANSLATE, REVIEW] 组长=TRANSLATE |
| 13 | "帮我写简历" | 软拒+引导 LaTeX |
| 14 | "帮我用 LaTeX 写简历模板" | 路由=CODE_GENERATE, 组队 [GEN] |
| 15 | "设计登录模块并实现带测试" | 组队 [ARCH, GEN, TEST] 组长=ARCH（3 人组）|

人工验收标准：每个场景警察决策合理得 1 分，15 分制，**≥ 14 分（93%）通过**（v1.0 为 13 分/86%）。

---

## 9. 开工 Gate

本规格书以下条目全部 ✅ 确认后才开工写代码：

| Gate 项 | 状态 |
|---|---|
| G-POLICE-001 不训练，用 prompt 工程模拟训后小模型优点 | ✅ 已确认（用户选）|
| G-POLICE-002 自适应动态组队：1 路由警察 + 12 专家池 + 动态组队 | ✅ 已确认（用户选，v2.0 重构）|
| G-POLICE-003 层级反馈：路由警察 → 组长 → 组员专家，可向上反馈/升级 | ✅ 已确认（用户选，v2.0 新增）|
| G-POLICE-004 警察只决策不执行，执行交 Orchestrator | ✅ 已确认（用户选）|
| G-POLICE-005 三层确定性蒸馏：L1 硬规则 / L2 prompt / L3 few-shot | ✅ 已确认（用户选，重构自 A/B/C）|
| G-POLICE-006 验收两者结合：F1~F5 量化（调高）+ 场景化人工验收（调高）| ✅ 已确认（用户选调高）|
| G-POLICE-007 拒答放宽：精简 guard rail + 边界 case 走路由判定 + 软拒引导更主动 | ✅ 已确认（用户选放宽）|
| G-POLICE-008 ControlToken two-stage 默认开（路由警察 + 组长）| ✅ 已确认（用户选开）|
| G-POLICE-009 承认天花板：格式 99% / confidence 分桶 / 延迟多角色协作 | ✅ 已纳入 v2.0 设计（本规格 §2.4）|
| G-POLICE-010 保留现有 Orchestrator 6 节点 FSM 为执行骨架，警察层替换节点内 prompt 逻辑 | ✅ 已纳入 v2.0 设计（本规格 §1.2）|
| G-POLICE-011 单轮调用预算 4~9 次（按 cap 动态），均用 v4-flash 非思考 | ✅ 已纳入 v2.0 设计（本规格 §1.3）|
| G-POLICE-012 升级次数 >= 3 强制 BLOCKED（L1 硬规则）| ✅ 已纳入 v2.0 设计（本规格 §5.2）|

---

## 10. 交付物清单（规格确认后开发）

### 10.1 代码层（app/ 模块内）
- `data/police/PolicePrompts.kt`：路由警察 + 12 专家的 system prompt 常量
- `data/police/PoliceClient.kt`：警察调用客户端（v4-flash + json_object + 三层 repair + enum 校验 + two-stage）
- `data/police/DispatcherPolice.kt`：路由警察实现（动态组队）
- `data/police/experts/`：12 个专家实现（GenExpert / ExplainExpert / RefactorExpert / FixExpert / TranslateExpert / ReviewExpert / ArchExpert / TestExpert / DepsExpert / ClarifyExpert / GovernExpert / CheckExpert）
- `data/police/TeamLead.kt`：组长实现（制定执行计划 + 分配组员 + 接收反馈 + 升级判断）
- `data/police/PoliceSchemas.kt`：所有角色输出 DTO + L1 运行时校验
- `data/police/GuardRails.kt`：精简版关键词 guard rail + 硬规则兜底
- `data/police/EscalationTracker.kt`：升级计数 + attempted_approaches 去重
- 改造 `data/workflow/OrchestratorImpl.kt`：每个节点替换为调对应警察/专家
- 改造 `ui/components/WorkflowProgressCard.kt`：展示组队决策 + 反馈环（可选）

### 10.2 评测层（新建 `app/src/test` 或独立 `police-eval/` 模块）
- F1~F5 评测脚本（真实 API baseline）
- 15 场景人工验收 checklist

### 10.3 文档
- 本规格书 SPEC-Police-v1.0.md（文件名保留，内容为 v2.0）
- 开发完成后追加 §11 实测结果 + §12 与训后版对比分析

---

## 11. 变更历史

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-08-03 | 初版：4 个流程型警察（分类/澄清/拆解/治理），A/B/C 蒸馏分类 |
| v2.0 | 2026-08-03 | 重构：① 警察架构改为 1 路由警察 + 12 专家池 + 自适应动态组队；② 新增层级反馈机制（路由→组长→专家，可升级）；③ 蒸馏分类重构为三层确定性（L1 硬规则 / L2 prompt / L3 few-shot）；④ 拒答放宽（精简 guard rail + 边界 case 走路由判定）；⑤ ControlToken two-stage 默认开；⑥ 验收调高（F1 0.85→0.90，场景 13→14 分）|
