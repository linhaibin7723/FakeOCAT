package com.example.fakeocat.network

import com.example.fakeocat.R

/** 从 API 获取到的单个模型信息 */
data class AiModel(
    val id: String,              // 模型标识符（如 "gpt-4o"、"claude-haiku-4-5"）
    val displayName: String = id // 人类可读名称（可选，Gemini 会提供）
)

data class AiProviderInfo(
    val id: String,
    val displayName: String,
    val profiles: List<EndpointProfile>,       // 替代原来的单一 URL + authScheme
) {
    /** 第一个 profile 为默认端点 */
    val defaultProfile: EndpointProfile get() = profiles.first()

    /** 兼容旧代码的便捷访问器（使用默认 profile） */
    val authScheme: AiAuthScheme get() = defaultProfile.authScheme
    val chatCompletionsUrl: String? get() = defaultProfile.chatCompletionsUrl
    val modelsEndpoint: String? get() = defaultProfile.modelsEndpoint
    val supportsModelList: Boolean get() = defaultProfile.supportsModelList

    /** 按 ID 查找 profile */
    fun getProfile(profileId: String): EndpointProfile? =
        profiles.firstOrNull { it.id == profileId }

    /** 是否有多个端点变体（用于 UI 决定是否显示端点选择器） */
    val hasMultipleProfiles: Boolean get() = profiles.size > 1
}

