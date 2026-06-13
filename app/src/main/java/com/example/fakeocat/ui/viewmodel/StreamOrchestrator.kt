package com.example.fakeocat.ui.viewmodel

import android.util.Log
import com.example.fakeocat.data.PreferencesManager
import com.example.fakeocat.data.db.DatabaseHelper
import com.example.fakeocat.data.db.entity.MessageEntity
import com.example.fakeocat.network.AiProviderCatalog
import com.example.fakeocat.network.ConnectionPrewarmer
import com.example.fakeocat.network.LlmClient
import com.example.fakeocat.network.ResponseCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单次流式请求的重试结果。
 */
data class StreamAttemptResult(
    val succeeded: Boolean,
    val abortFurtherTries: Boolean,
    val errorMessage: String?
)

/**
 * 流式请求编排器：封装 sendMessage 的核心流程——
 * API Key 解析、缓存查询、流式请求+重试、结果持久化。
 *
 * 通过回调将 UI 状态更新交还给 ChatViewModel，自身不持有 UI 状态。
 */
class StreamOrchestrator(
    private val llmClient: LlmClient,
    private val dbHelper: DatabaseHelper,
    private val prefs: PreferencesManager,
    private val responseCache: ResponseCache
) {
    private val TAG = "StreamOrchestrator"

    /**
     * 执行一次完整的流式请求流程。
     *
     * @param mode         当前模式（"HowToSay" / "WhatMeans" / "FreeChat"）
     * @param userId       已持久化的用户消息 ID（用于会话追踪）
     * @param systemPrompt 已构建的 system prompt
     * @param userPrompt   已构建的 user prompt
     * @param userText     用户原始输入（用于缓存键）
     * @param onToken      每收到一个 token 时回调，传入当前累积的全部回复文本
     * @param onFallback   当 API Key 回退到其他服务商时回调，传入提示文本
     * @param onComplete   流完成且结果已持久化时回调，传入助手消息实体
     * @param onCancelled  用户取消生成时回调（部分内容已由内部持久化）
     * @param onError      发生不可恢复的错误时回调
     */
    suspend fun executeStreamRequest(
        mode: String,
        userId: Long,
        systemPrompt: String,
        userPrompt: String,
        userText: String,
        onToken: (String) -> Unit,
        onFallback: (String) -> Unit,
        onComplete: (MessageEntity) -> Unit,
        onCancelled: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        // === 第1步：API Key 解析与 fallback ===
        val originalProvider = prefs.selectedProviderFlow.first()
        var provider = originalProvider
        var apiKey = prefs.apiKeyFlowFor(provider).first()

        Log.d(TAG, "Initial check - Provider: $provider, Key Length: ${apiKey.length}")

        if (apiKey.isBlank()) {
            Log.d(TAG, "API Key for $provider is missing. Searching for any available key...")
            val fallback = prefs.findAnyAvailableKey()
            if (fallback != null) {
                provider = fallback.first
                apiKey = fallback.second
                Log.d(TAG, "Fallback successful! Using $provider key instead.")
                onFallback("已切换到 $provider 服务商（缺少${originalProvider}的API Key）")
            } else {
                Log.e(TAG, "No API Keys found in storage!")
                onError(RuntimeException("API Key is missing for $provider! Please configure it in Settings."))
                return
            }
        }

        // === 第2步：解析 EndpointProfile 和额外配置 ===
        val profile = prefs.resolveEndpointProfile(provider)
        val endpointConfig = prefs.getEndpointConfigMap(provider, profile.id)
        Log.d(TAG, "Using profile: ${profile.id} for provider: $provider (protocol: ${profile.apiProtocol})")

        // === 第3步：异步预热连接 ===
        ConnectionPrewarmer.warmUp(provider, apiKey)

        // === 第4步：获取模型名（优先使用用户手动选择的模型） ===
        val model = prefs.resolveModel(provider)
        Log.d(TAG, "Using model: $model for provider: $provider")

        // === 第5步：缓存查询（仅 WhatMeans 模式） ===
        val useCache = mode == "WhatMeans"
        val cacheKey = if (useCache) {
            ResponseCache.buildCacheKey(provider, model, mode, userText)
        } else null

        if (cacheKey != null) {
            responseCache.get(cacheKey)?.let { cached ->
                Log.d(TAG, "ResponseCache HIT for $provider/$model ($mode)")
                val assistantMsg = MessageEntity(text = cached, isUser = false, mode = mode)
                val assistantId = dbHelper.insertMessage(assistantMsg)
                onComplete(assistantMsg.copy(id = assistantId))
                return
            }
            Log.d(TAG, "ResponseCache MISS for $provider/$model ($mode)")
        }

        // === 第6步：流式请求 + 重试 ===
        var assistantReply = ""
        try {
            val modelsToTry = listOf(model)

            var succeeded = false
            var lastError: String? = null
            for (m in modelsToTry) {
                if (succeeded) break
                val result = streamWithRetry(
                    profile = profile,
                    apiKey = apiKey,
                    model = m,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    endpointConfig = endpointConfig
                ) { chunk ->
                    assistantReply += chunk
                    onToken(assistantReply)
                }
                succeeded = result.succeeded
                if (!succeeded) {
                    lastError = result.errorMessage
                    if (result.abortFurtherTries || assistantReply.isNotBlank()) break
                }
            }

            if (!succeeded && assistantReply.isBlank()) {
                onError(RuntimeException(lastError ?: "No response received (stream ended without data)"))
                return
            }
        } catch (_: CancellationException) {
            // 用户取消——如果有部分内容则先持久化
            if (assistantReply.isNotBlank()) {
                val partialMsg = MessageEntity(text = assistantReply, isUser = false, mode = mode)
                val partialId = dbHelper.insertMessage(partialMsg)
                onComplete(partialMsg.copy(id = partialId))
            }
            onCancelled()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in stream: ${e.message}", e)
            onError(e)
            return
        }

        // === 第7步：结果持久化 ===
        if (assistantReply.isNotEmpty()) {
            val assistantMsg = MessageEntity(text = assistantReply, isUser = false, mode = mode)
            val assistantId = dbHelper.insertMessage(assistantMsg)

            // 写入响应缓存（仅 WhatMeans 模式）
            if (cacheKey != null) {
                responseCache.put(cacheKey, assistantReply)
            }

            onComplete(assistantMsg.copy(id = assistantId))
        }
    }

    /**
     * 带重试的流式请求，加入 TTFT 计时日志。
     * 重试逻辑：最多 3 次，指数退避（500ms * attempt）。
     * 如果已收到首个 token 后失败，立即终止不再重试。
     *
     * 使用 [EndpointProfile] 版本的 LlmClient 接口。
     */
    private suspend fun streamWithRetry(
        profile: com.example.fakeocat.network.EndpointProfile,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        endpointConfig: Map<String, String>,
        onChunk: (String) -> Unit
    ): StreamAttemptResult {
        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        )

        var lastError: String? = null
        for (attempt in 0 until 3) {
            val receivedAny = AtomicBoolean(false)
            val ttftStartNanos = System.nanoTime()
            try {
                Log.d(TAG, "Starting streamChatWithProfile attempt=${attempt + 1} profile=${profile.id} model=$model")
                llmClient.streamChatWithProfile(
                    profile = profile,
                    model = model,
                    messages = messages,
                    apiKey = apiKey,
                    extraConfig = endpointConfig
                ).collect { chunk ->
                    val isFirst = receivedAny.compareAndSet(false, true)
                    if (isFirst) {
                        val ttftMs = (System.nanoTime() - ttftStartNanos) / 1_000_000
                        Log.d(TAG, "TTFT: ${ttftMs}ms for ${profile.id}/$model (attempt=${attempt + 1})")
                    }
                    onChunk(chunk)
                }
                return StreamAttemptResult(succeeded = true, abortFurtherTries = false, errorMessage = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Stream error profile=${profile.id} model=$model attempt=${attempt + 1}: ${e.message}")
                lastError = e.message
                // Gemini 特殊错误立即终止
                if (lastError?.contains("HTTP 429") == true || lastError?.contains("HTTP 404") == true) {
                    return StreamAttemptResult(succeeded = false, abortFurtherTries = true, errorMessage = lastError)
                }
                // 已收到部分数据后失败，立即终止
                if (receivedAny.get()) {
                    return StreamAttemptResult(succeeded = false, abortFurtherTries = true, errorMessage = lastError)
                }
                if (attempt == 2) return StreamAttemptResult(succeeded = false, abortFurtherTries = false, errorMessage = lastError)
                delay(500L * (attempt + 1))
            }
        }
        return StreamAttemptResult(succeeded = false, abortFurtherTries = false, errorMessage = lastError)
    }
}
