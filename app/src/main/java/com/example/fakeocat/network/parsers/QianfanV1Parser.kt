package com.example.fakeocat.network.parsers

import com.example.fakeocat.network.ParsedSseChunk
import com.example.fakeocat.network.ProviderStreamParser
import org.json.JSONObject

/**
 * 百度千帆 v1 REST-RPC API 的 SSE 流式解析器。
 *
 * 响应格式示例：
 * ```json
 * {"result":"...","is_end":false,"usage":...}
 * ```
 *
 * 注意：`result` 是直接的文本字符串，不是 choices 数组。
 * 当 `is_end` 为 true 时表示流结束。
 */
internal object QianfanV1Parser : ProviderStreamParser {

    override fun parse(type: String?, data: String): ParsedSseChunk {
        // 检查通用 done token
        if (isDoneToken(type, data)) return ParsedSseChunk.Done

        val obj = parseJson(data) ?: return ParsedSseChunk.Ignore

        // 检查 is_end 字段
        val isEnd = obj.optBoolean("is_end", false)
        if (isEnd) return ParsedSseChunk.Done

        // 提取 result 字段（直接文本字符串）
        val text = obj.optString("result")
        if (text.isBlank()) return ParsedSseChunk.Ignore
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
