package com.example.fakeocat.network

import androidx.annotation.StringRes

/**
 * API 协议类型，决定请求体构建和响应流解析逻辑。
 * 每种协议对应 [RequestBuilder] 中的一个构建函数和 [ProviderStreamParsers] 中的一个解析器。
 */
enum class ApiProtocol {
    /** 标准 OpenAI /v1/chat/completions 格式 */
    OpenAICompatible,

    /** Anthropic Messages API (/v1/messages) */
    AnthropicMessages,

    /** Gemini 原生 generateContent API */
    GeminiNative,

    /** DashScope 原生 text-generation API（input.messages + parameters.stream） */
    DashScopeNative,

    /** 百度千帆 v1 REST-RPC 格式 */
    QianfanV1RPC,

    /** Azure OpenAI（请求体同 OpenAI，URL 路径和 header 不同） */
    AzureOpenAI,

    /** AWS Bedrock + Anthropic 模型 */
    BedrockAnthropic,

    /** GCP Vertex AI + Anthropic 模型 */
    VertexAnthropic,

    /** GCP Vertex AI + Gemini 模型 */
    VertexGemini,

    /** 腾讯云 TC3 签名 API */
    TencentCloudTC3
}

/**
 * 认证方案，决定 HTTP 请求的签名/鉴权方式。
 */
enum class AiAuthScheme {
    /** Authorization: Bearer {api_key} */
    BearerToken,

    /** x-api-key: {api_key} + anthropic-version header */
    AnthropicApiKeyHeader,

    /** Query parameter: ?key={api_key} */
    GeminiApiKeyQuery,

    /** Azure header: api-key: {api_key} */
    AzureApiKey,

    /** AWS Signature Version 4 签名 */
    AwsSigV4,

    /** GCP OAuth 2.0 Bearer token */
    GcpOAuth2Bearer,

    /** 腾讯云 TC3-HMAC-SHA256 签名 */
    TencentCloudTC3,

    /** 百度 access_token 作为 query parameter */
    BaiduAccessToken
}

/**
 * 额外配置字段，用于需要用户输入额外参数的端点（如 Azure resource name、AWS region 等）。
 *
 * @param key           存储键名，全局唯一（如 "resource"、"region"、"project"）
 * @param displayNameRes Android string resource ID（如 `R.string.endpoint_config_region`），用于 UI 显示
 * @param placeholder    输入框占位文本
 * @param required       是否必填
 * @param isSecret       是否为敏感信息（决定存储在 EncryptedSharedPreferences 还是 DataStore）
 */
data class ExtraConfigField(
    val key: String,
    @param:StringRes val displayNameRes: Int,
    val placeholder: String = "",
    val required: Boolean = true,
    val isSecret: Boolean = false
)

/**
 * 端点配置文件：封装连接到某个特定 AI 端点所需的全部信息。
 *
 * 一个 [AiProviderInfo] 可包含多个 EndpointProfile，每个代表该 Provider 的一种接入方式。
 *
 * @param id                Profile 唯一标识（在同一个 Provider 内唯一），如 "direct"、"azure"、"token_plan"
 * @param displayNameRes    Android string resource ID（如 `R.string.endpoint_openai_direct`），用于 UI 下拉显示
 * @param chatCompletionsUrl 聊天补全端点 URL 模板，可含 {placeholder} 占位符
 * @param modelsEndpoint    模型列表端点 URL 模板，null 表示不支持动态模型列表
 * @param authScheme        认证方案
 * @param apiProtocol       API 协议类型
 * @param supportsModelList 是否支持动态模型列表
 * @param extraConfigFields 需要用户填写的额外配置字段列表
 */
data class EndpointProfile(
    val id: String,
    @param:StringRes val displayNameRes: Int,
    val chatCompletionsUrl: String,
    val modelsEndpoint: String? = null,
    val authScheme: AiAuthScheme,
    val apiProtocol: ApiProtocol,
    val supportsModelList: Boolean = true,
    val extraConfigFields: List<ExtraConfigField> = emptyList()
) {
    /**
     * 是否需要额外用户配置（即 extraConfigFields 非空）。
     */
    val requiresExtraConfig: Boolean get() = extraConfigFields.isNotEmpty()

    /**
     * 用实际配置值替换 URL 模板中的占位符。
     * 例如 "https://{resource}.openai.azure.com/..." -> "https://my-resource.openai.azure.com/..."
     *
     * @param configValues 用户提供的配置值 Map (key -> value)
     * @param model        模型名（用于替换 {model} 占位符）
     * @return 解析后的最终 URL
     */
    fun resolveUrl(
        configValues: Map<String, String>,
        model: String = ""
    ): String {
        var url = chatCompletionsUrl
        // 替换 {model} 占位符
        url = url.replace("{model}", model)
        // 替换用户配置的占位符
        for ((key, value) in configValues) {
            url = url.replace("{$key}", value)
        }
        return url
    }

    /**
     * 同 resolveUrl，但用于 modelsEndpoint。
     */
    fun resolveModelsEndpointUrl(configValues: Map<String, String>): String? {
        val endpoint = modelsEndpoint ?: return null
        var url = endpoint
        for ((key, value) in configValues) {
            url = url.replace("{$key}", value)
        }
        return url
    }
}
