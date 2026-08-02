# DeepCoder v1.0.0 - Bug Fix Release

**Build Date:** 2026-08-02

## 修复 Fixes

### 🔴 请求格式错误修复 (Critical)
- **问题**: 发送聊天请求时报错  
  `请求格式错误：Failed to deserialize the JSON body into the target type: messages[0]: content should be a string or a list`
- **根因**: `ChatMessageDto.content` 使用了 `MessageContentDto` 密封类，序列化后产生嵌套对象：
  ```json
  // ❌ 错误格式
  {"content":{"type":"Text","text":"你好"}}
  ```
- **修复**:
  1. 请求侧 `ChatMessageDto.content` 改为 `String?`，序列化后为纯字符串：
     ```json
     // ✅ 正确格式
     {"content":"你好"}
     ```
  2. 响应侧新增 `ChatResponseMessageDto`，使用自定义 `ContentAsStringSerializer` 处理服务端返回 content 可能为 string / array 两种格式的多态问题
  3. 更新 `ChatRepository.domainToDto` / `dtoToDomain` 映射逻辑

### 其他修复
- 修复 `ChatRepository` 中 `reasoning: String?` 空安全调用错误（`m.reasoning.takeIf` → `m.reasoning?.takeIf`）

## 验证 Verification (云端测试通过)
| 测试项 | 结果 | 说明 |
|---|---|---|
| 单元测试 `chatMessageDto_content_is_plain_string` | ✅ PASS | JSON: `{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"写 HelloWorld Kotlin"}]}` |
| Chat 非流式 `deepseek-v4-flash` | ✅ HTTP 200 | 返回 HelloWorld Kotlin 代码，usage 正常 (506 tokens) |
| Chat 流式 + reasoning_effort=medium | ✅ PASS | 1026 frames, 3577 chars reasoning delta |
| FIM `/beta/completions` | ✅ HTTP 200 | 正确补全冒泡排序内层 swap 代码 |

## 文件 Hashes (SHA-256)
生成时请在本地运行 `sha256sum releases/*.apk apk/*.apk` 验证。
