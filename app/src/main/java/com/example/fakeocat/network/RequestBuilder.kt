package com.example.fakeocat.network

import com.example.fakeocat.network.auth.AwsSigV4Signer
import org.json.JSONArray
import org.json.JSONObject

/**
 * 请求体构建器。
 *
 * 根据 [ApiProtocol] 构建不同格式的 HTTP 请求体 JSON，并组装完整的请求信息。
 * 将请求构建逻辑集中管理，方便 LlmClient 统一调用。
 */
object RequestBuilder {

    /**
     * 构建聊天补全请求的 JSON body 字符串。
     *
     * @param protocol   API 协议类型
     * @param model      模型名
     * @param messages   消息列表，每个元素为包含 "role" 和 "content" 键的 Map
     * @param temperature 温度参数（null 时不设置）
     * @param maxTokens   最大 token 数（null 时不设置；Anthropic 协议必须设置，缺省 4096）
     * @param stream      是否流式输出
     * @return JSON 字符串
     */
    fun buildRequestBody(
        protocol: ApiProtocol,
        model: String,
        messages: List<Map<String, String>>,
        temperature: Double?,
        maxTokens: Int?,
        stream: Boolean = true
    ): String = when (protocol) {
        ApiProtocol.OpenAICompatible,
        ApiProtocol.AzureOpenAI -> buildOpenAICompatible(model, messages, temperature, maxTokens, stream)

        ApiProtocol.AnthropicMessages,
        ApiProtocol.BedrockAnthropic,
        ApiProtocol.VertexAnthropic -> buildAnthropic(model, messages, temperature, maxTokens, stream)

        ApiProtocol.GeminiNative,
        ApiProtocol.VertexGemini -> buildGemini(messages, temperature, maxTokens)

        ApiProtocol.DashScopeNative -> buildDashScopeNative(model, messages, temperature, maxTokens, stream)

        ApiProtocol.QianfanV1RPC -> buildQianfanV1(messages, temperature, stream)

        ApiProtocol.TencentCloudTC3 -> buildTencentCloudTC3(model, messages, temperature, maxTokens, stream)
    }

    /**
     * 构建完整的 HTTP 请求信息（含 URL、headers、body）。
     * 用于 LlmClient 统一调用。
     *
     * @param profile       端点配置
     * @param model         模型名
     * @param messages      消息列表
     * @param apiKey        API 密钥（含义取决于 authScheme）
     * @param temperature   温度参数
     * @param maxTokens     最大 token 数
     * @param extraConfig   用户提供的额外配置值 Map
     * @return Triple(url, headers, body) — headers 不含 Content-Type（由调用方添加）
     */
    fun buildRequest(
        profile: EndpointProfile,
        model: String,
        messages: List<Map<String, String>>,
        apiKey: String,
        temperature: Double?,
        maxTokens: Int?,
        extraConfig: Map<String, String> = emptyMap()
    ): Triple<String, Map<String, String>, String> {
        // 1. 解析 URL
        var url = profile.resolveUrl(extraConfig, model)

        // 对于通过 URL query 参数传递认证信息的方案，将 key 追加到 URL
        if (profile.authScheme == AiAuthScheme.GeminiApiKeyQuery) {
            val separator = if (url.contains("?")) "&" else "?"
            url = "${url}${separator}key=$apiKey"
        }

        // 2. 构建请求体
        val body = buildRequestBody(profile.apiProtocol, model, messages, temperature, maxTokens)

        // 3. 构建认证 headers
        val headers = buildAuthHeaders(profile, apiKey, extraConfig).toMutableMap()

        return Triple(url, headers, body)
    }

    // ══════════════════════════════════════════════════════════════
    // 认证 Headers 构建
    // ══════════════════════════════════════════════════════════════

