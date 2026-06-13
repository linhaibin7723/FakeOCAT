package com.example.fakeocat.network.parsers

import com.example.fakeocat.network.ParsedSseChunk
import org.junit.Assert.*
import org.junit.Test

/**
 * DashScopeNativeParser 单元测试。
 *
 * 覆盖：
 * - output.choices[0].message.content 增量文本提取
 * - output.choices[0].finish_reason 为 "stop" 时结束
 * - 通用 done token 处理
 * - 无效 JSON / 空数据处理
 */
class DashScopeNativeParserTest {

    private val parser = DashScopeNativeParser

    @Test
    fun `parses incremental content from output choices`() {
        val data = """{"output":{"choices":[{"message":{"content":"Hello"}}]}}"""
        val chunk = parser.parse(null, data)
        assertEquals(ParsedSseChunk.Text("Hello"), chunk)
    }

    @Test
    fun `parses incremental content with usage field`() {
        val data = """{"output":{"choices":[{"message":{"content":"World"}}]},"usage":{"total_tokens":100}}"""
        val chunk = parser.parse(null, data)
        assertEquals(ParsedSseChunk.Text("World"), chunk)
    }

    @Test
    fun `handles finish_reason stop as Done`() {
        val data = """{"output":{"choices":[{"message":{"content":""},"finish_reason":"stop"}]}}"""
        val chunk = parser.parse(null, data)
        assertTrue(chunk is ParsedSseChunk.Done)
    }

    @Test
    fun `handles finish_reason stop even with content`() {
        val data = """{"output":{"choices":[{"message":{"content":"last"},"finish_reason":"stop"}]}}"""
        val chunk = parser.parse(null, data)
        // finish_reason=stop 应该优先返回 Done
        assertTrue(chunk is ParsedSseChunk.Done)
    }

    @Test
    fun `ignores empty content`() {
        val data = """{"output":{"choices":[{"message":{"content":""}}]}}"""
        val chunk = parser.parse(null, data)
        assertTrue(chunk is ParsedSseChunk.Ignore)
    }

    @Test
    fun `ignores missing output field`() {
        val data = """{"usage":{"total_tokens":100}}"""
        val chunk = parser.parse(null, data)
        assertTrue(chunk is ParsedSseChunk.Ignore)
    }

    @Test
    fun `ignores empty choices array`() {
        val data = """{"output":{"choices":[]}}"""
        val chunk = parser.parse(null, data)
        assertTrue(chunk is ParsedSseChunk.Ignore)
    }

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
    fun `handles DONE bracket token`() {
        val chunk = parser.parse(null, "[DONE]")
        assertTrue(chunk is ParsedSseChunk.Done)
    }

    @Test
    fun `handles done type in JSON`() {
        val data = """{"type":"done"}"""
        val chunk = parser.parse(null, data)
        assertTrue(chunk is ParsedSseChunk.Done)
    }

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
    fun `handles CJK content`() {
        val data = """{"output":{"choices":[{"message":{"content":"你好世界"}}]}}"""
        val chunk = parser.parse(null, data)
        assertEquals(ParsedSseChunk.Text("你好世界"), chunk)
    }

    @Test
    fun `handles multiple consecutive chunks`() {
        val chunk1 = parser.parse(null, """{"output":{"choices":[{"message":{"content":"Hello"}}]}}""")
        val chunk2 = parser.parse(null, """{"output":{"choices":[{"message":{"content":" World"}}]}}""")
        val chunk3 = parser.parse(null, """{"output":{"choices":[{"message":{"content":""},"finish_reason":"stop"}]}}""")

        assertEquals(ParsedSseChunk.Text("Hello"), chunk1)
        assertEquals(ParsedSseChunk.Text(" World"), chunk2)
        assertTrue(chunk3 is ParsedSseChunk.Done)
    }
}
