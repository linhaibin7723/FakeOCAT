package com.example.fakeocat.network

import android.util.Log
import com.example.fakeocat.BuildConfig
import com.example.fakeocat.network.auth.AwsSigV4Signer
import com.example.fakeocat.network.auth.BaiduAccessTokenFetcher
import com.example.fakeocat.network.auth.GcpOAuth2Signer
import com.example.fakeocat.network.auth.TencentCloudTC3Signer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 带 DNS 缓存的自定义 Dns 实现。
 * 在应用启动时预解析所有 AI provider 的 host，消除首次请求的 DNS 查询延迟。
 */
internal class CachedDns : Dns {
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> {
        return cache.getOrPut(hostname) {
            Dns.SYSTEM.lookup(hostname)
        }
    }

    /** 预解析 host，结果将被缓存供后续 lookup 使用。 */
    fun prelookup(vararg hostnames: String) {
        hostnames.forEach { hostname ->
            try {
                // 同步解析后放入缓存
                cache[hostname] = Dns.SYSTEM.lookup(hostname)
            } catch (_: Exception) {
                // 预热失败不影响主流程
            }
        }
    }

    companion object {
        /** 所有 AI provider 的 host 列表 */
        val ALL_PROVIDER_HOSTS = arrayOf(
            "api.openai.com",
            "api.anthropic.com",
            "generativelanguage.googleapis.com",
            "api.deepseek.com",
            "api.x.ai",
            "dashscope.aliyuncs.com",
            "open.bigmodel.cn",
            "api.moonshot.cn",
            "api.moonshot.ai",              // Kimi 国际站
            "api.minimax.chat",
            "api.xiaomimimo.com",
            "token-plan-cn.xiaomimimo.com", // MiMo Token Plan
            "api.hunyuan.cloud.tencent.com",
            "qianfan.baidubce.com",
            "aip.baidubce.com",             // 百度千帆 v1
            "hunyuan.tencentcloudapi.com"   // 腾讯云 TC3
            // Azure / AWS / GCP host 是动态的，无法预解析
        )
    }
}

/**
 * 低延迟的 LLM 流式请求客户端。
 *
 * 核心优化：
 * - 共享连接池（20 空闲连接、10 分钟 keep-alive）减少 TCP/TLS 握手
 * - DNS 预解析 + 缓存消除 DNS 查询延迟
 * - 原始 OkHttp source 逐行读取替代 okhttp-sse 减少回调开销
 * - 首 token 超时检测（15s），快速失败避免长时间等待
 */
class LlmClient() {

    companion object {
        private const val TAG = "LlmClient"

        /** 安全日志：在 JVM 单元测试中不会抛出异常 */
        private fun safeLog(priority: Int, msg: String, tr: Throwable? = null) {
            try {
                if (tr != null) Log.println(priority, TAG, "$msg\n${Log.getStackTraceString(tr)}")
                else Log.println(priority, TAG, msg)
            } catch (_: Throwable) { /* 单元测试中 android.util.Log 不可用 */ }
        }
        private fun d(msg: String) = safeLog(Log.DEBUG, msg)
        private fun w(msg: String) = safeLog(Log.WARN, msg)
        private fun e(msg: String, tr: Throwable? = null) = safeLog(Log.ERROR, msg, tr)
        private const val FIRST_TOKEN_TIMEOUT_MS = 15_000L
        /** 证书固定引脚（SHA256 哈希）。仅在 Release 构建时启用。 */
        private val certificatePinner = CertificatePinner.Builder()
            .add("generativelanguage.googleapis.com",
                "sha256/vqg5bUG+qXcqS0J4VsQyBG/rH/5mQLKLYCpFr4bebvk=")
            .add("api.openai.com",
                "sha256/rwQEJp/dzuKRR34exkV/Eg+BvIqclbrD/QqVK44O1n0=")
            .add("api.anthropic.com",
                "sha256/PLNqWhvts4aLeuvBGZ2pDdKMfEF+w24PNmK0lnIH0Jc=")
            .add("api.deepseek.com",
                "sha256/v6jE0yqApnVtkKqJ7dSnRru0HkMRhcv5JhX1Pz4/z9I=")
            .add("api.mistral.ai",
                "sha256/9RArj3lJHCZ5gMr0qDVOjZs2UJ+emZfRvhQ7v+xY/KM=")
            .add("api.x.ai",
                "sha256/3HeDDOKCxGnZy3pDdKm1lhOIG/kSaLFUjYRTCpM8lMA=")
            .build()
        private const val CONNECT_TIMEOUT_SEC = 10L
        private const val READ_TIMEOUT_SEC = 30L
        private const val WRITE_TIMEOUT_SEC = 30L
    }

