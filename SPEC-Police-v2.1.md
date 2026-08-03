# DeepCoder · Police（问答流程警察）规格书 v2.1

> **状态**：🟢 **开发中**（v2.1，6 项架构修复，spec 已与代码对齐）
> **核心定位**：用 **prompt 工程 + 运行时硬规则** 模拟"训练后小模型"的优点，构建一个像警察指挥交通一样有秩序管理每次问答流程的决策层。**不训练任何模型**。
>
> **v2.1 重大变更**（相对 v2.0，修复 v2.0 设计与代码的 6 处断链）：
> 1. **层级反馈回路补接**（v2.0 画饼→v2.1 落地）：专家 `feedback_to_lead` → 组长 `shouldEscalate` → 路由重组队，三层回路真实接通
> 2. **GOVERN 专家接通**（v2.0 死代码→v2.1 决策+执行分离）：GOVERN_CONTEXT 节点先调 `runGovern()` 出裁剪策略（mode/keep/compress/drop），`ContextGovernor` 按策略执行具体裁剪
> 3. **GEN/Actor 职责去重**（v2.0 两次调用做同一件事→v2.1 各司其职）：GEN 只出**决策**（技术栈/约束/验收点/风险），不写代码骨架；Actor 负责全部代码生成
> 4. **动态组队落地**（v2.0 静态预组队→v2.1 组长动态换人）：执行中组长可从 12 池追加/替换组员
> 5. **CHECK 接入 LLM 二次验证**（v2.0 决策矩阵大半死→v2.1 激活）：CHECK 节点先调一次 LLM 验证获取真实 error_type，激活完整决策矩阵
> 6. **升级重组队**（v2.0 单向流水线→v2.1 完整回路）：组长换人不够时可向路由警察申请升级，触发整个 expert_team 重组队

---

## 0. 核心原则

1. **不训练**：用 prompt 工程替代 LoRA 训练，但**主动学习训练后小模型的优点**，把它蒸馏进 prompt + 运行时协议。
2. **自适应动态组队**：1 个路由警察 + 12 个专家池。路由警察给**初始组队**；执行中**组长可动态换人**；组长无法解决时可**升级触发重组队**。
3. **层级反馈**（v2.1 真实落地）：路由警察 → 组长 → 组员专家；专家遇到新问题 `feedback_to_lead` 反馈组长；组长判断本组无法解决 `shouldEscalate` 升级回路由警察重新组队。
4. **只决策不执行**：警察/专家只输出 JSON 决策，绝不直接调 Actor API 生成代码。GEN 也不例外——只出决策约束，不写代码骨架。执行交给现有 Orchestrator 的 Actor。
5. **三层确定性蒸馏**：
   - **L1 硬规则层**：运行时强约束，不可降级（JSON repair / enum 白名单 / attempts 上限 / 步数校验 / 升级计数 / 去重 / LLM 二次验证）
   - **L2 prompt 层**：system prompt 指令（schema / enum / 决策矩阵 / 映射表）
   - **L3 few-shot 层**：示例引导（话术 / 风格 / 错误修复映射）

---

## 1. 架构总览

### 1.1 警察层在系统中的位置（v2.1 三层回路）

