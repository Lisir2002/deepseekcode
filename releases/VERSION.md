# DeepCoder 发布记录

## v1.3.0 (2026-08-03) · 警察层 v2.0 上线：1 路由警察 + 12 专家池 + 自适应动态组队

### 重大架构升级
- 🚔 **警察层 v2.0 全量接入 Orchestrator**：用 prompt 工程模拟训练后小模型的优点，不训练任何模型，纯靠三层确定性蒸馏 + 层级反馈实现有秩序的问答流程管理
- 架构：**1 个路由警察 + 12 个专家池 + 自适应动态组队**（按问题种类动态组建 2~4 人临时专家组）
- 层级反馈链路：路由警察 → 组长 → 组员专家，专家可反馈组长，组长可升级回路由警察
- 核心原则：**只决策不执行**。警察/专家只输出 JSON 决策，代码生成仍走 Actor（ChatRepository.sendChat 流式）

### Orchestrator 6 节点 FSM 全部接入警察层
- CLASSIFY        → DispatcherPolice.dispatch()       路由警察 two-stage（意图 + 动态组队 + 指定组长）
- CLARIFY_QUESTION→ ExpertRunner.runClarify()         CLARIFY 专家生成澄清问题
- GOVERN_CONTEXT  → ContextGovernor.trim()            L1 硬 token 预算
- DECOMPOSE       → TeamLead.plan()                   组长 two-stage 制定执行计划
- EXECUTE         → ExpertRunner.run() + Actor 流式   专家决策 capability → Actor 生成代码
- SELF_CHECK      → ExpertRunner.runCheck()           CHECK 专家 + L1 决策矩阵硬覆盖

### 新增组件（data/police/，8 个文件）
- PoliceSchemas    统一 Schema + 枚举白名单 + L1 校验 + JSON 三层 repair（L1 直接 parse → L2 抽 {...} → L3 失败重试）
- PolicePrompts    路由/组长/12 专家 system prompt（L2 prompt 层）
- GuardRails       高危词硬拦截 + 软磨硬泡检测（L1 硬规则，仅高危词硬拦截，其余走路由判定 + 软拒引导）
- EscalationTracker 升级计数（≥3 强制 BLOCKED）+ attempted_approaches 去重（相似度 >0.8 强制换思路）+ 多轮状态注入
- PoliceClient     JSON-mode 调用 + two-stage + 三层 repair（v4-flash 非思考模式，temperature=0.05）
- ExpertRunner     12 专家统一调度 + CHECK 决策矩阵硬覆盖（attempts≥3 强制 BLOCKED / RETRY+test_failure/timeout/logic_error 强制 REWORK / 去重强制 REWORK）
- TeamLead         组长 two-stage 计划（粒度→步数映射 COARSE 2-3 / MEDIUM 5-7 / FINE 10-15）+ 升级判断
- DispatcherPolice 路由警察 two-stage（Stage1 意图/难度/范围/澄清/拒答 → Stage2 动态组队）+ GENERAL_CHAT 不组队直接拒答 + NEEDS_CLARIFICATION 强制 CLARIFY

### three-stage 蒸馏
- L1 硬规则层（运行时强约束）：GuardRails 硬拦截 / EscalationTracker 升级计数 / CHECK 决策矩阵 / 步数区间校验 / JSON 三层 repair
- L2 prompt 层（system prompt 指令）：路由/组长/12 专家各自 system prompt
- L3 few-shot 层（示例引导）：组队示例、粒度映射、决策矩阵

### 端到端真实 API 测试（7/7 全通过，耗时 21.8s）
- S1 代码生成全链路（路由→组队→计划→GEN→Actor→CHECK DONE）
- S2 闲聊拒答（GENERAL_CHAT + 引导话术）
- S3 澄清流程（NEEDS_CLARIFICATION + CLARIFY 专家 3 问题）
- S4 高危词硬拦截（L1 硬规则，不调 API）
- S5 软磨硬泡（历史拒答维持 GENERAL_CHAT，未妥协）
- S6 复杂任务组队（DESIGN_ARCH/hard → 4 人组队 ARCH 组长）
- S7 自检 L1 决策矩阵（attempts>=3 强制 BLOCKED，覆盖模型原始判定）
- 测试脚本：scripts/police_e2e_test.py（复用项目 prompt 常量，模拟完整 FSM）