    /** 共享 DNS 缓存实例，同时供外部 prelookup 调用 */
    internal val dnsCache = CachedDns()

    /** 共享连接池：20 个空闲连接，10 分钟 keep-alive */
    private val sharedConnectionPool = ConnectionPool(
        maxIdleConnections = 20,
        keepAliveDuration = 10,
        TimeUnit.MINUTES
    )

    /** 非流式 HTTP 客户端（用于 model list 等短请求） */
    internal val httpClient = OkHttpClient.Builder()
        .dns(dnsCache)
        .connectionPool(sharedConnectionPool)
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .apply {
            // 证书固定：仅在 Release 构建时启用，防止 MITM 攻击
            if (!BuildConfig.DEBUG) {
                certificatePinner(certificatePinner)
            }
        }
        .build()

    /** 流式 HTTP 客户端（长连接，readTimeout = 0） */
    private val streamClient = httpClient.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .connectionPool(sharedConnectionPool)
        .build()

    init {
        // 后台异步预解析所有 provider 的 DNS，避免阻塞主线程
        Thread {
            dnsCache.prelookup(*CachedDns.ALL_PROVIDER_HOSTS)
        }.apply {
            isDaemon = true
            name = "dns-prelookup"
        }.start()
    }

    // ═══════════════════════════════════════════════════
    // 新版公共入口（基于 EndpointProfile）
    // ═══════════════════════════════════════════════════

    /**
     * 基于 [EndpointProfile] 的统一流式聊天接口。
     *
     * 根据 profile 的 [ApiProtocol] 构建请求体、根据 [AiAuthScheme] 应用认证签名、
     * 根据 [ApiProtocol] 选择 SSE 解析器。
     *
     * @param profile       端点配置
     * @param model         模型名
     * @param messages      消息列表，每个元素为包含 "role" 和 "content" 键的 Map
     * @param apiKey        API 密钥（含义取决于 authScheme）
     * @param temperature   温度参数
     * @param maxTokens     最大 token 数
     * @param extraConfig   用户提供的额外配置值 Map
     * @param onToken       每收到一个 token 时回调
     * @param onDone        流完成时回调
     * @param onError       发生错误时回调
     */
    suspend fun streamChatWithProfile(
        profile: EndpointProfile,
        model: String,
        messages: List<Map<String, String>>,
        apiKey: String,
        temperature: Double? = null,
        maxTokens: Int? = null,
        extraConfig: Map<String, String> = emptyMap(),
        onToken: suspend (String) -> Unit,
        onDone: suspend () -> Unit = {},
        onError: suspend (String) -> Unit = {}
    ) {
        // 确保所有同步 OkHttp 调用在 IO 线程执行，避免 NetworkOnMainThreadException
        withContext(Dispatchers.IO) {
        // 对于需要特殊认证的端点，先获取 access_token
        val effectiveApiKey = resolveAccessToken(profile, apiKey, extraConfig)

        // 使用 RequestBuilder 构建请求信息（URL、headers、body）
        val (url, headers, body) = RequestBuilder.buildRequest(
            profile = profile,
            model = model,
            messages = messages,
            apiKey = effectiveApiKey,
            temperature = temperature,
            maxTokens = maxTokens,
            extraConfig = extraConfig
        )

        // 构建 OkHttp Request
        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Accept", "text/event-stream")
            .addHeader("Cache-Control", "no-cache")
            .post(body.toRequestBody("application/json".toMediaType()))

        // 添加 RequestBuilder 生成的 headers
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }

        // 对于 TC3 签名，需要在获得完整 body 后重新签名
        if (profile.authScheme == AiAuthScheme.TencentCloudTC3) {
            val secretId = extraConfig["secret_id"]
                ?: throw IllegalArgumentException("secret_id is required for TencentCloudTC3")
            val region = extraConfig["region"] ?: "ap-guangzhou"
            val tc3Headers = TencentCloudTC3Signer.sign(
                service = "hunyuan",
                action = "ChatCompletions",
                payload = body,
                secretId = secretId,
                secretKey = effectiveApiKey,
                region = region
            )
            // 清除 RequestBuilder 可能添加的 headers，使用 TC3 签名的 headers
            tc3Headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        }

        val request = requestBuilder.build()

        // 选择 SSE 解析器
        val parser = ProviderStreamParsers.forProtocol(profile.apiProtocol)

        // 发送请求并解析流
        // === 诊断日志：记录完整的请求信息 ===
        d("=== Gemini Debug ===")
        d("Protocol: ${profile.apiProtocol}, AuthScheme: ${profile.authScheme}")
        d("Request URL: $url")
        d("Request Headers: ${headers.keys}")
        d("Request Body (first 500): ${body.take(500)}")

        var response: Response? = null
        try {
            response = streamClient.newCall(request).execute()

            // === 诊断日志：记录响应信息 ===
            d("Response Code: ${response.code}, Message: ${response.message}")
            d("Response Headers: ${response.headers}")

            if (!response.isSuccessful) {
                val errorMsg = buildErrorMessage(response, profile)
                e("HTTP Error: $errorMsg")
                onError(errorMsg)
                return@withContext
            }

            val body2 = response.body ?: run {
                w("Response body is null!")
                onDone(); return@withContext
            }
            val source = body2.source()
            source.timeout().timeout(FIRST_TOKEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            var hasReceivedFirstToken = false
            var lineCount = 0

            while (currentCoroutineContext().isActive && !source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                lineCount++

                // === 诊断日志：记录前 5 行原始响应 ===
                if (lineCount <= 5) {
                    d("SSE line $lineCount: ${line.take(300)}")
                }

                if (line.trim().isEmpty()) continue
                if (!line.startsWith("data:")) {
                    // === 诊断日志：非 data: 开头的行 ===
                    if (lineCount <= 10) {
                        w("Non-data line $lineCount: ${line.take(200)}")
                    }
                    continue
                }

                val data = line.removePrefix("data:").trim()
                when (val chunk = parser.parse(null, data)) {
                    is ParsedSseChunk.Text -> {
                        if (!hasReceivedFirstToken) {
                            hasReceivedFirstToken = true
                            source.timeout().timeout(0, TimeUnit.MILLISECONDS)
                            d("First token received at line $lineCount")
                        }
                        onToken(chunk.value)
                    }
                    ParsedSseChunk.Done -> {
                        d("Stream done at line $lineCount")
                        onDone()
                        return@withContext
                    }
                    ParsedSseChunk.Ignore -> {
                        // === 诊断日志：被忽略的 chunk ===
                        if (lineCount <= 10) {
                            w("Ignored chunk at line $lineCount: ${data.take(200)}")
                        }
                    }
                }
            }
            d("Stream EOF after $lineCount lines, hasReceivedFirstToken=$hasReceivedFirstToken")
            onDone() // EOF
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // === 诊断日志：完整异常信息 ===
            e("Stream exception: ${e.javaClass.simpleName}: ${e.message}", e)
            onError(e.message ?: "${e.javaClass.simpleName} (check Logcat for details)")
        } finally {
            response?.close()
        }
        } // end withContext(Dispatchers.IO)
    }

