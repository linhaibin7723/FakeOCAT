package com.example.fakeocat.network

import com.example.fakeocat.network.parsers.DashScopeNativeParser
import com.example.fakeocat.network.parsers.QianfanV1Parser
import com.example.fakeocat.network.parsers.TencentCloudTC3Parser
import org.json.JSONObject

internal sealed class ParsedSseChunk {
    data class Text(val value: String) : ParsedSseChunk()
    object Done : ParsedSseChunk()
    object Ignore : ParsedSseChunk()
}

internal fun interface ProviderStreamParser {
    fun parse(type: String?, data: String): ParsedSseChunk
}

/**
 * 流式 SSE 数据解析器。
 *
 * 优化说明：
 * - 将多次重复的 `optJSONArray("choices")?.optJSONObject(0)` 链抽取为
 *   `extractFirstChoice()` 和 `extractDeltaContent()` 方法，避免重复 JSON 遍历。
 * - 每个 data 行只解析一次 JSONObject，然后通过预定义路径提取文本内容。
 */
internal object ProviderStreamParsers {

    private val doneEventTypes = setOf(
        "done",
        "message_stop",
        "completion_stop",
        "response.completed"
    )

    // ── 预定义的 JSON 路径提取器（每个 data 行只解析一次 JSONObject） ──

    /**
     * 从 OpenAI 兼容的 SSE chunk 中提取首个 choice 对象。
     * choices[0] 被访问 1 次后被缓存，避免重复遍历 JSON 树。
     */
    private fun extractFirstChoice(obj: JSONObject): JSONObject? {
        return obj.optJSONArray("choices")?.optJSONObject(0)
    }

    /**
     * 从 delta 对象中提取正文文本字段。
     * 仅返回 content 字段，忽略 reasoning_content / reasoning（思考过程）。
     */
    private fun extractDeltaContent(delta: JSONObject?): String? {
        if (delta == null) return null
        // 使用 optNullableString 避免 JSON null 被解析为字面量 "null"
        delta.optNullableString("content")?.let { return it }
        return null
    }

    // ── 各 provider 的解析器 ──

    val openAiCompatible = ProviderStreamParser { type, data ->
        if (isDoneToken(type, data)) return@ProviderStreamParser ParsedSseChunk.Done
        val obj = parseJson(data) ?: return@ProviderStreamParser ParsedSseChunk.Ignore

        // choices[0].delta.content
        val choice = extractFirstChoice(obj)
        val text = extractDeltaContent(choice?.optJSONObject("delta"))
            // choices[0].text（部分 provider 的非 delta 格式）
            ?: choice?.optString("text").takeIf { it.isNullOrBlank().not() }
            // choices[0].message.content
            ?: extractMessageContent(choice?.optJSONObject("message"))
            // 顶层回退
            ?: obj.optNullableString("output_text")
            ?: obj.optNullableString("text")

        if (text.isNullOrBlank()) ParsedSseChunk.Ignore else ParsedSseChunk.Text(text)
    }

    val anthropic = ProviderStreamParser { type, data ->
        if (isDoneToken(type, data)) return@ProviderStreamParser ParsedSseChunk.Done
        val obj = parseJson(data) ?: return@ProviderStreamParser ParsedSseChunk.Ignore
        if (doneEventTypes.contains(obj.optString("type"))) {
            return@ProviderStreamParser ParsedSseChunk.Done
        }

        val text = obj.optJSONObject("delta")?.optString("text")
        if (text.isNullOrBlank()) ParsedSseChunk.Ignore else ParsedSseChunk.Text(text)
    }

    val gemini = ProviderStreamParser { type, data ->
        if (isDoneToken(type, data)) return@ProviderStreamParser ParsedSseChunk.Done
        val obj = parseJson(data) ?: return@ProviderStreamParser ParsedSseChunk.Ignore

        // 检测 Gemini 错误响应（HTTP 200 但 body 包含 error 字段）
        val errorObj = obj.optJSONObject("error")
        if (errorObj != null) {
            val code = errorObj.optInt("code", 0)
            val message = errorObj.optString("message", "Unknown Gemini error")
            val status = errorObj.optString("status", "")
            return@ProviderStreamParser ParsedSseChunk.Text(
                "[Gemini Error $code/$status] $message"
            )
        }

        val candidate = obj.optJSONArray("candidates")?.optJSONObject(0)
        val content = candidate?.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        val text = parts?.optJSONObject(0)?.optString("text")

        if (text.isNullOrBlank()) ParsedSseChunk.Ignore else ParsedSseChunk.Text(text)
    }

