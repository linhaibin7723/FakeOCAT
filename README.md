# FakeOCAT

**语言 / Languages:** 中文（当前） | [日本語](README.ja.md) | [English](README.en.md)

FakeOCAT 是一款支持多种 AI 服务的语言学习 Android 应用，受 [OCAT](https://ocat.app/) 启发，基于个人兴趣独立开发。核心理念是"自带密钥 (BYOK)"——用户需自备 API 密钥即可免费使用全部功能，无需支付任何订阅费用。

---

## 📱 功能特性

- **🤖 12 家 AI 服务商** — 内置 OpenAI、Anthropic (Claude)、Google (Gemini)、Grok (xAI)、小米 MiMo、DeepSeek、阿里千问、腾讯混元、百度文心一言、智谱 (GLM)、Kimi (月之暗面)、MiniMax 的 API 适配，自由选择切换。
- **🔀 多端点 / 多计划支持** — 8 家服务商提供多种接入方式：OpenAI（直连 / Azure OpenAI）、Anthropic（直连 / AWS Bedrock / Google Vertex AI）、Gemini（AI Studio / Vertex AI）、小米 MiMo（按量付费 / Token Plan）、阿里千问（兼容模式 / 原生 API）、百度文心一言（千帆 v2 / 千帆 v1）、腾讯混元（独立 API / 腾讯云 API）、Kimi（中国版 / 国际版）。
- **📋 动态模型获取** — 通过 API 实时查询各服务商可用模型列表，支持下拉选择与手动输入模型名。
- **📖 三种学习模式** — "用 X 语怎么说"（中→外翻译）、"什么意思"（外→中解释）、"自由聊天"（开放式对话），覆盖输入输出双向练习。
- **⚡ 流式响应** — 基于 OkHttp SSE 的流式传输，逐 token 实时显示 AI 回复。
- **📝 Markdown 渲染** — 支持代码高亮、表格、列表等富文本格式，关键词附带发音按钮与国际音标标注。
- **🔊 TTS 语音朗读** — 支持 AI 回复的文本转语音播放，点击任意句子即可发音，提升听力与口语体验。
- **📜 对话历史** — 完整保留历史会话记录，支持按时间回顾与恢复会话上下文。
- **🔖 书签收藏** — 一键收藏重要对话片段，收藏与历史记录独立存储，清空聊天历史不影响已收藏内容。
- **🌐 22 种界面语言** — 应用 UI 覆盖简体中文、English、日本語、한국어、Français、Deutsch、Español、Português、Italiano、Nederlands、Svenska、Polski、Čeština、Русский、العربية、हिन्दी、Bahasa Indonesia、עברית、Ελληνικά、Türkçe、Tiếng Việt、ไทย，一键切换即时生效。
- **🔒 安全存储** — API Key 与敏感配置使用 `EncryptedSharedPreferences`（AES-256 加密）本地存储，绝不上传至任何服务器。
- **🎨 Material3 主题** — 基于 Jetpack Compose Material3 构建，支持亮色 / 暗色 / 跟随系统主题切换。

---

## 🤖 支持的 AI 服务商

| 服务商 | 端点变体 | 认证方式 |
|--------|----------|----------|
| **OpenAI** | 直连 / Azure OpenAI | Bearer Token / Azure API Key |
| **Anthropic** | 直连 / AWS Bedrock / GCP Vertex AI | API Key Header / AWS SigV4 / GCP OAuth 2.0 |
| **Gemini** | AI Studio / Vertex AI | API Key (Query) / GCP OAuth 2.0 |
| **Grok (xAI)** | 直连 | Bearer Token |
| **小米 MiMo** | 按量付费 / Token Plan | Bearer Token |
| **DeepSeek** | 直连 | Bearer Token |
| **阿里千问** | DashScope 兼容模式 / DashScope 原生 API | Bearer Token |
| **腾讯混元** | 独立 API / 腾讯云 TC3 | Bearer Token / TC3-HMAC-SHA256 签名 |
| **百度文心一言** | 千帆 v2 / 千帆 v1 | Bearer Token / OAuth 2.0 Access Token |
| **智谱 AI** | 直连 | Bearer Token |
| **Kimi（月之暗面）** | 中国版 (moonshot.cn) / 国际版 (moonshot.ai) | Bearer Token |
| **MiniMax** | 直连 | Bearer Token |

---

## 🛠 技术架构

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.2 |
| UI 框架 | Jetpack Compose + Material3 |
| 导航 | Navigation Compose |
| 网络 | OkHttp + SSE（流式响应） |
| 持久化 | DataStore（偏好设置） + EncryptedSharedPreferences（加密存储） + SQLite（对话与书签） |
| 异步 | Kotlin Coroutines + Flow |
| 测试 | JUnit + MockK + Turbine + MockWebServer |
| 构建 | Gradle (Kotlin DSL) + Version Catalog |

### 网络层优化

- **DNS 预解析缓存** — 应用启动时异步预解析所有 AI 服务商域名，消除首次请求的 DNS 查询延迟。
- **连接预热** — 切换服务商时提前建立 TCP + TLS 连接，降低首次流式请求的 TTFT。
- **共享连接池** — 20 个空闲连接、10 分钟 keep-alive，减少重复握手开销。
- **首 token 超时** — 15 秒首 token 超时检测，兼容冷启动场景，快速失败避免长时间等待。
- **响应缓存** — 内存 LRU（128 条）+ 磁盘双层缓存，"什么意思"模式的短查询结果缓存 30 分钟。

---

## 📸 截图



---

## 📦 安装与使用

### 系统要求

- Android 7.0 (API 24) 及以上

### 下载安装

1. 从 Releases 页面下载最新 APK
2. 在设备上安装（需允许未知来源安装）

### 快速开始

1. 启动应用后，进入底部导航栏的 **设置** 页面
2. 在 **AI 服务商** 下拉框中选择一个服务商
3. 填入您的 **API Key**（密钥仅存储在本地设备）
4. 如所选服务商支持多端点，在 **API 端点** 下拉框中选择接入方式
5. 如需自定义模型，可在 **模型** 下拉框中选择或手动输入
6. 点击 **保存**
7. 返回 **聊天** 页面，选择学习模式，开始对话

---

## 🚀 构建指南

### 环境要求

- **Android Studio** — 推荐最新稳定版（Ladybug 或更新）
- **JDK 21+** — Gradle 9.x 需要 JDK 21 或以上。项目已在 `gradle.properties` 中配置指向 Android Studio 附带的 JBR 路径，请根据本地环境调整
- **Android SDK** — 由 Android Studio 自动管理（compileSdk 35, minSdk 24）

### 快速开始

```bash
# 1. 克隆项目
git clone https://github.com/your-username/FakeOCAT.git

# 2. 用 Android Studio 打开项目目录，等待 Gradle 同步完成

# 3. 连接设备或启动模拟器，点击运行

# 4. 首次启动后，进入「设置」→ 选择 AI 服务商 → 填入 API 密钥
```

### 命令行构建

```bash
# Debug 构建
./gradlew assembleDebug

# 运行单元测试
./gradlew testDebugUnitTest
```

---

## 📁 项目结构

```
FakeOCAT/
├── app/src/main/java/com/example/fakeocat/
│   ├── ui/                             # Compose UI 层
│   │   ├── screens/                    # 页面：Chat / Bookmarks / History / Settings
│   │   ├── components/                 # 可复用组件：MarkdownMessage（Markdown 渲染 + 发音按钮）
│   │   ├── viewmodel/                  # ViewModel + 业务编排
│   │   │   ├── ChatViewModel.kt        # 主 ViewModel：状态管理与业务协调
│   │   │   ├── StreamOrchestrator.kt   # 流式请求编排器
│   │   │   ├── PromptBuilder.kt        # Prompt 模板构建（三种学习模式）
│   │   │   └── LanguageConfigManager.kt # 语言配置管理
│   │   └── theme/                      # Material3 主题配置（Color / Theme / Type）
│   ├── network/                        # 网络层
│   │   ├── AiProviderCatalog.kt        # 12 家服务商 + 端点配置目录
│   │   ├── EndpointProfile.kt          # 端点配置数据模型（URL 模板、认证、协议）
│   │   ├── LlmClient.kt               # 统一流式聊天客户端（OkHttp SSE）
│   │   ├── RequestBuilder.kt           # 按协议构建请求体（10 种 API 协议）
│   │   ├── ProviderStreamParsers.kt    # 统一 SSE 流解析器分发
│   │   ├── ModelFetcher.kt             # 动态模型列表获取
│   │   ├── ModelCache.kt               # 模型列表内存缓存（30 分钟 TTL）
│   │   ├── ConnectionPrewarmer.kt      # 连接预热器
│   │   ├── TtsManager.kt              # TTS 语音合成管理
│   │   ├── ResponseCache.kt           # AI 响应缓存（内存 LRU + 磁盘）
│   │   ├── auth/                       # 认证签名器
│   │   │   ├── AwsSigV4Signer.kt      # AWS Signature V4（Bedrock）
│   │   │   ├── GcpOAuth2Signer.kt     # GCP Service Account OAuth 2.0（Vertex AI）
│   │   │   ├── TencentCloudTC3Signer.kt # 腾讯云 TC3-HMAC-SHA256 签名
│   │   │   └── BaiduAccessTokenFetcher.kt # 百度 OAuth 2.0 Access Token
│   │   └── parsers/                    # 自定义 SSE 解析器
│   │       ├── DashScopeNativeParser.kt  # 阿里 DashScope 原生格式
│   │       ├── QianfanV1Parser.kt        # 百度千帆 v1 REST-RPC 格式
│   │       └── TencentCloudTC3Parser.kt  # 腾讯云 TC3 响应格式
│   └── data/                           # 数据层
│       ├── PreferencesManager.kt       # 配置管理（DataStore + EncryptedSharedPreferences）
│       └── db/                         # SQLite 数据库
│           ├── DatabaseHelper.kt       # 数据库助手（Base64 混淆存储）
│           └── entity/                 # 实体：MessageEntity / BookmarkEntity
├── app/src/main/res/                   # 资源文件（含 22 种语言的 strings.xml）
├── app/src/test/                       # 单元测试
├── app/src/androidTest/                # 仪表化测试（Android 设备测试）
├── docs/                               # 设计与集成文档
└── gradle/libs.versions.toml           # 依赖版本目录
```

---

## ⚠️ 免责声明

虽然应用内集成了 12 家服务商的代码，但受限于个人条件，目前 **Gemini**、**小米 MiMo** 和 **DeepSeek** 已经过完整测试并确保可用。其他 9 家服务商的适配代码为"盲写"实现，未经实际 API 验证。如在使用其他服务商时遇到问题，欢迎提交 Issue 或 Pull Request。

---

## 📄 许可

本项目采用 [MIT License](LICENSE) 开源。