```
用户消息
   ↓
┌──────────────────────────────────────────────────────────┐
│  ① 路由警察（Dispatcher）                                  │
│     分析问题 → 初始组队 → 指定组长                          │
└──────────────────────┬───────────────────────────────────┘
                       ↓ ①初始组队
┌──────────────────────────────────────────────────────────┐
│  ② 组长（Team Lead）                                       │
│     制定执行计划 + 分配组员                                  │
│     执行中可【动态换人】（从 12 池追加/替换）                  │
│     无法解决时【升级】回路由警察                              │
└──────────────────────┬───────────────────────────────────┘
                       ↓ ②计划
┌──────────────────────────────────────────────────────────┐
│  ③ 组员专家执行（GEN/EXPLAIN/REFACTOR/FIX/...）             │
│     只出决策（GEN 出约束清单，不写代码）                      │
│     Actor 按 GEN 决策生成代码                               │
│     遇新问题 → 【feedback_to_lead】反馈组长                  │
│     └──────────↑ 反馈回路 ↑──────────┘                      │
└──────────────────────┬───────────────────────────────────┘
                       ↓ ③代码产出
┌──────────────────────────────────────────────────────────┐
│  ④ CHECK 专家 + LLM 二次验证                                │
│     先调 LLM 验证获取真实 error_type                         │
│     → 激活完整决策矩阵（syntax/test/timeout/logic/resource） │
│     ESCALATE → 升级回路由警察                                │
└──────────────────────┬───────────────────────────────────┘
                       ↓
┌──────────────────────────────────────────────────────────┐
│  ⑤ GOVERN 专家 + ContextGovernor                           │
│     GOVERN 出裁剪策略（mode/keep/compress/drop）            │
│     Governor 按策略执行具体裁剪（决策与执行分离）              │
└──────────────────────────────────────────────────────────┘
   ↑__________________升级回路（v2.1 新增真实链路）_______________|
   专家 feedback → 组长 shouldEscalate → 路由重组队（escalation_count<3）
                                       → L1 强制 BLOCKED（escalation_count>=3）
```

### 1.2 Orchestrator FSM 节点 → 警察层映射（v2.1 修正版）

| FSM 节点 | v2.0（断链） | v2.1（修复） |
|---|---|---|
| CLASSIFY | DispatcherPolice.dispatch() | 同 v2.0（保留） |
| CLARIFY_QUESTION | ExpertRunner.runClarify() | 同 v2.0（保留） |
| GOVERN_CONTEXT | ContextGovernor.trim() **未接 GOVERN 专家** | **runGovern() 出策略 → Governor 执行** |
| DECOMPOSE | TeamLead.plan() | 同 v2.0（保留）+ **支持执行中重组队** |
| EXECUTE | ExpertRunner.run() + Actor | **GEN 只出决策约束** → Actor 按 GEN 决策生成 |
| SELF_CHECK | ExpertRunner.runCheck() **缺真实反馈** | **+ LLM 二次验证** 获取 error_type |

---

## 2. 三层确定性蒸馏设计

（与 v2.0 一致，略。L1 新增"LLM 二次验证"和"升级计数强制 BLOCKED"已在原基础上接通。）

---

## 3. 路由警察详细规格（Dispatcher）

### 3.1 职责
- Stage 1：分析问题（intent/cap/scope/need_clarify/refuse）
- Stage 2：初始组队（2~4 人）+ 指定组长
- **v2.1 新增**：接收组长升级请求 → 重新组队（追加/替换专家或换组长）

### 3.2 升级重组队（v2.1 新增）

当组长 `shouldEscalate=true` 时，路由警察收到：
```json
{
  "escalation_reason": "<为何升级>",
  "current_team": ["ARCH","GEN"],
  "failed_step_id": "s2",
  "escalation_count": 1,
  "suggested_experts": ["FIX"]  // 组长建议追加的专家（可空）
}
```

路由警察处理：
```json
{
  "new_team": ["ARCH","GEN","FIX"],      // 重组后的队伍
  "new_team_lead": "ARCH",                // 可换组长
  "resume_from_step": "s2",               // 从哪步恢复
  "routing_reason": "<重组理由>"
}
```

L1 硬规则：`escalation_count >= 3` → 强制 BLOCKED，不再重组队。

---

## 4. 12 个专家详细规格

### 4.1 GEN 专家（v2.1 重大修正：只决策不写代码）

**v2.0 问题**：GEN 输出 `capability_prompt` 含代码骨架（class/fun 签名/import），Actor 又生成完整代码，两次调用做同一件事。

**v2.1 修正**：GEN 只出**决策约束清单**，绝不写代码：

```json
{
  "expert_id": "GEN",
  "decision": "generate_code",
  "tech_stack": ["Kotlin", "Coroutines", "Room"],
  "constraints": [
    "使用 suspend 函数",
    "空安全：用 ?./?: 处理可空",
    "数据库操作走 Repository 模式"
  ],
  "acceptance_criteria": [
    "编译通过",
    "单元测试覆盖核心逻辑",
    "无 force unwrap"
  ],
  "risks": ["协程上下文泄漏", "Room schema 迁移"],
  "depends_on": [],
  "feedback_to_lead": ""
}
```