    val hunyuan = ProviderStreamParser { type, data ->
        when (val base = openAiCompatible.parse(type, data)) {
            is ParsedSseChunk.Text -> base
            ParsedSseChunk.Done -> ParsedSseChunk.Done
            ParsedSseChunk.Ignore -> {
                val obj = parseJson(data) ?: return@ProviderStreamParser ParsedSseChunk.Ignore
                val text = firstNonBlank(
                    obj.optNullableString("reply"),
                    obj.optNullableString("result")
                )
                if (text.isNullOrBlank()) ParsedSseChunk.Ignore else ParsedSseChunk.Text(text)
            }
        }
    }

    val ernie = ProviderStreamParser { type, data ->
        when (val base = openAiCompatible.parse(type, data)) {
            is ParsedSseChunk.Text -> base
            ParsedSseChunk.Done -> ParsedSseChunk.Done
            ParsedSseChunk.Ignore -> {
                val obj = parseJson(data) ?: return@ProviderStreamParser ParsedSseChunk.Ignore
                val text = obj.optNullableString("result")
                if (text.isNullOrBlank()) ParsedSseChunk.Ignore else ParsedSseChunk.Text(text)
            }
        }
    }

    val minimax = ProviderStreamParser { type, data ->
        when (val base = openAiCompatible.parse(type, data)) {
            is ParsedSseChunk.Text -> base
            ParsedSseChunk.Done -> ParsedSseChunk.Done
            ParsedSseChunk.Ignore -> {
                val obj = parseJson(data) ?: return@ProviderStreamParser ParsedSseChunk.Ignore
                val text = firstNonBlank(
                    obj.optNullableString("reply"),
                    obj.optNullableString("output_text")
                )
                if (text.isNullOrBlank()) ParsedSseChunk.Ignore else ParsedSseChunk.Text(text)
            }
        }
    }

    /**
     * 按 [ApiProtocol] 分发解析器。
     *
     * 这是新的推荐入口，替代基于 provider ID 字符串的 [forProvider] 方法。
     * 每种协议对应唯一解析器，同协议的不同端点变体（如 Azure OpenAI vs OpenAI）共享同一解析器。
     */
    fun forProtocol(protocol: ApiProtocol): ProviderStreamParser = when (protocol) {
        ApiProtocol.OpenAICompatible,
        ApiProtocol.AzureOpenAI -> openAiCompatible

        ApiProtocol.AnthropicMessages,
        ApiProtocol.BedrockAnthropic,
        ApiProtocol.VertexAnthropic -> anthropic

        ApiProtocol.GeminiNative,
        ApiProtocol.VertexGemini -> gemini

        ApiProtocol.DashScopeNative -> DashScopeNativeParser
        ApiProtocol.QianfanV1RPC -> QianfanV1Parser
        ApiProtocol.TencentCloudTC3 -> TencentCloudTC3Parser
    }

    /**
     * 按 provider ID 字符串分发解析器（向后兼容）。
     *
     * 对于已迁移到 [ApiProtocol] 的场景，请优先使用 [forProtocol]。
     */
    fun forProvider(provider: String): ProviderStreamParser = when (provider) {
        "anthropic" -> anthropic
        "gemini" -> gemini
        "hunyuan" -> hunyuan
        "ernie" -> ernie
        "minimax" -> minimax
        else -> openAiCompatible
    }

    private fun parseJson(data: String): JSONObject? {
        val trimmed = data.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractMessageContent(message: JSONObject?): String? {
        if (message == null) return null
        val direct = message.optNullableString("content")
        if (!direct.isNullOrBlank()) return direct

        val contentArray = message.optJSONArray("content") ?: return null
        for (i in 0 until contentArray.length()) {
            val part = contentArray.opt(i)
            when (part) {
                is JSONObject -> {
                    val text = part.optNullableString("text")
                    if (!text.isNullOrBlank()) return text
                }
                is String -> if (part.isNotBlank()) return part
            }
        }
        return null
    }

    private fun isDoneToken(type: String?, data: String): Boolean {
        val trimmedType = type?.trim().orEmpty()
        if (doneEventTypes.contains(trimmedType)) return true

        val trimmedData = data.trim()
        if (trimmedData == "[DONE]") return true

        val obj = parseJson(trimmedData) ?: return false
        return doneEventTypes.contains(obj.optString("type"))
    }

    private fun firstNonBlank(vararg values: String?): String? {
        for (v in values) {
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }
}

