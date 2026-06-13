package com.example.fakeocat.network.parsers

import com.example.fakeocat.network.ParsedSseChunk
import com.example.fakeocat.network.ProviderStreamParser
import com.example.fakeocat.network.ProviderStreamParsers
import org.json.JSONObject

/**
 * 阿里 DashScope 原生 API 的 SSE 流式解析器。
 *
 * 响应格式示例：
 * ```json
 * {"output":{"choices":[{"message":{"content":"..."}}]}, "usage":...}
 * ```
 *
 * 需要处理：
 * - `output.choices[0].message.content` — 增量文本
 * - `output.choices[0].finish_reason` — 结束标志（值为 "stop" 时结束）
 */
internal object DashScopeNativeParser : ProviderStreamParser {

    override fun parse(type: String?, data: String): ParsedSseChunk {
        // 检查通用 done token
        if (isDoneToken(type, data)) return ParsedSseChunk.Done

        val obj = parseJson(data) ?: return ParsedSseChunk.Ignore

        // DashScope 原生格式：output.choices[0].message.content
        val output = obj.optJSONObject("output")
        val choices = output?.optJSONArray("choices")
        val firstChoice = choices?.optJSONObject(0)

        // 检查 finish_reason
        val finishReason = firstChoice?.optString("finish_reason")
        if (finishReason == "stop") return ParsedSseChunk.Done

        // 提取 content（增量输出模式下为增量文本）
        val message = firstChoice?.optJSONObject("message")
        val text = message?.optString("content")

        if (text.isNullOrBlank()) return ParsedSseChunk.Ignore
        return ParsedSseChunk.Text(text)
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

    private fun isDoneToken(type: String?, data: String): Boolean {
        val trimmedType = type?.trim().orEmpty()
        val doneEventTypes = setOf("done", "message_stop", "completion_stop", "response.completed")
        if (doneEventTypes.contains(trimmedType)) return true

        val trimmedData = data.trim()
        if (trimmedData == "[DONE]") return true

        val obj = parseJson(trimmedData) ?: return false
        return doneEventTypes.contains(obj.optString("type"))
    }
}
