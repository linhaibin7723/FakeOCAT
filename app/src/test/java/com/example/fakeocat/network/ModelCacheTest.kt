package com.example.fakeocat.network

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ModelCacheTest {

    @After
    fun tearDown() {
        ModelCache.clear()
    }

    @Test
    fun `put and get returns cached models`() {
        val models = listOf(AiModel("gpt-4o"), AiModel("gpt-5.4-mini"))
        ModelCache.put("openai", models)

        val cached = ModelCache.get("openai")
        assertNotNull(cached)
        assertEquals(2, cached!!.size)
        assertEquals("gpt-4o", cached[0].id)
    }

    @Test
    fun `get returns null for unknown provider`() {
        assertNull(ModelCache.get("unknown"))
    }

    @Test
    fun `getOrNull returns models even if not yet expired`() {
        val models = listOf(AiModel("claude-haiku-4-5"))
        ModelCache.put("anthropic", models)

        val result = ModelCache.getOrNull("anthropic")
        assertNotNull(result)
        assertEquals(1, result!!.size)
    }

    @Test
    fun `getOrNull returns null for unknown provider`() {
        assertNull(ModelCache.getOrNull("unknown"))
    }

    @Test
    fun `clear specific provider removes only that provider`() {
        ModelCache.put("openai", listOf(AiModel("gpt-4o")))
        ModelCache.put("gemini", listOf(AiModel("gemini-2.5-flash")))

        ModelCache.clear("openai")

        assertNull(ModelCache.get("openai"))
        assertNotNull(ModelCache.get("gemini"))
    }

    @Test
    fun `clear all removes everything`() {
        ModelCache.put("openai", listOf(AiModel("gpt-4o")))
        ModelCache.put("gemini", listOf(AiModel("gemini-2.5-flash")))

        ModelCache.clear()

        assertNull(ModelCache.get("openai"))
        assertNull(ModelCache.get("gemini"))
    }

    @Test
    fun `getLastUpdateTime returns non-null for fresh cache`() {
        ModelCache.put("openai", listOf(AiModel("gpt-4o")))

        val timestamp = ModelCache.getLastUpdateTime("openai")
        assertNotNull(timestamp)
        assertTrue(timestamp!! > 0)
    }

    @Test
    fun `getLastUpdateTime returns null for unknown provider`() {
        assertNull(ModelCache.getLastUpdateTime("unknown"))
    }

    @Test
    fun `put overwrites previous cache`() {
        ModelCache.put("openai", listOf(AiModel("gpt-3.5-turbo")))
        ModelCache.put("openai", listOf(AiModel("gpt-4o"), AiModel("gpt-5.4-mini")))

        val cached = ModelCache.get("openai")
        assertEquals(2, cached!!.size)
        assertEquals("gpt-4o", cached[0].id)
    }

    @Test
    fun `empty model list is cached correctly`() {
        ModelCache.put("empty", emptyList())

        val cached = ModelCache.get("empty")
        assertNotNull(cached)
        assertTrue(cached!!.isEmpty())
    }
}
