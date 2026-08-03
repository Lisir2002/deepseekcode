# DeepCoder 发布记录

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
