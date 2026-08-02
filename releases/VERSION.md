# DeepCoder 发布记录

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
