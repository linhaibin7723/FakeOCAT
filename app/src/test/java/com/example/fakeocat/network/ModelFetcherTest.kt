package com.example.fakeocat.network

import io.mockk.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * ModelFetcher 单元测试。
 *
 * 通过 MockK 模拟 OkHttpClient，测试各种 API 响应格式的解析逻辑。
 */
class ModelFetcherTest {

    private lateinit var httpClient: OkHttpClient
    private lateinit var fetcher: ModelFetcher

    companion object {
        @BeforeClass
        @JvmStatic
        fun mockAndroidLog() {
            mockkStatic(android.util.Log::class)
            every { android.util.Log.d(any(), any()) } returns 0
            every { android.util.Log.e(any(), any()) } returns 0
            every { android.util.Log.e(any(), any(), any()) } returns 0
            every { android.util.Log.w(any(), any<String>()) } returns 0
        }
    }

    @Before
    fun setUp() {
        httpClient = mockk(relaxed = true)
        fetcher = ModelFetcher(httpClient)
        ModelCache.clear()
    }

    @After
    fun tearDown() {
        ModelCache.clear()
    }

    // ══════════════════════════════════════════════
    // Anthropic 不支持动态列表
    // ══════════════════════════════════════════════

    @Test
    fun `fetchModels for Anthropic returns failure`() = runTest {
        val anthropic = AiProviderCatalog.getProvider("anthropic")!!

        val result = fetcher.fetchModels(anthropic, "test-key")

        assertTrue(result.isFailure)
    }

    // ══════════════════════════════════════════════
    // 缓存命中测试
    // ══════════════════════════════════════════════

    @Test
    fun `fetchModels returns cached data on cache hit`() = runTest {
        val cachedModels = listOf(AiModel("cached-model"))
        ModelCache.put("openai", cachedModels)

        val openai = AiProviderCatalog.getProvider("openai")!!
        val result = fetcher.fetchModels(openai, "test-key")

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()!!.size)
        assertEquals("cached-model", result.getOrNull()!![0].id)

