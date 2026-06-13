package com.example.fakeocat.network.parsers

import com.example.fakeocat.network.ParsedSseChunk
import org.junit.Assert.*
import org.junit.Test

/**
 * TencentCloudTC3Parser 单元测试。
 *
 * 覆盖：
 * - TC3 Response 包装格式：Response.Choices[0].Delta.Content
 * - 标准 OpenAI 兼容格式：choices[0].delta.content
 * - choices[0].text 回退
 * - 通用 done token 处理
 * - 无效 JSON / 空数据处理
 */
class TencentCloudTC3ParserTest {

    private val parser = TencentCloudTC3Parser

    // ══════════════════════════════════════════════
    // TC3 Response 包装格式
    // ══════════════════════════════════════════════

    @Test
    fun `parses TC3 Response wrapper format`() {
        val data = """{"Response":{"Choices":[{"Delta":{"Content":"Hello"}}]}}"""
        val chunk = parser.parse(null, data)
        assertEquals(ParsedSseChunk.Text("Hello"), chunk)
    }

    @Test
    fun `parses TC3 Response wrapper with usage`() {
        val data = """{"Response":{"Choices":[{"Delta":{"Content":"World"}}],"Usage":{"TotalTokens":100}}}"""
        val chunk = parser.parse(null, data)
        assertEquals(ParsedSseChunk.Text("World"), chunk)
    }

    @Test
    fun `ignores TC3 Response wrapper with empty content`() {
        val data = """{"Response":{"Choices":[{"Delta":{"Content":""}}]}}"""
        val chunk = parser.parse(null, data)
        assertTrue(chunk is ParsedSseChunk.Ignore)
    }

    // ══════════════════════════════════════════════
    // OpenAI 兼容格式
    // ══════════════════════════════════════════════

    @Test
    fun `parses standard OpenAI compatible format`() {
        val data = """{"choices":[{"delta":{"content":"Hello"}}]}"""
        val chunk = parser.parse(null, data)
        assertEquals(ParsedSseChunk.Text("Hello"), chunk)
    }

    @Test
    fun `parses choices text field fallback`() {
        val data = """{"choices":[{"text":"completion text"}]}"""
        val chunk = parser.parse(null, data)
        assertEquals(ParsedSseChunk.Text("completion text"), chunk)
    }

    // ══════════════════════════════════════════════
    // Done / 结束标志
    // ══════════════════════════════════════════════

    @Test
    fun `handles done type event`() {
        val chunk = parser.parse("done", "{}")
        assertTrue(chunk is ParsedSseChunk.Done)
    }

    @Test
    fun `handles message_stop type event`() {
        val chunk = parser.parse("message_stop", """{"type":"message_stop"}""")
        assertTrue(chunk is ParsedSseChunk.Done)
    }

    @Test
    fun `handles completion_stop type event`() {
        val chunk = parser.parse("completion_stop", """{"type":"completion_stop"}""")
        assertTrue(chunk is ParsedSseChunk.Done)
    }

    @Test
    fun `handles response_completed type event`() {
        val chunk = parser.parse("response.completed", "{}")
        assertTrue(chunk is ParsedSseChunk.Done)
    }

    @Test
    fun `handles DONE bracket token`() {
        val chunk = parser.parse(null, "[DONE]")
        assertTrue(chunk is ParsedSseChunk.Done)
    }

    @Test
    fun `handles done type in JSON body`() {
        val data = """{"type":"done"}"""
        val chunk = parser.parse(null, data)
        assertTrue(chunk is ParsedSseChunk.Done)
    }

    // ══════════════════════════════════════════════
    // 忽略无效数据
    // ══════════════════════════════════════════════

    @Test
    fun `ignores invalid JSON`() {
        val chunk = parser.parse(null, "not json")
        assertTrue(chunk is ParsedSseChunk.Ignore)
    }

    @Test
    fun `ignores empty string`() {
        val chunk = parser.parse(null, "")
        assertTrue(chunk is ParsedSseChunk.Ignore)
    }

    @Test
    fun `ignores non-object data`() {
        val chunk = parser.parse(null, "[]")
        assertTrue(chunk is ParsedSseChunk.Ignore)
    }

    @Test
    fun `ignores empty choices array`() {
        val data = """{"choices":[]}"""
        val chunk = parser.parse(null, data)
        assertTrue(chunk is ParsedSseChunk.Ignore)
    }

    // ══════════════════════════════════════════════
    // CJK 内容
    // ══════════════════════════════════════════════

    @Test
    fun `handles CJK content in TC3 format`() {
        val data = """{"Response":{"Choices":[{"Delta":{"Content":"你好世界"}}]}}"""
        val chunk = parser.parse(null, data)
        assertEquals(ParsedSseChunk.Text("你好世界"), chunk)
    }

    @Test
    fun `handles CJK content in OpenAI format`() {
        val data = """{"choices":[{"delta":{"content":"测试内容"}}]}"""
        val chunk = parser.parse(null, data)
        assertEquals(ParsedSseChunk.Text("测试内容"), chunk)
    }

    // ══════════════════════════════════════════════
    // 连续 chunk 处理
    // ══════════════════════════════════════════════

    @Test
    fun `handles mixed TC3 and OpenAI format chunks`() {
        val chunk1 = parser.parse(null, """{"Response":{"Choices":[{"Delta":{"Content":"TC3"}}]}}""")
        val chunk2 = parser.parse(null, """{"choices":[{"delta":{"content":" OpenAI"}}]}}""")
        val chunk3 = parser.parse("[DONE]", """{"type":"done"}""")

        assertEquals(ParsedSseChunk.Text("TC3"), chunk1)
        assertEquals(ParsedSseChunk.Text(" OpenAI"), chunk2)
        assertTrue(chunk3 is ParsedSseChunk.Done)
    }
}
