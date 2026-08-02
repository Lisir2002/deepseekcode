# deepseekcode / DeepCoder Android App

专门对接 **DeepSeek V4 大模型**、以写代码为主的安卓 App。
Kotlin 2.0 + Jetpack Compose + Material 3 + MVVM + Clean Architecture。

## ✨ 功能

- 🔑 **API Key 加密保存**：通过 AndroidX Security EncryptedDataStore 本地加密
- 💬 **代码对话聊天**：支持流式响应（SSE），实时显示 thinking / reasoning 过程
- 🧠 **思考模式（Reasoning）**：`low / medium / high` 三档 effort，自动推导 thinking_budget
- 📚 **会话历史持久化**：Room 数据库，多会话管理
- ⚙️ **模型 / 温度 / max_tokens / system prompt 配置**
- 🧩 **FIM 补全**：对接 `/beta/completions`（已封装网络层 + Repository）

## 🔧 修复版本 v1.0.0 (2026-08-02)

### ❌→✅ 请求格式 Bug Fix
修复 `content should be a string or a list` 错误，请求 JSON 现为：
```json
{"messages":[{"role":"user","content":"你的内容，纯字符串"}]}
```
不再产生 `{"content":{"type":"Text","text":"..."}}` 这种对象格式。

详见 [releases/VERSION.md](./releases/VERSION.md)

## 📦 下载

| 版本 | 文件 | 说明 |
|---|---|---|
| Debug | [DeepCoder-v1.0.0-debug.apk](./releases/DeepCoder-v1.0.0-debug.apk) | 可调试、无签名校验、开发推荐 |
| Release (unsigned) | [DeepCoder-v1.0.0-release-unsigned.apk](./releases/DeepCoder-v1.0.0-release-unsigned.apk) | 未签名，需用 `apksigner` / 自己的 keystore 重签后安装 |
| Debug (latest) | [apk/app-debug.apk](./apk/app-debug.apk) | 与 releases/debug 同文件，便捷链接 |

安装：
```bash
adb install -r releases/DeepCoder-v1.0.0-debug.apk
```

## 🚀 使用

1. 安装 App 后打开，进入 **设置页** 填入你的 DeepSeek API Key（形如 `sk-xxx`）
2. 返回首页，选模型（`deepseek-v4-flash` / `deepseek-v4-pro`），开/关 思考模式
3. 输入代码需求 → 发送 → 流式接收结果

## 🧪 云端验证报告 (2026-08-02)
使用真实 Key `sk-9dd722...` 跑通：
- Chat non-stream ✅ HTTP 200 / 506 tokens
- Chat stream + reasoning ✅ 1026 frames / 3577 chars 思考过程
- FIM beta completions ✅ HTTP 200 / 正确补全代码
- 单元测试 `ChatMessageDto 序列化格式` ✅ PASS

## 📁 源码
完整工程在独立工作目录：`/workspace/DeepCoder/`（本仓库为 APK 发布仓库）