    /**
     * 根据 [AiAuthScheme] 构建认证相关的 HTTP headers。
     * 返回的 Map 不含 Content-Type（由调用方统一添加）。
     */
    private fun buildAuthHeaders(
        profile: EndpointProfile,
        apiKey: String,
        config: Map<String, String>
    ): Map<String, String> = when (profile.authScheme) {
        AiAuthScheme.BearerToken -> mapOf(
            "Authorization" to "Bearer $apiKey"
        )
        AiAuthScheme.AnthropicApiKeyHeader -> mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to "2023-06-01"
        )
        AiAuthScheme.GeminiApiKeyQuery -> emptyMap() // key 通过 URL query 参数传递
        AiAuthScheme.AzureApiKey -> mapOf(
            "api-key" to apiKey
        )
        AiAuthScheme.GcpOAuth2Bearer -> mapOf(
            "Authorization" to "Bearer $apiKey"
        )
        AiAuthScheme.AwsSigV4 -> {
            val accessKeyId = config["aws_access_key_id"]
                ?: throw IllegalArgumentException("aws_access_key_id is required for AwsSigV4")
            val secretAccessKey = apiKey
            val region = config["region"] ?: "us-east-1"
            val url = profile.resolveUrl(config)
            val bodyBytes = ByteArray(0) // 实际 body 在调用时传入
            AwsSigV4Signer.sign(
                method = "POST",
                url = url,
                headers = emptyMap(),
                body = bodyBytes,
                region = region,
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey
            )
        }
        AiAuthScheme.TencentCloudTC3 -> {
            // TC3 签名需要完整的 payload，此处返回空 Map，
            // 实际签名由 LlmClient 在获得完整 body 后调用 TencentCloudTC3Signer
            emptyMap()
        }
        AiAuthScheme.BaiduAccessToken -> emptyMap() // access_token 通过 URL query 参数传递
    }

    // ══════════════════════════════════════════════════════════════
    // OpenAI Compatible
    // ══════════════════════════════════════════════════════════════

    private fun buildOpenAICompatible(
        model: String,
        messages: List<Map<String, String>>,
        temperature: Double?,
        maxTokens: Int?,
        stream: Boolean
    ): String {
        val json = JSONObject().apply {
            put("model", model)
            put("stream", stream)
            put("messages", messagesToJsonArray(messages))
            temperature?.let { put("temperature", it) }
            maxTokens?.let { put("max_tokens", it) }
        }
        return json.toString()
    }

    // ══════════════════════════════════════════════════════════════
    // Anthropic Messages
    // ══════════════════════════════════════════════════════════════

    private fun buildAnthropic(
        model: String,
        messages: List<Map<String, String>>,
        temperature: Double?,
        maxTokens: Int?,
        stream: Boolean
    ): String {
        // 从 messages 中提取 system prompt
        val systemMessages = messages.filter { it["role"] == "system" }
        val nonSystemMessages = messages.filter { it["role"] != "system" }

        val json = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens ?: 4096)
            put("stream", stream)
            if (systemMessages.isNotEmpty()) {
                put("system", systemMessages.joinToString("\n") { it["content"] ?: "" })
            }
            put("messages", messagesToJsonArray(nonSystemMessages))
            temperature?.let { put("temperature", it) }
        }
        return json.toString()
    }

    // ══════════════════════════════════════════════════════════════
    // Gemini Native
    // ══════════════════════════════════════════════════════════════

    private fun buildGemini(
        messages: List<Map<String, String>>,
        temperature: Double?,
        maxTokens: Int?
    ): String {
        // 提取 system prompt
        val systemMessages = messages.filter { it["role"] == "system" }
        val nonSystemMessages = messages.filter { it["role"] != "system" }

        val json = JSONObject().apply {
            // systemInstruction（Gemini REST API 使用 camelCase）
            if (systemMessages.isNotEmpty()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        systemMessages.forEach { msg ->
                            put(JSONObject().apply { put("text", msg["content"] ?: "") })
                        }
                    })
                })
            }
            // contents
            put("contents", JSONArray().apply {
                nonSystemMessages.forEach { msg ->
                    put(JSONObject().apply {
                        // Gemini 使用 "user" 和 "model" 角色
                        val role = if (msg["role"] == "assistant") "model" else "user"
                        put("role", role)
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", msg["content"] ?: "") })
                        })
                    })
                }
            })
            // generationConfig
            if (temperature != null || maxTokens != null) {
                put("generationConfig", JSONObject().apply {
                    temperature?.let { put("temperature", it) }
                    maxTokens?.let { put("maxOutputTokens", it) }
                })
            }
        }
        return json.toString()
    }

    // ══════════════════════════════════════════════════════════════
    // DashScope Native
    // ══════════════════════════════════════════════════════════════

    /**
     * DashScope 原生 API 格式。
     * 关键差异：messages 在 `input` 中，stream 在 `parameters` 中。
     */
    private fun buildDashScopeNative(
        model: String,
        messages: List<Map<String, String>>,
        temperature: Double?,
        maxTokens: Int?,
        stream: Boolean
    ): String {
        val json = JSONObject().apply {
            put("model", model)
            put("input", JSONObject().apply {
                put("messages", messagesToJsonArray(messages))
            })
            put("parameters", JSONObject().apply {
                put("stream", stream)
                if (stream) put("incremental_output", true)
                temperature?.let { put("temperature", it) }
                maxTokens?.let { put("max_tokens", it) }
            })
        }
        return json.toString()
    }

    // ══════════════════════════════════════════════════════════════
    // Qianfan V1 RPC
    // ══════════════════════════════════════════════════════════════

    /**
     * 百度千帆 v1 REST-RPC 格式。
     * messages 在 body 顶层，无 model 字段（model 在 URL 路径中）。
     */
    private fun buildQianfanV1(
        messages: List<Map<String, String>>,
        temperature: Double?,
        stream: Boolean
    ): String {
        val systemMessages = messages.filter { it["role"] == "system" }
        val nonSystemMessages = messages.filter { it["role"] != "system" }

        val json = JSONObject().apply {
            put("messages", messagesToJsonArray(nonSystemMessages))
            put("stream", stream)
            temperature?.let { put("temperature", it) }
            if (systemMessages.isNotEmpty()) {
                put("system", systemMessages.joinToString("\n") { it["content"] ?: "" })
            }
        }
        return json.toString()
    }

    // ══════════════════════════════════════════════════════════════
    // Tencent Cloud TC3
    // ══════════════════════════════════════════════════════════════

    /**
     * 腾讯云混元 TC3 格式。
     * 请求体为大写键名的 OpenAI 兼容格式。
     */
    private fun buildTencentCloudTC3(
        model: String,
        messages: List<Map<String, String>>,
        temperature: Double?,
        maxTokens: Int?,
        stream: Boolean
    ): String {
        val json = JSONObject().apply {
            put("Model", model)
            put("Stream", stream)
            put("Messages", JSONArray().apply {
                messages.forEach { msg ->
                    put(JSONObject().apply {
                        put("Role", msg["role"] ?: "user")
                        put("Content", msg["content"] ?: "")
                    })
                }
            })
            temperature?.let { put("Temperature", it) }
            maxTokens?.let { put("MaxTokens", it) }
        }
        return json.toString()
    }

    // ══════════════════════════════════════════════════════════════
    // 共用工具方法
    // ══════════════════════════════════════════════════════════════

    /**
     * 将 List<Map<String, String>> 转换为 JSONArray。
     * 每个 Map 转换为 JSONObject，保留所有键值对。
     */
    internal fun messagesToJsonArray(messages: List<Map<String, String>>): JSONArray {
        return JSONArray().apply {
            messages.forEach { msg ->
                put(JSONObject().apply {
                    msg.forEach { (k, v) -> put(k, v) }
                })
            }
        }
    }
}
