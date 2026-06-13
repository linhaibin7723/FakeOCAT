package com.example.fakeocat.network.parsers

import com.example.fakeocat.network.ParsedSseChunk
import org.junit.Assert.*
import org.junit.Test

/**
 * QianfanV1Parser 单元测试。
 *
 * 覆盖：
 * - result 字段直接文本提取
 * - is_end 为 true 时结束
 * - 通用 done token 处理
 * - 无效 JSON / 空数据处理
 */
class QianfanV1ParserTest {

    private val parser = QianfanV1Parser

    @Test
    fun `parses result field as text`() {
        val data = """{"result":"Hello, world!","is_end":false}"""
        val chunk = parser.parse(null, data)
        assertEquals(ParsedSseChunk.Text("Hello, world!"), chunk)
    }

    @Test
    fun `parses result without is_end field`() {
        val data = """{"result":"some text"}"""
        val chunk = parser.parse(null, data)
        assertEquals(ParsedSseChunk.Text("some text"), chunk)
    }

    @Test
    fun `handles is_end true as Done`() {
        val data = """{"result":"final","is_end":true}"""
        val chunk = parser.parse(null, data)
        assertTrue(chunk is ParsedSseChunk.Done)
    }

    @Test
    fun `handles is_end true with empty result as Done`() {
        val data = """{"result":"","is_end":true}"""
        val chunk = parser.parse(null, data)
        assertTrue(chunk is ParsedSseChunk.Done)
    }

    @Test
    fun `handles is_end true with usage field`() {
        val data = """{"result":"done text","is_end":true,"usage":{"total_tokens":50}}"""
        val chunk = parser.parse(null, data)
        assertTrue(chunk is ParsedSseChunk.Done)
    }

    @Test
    fun `ignores empty result when not is_end`() {
        val data = """{"result":"","is_end":false}"""
        val chunk = parser.parse(null, data)
        assertTrue(chunk is ParsedSseChunk.Ignore)
    }

    @Test
    fun `ignores blank result`() {
        val data = """{"result":"   ","is_end":false}"""
        val chunk = parser.parse(null, data)
        assertTrue(chunk is ParsedSseChunk.Ignore)
    }

    @Test
    fun `ignores missing result field`() {
        val data = """{"is_end":false,"usage":{"total_tokens":0}}"""
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
    fun `handles CJK result text`() {
        val data = """{"result":"你好，这是一个测试","is_end":false}"""
        val chunk = parser.parse(null, data)
        assertEquals(ParsedSseChunk.Text("你好，这是一个测试"), chunk)
    }

    @Test
    fun `handles multiple consecutive chunks`() {
        val chunk1 = parser.parse(null, """{"result":"Hello","is_end":false}""")
        val chunk2 = parser.parse(null, """{"result":" World","is_end":false}""")
        val chunk3 = parser.parse(null, """{"result":"!","is_end":true}""")

        assertEquals(ParsedSseChunk.Text("Hello"), chunk1)
        assertEquals(ParsedSseChunk.Text(" World"), chunk2)
        assertTrue(chunk3 is ParsedSseChunk.Done)
    }
}
