package com.example.fakeocat.network.parsers

import com.example.fakeocat.network.ParsedSseChunk
import com.example.fakeocat.network.ProviderStreamParser
import org.json.JSONObject

/**
 * 腾讯云 TC3 混元 API 的 SSE 流式解析器。
 *
 * 响应格式可能有两种：
 * 1. 标准 OpenAI 兼容格式：`{"choices":[{"delta":{"content":"..."}}]}`
 * 2. TC3 Response 包装格式：`{"Response":{"Choices":[{"Delta":{"Content":"..."}}]}}`
 *
 * 本解析器优先尝试 TC3 包装格式，失败后回退到 OpenAI 兼容格式。
 */
internal object TencentCloudTC3Parser : ProviderStreamParser {

    override fun parse(type: String?, data: String): ParsedSseChunk {
        // 检查通用 done token
        if (isDoneToken(type, data)) return ParsedSseChunk.Done

        val obj = parseJson(data) ?: return ParsedSseChunk.Ignore

        // 优先尝试 TC3 包装格式：Response.Choices[0].Delta.Content
        val response = obj.optJSONObject("Response")
        if (response != null) {
            val choices = response.optJSONArray("Choices")
            val firstChoice = choices?.optJSONObject(0)
            val delta = firstChoice?.optJSONObject("Delta")
            val text = delta?.optString("Content")
            if (!text.isNullOrBlank()) return ParsedSseChunk.Text(text)
        }

        // 回退到 OpenAI 兼容格式：choices[0].delta.content
        val choices = obj.optJSONArray("choices")
        val firstChoice = choices?.optJSONObject(0)
        val delta = firstChoice?.optJSONObject("delta")
        val text = delta?.optString("content")
        if (!text.isNullOrBlank()) return ParsedSseChunk.Text(text)

        // 尝试 choices[0].text
        val choiceText = firstChoice?.optString("text")
        if (!choiceText.isNullOrBlank()) return ParsedSseChunk.Text(choiceText)

        return ParsedSseChunk.Ignore
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