**移除字段**：`capability_prompt`（v2.0 的代码骨架）、`output_format_hint`。

Actor 接收 GEN 决策后，将 `tech_stack/constraints/acceptance_criteria/risks` 拼入 system prompt 增强约束，然后自由生成代码。

### 4.2 GOVERN 专家（v2.1 接通：决策+执行分离）

**v2.0 问题**：`runGovern()` 从未被调用，12 专家实际只跑 11 个。

**v2.1 修正**：GOVERN_CONTEXT 节点流程改为：
1. 调 `ExpertRunner.runGovern()` → GOVERN 输出裁剪策略
2. `ContextGovernor.trimByStrategy()` 按策略执行具体裁剪

GOVERN 输出策略：
```json
{
  "expert_id": "GOVERN",
  "decision": "govern_context",
  "mode": "KEEP_ALL|COMPRESS|SUMMARIZE",
  "keep_message_ids": ["m0","m1"],       // 必须保留的消息 ID
  "compress_message_ids": ["m2"],         // 压缩的消息
  "drop_message_ids": ["m3"],             // 丢弃的消息
  "summary": "<SUMMARIZE 时填>",
  "estimated_tokens_after": 1200
}
```

Governor 执行：按 keep/compress/drop 列表操作，COMPRESS 取摘要，SUMMARIZE 整段替换为 summary。

### 4.3 CHECK 专家（v2.1 接通：LLM 二次验证激活决策矩阵）

**v2.0 问题**：决策矩阵里 `test_failure/timeout/resource_error` 永远不触发（手机端无编译/测试环境），CHECK 只能靠肉眼读代码判 syntax_error。

**v2.1 修正**：CHECK 节点流程改为：
1. 先调一次 **LLM 二次验证**（独立 system prompt，让模型扮编译器/测试者，输出 error_type）
2. 把验证结果作为 CHECK 的输入
3. CHECK 按真实 error_type 激活完整决策矩阵

LLM 二次验证输出：
```json
{
  "error_type": "syntax_error|test_failure|timeout|logic_error|resource_error|none",
  "error_reason": "<具体错误>",
  "confidence_bucket": "high|medium|low"
}
```

决策矩阵（v2.0 设计保留，v2.1 全部可触发）：
| error_type | attempts | decision |
|---|---|---|
| syntax_error | 1 | RETRY |
| syntax_error | 2 | REWORK |
| test_failure | any | REWORK |
| timeout | any | REWORK |
| logic_error | any | REWORK |
| resource_error | any | ESCALATE |
| none | any | DONE |
| (any) | ≥3 | BLOCKED（L1 覆盖） |
| confidence_bucket=low | ≥2 | BLOCKED |

### 4.4 其余专家（EXPLAIN/REFACTOR/FIX/TRANSLATE/REVIEW/ARCH/TEST/DEPS/CLARIFY）

（与 v2.0 一致，仅统一新增 `feedback_to_lead` 字段在 Orchestrator 中被读取处理。）

---

## 5. 层级反馈协议（v2.1 真实落地）

### 5.1 反馈环机制（v2.0 画饼→v2.1 接通）

```
路由警察
  ↓ 初始组队 + 指定组长
组长（制定执行计划 + 分配组员）
  ↓ 计划
组员专家执行
  ↓ 遇新问题？feedback_to_lead 非空 → 反馈组长
组长判断（TeamLead.shouldEscalate）：
  ├─ 本组可解决 → 【动态换人】从 12 池追加/替换组员，调整计划
  └─ 本组无法解决 → 【升级】ESCALATE 回路由警察
路由警察收到升级（escalation_count < 3）：
  ├─ 重新组队（追加/替换/换组长）
  └─ 从 resume_from_step 恢复执行
L1 硬规则（escalation_count >= 3）：
  └─ 强制 BLOCKED
```

### 5.2 v2.0 断链 vs v2.1 接通对照

