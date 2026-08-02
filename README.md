# DeepCoder - DeepSeek 代码助手 Android App

> 对接 DeepSeek V4 大模型接口，以写代码为主的安卓客户端

## 功能特性

| 模块 | 说明 |
|------|------|
| 🔑 **API Key 配置** | EncryptedSharedPreferences 加密存储，sk- 前缀校验 |
| 💬 **代码对话** | SSE 流式响应 + thinking_content 思考模式逐字显示 |
| 📝 **代码编辑器** | 支持 13 种语言 + FIM (Fill-In-Middle) 代码补全 |
| 📚 **历史会话** | Room 数据库持久化，新建 / 切换 / 删除会话 |
| ⚙️ **设置页** | 模型切换 (v4-flash / v4-pro)、温度、TopP、MaxTokens |

## 技术栈

- **语言**: Kotlin 2.0
- **UI**: Jetpack Compose + Material 3
- **架构**: MVVM + Clean Architecture (UI / Domain / Data 三层)
- **网络**: Retrofit + OkHttp + 认证/限流拦截器 + SSE 流式解析
- **依赖注入**: Hilt
- **数据库**: Room
- **配置存储**: DataStore Preferences + EncryptedSharedPreferences

## DeepSeek API 对接

| 接口 | URL | 说明 |
|------|-----|------|
| Chat Completions | `POST /chat/completions` | 对话，stream=true，reasoning_content |
| FIM Completions  | `POST /beta/completions` | 代码补全 (prompt + suffix) |

## 安装包下载

| 版本 | 文件 | 大小 |
|------|------|------|
| **Debug 签名 (推荐直接安装)** | [DeepCoder-v1.0.0-debug.apk](./releases/DeepCoder-v1.0.0-debug.apk) | 25 MB |
| Release 未签名 | [DeepCoder-v1.0.0-release-unsigned.apk](./releases/DeepCoder-v1.0.0-release-unsigned.apk) | 14 MB |

### 安装方法

1. 下载 `DeepCoder-v1.0.0-debug.apk`
2. 传到安卓手机 / 雷电模拟器 / 夜神模拟器
3. 允许安装未知来源应用
4. 双击安装，打开 App
5. 进入 Setup 页面填入你的 DeepSeek API Key (https://platform.deepseek.com/ 获取)
6. 开始代码对话！

## 项目结构

```
app/src/main/java/com/deepseek/coder/
├── app/          Hilt App 入口
├── core/         Outcome / AppLogger / DispatcherProvider
├── di/           CoreModule / NetworkModule / DatabaseModule
├── domain/       领域模型 + Use Cases
├── data/         Repository + Retrofit API + Room DB
│   ├── remote/   DeepSeekApi / FimApi / DTO / 拦截器 / SSE 解析
│   ├── db/       Entity / DAO / Database / Mapper
│   └── settings/ credentials/
└── ui/           Compose Screens + ViewModels + NavGraph
```