### 规格文档
- SPEC-Police-v1.0.md（内容为 v2.0）：警察层规格书，定义架构/设计原则/详细规格/验收标准

### APK
- Debug: releases/DeepCoder-v1.3.0-debug.apk (~20 MB)
- versionCode: 2 / versionName: 1.3.0

### 验证
- compileDebugKotlin：0 error（仅 4 个既有 deprecation 警告）
- assembleDebug：BUILD SUCCESSFUL
- testDebugUnitTest：全部通过，无回归
- 端到端真实 API 测试：7/7 PASS

---

## v1.2.0 (2026-08-03) · 丢弃 LoRA 训练计划，回归纯通用大模型 + Orchestrator

### 重大决策
- ❌ **正式废弃 LoRA 小模型训练计划**：原 `SPEC-Planner-v0.7.md` 中 Planner-Actor-Verifier 三角协同 + 专用 LoRA 小模型训练的全部计划予以丢弃，不再执行。
- 根因：DeepSeek 公共 OpenAPI 不开放 `/v1/fine_tuning/jobs` 与 `/v1/files` 端点（返回 404），LoRA 训练需走企业商务渠道；且真实 API F1 回测显示通用大模型当 Planner 不达标（v4-flash 81.33% / v4-pro 72.00%，均低于 85% 阈值），但经评估决定不再走自训练路线。
- App 现定位为：**通用 DeepSeek 大模型（v4-flash / v4-pro）+ Orchestrator 6 节点 FSM 编排** 的代码助手，不再具备自训练能力。

### 删除（训练相关全部清空）
- `SPEC-Planner-v0.7.md`：训练规格文档（整文件删除）
- `planner-benchmarks/`：整个 JVM 模块（F1~F6 评测 / Q1~Q10 质检 / 合成管线 / LoRA 工具链 / LiveF1 回测工具）
- `scripts/`：4 个训练脚本（`deepseek-lora-cli.sh` / `run-planner-lora-pipeline.sh` / `run-gen-dataset.sh` / `run-f1-backtest.sh`），仅保留 `ldplayer-automate.sh`（模拟器测试）
- `releases/deepseek_coder_ft_train_200_v1.1.0.jsonl`：预合成训练样本
- `settings.gradle.kts` / `build.gradle.kts`：移除 `:planner-benchmarks` 模块 include 与 `kotlin.jvm` 插件

### App 层移除 LoRA 接口预埋
- `AppSettings.kt`：删除 `customFineTuneModelId` / `fineTuneDataCollectionEnabled` 字段 + `effectiveModelId` 派生属性
- `SettingsRepository.kt` / `SettingsRepositoryEx.kt` / `SettingsViewModel.kt`：删除对应 DataStore Key、读写、setter
- `SettingsScreen.kt`：删除「LoRA 微调 / 自训练模型」设置卡片
- `ChatViewModel.kt`：移除 `FineTuneCollector` 注入与训练样本采集逻辑
- `ChatRepository.kt`：`effectiveModelId` 回退为 `model.id`
- `OrchestratorImpl.kt`：删除 `buildFinalSettings`（LoRA 模型覆盖逻辑）
- `DiagnosticZipExporter.kt`：诊断包移除 `fine_tune_bucket.json`（8 类→7 类文件）
- `DeveloperPanel.kt`：移除 Q1~Q10 质检开关卡片（该开关属于 planner-benchmarks 质检体系）
- 删除 `data/telemetry/FineTuneCollector.kt`（本地训练数据采集器，整文件删除）

### 保留
- ✅ Orchestrator 6 节点 FSM（CLASSIFY → CLARIFY → GOVERN_CONTEXT → DECOMPOSE → EXECUTE → SELF_CHECK）
- ✅ granularity / scope / rerank 三档粒度与范围控制（运行时 prompt 注入，不依赖训练）
- ✅ 隐藏开发者面板 + 诊断 Zip 导出（7 类文件）
- ✅ 所有 v1.0.0 / v1.1.0 的 App 核心功能（Chat / FIM / Sessions / Editor / Settings）