object AiProviderCatalog {
    val providers: List<AiProviderInfo> = listOf(
        // ── 单端点 Provider ──────────────────────────────────────
        AiProviderInfo(
            id = "openai",
            displayName = "OpenAI",
            profiles = listOf(
                EndpointProfile(
                    id = "direct",
                    displayNameRes = R.string.endpoint_openai_direct,
                    chatCompletionsUrl = "https://api.openai.com/v1/chat/completions",
                    modelsEndpoint = "https://api.openai.com/v1/models",
                    authScheme = AiAuthScheme.BearerToken,
                    apiProtocol = ApiProtocol.OpenAICompatible
                ),
                EndpointProfile(
                    id = "azure",
                    displayNameRes = R.string.endpoint_openai_azure,
                    chatCompletionsUrl = "https://{resource}.openai.azure.com/openai/deployments/{deployment}/chat/completions?api-version=2024-10-21",
                    modelsEndpoint = null,
                    authScheme = AiAuthScheme.AzureApiKey,
                    apiProtocol = ApiProtocol.AzureOpenAI,
                    supportsModelList = false,
                    extraConfigFields = listOf(
                        ExtraConfigField(key = "resource", displayNameRes = R.string.endpoint_config_resource, placeholder = "my-resource"),
                        ExtraConfigField(key = "deployment", displayNameRes = R.string.endpoint_config_deployment, placeholder = "gpt-4o")
                    )
                )
            )
        ),
        AiProviderInfo(
            id = "anthropic",
            displayName = "Anthropic",
            profiles = listOf(
                EndpointProfile(
                    id = "direct",
                    displayNameRes = R.string.endpoint_anthropic_direct,
                    chatCompletionsUrl = "https://api.anthropic.com/v1/messages",
                    modelsEndpoint = null,
                    authScheme = AiAuthScheme.AnthropicApiKeyHeader,
                    apiProtocol = ApiProtocol.AnthropicMessages,
                    supportsModelList = false
                ),
                EndpointProfile(
                    id = "aws_bedrock",
                    displayNameRes = R.string.endpoint_anthropic_bedrock,
                    chatCompletionsUrl = "https://bedrock-runtime.{region}.amazonaws.com/model/{model}/invoke-with-response-stream",
                    modelsEndpoint = null,
                    authScheme = AiAuthScheme.AwsSigV4,
                    apiProtocol = ApiProtocol.BedrockAnthropic,
                    supportsModelList = false,
                    extraConfigFields = listOf(
                        ExtraConfigField(key = "region", displayNameRes = R.string.endpoint_config_region, placeholder = "us-east-1"),
                        ExtraConfigField(key = "aws_access_key_id", displayNameRes = R.string.endpoint_config_aws_access_key_id, placeholder = "AKIA...", isSecret = false)
                    )
                ),
                EndpointProfile(
                    id = "gcp_vertex",
                    displayNameRes = R.string.endpoint_anthropic_vertex,
                    chatCompletionsUrl = "https://{region}-aiplatform.googleapis.com/v1/projects/{project}/locations/{region}/publishers/anthropic/models/{model}:streamRawPredict",
                    modelsEndpoint = null,
                    authScheme = AiAuthScheme.GcpOAuth2Bearer,
                    apiProtocol = ApiProtocol.VertexAnthropic,
                    supportsModelList = false,
                    extraConfigFields = listOf(
                        ExtraConfigField(key = "region", displayNameRes = R.string.endpoint_config_region, placeholder = "us-east5"),
                        ExtraConfigField(key = "project", displayNameRes = R.string.endpoint_config_project, placeholder = "my-project-123")
                    )
                )
            )
        ),
        AiProviderInfo(
            id = "gemini",
            displayName = "Gemini",
            profiles = listOf(
                EndpointProfile(
                    id = "ai_studio",
                    displayNameRes = R.string.endpoint_gemini_studio,
                    chatCompletionsUrl = "https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse",
                    modelsEndpoint = "https://generativelanguage.googleapis.com/v1beta/models",
                    authScheme = AiAuthScheme.GeminiApiKeyQuery,
                    apiProtocol = ApiProtocol.GeminiNative
                ),
                EndpointProfile(
                    id = "vertex_ai",
                    displayNameRes = R.string.endpoint_gemini_vertex,
                    chatCompletionsUrl = "https://{region}-aiplatform.googleapis.com/v1/projects/{project}/locations/{region}/publishers/google/models/{model}:streamGenerateContent?alt=sse",
                    modelsEndpoint = null,
                    authScheme = AiAuthScheme.GcpOAuth2Bearer,
                    apiProtocol = ApiProtocol.VertexGemini,
                    supportsModelList = false,
                    extraConfigFields = listOf(
                        ExtraConfigField(key = "region", displayNameRes = R.string.endpoint_config_region, placeholder = "us-central1"),
                        ExtraConfigField(key = "project", displayNameRes = R.string.endpoint_config_project, placeholder = "my-project-123")
                    )
                )
            )
        ),
        AiProviderInfo(
            id = "grok",
            displayName = "Grok（xAI）",
            profiles = listOf(
                EndpointProfile(
                    id = "direct",
                    displayNameRes = R.string.endpoint_grok_direct,
                    chatCompletionsUrl = "https://api.x.ai/v1/chat/completions",
                    modelsEndpoint = "https://api.x.ai/v1/models",
                    authScheme = AiAuthScheme.BearerToken,
                    apiProtocol = ApiProtocol.OpenAICompatible
                )
            )
        ),
        // ── 多端点 Provider ──────────────────────────────────────
        AiProviderInfo(
            id = "mimo",
            displayName = "小米 mimo",
            profiles = listOf(
                EndpointProfile(
                    id = "pay_as_you_go",
                    displayNameRes = R.string.endpoint_mimo_payg,
                    chatCompletionsUrl = "https://api.xiaomimimo.com/v1/chat/completions",
                    modelsEndpoint = "https://api.xiaomimimo.com/v1/models",
                    authScheme = AiAuthScheme.BearerToken,
                    apiProtocol = ApiProtocol.OpenAICompatible
                ),
                EndpointProfile(
                    id = "token_plan",
                    displayNameRes = R.string.endpoint_mimo_token,
                    chatCompletionsUrl = "https://token-plan-cn.xiaomimimo.com/v1/chat/completions",
                    modelsEndpoint = "https://token-plan-cn.xiaomimimo.com/v1/models",
                    authScheme = AiAuthScheme.BearerToken,
                    apiProtocol = ApiProtocol.OpenAICompatible
                )
            )
        ),
        AiProviderInfo(
            id = "deepseek",
            displayName = "DeepSeek",
            profiles = listOf(
                EndpointProfile(
                    id = "direct",
                    displayNameRes = R.string.endpoint_deepseek_direct,
                    chatCompletionsUrl = "https://api.deepseek.com/v1/chat/completions",
                    modelsEndpoint = "https://api.deepseek.com/v1/models",
                    authScheme = AiAuthScheme.BearerToken,
                    apiProtocol = ApiProtocol.OpenAICompatible
                )
            )
        ),
        AiProviderInfo(
            id = "qwen",
            displayName = "阿里千问",
            profiles = listOf(
                EndpointProfile(
                    id = "dashscope_compat",
                    displayNameRes = R.string.endpoint_qwen_compat,
                    chatCompletionsUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                    modelsEndpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/models",
                    authScheme = AiAuthScheme.BearerToken,
                    apiProtocol = ApiProtocol.OpenAICompatible
                ),
                EndpointProfile(
                    id = "dashscope_native",
                    displayNameRes = R.string.endpoint_qwen_native,
                    chatCompletionsUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation",
                    modelsEndpoint = null,
                    authScheme = AiAuthScheme.BearerToken,
                    apiProtocol = ApiProtocol.DashScopeNative,
                    supportsModelList = false
                )
            )
        ),
        AiProviderInfo(
            id = "hunyuan",
            displayName = "腾讯混元",
            profiles = listOf(
                EndpointProfile(
                    id = "independent",
                    displayNameRes = R.string.endpoint_hunyuan_independent,
                    chatCompletionsUrl = "https://api.hunyuan.cloud.tencent.com/v1/chat/completions",
                    modelsEndpoint = "https://api.hunyuan.cloud.tencent.com/v1/models",
                    authScheme = AiAuthScheme.BearerToken,
                    apiProtocol = ApiProtocol.OpenAICompatible
                ),
                EndpointProfile(
                    id = "tencent_cloud",
                    displayNameRes = R.string.endpoint_hunyuan_tc3,
                    chatCompletionsUrl = "https://hunyuan.tencentcloudapi.com",
                    modelsEndpoint = null,
                    authScheme = AiAuthScheme.TencentCloudTC3,
                    apiProtocol = ApiProtocol.TencentCloudTC3,
                    supportsModelList = false,
                    extraConfigFields = listOf(
                        ExtraConfigField(key = "secret_id", displayNameRes = R.string.endpoint_config_secret_id, placeholder = "AKID...", isSecret = false),
                        ExtraConfigField(key = "region", displayNameRes = R.string.endpoint_config_region, placeholder = "ap-guangzhou")
                    )
                )
            )
        ),
        AiProviderInfo(
            id = "ernie",
            displayName = "百度文心一言",
            profiles = listOf(
                EndpointProfile(
                    id = "qianfan_v2",
                    displayNameRes = R.string.endpoint_ernie_v2,
                    chatCompletionsUrl = "https://qianfan.baidubce.com/v2/chat/completions",
                    modelsEndpoint = "https://qianfan.baidubce.com/v2/models",
                    authScheme = AiAuthScheme.BearerToken,
                    apiProtocol = ApiProtocol.OpenAICompatible
                ),
                EndpointProfile(
                    id = "qianfan_v1",
                    displayNameRes = R.string.endpoint_ernie_v1,
                    chatCompletionsUrl = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/{model}",
                    modelsEndpoint = null,
                    authScheme = AiAuthScheme.BaiduAccessToken,
                    apiProtocol = ApiProtocol.QianfanV1RPC,
                    supportsModelList = false
                )
            )
        ),
        AiProviderInfo(
            id = "zhipu",
            displayName = "智谱AI",
            profiles = listOf(
                EndpointProfile(
                    id = "direct",
                    displayNameRes = R.string.endpoint_zhipu_direct,
                    chatCompletionsUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                    modelsEndpoint = "https://open.bigmodel.cn/api/paas/v4/models",
                    authScheme = AiAuthScheme.BearerToken,
                    apiProtocol = ApiProtocol.OpenAICompatible
                )
            )
        ),
        AiProviderInfo(
            id = "kimi",
            displayName = "Kimi（Moonshot）",
            profiles = listOf(
                EndpointProfile(
                    id = "cn",
                    displayNameRes = R.string.endpoint_kimi_cn,
                    chatCompletionsUrl = "https://api.moonshot.cn/v1/chat/completions",
                    modelsEndpoint = "https://api.moonshot.cn/v1/models",
                    authScheme = AiAuthScheme.BearerToken,
                    apiProtocol = ApiProtocol.OpenAICompatible
                ),
                EndpointProfile(
                    id = "international",
                    displayNameRes = R.string.endpoint_kimi_international,
                    chatCompletionsUrl = "https://api.moonshot.ai/v1/chat/completions",
                    modelsEndpoint = "https://api.moonshot.ai/v1/models",
                    authScheme = AiAuthScheme.BearerToken,
                    apiProtocol = ApiProtocol.OpenAICompatible
                )
            )
        ),
        AiProviderInfo(
            id = "minimax",
            displayName = "MiniMax",
            profiles = listOf(
                EndpointProfile(
                    id = "direct",
                    displayNameRes = R.string.endpoint_minimax_direct,
                    chatCompletionsUrl = "https://api.minimax.chat/v1/chat/completions",
                    modelsEndpoint = "https://api.minimax.chat/v1/models",
                    authScheme = AiAuthScheme.BearerToken,
                    apiProtocol = ApiProtocol.OpenAICompatible
                )
            )
        )
    )

    fun getProvider(id: String): AiProviderInfo? = providers.firstOrNull { it.id == id }

    /** 根据 provider ID + profile ID 获取端点配置 */
    fun getEndpoint(providerId: String, profileId: String?): EndpointProfile? {
        val provider = getProvider(providerId) ?: return null
        return if (profileId != null) provider.getProfile(profileId) else provider.defaultProfile
    }

    /** 获取 provider 的默认 profile */
    fun getDefaultProfile(providerId: String): EndpointProfile? =
        getProvider(providerId)?.defaultProfile

    /** 获取指定 provider 和 profile 的端点配置 */
    fun getProfile(providerId: String, profileId: String): EndpointProfile? =
        getProvider(providerId)?.getProfile(profileId)
}