    /**
     * 基于 [EndpointProfile] 的 Flow 版流式聊天接口。
     *
     * 供 StreamOrchestrator 使用，返回一个冷 Flow。
     */
    fun streamChatWithProfile(
        profile: EndpointProfile,
        model: String,
        messages: List<Map<String, String>>,
        apiKey: String,
        temperature: Double? = null,
        maxTokens: Int? = null,
        extraConfig: Map<String, String> = emptyMap()
    ): Flow<String> = channelFlow {
        streamChatWithProfile(
            profile = profile,
            model = model,
            messages = messages,
            apiKey = apiKey,
            temperature = temperature,
            maxTokens = maxTokens,
            extraConfig = extraConfig,
            onToken = { send(it) },
            onDone = { close() },
            onError = { close(Exception(it)) }
        )
    }

    /**
     * 对于需要特殊认证的端点，先获取 access_token。
     * GcpOAuth2 需要从 Service Account JSON 获取 access token。
     * BaiduAccessToken 需要从 API Key + Secret Key 获取 access token。
     */
    private suspend fun resolveAccessToken(
        profile: EndpointProfile,
        apiKey: String,
        extraConfig: Map<String, String>
    ): String = when (profile.authScheme) {
        AiAuthScheme.GcpOAuth2Bearer -> {
            // apiKey 存储的是 Service Account JSON，需要先获取 access_token
            GcpOAuth2Signer.getAccessToken(apiKey, httpClient)
        }
        AiAuthScheme.BaiduAccessToken -> {
            // apiKey 存储的是 API Key，需要 secret_key 获取 access_token
            val secretKey = extraConfig["secret_key"] ?: apiKey
            BaiduAccessTokenFetcher.getAccessToken(apiKey, secretKey, httpClient)
        }
        else -> apiKey
    }