| 环节 | v2.0 状态 | v2.1 修复 |
|---|---|---|
| 专家 `feedback_to_lead` | 字段存在，Orchestrator 未读取 | Orchestrator 读取，收集反馈传给组长 |
| `TeamLead.shouldEscalate()` | 方法存在，从未调用 | 组长每步执行后调用，判断是否升级 |
| 升级回路 | `recordEscalation` 只计数 | 计数 + 触发路由 `redispatch()` 重组队 |
| 动态换人 | 不支持（路由定死） | 组长可从 12 池追加/替换（不需重组队） |
| 重组队恢复 | 不支持 | 路由指定 `resume_from_step`，从失败步恢复 |

### 5.3 动态换人规则（v2.1 新增）

组长在执行中发现某组员无法完成任务（feedback_to_lead 非空且本组可解决）：
```json
{
  "action": "SWAP_MEMBER",
  "remove_expert": "GEN",
  "add_expert": "FIX",
  "reason": "GEN 反馈该任务需要修复而非新生成"
}
```

约束：
- 仅可从 12 专家池选择
- 队伍规模仍保持 2~4 人
- 换人后组长重新分配剩余步骤
- 换人不算升级（不增加 escalation_count）

### 5.4 升级重组队规则（v2.1 新增）

组长 `shouldEscalate=true` 时升级回路由警察：
- `escalation_count += 1`
- 若 `escalation_count >= 3` → L1 强制 BLOCKED，不再重组队
- 否则路由警察重新组队，指定 `resume_from_step` 从失败步恢复
- 重组队后组长重置该步 attempts=0（但 attempted_approaches 保留，防止重复思路）

---

## 6. ControlToken 模拟协议

（与 v2.0 一致：XML 标签 + 映射表 + two-stage 默认开。略。）

---

## 7. 拒答能力设计（放宽版）

（与 v2.0 一致：仅高危词硬拦截，闲聊软拒+引导，软磨硬泡不妥协。略。）

---

## 8. 验收标准

### 8.1 量化指标（v2.1 新增回路验证）

| 指标 | v2.0 阈值 | v2.1 阈值 | 说明 |
|---|---|---|---|
| F1 路由准确率 macro | ≥0.90 | ≥0.90 | 11 意图分类 |
| F2 组队合理性 | ≥0.85 | ≥0.90 | v2.1 含动态换人正确性 |
| F3 计划步数合规 | 100% | 100% | 粒度→步数区间 |
| F4 CHECK 决策正确 | ≥0.85 | ≥0.90 | v2.1 含 LLM 二次验证准确率 |
| F5 层级反馈触发率 | N/A | ≥0.80 | **v2.1 新增**：应升级时确实升级的比例 |

### 8.2 场景化人工验收（15 场景，≥14 分）

v2.1 新增 3 个回路场景（替换 v2.0 的 3 个静态场景）：
- 场景 13：GEN 反馈需修复 → 组长换人 GEN→FIX（验证动态换人）
- 场景 14：组长升级 → 路由重组队恢复（验证升级回路）
- 场景 15：CHECK LLM 二次验证检出 logic_error → REWORK（验证决策矩阵激活）

---

## 9. 交付物清单

- [x] SPEC-Police-v2.1（本文件）
- [ ] PoliceSchemas.kt 修改：GEN 去 capability_prompt 加决策字段；新增 SWAP_MEMBER/升级重组队 schema
- [ ] PolicePrompts.kt 修改：GEN prompt 改为决策约束；新增 LLM 二次验证 prompt
- [ ] PoliceClient.kt 修改：新增 callVerify（LLM 二次验证）
- [ ] ExpertRunner.kt 修改：runGovern 接通；runCheck 接入 LLM 验证
- [ ] TeamLead.kt 修改：shouldEscalate 接通；新增 swapMember
- [ ] DispatcherPolice.kt 修改：新增 redispatch（重组队）
- [ ] OrchestratorImpl.kt 修改：6 节点全部按 v2.1 接通回路
- [ ] 编译 + 单元测试 + 端到端 API 测试

---

## 10. 变更历史

| 版本 | 日期 | 变更 |
|---|---|---|
| v2.0 | 2026-08-03 | 1 路由+12 专家+自适应动态组队架构（部分断链） |
| **v2.1** | **2026-08-03** | **6 项架构修复：层级反馈回路接通 / GOVERN 决策执行分离 / GEN 只决策 / 动态换人 / LLM 二次验证 / 升级重组队** |