### 顺带修复的源码 bug（构建过程发现）
- `DeveloperPanel.kt`：`collectAsStateWithLifecycle` import 包名错误（`androidx.compose.runtime` → `androidx.lifecycle.compose`）
- `AppSettings.kt`：补 `@Serializable` 注解
- `DiagnosticZipExporter.kt`：`@Serializable Any?` 改 `String`；`Sequence.takeLast` 改 `toList().takeLast`
- `app/build.gradle.kts`：补 `-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi`（FlowRow）

### 构建环境配置
- `gradle.properties`：追加沙箱代理（`http.proxyHost=127.0.0.1:18080`）+ 延长 socket timeout
- `settings.gradle.kts`：追加阿里云 Maven 镜像（dl.google.com 经代理不稳定）

### 验证
- `:app:assembleDebug`：BUILD SUCCESSFUL（0 error，3 既有 deprecation 警告）
- `:app:testDebugUnitTest`：7/7 PASS（删除 `effectiveModelId` 测试后，原 8 项减为 7 项）

---

## v1.1.0 (2025-08-02) · Orchestrator + LoRA 接口预埋

### 新增
- 🧠 Orchestrator 6 节点 FSM：意图分类 → 需求澄清 → 上下文治理 → 任务拆解 → 流式执行 → 代码自检 / 自动重试
  - 支持 10 种代码意图（生成/重构/解释/修复/翻译/Review/架构/FIM/闲聊/澄清）
  - 中文关键词 fallback，无网络也能给出分类初值
  - CLARIFY_QUESTION：信息不足时 UI 弹出补充信息面板
  - SELF_CHECK + RETRY_FIX：自检失败自动追加修复指令重跑，最多 selfCheckMaxRetry 次
- 📐 UI：折叠式 WorkflowProgressCard 展示编排节点、步骤阶梯、自检结果
- 🪜 LoRA 接口预埋：
  - 设置页新增「自定义 Fine-tune 模型 ID」输入框（填好后模型请求自动切到 ft-id）
  - 设置页新增「本地 JSONL 训练数据采集」开关（默认关闭，符合隐私）
  - 采集文件路径：`/data/data/com.deepseek.coder/files/deepcoder_ft_samples.jsonl`
  - 同步 releases 目录附 200 条预合成 Kotlin/Android 训练样本（deepseek_coder_ft_train_200_v1.1.0.jsonl）
- 💾 SettingsRepository 新增 5 个 DataStore 键（orchestratorEnabled / customFineTuneModelId / fineTuneDataCollectionEnabled / selfCheckMaxRetry / clarificationsAutoAsk）

### 修复 & 优化
- 修复请求体 `content` 字段为纯字符串，完全符合 DeepSeek /chat/completions schema，不会再报 "content should be a string or a list"
- effectiveModelId 优先使用自定义 ft-id，允许企业渠道训练好的 LoRA 模型无缝接入
- 新增 `ChatRepository.sendChatBlockingJsonOverride`，支持 response_format=json_object，供 Orchestrator 分类/拆解/自检 3 节点使用

### APK
- Debug: releases/DeepCoder-v1.1.0-debug.apk (~20 MB)
- Release (unsigned): releases/DeepCoder-v1.1.0-release-unsigned.apk (~14 MB)

### 验证
- compileDebugKotlin：0 error
- testDebugUnitTest：13/13 PASS
- 独立 API 连通性脚本（/workspace/test_deepseek_api.kt）：非流式/SSE 流式/FIM 三项全部通过（使用 sk-9dd7227bc1684084b4d2922af42f1aa1）

### 已知限制
- DeepSeek 目前公共 OpenAPI 不开放 /v1/files 与 /v1/fine_tuning/jobs 等端点（404），LoRA 训练需走企业商务渠道，v1.1.0 已按企业要求的 JSONL schema 预埋采集与模型切换逻辑

---

## v1.0.0
- 初版：MVVM + Clean Architecture，Chat / Settings / Sessions / Editor 四大页面，FIM 补全，思考模式展示