    /**
     * 根据 HTTP 状态码和 profile 信息构建友好的错误消息。
     * 尝试从响应体中提取结构化的错误信息（如 Gemini/Google API 的 JSON error）。
     */
    private fun buildErrorMessage(response: Response, profile: EndpointProfile): String {
        val code = response.code
        val detail = try { response.peekBody(4096).string() } catch (_: Exception) { "" }

        // 尝试解析 JSON 错误体，提取有用信息
        val parsedDetail = try {
            if (detail.isNotBlank() && detail.trimStart().startsWith("{")) {
                val obj = org.json.JSONObject(detail)
                // Gemini/Google API 错误格式: {"error": {"code": 400, "message": "...", "status": "..."}}
                val error = obj.optJSONObject("error")
                if (error != null) {
                    val msg = error.optString("message", "")
                    val status = error.optString("status", "")
                    if (msg.isNotBlank()) {
                        if (status.isNotBlank()) "$msg ($status)" else msg
                    } else null
                } else null
            } else null
        } catch (_: Exception) { null }

        val displayDetail = parsedDetail ?: detail

        return when (code) {
            400 -> {
                val base = "请求格式错误"
                if (displayDetail.isNotBlank()) "$base：$displayDetail" else base
            }
            401 -> "认证失败：API Key 无效或已过期"
            403 -> "访问被拒绝：无权访问该资源"
            404 -> "模型或端点不存在，请检查模型名是否正确"
            429 -> "请求过于频繁，请稍后重试"
            500 -> "服务器内部错误，请稍后重试"
            503 -> "服务暂时不可用，请稍后重试"
            else -> "HTTP $code: ${response.message}${
                if (displayDetail.isNotBlank()) " — $displayDetail" else ""
            }"
        }
    }

    // ═══════════════════════════════════════════════════
    // 旧版公共入口（向后兼容）
    // ═══════════════════════════════════════════════════

    /**
     * 旧版流式聊天接口（按 provider ID 路由）。
     *
     * 向后兼容：内部查找默认 Profile 后委托给 [streamChatWithProfile]。
     * 如果找不到 Profile（provider ID 未知），则回退到原来的硬编码逻辑。
     */
    fun streamChat(
        provider: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        baseUrlOverride: String? = null
    ): Flow<String> {
        val providerInfo = AiProviderCatalog.getProvider(provider)
        var profile = providerInfo?.defaultProfile

        if (profile != null) {
            // 如果提供了 baseUrlOverride，覆盖 profile 的 URL
            if (baseUrlOverride != null) {
                profile = profile.copy(chatCompletionsUrl = baseUrlOverride)
            }
            // 委托给新版方法
            val messages = listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
            )
            return streamChatWithProfile(
                profile = profile,
                model = model,
                messages = messages,
                apiKey = apiKey
            )
        }

        // 回退：未知 provider，使用原来的硬编码逻辑
        return streamChatLegacy(provider, apiKey, model, systemPrompt, userPrompt, baseUrlOverride)
    }

    // ═══════════════════════════════════════════════════
    // 旧版硬编码实现（向后兼容回退）
    // ═══════════════════════════════════════════════════

    private fun streamChatLegacy(
        provider: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        baseUrlOverride: String?
    ): Flow<String> = channelFlow {
        val parser = ProviderStreamParsers.forProvider(provider)

        val json = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", userPrompt) })
            })
        }

        val request = Request.Builder()
            .url(baseUrlOverride ?: "https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .addHeader("Cache-Control", "no-cache")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        launch(Dispatchers.IO) {
            var response: Response? = null
            try {
                response = streamClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    val errorMsg = when (response.code) {
                        401 -> "认证失败：API Key 无效或已过期"
                        429 -> "请求过于频繁，请稍后重试"
                        500 -> "服务器内部错误，请稍后重试"
                        503 -> "服务暂时不可用，请稍后重试"
                        else -> "HTTP ${response.code}: ${response.message}"
                    }
                    close(Exception(errorMsg))
                    return@launch
                }

                val body = response.body ?: run { close(); return@launch }
                val source = body.source()
                source.timeout().timeout(FIRST_TOKEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                var hasReceivedFirstToken = false

                while (isActive && !source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.trim().isEmpty()) continue
                    if (!line.startsWith("data:")) continue

                    val data = line.removePrefix("data:").trim()
                    when (val chunk = parser.parse(null, data)) {
                        is ParsedSseChunk.Text -> {
                            if (!hasReceivedFirstToken) {
                                hasReceivedFirstToken = true
                                source.timeout().timeout(0, TimeUnit.MILLISECONDS)
                            }
                            send(chunk.value)
                        }
                        ParsedSseChunk.Done -> {
                            close()
                            return@launch
                        }
                        ParsedSseChunk.Ignore -> Unit
                    }
                }
                close() // EOF
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                close(e)
            } finally {
                response?.close()
            }
        }
    }
}
