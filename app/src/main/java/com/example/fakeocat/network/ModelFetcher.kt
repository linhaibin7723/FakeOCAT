package com.example.fakeocat.network

import android.util.Log
import com.example.fakeocat.network.auth.AwsSigV4Signer
import com.example.fakeocat.network.auth.BaiduAccessTokenFetcher
import com.example.fakeocat.network.auth.GcpOAuth2Signer
import com.example.fakeocat.network.auth.TencentCloudTC3Signer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 模型获取专用异常，携带 HTTP 状态码供上层映射错误类型。
 */
class ModelFetchException(
    val httpStatusCode: Int = -1,
    message: String = "",
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * 模型列表获取器。
 *
 * 根据 Provider 类型调用对应的模型列表 API，解析三种不同的响应格式：
 * - OpenAI 兼容：`GET /v1/models` → `{ data: [{ id }] }`
 * - Gemini：`GET /v1/models?key=` → `{ models: [{ name, displayName }] }`
 * - Anthropic：不支持动态列表，返回硬编码常用模型
 *
 * @param httpClient 复用 LlmClient 的非流式 HTTP 客户端
 */
class ModelFetcher(private val httpClient: OkHttpClient) {

    companion object {
        private const val TAG = "ModelFetcher"
        private const val TIMEOUT_MS = 10_000L
    }

    /**
     * 获取指定 Provider 的可用模型列表。
     *
     * 优先返回内存缓存；缓存未命中时发起网络请求。
     * 网络失败时尝试返回过期缓存数据作为降级。
     *
     * @param provider 目标服务商
     * @param apiKey   API 密钥
     * @param forceRefresh true 时跳过缓存，强制从网络获取
     * @return Result 包含模型列表，失败时包含异常信息
     */
    suspend fun fetchModels(
        provider: AiProviderInfo,
        apiKey: String,
        forceRefresh: Boolean = false
    ): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        // 1. 检查内存缓存（强制刷新时跳过）
        if (!forceRefresh) {
            ModelCache.get(provider.id)?.let { cached ->
                return@withContext Result.success(cached)
            }
        }

        // 2. 不支持动态列表的 Provider
        if (!provider.supportsModelList) {
            return@withContext Result.failure(
                ModelFetchException(message = "Provider ${provider.id} does not support dynamic model list")
            )
        }

        // 3. 发起网络请求
        val endpoint = provider.modelsEndpoint
        if (endpoint.isNullOrBlank()) {
            return@withContext Result.failure(
                IllegalStateException("No models endpoint configured for ${provider.id}")
            )
        }

        try {
            val models = when (provider.authScheme) {
                AiAuthScheme.GeminiApiKeyQuery -> fetchGeminiModels(endpoint, apiKey)
                else -> fetchOpenAICompatibleModels(endpoint, apiKey, provider.id)
            }

            // 4. 空列表降级
            if (models.isEmpty()) {
                return@withContext Result.failure(
                    ModelFetchException(message = "Empty model list for ${provider.id}")
                )
            }

            // 5. 排序：按模型名字母序
            val sorted = models.sortedBy { it.id }
            ModelCache.put(provider.id, sorted)
            Result.success(sorted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch models for ${provider.id}: ${e.message}", e)
            // 6. 降级：返回过期缓存（如果存在）
            val stale = ModelCache.getOrNull(provider.id)
            if (stale != null) {
                return@withContext Result.success(stale)
            }
            Result.failure(e)
        }
    }

    /**
     * 通过 [EndpointProfile] 获取模型列表。
     *
     * 这是新的推荐入口，支持所有认证方案。
     * 对不支持动态模型列表的 Profile，返回硬编码模型。
     *
     * @param profile       端点配置
     * @param apiKey        API 密钥
     * @param endpointConfig 用户提供的额外配置值 Map
     * @param providerId    Provider ID（用于缓存键和模型过滤）
     * @param forceRefresh  true 时跳过缓存，强制从网络获取
     * @return Result 包含模型列表，失败时包含异常信息
     */
    suspend fun fetchModels(
        profile: EndpointProfile,
        apiKey: String,
        endpointConfig: Map<String, String> = emptyMap(),
        providerId: String = "",
        forceRefresh: Boolean = false
    ): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        val cacheKey = if (providerId.isNotEmpty()) {
            "${providerId}:${profile.id}"
        } else {
            profile.id
        }

        // 1. 检查内存缓存（强制刷新时跳过）
        if (!forceRefresh) {
            ModelCache.get(cacheKey)?.let { cached ->
                return@withContext Result.success(cached)
            }
        }

        // 2. 不支持动态列表的 Profile
        if (!profile.supportsModelList || profile.modelsEndpoint.isNullOrBlank()) {
            return@withContext Result.failure(
                ModelFetchException(message = "Profile ${profile.id} does not support dynamic model list")
            )
        }

        // 3. 解析模型列表 URL
        val modelsUrl = profile.resolveModelsEndpointUrl(endpointConfig)
            ?: profile.modelsEndpoint!!

        try {
            val models = fetchModelListWithProfile(profile, modelsUrl, apiKey, endpointConfig, providerId)

            // 4. 空列表降级
            if (models.isEmpty()) {
                return@withContext Result.failure(
                    ModelFetchException(message = "Empty model list for $cacheKey")
                )
            }

            // 5. 排序：按模型名字母序
            val sorted = models.sortedBy { it.id }
            ModelCache.put(cacheKey, sorted)
            Result.success(sorted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch models for $cacheKey: ${e.message}", e)
            // 6. 降级：返回过期缓存（如果存在）
            val stale = ModelCache.getOrNull(cacheKey)
            if (stale != null) {
                return@withContext Result.success(stale)
            }
            Result.failure(e)
        }
    }

    /**
     * 根据 [EndpointProfile] 的认证方案发起模型列表请求。
     *
     * 对于需要特殊认证的端点（GcpOAuth2、BaiduAccessToken），先获取 access_token。
     */
    private suspend fun fetchModelListWithProfile(
        profile: EndpointProfile,
        endpoint: String,
        apiKey: String,
        endpointConfig: Map<String, String>,
        providerId: String
    ): List<AiModel> {
        // 对于需要特殊认证的端点，先获取 access_token
        val effectiveApiKey = when (profile.authScheme) {
            AiAuthScheme.GcpOAuth2Bearer -> {
                // apiKey 存储的是 Service Account JSON，需要先获取 access_token
                GcpOAuth2Signer.getAccessToken(apiKey, httpClient)
            }
            AiAuthScheme.BaiduAccessToken -> {
                // apiKey 存储的是 API Key，需要 secret_key 获取 access_token
                val secretKey = endpointConfig["secret_key"] ?: apiKey
                BaiduAccessTokenFetcher.getAccessToken(apiKey, secretKey, httpClient)
            }
            else -> apiKey
        }
        return when (profile.apiProtocol) {
            ApiProtocol.GeminiNative,
            ApiProtocol.VertexGemini -> {
                // Gemini 格式：需要通过 query parameter 或 header 传递 key
                val url = if (profile.authScheme == AiAuthScheme.GeminiApiKeyQuery) {
                    "$endpoint?key=$effectiveApiKey"
                } else {
                    endpoint
                }
                val requestBuilder = Request.Builder().url(url).get()
                if (profile.authScheme == AiAuthScheme.GcpOAuth2Bearer) {
                    requestBuilder.addHeader("Authorization", "Bearer $effectiveApiKey")
                }
                fetchGeminiModelsFromRequest(requestBuilder.build())
            }
            else -> {
                // OpenAI 兼容格式
                val requestBuilder = Request.Builder().url(endpoint).get()
                applyAuth(requestBuilder, profile, effectiveApiKey, endpointConfig)
                fetchOpenAICompatibleModelsFromRequest(requestBuilder.build(), providerId)
            }
        }
    }

    /**
     * 为模型列表请求添加认证信息。
     */
    private fun applyAuth(
        requestBuilder: Request.Builder,
        profile: EndpointProfile,
        apiKey: String,
        config: Map<String, String>
    ) {
        when (profile.authScheme) {
            AiAuthScheme.BearerToken ->
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            AiAuthScheme.AzureApiKey ->
                requestBuilder.addHeader("api-key", apiKey)
            AiAuthScheme.GcpOAuth2Bearer ->
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            AiAuthScheme.GeminiApiKeyQuery -> {
                // 已在 URL 中处理
            }
            AiAuthScheme.AnthropicApiKeyHeader -> {
                requestBuilder.addHeader("x-api-key", apiKey)
                requestBuilder.addHeader("anthropic-version", "2023-06-01")
            }
            AiAuthScheme.AwsSigV4 -> {
                val accessKeyId = config["aws_access_key_id"] ?: ""
                val region = config["region"] ?: "us-east-1"
                val signedHeaders = AwsSigV4Signer.sign(
                    method = "GET",
                    url = profile.resolveUrl(config),
                    headers = emptyMap(),
                    body = ByteArray(0),
                    region = region,
                    accessKeyId = accessKeyId,
                    secretAccessKey = apiKey
                )
                signedHeaders.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            }
            AiAuthScheme.TencentCloudTC3 -> {
                // 模型列表不支持 TC3 签名
            }
            AiAuthScheme.BaiduAccessToken -> {
                val url = requestBuilder.build().url
                requestBuilder.url(url.newBuilder().addQueryParameter("access_token", apiKey).build())
            }
        }
    }

    /**
     * 使用 Request 对象发起 OpenAI 兼容格式的模型列表请求。
     */
    private fun fetchOpenAICompatibleModelsFromRequest(
        request: Request,
        providerId: String
    ): List<AiModel> {
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "OpenAI models HTTP error: ${response.code} ${response.message}")
                throw ModelFetchException(
                    httpStatusCode = response.code,
                    message = "HTTP ${response.code}: ${response.message}"
                )
            }

            val body = response.body?.string()
                ?: throw RuntimeException("Empty response body")
            val json = JSONObject(body)
            val dataArray = json.getJSONArray("data")

            val models = mutableListOf<AiModel>()
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                val id = obj.getString("id")
                if (shouldIncludeModel(id, providerId)) {
                    models.add(AiModel(id = id))
                }
            }
            return models
        }
    }

    /**
     * 使用 Request 对象发起 Gemini 格式的模型列表请求。
     */
    private fun fetchGeminiModelsFromRequest(request: Request): List<AiModel> {
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini models HTTP error: ${response.code} ${response.message}")
                throw ModelFetchException(
                    httpStatusCode = response.code,
                    message = "HTTP ${response.code}: ${response.message}"
                )
            }

            val body = response.body?.string()
                ?: throw RuntimeException("Empty response body")
            val json = JSONObject(body)
            val modelsArray = json.getJSONArray("models")

            val models = mutableListOf<AiModel>()
            for (i in 0 until modelsArray.length()) {
                val obj = modelsArray.getJSONObject(i)
                val methods = obj.optJSONArray("supportedGenerationMethods")
                val supportsGenerate = (0 until (methods?.length() ?: 0))
                    .any { methods?.getString(it) == "generateContent" }
                if (!supportsGenerate) continue

                val fullName = obj.getString("name")
                val id = fullName.removePrefix("models/")
                val displayName = obj.optString("displayName", id)
                models.add(AiModel(id = id, displayName = displayName))
            }
            return models
        }
    }

    /**
     * OpenAI 兼容格式：`{ data: [{ id, owned_by }] }`
     */
    private fun fetchOpenAICompatibleModels(
        endpoint: String,
        apiKey: String,
        providerId: String
    ): List<AiModel> {
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ModelFetchException(
                    httpStatusCode = response.code,
                    message = "HTTP ${response.code}: ${response.message}"
                )
            }

            val body = response.body?.string()
                ?: throw RuntimeException("Empty response body")
            val json = JSONObject(body)
            val dataArray = json.getJSONArray("data")

            val models = mutableListOf<AiModel>()
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                val id = obj.getString("id")
                // 过滤非聊天模型（embedding、audio、tts、dall-e 等）
                if (shouldIncludeModel(id, providerId)) {
                    models.add(AiModel(id = id))
                }
            }
            return models
        }
    }

    /**
     * Gemini 格式：`{ models: [{ name, displayName, supportedGenerationMethods }] }`
     */
    private fun fetchGeminiModels(endpoint: String, apiKey: String): List<AiModel> {
        val url = "$endpoint?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ModelFetchException(
                    httpStatusCode = response.code,
                    message = "HTTP ${response.code}: ${response.message}"
                )
            }

            val body = response.body?.string()
                ?: throw RuntimeException("Empty response body")
            val json = JSONObject(body)
            val modelsArray = json.getJSONArray("models")

            val models = mutableListOf<AiModel>()
            for (i in 0 until modelsArray.length()) {
                val obj = modelsArray.getJSONObject(i)
                // 仅保留支持 generateContent 的模型
                val methods = obj.optJSONArray("supportedGenerationMethods")
                val supportsGenerate = (0 until (methods?.length() ?: 0))
                    .any { methods?.getString(it) == "generateContent" }
                if (!supportsGenerate) continue

                val fullName = obj.getString("name")           // "models/gemini-2.5-flash"
                val id = fullName.removePrefix("models/")       // "gemini-2.5-flash"
                val displayName = obj.optString("displayName", id)
                models.add(AiModel(id = id, displayName = displayName))
            }
            return models
        }
    }

    /**
     * 过滤掉非聊天模型（embedding、audio、tts、dall-e、moderation 等）。
     */
    private fun shouldIncludeModel(modelId: String, providerId: String): Boolean {
        val lower = modelId.lowercase()
        val excludedPrefixes = listOf(
            "dall-e", "whisper", "tts-", "text-embedding",
            "moderation", "omni-moderation", "gpt-4o-audio",
            "gpt-4o-mini-audio", "gpt-4o-realtime", "gpt-4o-mini-realtime",
            "gpt-4o-search", "gpt-4o-mini-search"
        )
        val excludedSuffixes = listOf("-instruct", "-embedding", "-embed")

        if (excludedPrefixes.any { lower.startsWith(it) }) return false
        if (excludedSuffixes.any { lower.endsWith(it) }) return false
        return true
    }
}
