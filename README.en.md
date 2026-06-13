# FakeOCAT

**Languages:** [中文](README.md) | [日本語](README.ja.md) | English (current)

FakeOCAT is a multi-AI-service language learning Android app, inspired by [OCAT](https://ocat.app/) and developed as a personal hobby project. The core philosophy is BYOK (Bring Your Own Key) — users only need to supply their own API key to unlock all features, with no subscription fees required.

---

## 📱 Features

- **🤖 12 AI Providers** — Built-in API adapters for OpenAI, Anthropic (Claude), Google (Gemini), Grok (xAI), Xiaomi MiMo, DeepSeek, Alibaba Qwen, Tencent Hunyuan, Baidu ERNIE, Zhipu AI (GLM), Kimi (Moonshot), and MiniMax. Switch freely at any time.
- **🔀 Multi-Endpoint / Multi-Plan Support** — 8 providers offer multiple access methods: OpenAI (Direct / Azure OpenAI), Anthropic (Direct / AWS Bedrock / Google Vertex AI), Gemini (AI Studio / Vertex AI), Xiaomi MiMo (Pay-as-you-go / Token Plan), Alibaba Qwen (Compatible Mode / Native API), Baidu ERNIE (Qianfan v2 / Qianfan v1), Tencent Hunyuan (Independent API / Tencent Cloud API), Kimi (China / International).
- **📋 Dynamic Model Fetching** — Query available model lists from each provider's API in real time, with dropdown selection and manual model name input.
- **📖 Three Learning Modes** — "How to Say in X" (CN→Foreign translation), "What Does It Mean" (Foreign→CN explanation), and "Free Chat" (open-ended conversation), covering both input and output practice.
- **⚡ Streaming Responses** — SSE-based streaming via OkHttp, displaying AI replies token by token in real time.
- **📝 Markdown Rendering** — Rich text formatting with code highlighting, tables, lists, and pronunciation buttons with IPA annotations for keywords.
- **🔊 TTS Text-to-Speech** — AI replies can be played back as speech. Tap any sentence to hear it pronounced, enhancing listening and speaking skills.
- **📜 Chat History** — Full conversation history with timestamp-based browsing and session context restoration.
- **🔖 Bookmarks** — One-tap bookmarking of important conversation snippets. Bookmarks and chat history are stored independently — clearing history never removes bookmarked content.
- **🌐 22 UI Languages** — The app interface supports Simplified Chinese, English, 日本語, 한국어, Français, Deutsch, Español, Português, Italiano, Nederlands, Svenska, Polski, Čeština, Русский, العربية, हिन्दी, Bahasa Indonesia, עברית, Ελληνικά, Türkçe, Tiếng Việt, and ไทย. Switching takes effect instantly.
- **🔒 Secure Storage** — API keys and sensitive configuration are stored locally using `EncryptedSharedPreferences` (AES-256 encryption) and never uploaded to any server.
- **🎨 Material3 Theme** — Built with Jetpack Compose Material3, supporting light / dark / follow-system theme switching.

---

## 🤖 Supported AI Providers

| Provider | Endpoint Variants | Authentication | Default Model |
|----------|-------------------|----------------|---------------|
| **OpenAI** | Direct / Azure OpenAI | Bearer Token / Azure API Key | `gpt-5.4-mini` |
| **Anthropic** | Direct / AWS Bedrock / GCP Vertex AI | API Key Header / AWS SigV4 / GCP OAuth 2.0 | `claude-haiku-4-5` |
| **Gemini** | AI Studio / Vertex AI | API Key (Query) / GCP OAuth 2.0 | `gemini-2.5-flash` |
| **Grok (xAI)** | Direct | Bearer Token | `grok-3-mini` |
| **Xiaomi MiMo** | Pay-as-you-go / Token Plan | Bearer Token | `mimo-v2-flash` |
| **DeepSeek** | Direct | Bearer Token | `deepseek-chat` |
| **Alibaba Qwen** | DashScope Compatible / DashScope Native | Bearer Token | `qwen-mt-lite` |
| **Tencent Hunyuan** | Independent API / Tencent Cloud TC3 | Bearer Token / TC3-HMAC-SHA256 Signing | `hunyuan-lite` |
| **Baidu ERNIE** | Qianfan v2 / Qianfan v1 | Bearer Token / OAuth 2.0 Access Token | `ernie-speed-128k` |
| **Zhipu AI** | Direct | Bearer Token | `glm-4.7-flash` |
| **Kimi (Moonshot)** | China (moonshot.cn) / International (moonshot.ai) | Bearer Token | `moonshot-v1-8k` |
| **MiniMax** | Direct | Bearer Token | `minimax-m2.5-highspeed` |

---

## 🛠 Tech Stack

| Category | Technology |
|----------|------------|
| Language | Kotlin 2.2 |
| UI Framework | Jetpack Compose + Material3 |
| Navigation | Navigation Compose |
| Networking | OkHttp + SSE (streaming responses) |
| Persistence | DataStore (preferences) + EncryptedSharedPreferences (encrypted storage) + SQLite (messages & bookmarks) |
| Async | Kotlin Coroutines + Flow |
| Testing | JUnit + MockK + Turbine + MockWebServer |
| Build | Gradle (Kotlin DSL) + Version Catalog |

### Network Layer Optimizations

- **DNS Prefetch Cache** — Asynchronously pre-resolves all AI provider hostnames at app launch, eliminating DNS lookup latency on first request.
- **Connection Prewarming** — Pre-establishes TCP + TLS connections when switching providers, reducing TTFT for the first streaming request.
- **Shared Connection Pool** — 20 idle connections with 10-minute keep-alive, reducing redundant handshake overhead.
- **Certificate Pinning** — Enabled in release builds to prevent man-in-the-middle attacks.
- **First Token Timeout** — 15-second first-token timeout detection, compatible with cold-start scenarios, for fast failure and avoiding long waits.
- **Response Caching** — Dual-layer cache (in-memory LRU with 128 entries + disk), caching "What Means" mode short queries for 30 minutes.

---

## 📸 Screenshots



---

## 📦 Installation & Usage

### Requirements

- Android 7.0 (API 24) or above

### Download & Install

1. Download the latest APK from the Releases page
2. Install on your device (allow installation from unknown sources if prompted)

### Quick Start

1. Launch the app and navigate to the **Settings** screen via the bottom navigation bar
2. Select an AI provider from the **AI Provider** dropdown
3. Enter your **API Key** (stored locally on device only)
4. If the selected provider supports multiple endpoints, choose an access method from the **API Endpoint** dropdown
5. To customize the model, select from the **Model** dropdown or enter a model name manually
6. Tap **Save**
7. Return to the **Chat** screen, select a learning mode, and start chatting

---

## 🚀 Build Guide

### Requirements

- **Android Studio** — Latest stable recommended (Ladybug or newer)
- **JDK 17+** — The project has `gradle.properties` configured to point to Eclipse Adoptium JDK 25; adjust or remove this path for your local environment
- **Android SDK** — Managed automatically by Android Studio (compileSdk 35, minSdk 24)

### Quick Start

```bash
# 1. Clone the repository
git clone https://github.com/your-username/FakeOCAT.git

# 2. Open the project directory in Android Studio and wait for Gradle sync

# 3. Connect a device or launch an emulator, then click Run

# 4. On first launch, go to Settings → select an AI provider → enter your API key
```

### Command-Line Build

```bash
# Debug build
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest
```

---

## 📁 Project Structure

```
FakeOCAT/
├── app/src/main/java/com/example/fakeocat/
│   ├── ui/                             # Compose UI layer
│   │   ├── screens/                    # Screens: Chat / Bookmarks / History / Settings
│   │   ├── components/                 # Reusable components: MarkdownMessage (rendering + pronunciation buttons)
│   │   ├── viewmodel/                  # ViewModels + business orchestration
│   │   │   ├── ChatViewModel.kt        # Main ViewModel: state management & coordination
│   │   │   ├── StreamOrchestrator.kt   # Streaming request orchestrator
│   │   │   ├── PromptBuilder.kt        # Prompt template builder (3 learning modes)
│   │   │   └── LanguageConfigManager.kt # Language configuration manager
│   │   └── theme/                      # Material3 theme config (Color / Theme / Type)
│   ├── network/                        # Network layer
│   │   ├── AiProviderCatalog.kt        # 12 providers + endpoint configuration catalog
│   │   ├── EndpointProfile.kt          # Endpoint profile data model (URL templates, auth, protocol)
│   │   ├── LlmClient.kt               # Unified streaming chat client (OkHttp SSE)
│   │   ├── RequestBuilder.kt           # Protocol-specific request body builder (10 API protocols)
│   │   ├── ProviderStreamParsers.kt    # Unified SSE stream parser dispatcher
│   │   ├── ModelFetcher.kt             # Dynamic model list fetcher
│   │   ├── ModelCache.kt               # Model list in-memory cache (30-min TTL)
│   │   ├── ConnectionPrewarmer.kt      # Connection prewarmer
│   │   ├── TtsManager.kt              # TTS text-to-speech manager
│   │   ├── ResponseCache.kt           # AI response cache (in-memory LRU + disk)
│   │   ├── auth/                       # Authentication signers
│   │   │   ├── AwsSigV4Signer.kt      # AWS Signature V4 (Bedrock)
│   │   │   ├── GcpOAuth2Signer.kt     # GCP Service Account OAuth 2.0 (Vertex AI)
│   │   │   ├── TencentCloudTC3Signer.kt # Tencent Cloud TC3-HMAC-SHA256 signing
│   │   │   └── BaiduAccessTokenFetcher.kt # Baidu OAuth 2.0 Access Token
│   │   └── parsers/                    # Custom SSE parsers
│   │       ├── DashScopeNativeParser.kt  # Alibaba DashScope native format
│   │       ├── QianfanV1Parser.kt        # Baidu Qianfan v1 REST-RPC format
│   │       └── TencentCloudTC3Parser.kt  # Tencent Cloud TC3 response format
│   └── data/                           # Data layer
│       ├── PreferencesManager.kt       # Configuration manager (DataStore + EncryptedSharedPreferences)
│       └── db/                         # SQLite database
│           ├── DatabaseHelper.kt       # Database helper (Base64 obfuscation storage)
│           └── entity/                 # Entities: MessageEntity / BookmarkEntity
├── app/src/main/res/                   # Resources (includes strings.xml for 22 languages)
├── app/src/test/                       # Unit tests
├── app/src/androidTest/                # Instrumented tests (Android device tests)
├── docs/                               # Design & integration documents
└── gradle/libs.versions.toml           # Dependency version catalog
```

---

## ⚠️ Disclaimer

Although the app includes integration code for 12 providers, due to personal constraints only **Gemini** and **Xiaomi MiMo** have been fully tested and are guaranteed to work. The other 10 provider adapters are unverified implementations written without access to actual API keys. If you encounter issues with other providers, issues and pull requests are welcome.

---

## 📄 License

This project is open-sourced under the [MIT License](LICENSE).
