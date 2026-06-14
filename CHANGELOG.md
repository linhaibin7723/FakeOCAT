# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.1] - 2026-06-14

### Added
- 新增小米 MiMo Provider 支持，基于 OpenAI 兼容协议接入，API 地址 `api.xiaomimimo.com`。
- MiMo 硬编码模型列表：`mimo-v2-flash`、`mimo-v2-pro`。
- MiMo Token Plan 端点支持（`token-plan-cn.xiaomimimo.com`），兼容按量付费与 Token Plan 两种接入方式。

### Fixed
- 修复 Gemini `modelsEndpoint` 使用错误的 `/v1/` 路径，改为正确的 `/v1beta/models`。
- 修复 `NetworkOnMainThreadException`：`LlmClient.streamChatWithProfile()` 中 OkHttp 同步调用未切换到 IO 调度器的问题。
- 修复 SSE 流式解析中 `JSONObject.optString()` 将 JSON `null` 返回为字符串 `"null"` 导致的异常。
- 过滤推理模型（如 MiMo）的 `reasoning_content` 思考过程，不再显示给用户。
- 首 token 超时从 5 秒增大到 15 秒，兼容 Gemini 等 API 的冷启动场景。
- 改进错误消息：不再显示 "Unknown error"，改为显示具体异常类型名。
- 改进流结束无数据时的兜底错误消息。
- 修复 MiMo API 域名（从 `api.mimogpt.com` 改为 `api.xiaomimimo.com`）。
- 修复 Release 构建中 API 端点名称显示为内部标识符（如 `endpoint_gemini_studio`）的问题：将 `displayNameRes` 从运行时字符串查找改为编译时 `@StringRes Int` 引用，避免 R8 资源缩减器移除动态查找的字符串资源。
- 移除所有 AI 服务商的硬编码默认模型名称，模型列表完全由 API 动态获取。
- 移除所有域名的证书固定（Certificate Pinning），解决 Google 等服务商证书轮换后 Release 构建 SSL 握手失败的问题。
- 补全 21 种语言的缺失字符串 key（UI 操作字符串 + 语言名称），确保「跟随系统」语言选项在所有 locale 下正确工作。

### Testing
- 已验证：Google Gemini、小米 MiMo、DeepSeek。
- 未验证（暂无测试条件）：OpenAI、Anthropic Claude、通义千问、腾讯混元、百度文心、智谱 GLM、Kimi (Moonshot)、MiniMax、Grok (xAI)。

## [1.0] - 2026-05-23

### Added
- 完整实现三种学习模式：用 X 语怎么说（HowToSay）、什么意思（WhatMeans）、自由聊天（FreeChat）。
- 集成 12 家 AI 服务商 API：OpenAI、Anthropic (Claude)、Google (Gemini)、Grok (xAI)、DeepSeek、通义千问、腾讯混元、文心一言、智谱 (GLM)、Kimi、MiniMax、百川。
- 支持 22 种界面语言，一键切换即时生效，覆盖简体中文、English、日本語、한국어、Français、Deutsch、Español、Português、Italiano、Nederlands、Svenska、Polski、Čeština、Русский、العربية、हिन्दी、Bahasa Indonesia、עברית、Ελληνικά、Türkçe、Tiếng Việt、ไทย。
- TTS 文本转语音播放，支持句子级点击发音。
- 书签收藏功能，独立于历史记录存储，清空历史不影响已收藏内容。
- 对话历史记录，支持按时间回顾与恢复会话上下文。
- Material3 亮色/暗色主题切换。
- OkHttp SSE 流式响应，支持各家服务商差异化 SSE 解析。
- 完整的数据持久化层：DataStore（偏好设置）+ SQLite（Room 风格 DAO）。
- 单元测试覆盖网络层与 ViewModel 层（JUnit + MockK + Turbine + MockWebServer）。

### Fixed
- 修复冷启动主线程阻塞问题：DNS 预解析异步化、TTS 懒初始化、数据库操作调度到 IO 线程、`reportFullyDrawn` 防 ANR。
- 修复应用语言切换不即时生效的问题。
- 修复 Gemini 模型 404 / 429 错误提示不明确的问题。
- 修复聊天界面与输入法联动导致的布局异常（底部区遮挡 / 黑色空隙）。

### Changed
- 完善 `.gitignore`，屏蔽 `.roo/`、`plans/`、`.geminiignore` 等开发工具目录与构建产物。
- 重写三语 README（中文 / English / 日本語），统一内容结构，补充技术栈、构建指南与项目结构说明。
- 移除无用样例测试文件，保留与业务相关的回归测试。
- 应用图标入口切换到新生成的 `icon` 资源组。

## [0.1.0] - 2026-05-19

### Added
- 新增多语言说明文档：`README.md`（中文）、`README.ja.md`（日文）、`README.en.md`（英文）。
- 新增 `LICENSE`，采用 MIT 协议。
- 新增三语 README 互相跳转链接。

### Changed
- 更新三语 README 的项目背景说明，统一包含 OCAT 来源与 BYOK 使用方式说明。
- 完善开源发布前说明与多语言文档维护约定。
- 应用图标入口切换到新生成的 `icon` 资源组（`@mipmap/icon` / `@mipmap/icon_round`）。
- 清理默认样例测试文件，保留与业务相关的回归测试。
- 清理可再生成构建产物与缓存目录，减少仓库噪音。
- 完善 `.gitignore`，忽略本地配置、IDE 文件、构建输出与敏感文件。

### Fixed
- 修复 `attachBaseContext` 早期调用导致的启动崩溃风险。
- 修复聊天界面与输入法联动导致的布局问题（黑色空隙 / 底部区遮挡）。
- 调整 Gemini 模型配置与错误处理逻辑，明确 404 与 429 错误提示。
