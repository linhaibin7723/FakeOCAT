# FakeOCAT

**言語 / Languages:** [中文](README.md) | 日本語（現在） | [English](README.en.md)

FakeOCAT は、複数の AI サービスに対応した語学学習 Android アプリです。[OCAT](https://ocat.app/) にインスパイアされ、個人の趣味として開発されました。基本理念は「BYOK（Bring Your Own Key）」——ユーザーは自分の API キーを用意するだけで、すべての機能を無料で利用でき、サブスクリプション料金は一切不要です。

---

## 📱 機能

- **🤖 12 社の AI プロバイダー** — OpenAI、Anthropic (Claude)、Google (Gemini)、Grok (xAI)、小米 MiMo、DeepSeek、Alibaba 通義千問、Tencent 混元、百度文心一言、智譜 AI (GLM)、Kimi (月之暗面)、MiniMax の API アダプターを内蔵。いつでも自由に切り替え可能。
- **🔀 マルチエンドポイント / マルチプラン対応** — 8 社のプロバイダーが複数の接続方式を提供：OpenAI（ダイレクト / Azure OpenAI）、Anthropic（ダイレクト / AWS Bedrock / Google Vertex AI）、Gemini（AI Studio / Vertex AI）、小米 MiMo（従量課金 / Token Plan）、Alibaba 通義千問（互換モード / ネイティブ API）、百度文心一言（千帆 v2 / 千帆 v1）、Tencent 混元（独立 API / Tencent Cloud API）、Kimi（中国版 / 国際版）。
- **📋 動的モデル取得** — 各プロバイダーの API から利用可能なモデル一覧をリアルタイムで取得し、ドロップダウン選択または手動入力でモデルを指定可能。
- **📖 3 つの学習モード** — 「X語でどう言う？」（中→外翻訳）、「どういう意味？」（外→中説明）、「フリーチャット」（自由対話）で、インプットとアウトプットの両方をカバー。
- **⚡ ストリーミング応答** — OkHttp ベースの SSE ストリーミングにより、トークン単位で AI の返信をリアルタイム表示。
- **📝 Markdown レンダリング** — コードハイライト、テーブル、リストなどのリッチテキストに対応。キーワードには発音ボタンと国際音声記号 (IPA) の注釈付き。
- **🔊 TTS 音声読み上げ** — AI の返信を音声再生可能。任意の文をタップして発音を聞くことで、リスニングとスピーキングを強化。
- **📜 チャット履歴** — タイムスタンプ付きの完全な会話履歴と、セッションコンテキストの復元に対応。
- **🔖 ブックマーク** — 重要な会話の抜粋をワンタップで保存。ブックマークとチャット履歴は独立して保存され、履歴を消去してもブックマークは保持されます。
- **🌐 22 言語 UI** — アプリのインターフェースは、簡体字中国語、English、日本語、한국어、Français、Deutsch、Español、Português、Italiano、Nederlands、Svenska、Polski、Čeština、Русский、العربية、हिन्दी、Bahasa Indonesia、עברית、Ελληνικά、Türkçe、Tiếng Việt、ไทย に対応。切り替えは即座に反映されます。
- **🔒 セキュアストレージ** — API キーと機密設定は `EncryptedSharedPreferences`（AES-256 暗号化）でローカルに保存され、外部サーバーに送信されることはありません。
- **🎨 Material3 テーマ** — Jetpack Compose Material3 で構築され、ライト / ダーク / システム追随のテーマ切り替えに対応。

---

## 🤖 対応 AI プロバイダー

| プロバイダー | エンドポイント | 認証方式 |
|-------------|---------------|----------|
| **OpenAI** | ダイレクト / Azure OpenAI | Bearer Token / Azure API Key |
| **Anthropic** | ダイレクト / AWS Bedrock / GCP Vertex AI | API Key Header / AWS SigV4 / GCP OAuth 2.0 |
| **Gemini** | AI Studio / Vertex AI | API Key (Query) / GCP OAuth 2.0 |
| **Grok (xAI)** | ダイレクト | Bearer Token |
| **小米 MiMo** | 従量課金 / Token Plan | Bearer Token |
| **DeepSeek** | ダイレクト | Bearer Token |
| **Alibaba 通義千問** | DashScope 互換 / DashScope ネイティブ | Bearer Token |
| **Tencent 混元** | 独立 API / Tencent Cloud TC3 | Bearer Token / TC3-HMAC-SHA256 署名 |
| **百度文心一言** | 千帆 v2 / 千帆 v1 | Bearer Token / OAuth 2.0 Access Token |
| **智譜 AI** | ダイレクト | Bearer Token |
| **Kimi（月之暗面）** | 中国版 (moonshot.cn) / 国際版 (moonshot.ai) | Bearer Token |
| **MiniMax** | ダイレクト | Bearer Token |

---

## 🛠 技術スタック

| カテゴリ | 技術 |
|----------|------|
| 言語 | Kotlin 2.2 |
| UI フレームワーク | Jetpack Compose + Material3 |
| ナビゲーション | Navigation Compose |
| ネットワーク | OkHttp + SSE（ストリーミング応答） |
| 永続化 | DataStore（設定） + EncryptedSharedPreferences（暗号化ストレージ） + SQLite（メッセージ＆ブックマーク） |
| 非同期処理 | Kotlin Coroutines + Flow |
| テスト | JUnit + MockK + Turbine + MockWebServer |
| ビルド | Gradle (Kotlin DSL) + Version Catalog |

### ネットワーク層の最適化

- **DNS プリフェッチキャッシュ** — アプリ起動時にすべての AI プロバイダーのホスト名を非同期で事前解決し、初回リクエスト時の DNS ルックアップ遅延を排除。
- **コネクションプリウォーム** — プロバイダー切替時に TCP + TLS 接続を事前確立し、初回ストリーミングリクエストの TTFT を短縮。
- **共有コネクションプール** — アイドル接続 20 本、keep-alive 10 分で、重複ハンドシェイクのオーバーヘッドを削減。
- **ファーストトークンタイムアウト** — 15 秒の初回トークンタイムアウト検出。コールドスタート時の互換性を確保しつつ、長時間の待ちを回避。
- **レスポンスキャッシュ** — メモリ LRU（128 エントリ）＋ディスクの二層キャッシュ。「どういう意味？」モードの短いクエリ結果を 30 分間キャッシュ。

---

## 📸 スクリーンショット



---

## 📦 インストールと使い方

### 必要要件

- Android 7.0 (API 24) 以上

### ダウンロードとインストール

1. Releases ページから最新の APK をダウンロード
2. デバイスにインストール（不明なソースからのインストールを許可）

### クイックスタート

1. アプリを起動し、下部ナビゲーションバーから **設定** 画面へ移動
2. **AI プロバイダー** ドロップダウンからプロバイダーを選択
3. **API キー** を入力（キーはローカルデバイスにのみ保存）
4. 選択したプロバイダーがマルチエンドポイントに対応している場合、**API エンドポイント** ドロップダウンから接続方式を選択
5. モデルをカスタマイズする場合、**モデル** ドロップダウンから選択するかモデル名を手動入力
6. **保存** をタップ
7. **チャット** 画面に戻り、学習モードを選択して会話を開始

---

## 🚀 ビルドガイド

### 必要環境

- **Android Studio** — 最新安定版推奨（Ladybug 以降）
- **JDK 21+** — Gradle 9.x は JDK 21 以上が必要です。プロジェクトの `gradle.properties` には Android Studio 付属の JBR へのパスが設定されています。ローカル環境に合わせて調整してください
- **Android SDK** — Android Studio が自動管理（compileSdk 35, minSdk 24）

### クイックスタート

```bash
# 1. リポジトリをクローン
git clone https://github.com/your-username/FakeOCAT.git

# 2. Android Studio でプロジェクトディレクトリを開き、Gradle 同期を待つ

# 3. デバイスを接続またはエミュレーターを起動し、実行をクリック

# 4. 初回起動後、「設定」→ AI プロバイダーを選択 → API キーを入力
```

### コマンドラインビルド

```bash
# デバッグビルド
./gradlew assembleDebug

# ユニットテスト実行
./gradlew testDebugUnitTest
```

---

## 📁 プロジェクト構成

```
FakeOCAT/
├── app/src/main/java/com/example/fakeocat/
│   ├── ui/                             # Compose UI 層
│   │   ├── screens/                    # 画面：Chat / Bookmarks / History / Settings
│   │   ├── components/                 # 再利用可能コンポーネント：MarkdownMessage（レンダリング＋発音ボタン）
│   │   ├── viewmodel/                  # ViewModel + ビジネスロジック
│   │   │   ├── ChatViewModel.kt        # メイン ViewModel：状態管理＆協調
│   │   │   ├── StreamOrchestrator.kt   # ストリーミングリクエストオーケストレーター
│   │   │   ├── PromptBuilder.kt        # プロンプトテンプレート構築（3 つの学習モード）
│   │   │   └── LanguageConfigManager.kt # 言語設定マネージャー
│   │   └── theme/                      # Material3 テーマ設定（Color / Theme / Type）
│   ├── network/                        # ネットワーク層
│   │   ├── AiProviderCatalog.kt        # 12 プロバイダー＋エンドポイント設定カタログ
│   │   ├── EndpointProfile.kt          # エンドポイントプロファイルデータモデル（URLテンプレート、認証、プロトコル）
│   │   ├── LlmClient.kt               # 統合ストリーミングチャットクライアント（OkHttp SSE）
│   │   ├── RequestBuilder.kt           # プロトコル別リクエストボディ構築（10 種の API プロトコル）
│   │   ├── ProviderStreamParsers.kt    # 統合 SSE ストリームパーサー分岐
│   │   ├── ModelFetcher.kt             # 動的モデル一覧取得
│   │   ├── ModelCache.kt               # モデル一覧メモリキャッシュ（30 分 TTL）
│   │   ├── ConnectionPrewarmer.kt      # コネクションプリウォーマー
│   │   ├── TtsManager.kt              # TTS 音声合成マネージャー
│   │   ├── ResponseCache.kt           # AI レスポンスキャッシュ（メモリ LRU ＋ディスク）
│   │   ├── auth/                       # 認証署名器
│   │   │   ├── AwsSigV4Signer.kt      # AWS Signature V4（Bedrock）
│   │   │   ├── GcpOAuth2Signer.kt     # GCP Service Account OAuth 2.0（Vertex AI）
│   │   │   ├── TencentCloudTC3Signer.kt # Tencent Cloud TC3-HMAC-SHA256 署名
│   │   │   └── BaiduAccessTokenFetcher.kt # 百度 OAuth 2.0 Access Token
│   │   └── parsers/                    # カスタム SSE パーサー
│   │       ├── DashScopeNativeParser.kt  # Alibaba DashScope ネイティブ形式
│   │       ├── QianfanV1Parser.kt        # 百度千帆 v1 REST-RPC 形式
│   │       └── TencentCloudTC3Parser.kt  # Tencent Cloud TC3 レスポンス形式
│   └── data/                           # データ層
│       ├── PreferencesManager.kt       # 設定管理（DataStore + EncryptedSharedPreferences）
│       └── db/                         # SQLite データベース
│           ├── DatabaseHelper.kt       # データベースヘルパー（Base64 混淆ストレージ）
│           └── entity/                 # エンティティ：MessageEntity / BookmarkEntity
├── app/src/main/res/                   # リソース（22 言語の strings.xml を含む）
├── app/src/test/                       # ユニットテスト
├── app/src/androidTest/                # インストルメント化テスト（Android デバイステスト）
├── docs/                               # 設計・統合ドキュメント
└── gradle/libs.versions.toml           # 依存関係バージョンカタログ
```

---

## ⚠️ 免責事項

アプリには 12 社のプロバイダー向けの統合コードが含まれていますが、個人開発の制約により、現在 **Gemini**、**小米 MiMo**、**DeepSeek** が十分にテストされ、動作が保証されています。その他 9 社のプロバイダーアダプターは、実際の API キーにアクセスできない状態で実装された未検証のコードです。他のプロバイダーで問題が発生した場合は、Issue や Pull Request を歓迎します。

---

## 📄 ライセンス

本プロジェクトは [MIT License](LICENSE) で公開されています。