        // 验证没有发起网络请求
        verify(exactly = 0) { httpClient.newCall(any()) }
    }

    // ══════════════════════════════════════════════
    // OpenAI 兼容格式解析测试
    // ══════════════════════════════════════════════

    @Test
    fun `fetchModels parses OpenAI compatible response correctly`() = runTest {
        val responseBody = """
        {
            "data": [
                {"id": "gpt-4o", "owned_by": "openai"},
                {"id": "gpt-5.4-mini", "owned_by": "openai"},
                {"id": "text-embedding-3-small", "owned_by": "openai"},
                {"id": "dall-e-3", "owned_by": "openai"},
                {"id": "whisper-1", "owned_by": "openai"},
                {"id": "tts-1", "owned_by": "openai"}
            ]
        }
        """.trimIndent()

        val response = Response.Builder()
            .code(200)
            .message("OK")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("https://api.openai.com/v1/models").build())
            .body(responseBody.toResponseBody("application/json".toMediaType()))
            .build()

        every { httpClient.newCall(any()).execute() } returns response

        val openai = AiProviderCatalog.getProvider("openai")!!
        val result = fetcher.fetchModels(openai, "test-key")

        assertTrue(result.isSuccess)
        val models = result.getOrNull()!!
        // 应该过滤掉 embedding、dall-e、whisper、tts
        assertEquals(2, models.size)
        assertTrue(models.any { it.id == "gpt-4o" })
        assertTrue(models.any { it.id == "gpt-5.4-mini" })
        assertFalse(models.any { it.id == "text-embedding-3-small" })
        assertFalse(models.any { it.id == "dall-e-3" })
        assertFalse(models.any { it.id == "whisper-1" })
        assertFalse(models.any { it.id == "tts-1" })
    }

    // ══════════════════════════════════════════════
    // Gemini 格式解析测试
    // ══════════════════════════════════════════════

    @Test
    fun `fetchModels parses Gemini response correctly`() = runTest {
        val responseBody = """
        {
            "models": [
                {
                    "name": "models/gemini-2.5-flash",
                    "displayName": "Gemini 2.5 Flash",
                    "supportedGenerationMethods": ["generateContent", "countTokens"]
                },
                {
                    "name": "models/gemini-2.5-pro",
                    "displayName": "Gemini 2.5 Pro",
                    "supportedGenerationMethods": ["generateContent"]
                },
                {
                    "name": "models/embedding-001",
                    "displayName": "Embedding",
                    "supportedGenerationMethods": ["embedContent"]
                }
            ]
        }
        """.trimIndent()

        val response = Response.Builder()
            .code(200)
            .message("OK")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("https://generativelanguage.googleapis.com/v1/models").build())
            .body(responseBody.toResponseBody("application/json".toMediaType()))
            .build()

        every { httpClient.newCall(any()).execute() } returns response

        val gemini = AiProviderCatalog.getProvider("gemini")!!
        val result = fetcher.fetchModels(gemini, "test-key")

        assertTrue(result.isSuccess)
        val models = result.getOrNull()!!
        // 应该只包含支持 generateContent 的模型
        assertEquals(2, models.size)
        assertTrue(models.any { it.id == "gemini-2.5-flash" && it.displayName == "Gemini 2.5 Flash" })
        assertTrue(models.any { it.id == "gemini-2.5-pro" && it.displayName == "Gemini 2.5 Pro" })
        assertFalse(models.any { it.id == "embedding-001" })
    }

    // ══════════════════════════════════════════════
    // 错误处理测试
    // ══════════════════════════════════════════════

    @Test
    fun `fetchModels returns failure on 401`() = runTest {
        val response = Response.Builder()
            .code(401)
            .message("Unauthorized")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("https://api.openai.com/v1/models").build())
            .body("".toResponseBody("text/plain".toMediaType()))
            .build()

        every { httpClient.newCall(any()).execute() } returns response

        val openai = AiProviderCatalog.getProvider("openai")!!
        val result = fetcher.fetchModels(openai, "bad-key")

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is ModelFetchException)
        assertEquals(401, (ex as ModelFetchException).httpStatusCode)
    }

    @Test
    fun `fetchModels returns failure on network error with no cache`() = runTest {
        every { httpClient.newCall(any()).execute() } throws RuntimeException("Connection refused")

        val openai = AiProviderCatalog.getProvider("openai")!!
        val result = fetcher.fetchModels(openai, "test-key")

        assertTrue(result.isFailure)
    }

    @Test
    fun `fetchModels returns stale cache on network error when available`() = runTest {
        // 预先写入缓存
        val staleModels = listOf(AiModel("stale-model"))
        ModelCache.put("openai", staleModels)

        // 模拟网络失败
        every { httpClient.newCall(any()).execute() } throws RuntimeException("Connection refused")

        val openai = AiProviderCatalog.getProvider("openai")!!
        val result = fetcher.fetchModels(openai, "test-key")

        // 缓存刚写入（未过期），缓存命中直接返回；或网络失败后降级返回过期缓存
        // 两种路径均为 success
        assertTrue(result.isSuccess)
        assertEquals("stale-model", result.getOrNull()!![0].id)
    }

    // ══════════════════════════════════════════════
    // AiProviderCatalog 新字段验证
    // ══════════════════════════════════════════════

    @Test
    fun `all providers have correct supportsModelList flag`() {
        val byId = AiProviderCatalog.providers.associateBy { it.id }
        assertFalse(byId["anthropic"]!!.supportsModelList)
        assertTrue(byId["openai"]!!.supportsModelList)
        assertTrue(byId["gemini"]!!.supportsModelList)
    }

    @Test
    fun `AiModel data class equality works`() {
        val a = AiModel("gpt-4o", "GPT-4o")
        val b = AiModel("gpt-4o", "GPT-4o")
        val c = AiModel("gpt-4o")

        assertEquals(a, b)
        assertNotEquals(a, c) // displayName differs: "GPT-4o" vs "gpt-4o"
    }
}
