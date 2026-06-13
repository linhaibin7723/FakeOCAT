package com.example.fakeocat.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * RequestBuilder 单元测试。
 *
 * 验证各 ApiProtocol 的请求体构建正确性，包括 JSON 结构、字段名称和值。
 */
class RequestBuilderTest {

    private val sampleMessages = listOf(
        mapOf("role" to "system", "content" to "You are a helpful assistant."),
        mapOf("role" to "user", "content" to "Hello!")
    )

    // ══════════════════════════════════════════════
    // OpenAI Compatible
    // ══════════════════════════════════════════════

    @Test
    fun `buildRequestBody OpenAI compatible produces correct JSON`() {
        val body = RequestBuilder.buildRequestBody(
            protocol = ApiProtocol.OpenAICompatible,
            model = "gpt-4o",
            messages = sampleMessages,
            temperature = 0.7,
            maxTokens = 1024,
            stream = true
        )
        val json = JSONObject(body)
        assertEquals("gpt-4o", json.getString("model"))
        assertTrue(json.getBoolean("stream"))
        assertEquals(0.7, json.getDouble("temperature"), 0.001)
        assertEquals(1024, json.getInt("max_tokens"))
        val messages = json.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun `buildRequestBody OpenAI compatible with null optional params`() {
        val body = RequestBuilder.buildRequestBody(
            protocol = ApiProtocol.OpenAICompatible,
            model = "gpt-4o",
            messages = sampleMessages,
            temperature = null,
            maxTokens = null
        )
        val json = JSONObject(body)
        assertEquals("gpt-4o", json.getString("model"))
        assertFalse(json.has("temperature"))
        assertFalse(json.has("max_tokens"))
    }

    // ══════════════════════════════════════════════
    // Azure OpenAI
    // ══════════════════════════════════════════════

    @Test
    fun `buildRequestBody Azure OpenAI uses same format as OpenAI`() {
        val body = RequestBuilder.buildRequestBody(
            protocol = ApiProtocol.AzureOpenAI,
            model = "gpt-4o",
            messages = sampleMessages,
            temperature = 0.5,
            maxTokens = 2048
        )
        val json = JSONObject(body)
        assertEquals("gpt-4o", json.getString("model"))
        assertTrue(json.getBoolean("stream"))
        assertEquals(0.5, json.getDouble("temperature"), 0.001)
    }

    // ══════════════════════════════════════════════
    // Anthropic
    // ══════════════════════════════════════════════

    @Test
    fun `buildRequestBody Anthropic extracts system prompt`() {
        val body = RequestBuilder.buildRequestBody(
            protocol = ApiProtocol.AnthropicMessages,
            model = "claude-sonnet-4-5",
            messages = sampleMessages,
            temperature = 1.0,
            maxTokens = 4096
        )
        val json = JSONObject(body)
        assertEquals("claude-sonnet-4-5", json.getString("model"))
        assertEquals(4096, json.getInt("max_tokens"))
        assertTrue(json.getBoolean("stream"))
        assertEquals("You are a helpful assistant.", json.getString("system"))
        // messages 应该只有 user 消息
        val messages = json.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
    }

    @Test
    fun `buildRequestBody Anthropic uses default max_tokens when null`() {
        val body = RequestBuilder.buildRequestBody(
            protocol = ApiProtocol.AnthropicMessages,
            model = "claude-sonnet-4-5",
            messages = sampleMessages,
            temperature = null,
            maxTokens = null
        )
        val json = JSONObject(body)
        assertEquals(4096, json.getInt("max_tokens"))
        assertFalse(json.has("temperature"))
    }

    @Test
    fun `buildRequestBody Bedrock Anthropic uses Anthropic format`() {
        val body = RequestBuilder.buildRequestBody(
            protocol = ApiProtocol.BedrockAnthropic,
            model = "anthropic.claude-3-sonnet-20240229-v1:0",
            messages = sampleMessages,
            temperature = 0.5,
            maxTokens = 2048
        )
        val json = JSONObject(body)
        assertEquals("anthropic.claude-3-sonnet-20240229-v1:0", json.getString("model"))
        assertTrue(json.has("system"))
    }

    @Test
    fun `buildRequestBody Vertex Anthropic uses Anthropic format`() {
        val body = RequestBuilder.buildRequestBody(
            protocol = ApiProtocol.VertexAnthropic,
            model = "claude-sonnet-4-5",
            messages = sampleMessages,
            temperature = null,
            maxTokens = 2048
        )
        val json = JSONObject(body)
        assertTrue(json.has("system"))
        assertTrue(json.has("messages"))
    }

    // ══════════════════════════════════════════════
    // Gemini
    // ══════════════════════════════════════════════

    @Test
    fun `buildRequestBody Gemini uses systemInstruction and contents`() {
        val body = RequestBuilder.buildRequestBody(
            protocol = ApiProtocol.GeminiNative,
            model = "gemini-2.5-flash",
            messages = sampleMessages,
            temperature = 0.8,
            maxTokens = 2048
        )
        val json = JSONObject(body)
        // systemInstruction（camelCase，匹配 Gemini REST API）
        val systemInst = json.getJSONObject("systemInstruction")
        val parts = systemInst.getJSONArray("parts")
        assertEquals("You are a helpful assistant.", parts.getJSONObject(0).getString("text"))
        // contents
        val contents = json.getJSONArray("contents")
        assertEquals(1, contents.length()) // 只有 user 消息
        val userContent = contents.getJSONObject(0)
        assertEquals("user", userContent.getString("role"))
        assertEquals("Hello!", userContent.getJSONArray("parts").getJSONObject(0).getString("text"))
        // generationConfig
        val genConfig = json.getJSONObject("generationConfig")
        assertEquals(0.8, genConfig.getDouble("temperature"), 0.001)
        assertEquals(2048, genConfig.getInt("maxOutputTokens"))
    }

    @Test
    fun `buildRequestBody Gemini without generationConfig when params null`() {
        val body = RequestBuilder.buildRequestBody(
            protocol = ApiProtocol.GeminiNative,
            model = "gemini-2.5-flash",
            messages = listOf(mapOf("role" to "user", "content" to "Hi")),
            temperature = null,
            maxTokens = null
        )
        val json = JSONObject(body)
        assertFalse(json.has("generationConfig"))
    }

    @Test
    fun `buildRequestBody Gemini maps assistant role to model`() {
        val messages = listOf(
            mapOf("role" to "user", "content" to "Hi"),
            mapOf("role" to "assistant", "content" to "Hello!"),
            mapOf("role" to "user", "content" to "How are you?")
        )
        val body = RequestBuilder.buildRequestBody(
            protocol = ApiProtocol.GeminiNative,
            model = "gemini-2.5-flash",
            messages = messages,
            temperature = null,
            maxTokens = null
        )
        val json = JSONObject(body)
        val contents = json.getJSONArray("contents")
        assertEquals(3, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertEquals("user", contents.getJSONObject(2).getString("role"))
    }

    // ══════════════════════════════════════════════
    // DashScope Native
    // ══════════════════════════════════════════════

    @Test
    fun `buildRequestBody DashScope Native has input and parameters`() {
        val body = RequestBuilder.buildRequestBody(
            protocol = ApiProtocol.DashScopeNative,
            model = "qwen-max",
            messages = sampleMessages,
            temperature = 0.7,
            maxTokens = 2048
        )
        val json = JSONObject(body)
        assertEquals("qwen-max", json.getString("model"))
        // input.messages
        val input = json.getJSONObject("input")
        val messages = input.getJSONArray("messages")
        assertEquals(2, messages.length())
        // parameters
        val params = json.getJSONObject("parameters")
        assertTrue(params.getBoolean("stream"))
        assertTrue(params.getBoolean("incremental_output"))
        assertEquals(0.7, params.getDouble("temperature"), 0.001)
        assertEquals(2048, params.getInt("max_tokens"))
    }

    @Test
    fun `buildRequestBody DashScope Native non-stream has no incremental_output`() {
        val body = RequestBuilder.buildRequestBody(
            protocol = ApiProtocol.DashScopeNative,
            model = "qwen-max",
            messages = sampleMessages,
            temperature = null,
            maxTokens = null,
            stream = false
        )
        val json = JSONObject(body)
        val params = json.getJSONObject("parameters")
        assertFalse(params.getBoolean("stream"))
        assertFalse(params.has("incremental_output"))
    }

    // ══════════════════════════════════════════════
    // Qianfan V1 RPC
    // ══════════════════════════════════════════════

    @Test
    fun `buildRequestBody Qianfan V1 has no model field`() {
        val body = RequestBuilder.buildRequestBody(
            protocol = ApiProtocol.QianfanV1RPC,
            model = "ernie-speed-128k",
            messages = sampleMessages,
            temperature = 0.9,
            maxTokens = null
        )
        val json = JSONObject(body)
        assertFalse(json.has("model"))
        assertTrue(json.getBoolean("stream"))
        assertEquals(0.9, json.getDouble("temperature"), 0.001)
        assertEquals("You are a helpful assistant.", json.getString("system"))
        val messages = json.getJSONArray("messages")
        assertEquals(1, messages.length()) // 只有 user 消息
    }

    // ══════════════════════════════════════════════
    // Tencent Cloud TC3
    // ══════════════════════════════════════════════

    @Test
    fun `buildRequestBody Tencent Cloud TC3 uses uppercase keys`() {
        val body = RequestBuilder.buildRequestBody(
            protocol = ApiProtocol.TencentCloudTC3,
            model = "hunyuan-lite",
            messages = sampleMessages,
            temperature = 0.6,
            maxTokens = 1024
        )
        val json = JSONObject(body)
        assertEquals("hunyuan-lite", json.getString("Model"))
        assertTrue(json.getBoolean("Stream"))
        assertEquals(0.6, json.getDouble("Temperature"), 0.001)
        assertEquals(1024, json.getInt("MaxTokens"))
        val messages = json.getJSONArray("Messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("Role"))
        assertEquals("user", messages.getJSONObject(1).getString("Role"))
    }

    // ══════════════════════════════════════════════
    // buildRequest (full request info)
    // ══════════════════════════════════════════════

    @Test
    fun `buildRequest OpenAI compatible produces correct triple`() {
        val profile = AiProviderCatalog.getProvider("openai")!!.defaultProfile
        val (url, headers, body) = RequestBuilder.buildRequest(
            profile = profile,
            model = "gpt-4o",
            messages = sampleMessages,
            apiKey = "test-api-key",
            temperature = 0.7,
            maxTokens = 1024
        )
        assertEquals("https://api.openai.com/v1/chat/completions", url)
        assertEquals("Bearer test-api-key", headers["Authorization"])
        val json = JSONObject(body)
        assertEquals("gpt-4o", json.getString("model"))
    }

    @Test
    fun `buildRequest Anthropic includes anthropic-version header`() {
        val profile = AiProviderCatalog.getProvider("anthropic")!!.defaultProfile
        val (url, headers, body) = RequestBuilder.buildRequest(
            profile = profile,
            model = "claude-sonnet-4-5",
            messages = sampleMessages,
            apiKey = "test-key",
            temperature = null,
            maxTokens = 2048
        )
        assertEquals("https://api.anthropic.com/v1/messages", url)
        assertEquals("test-key", headers["x-api-key"])
        assertEquals("2023-06-01", headers["anthropic-version"])
    }

    @Test
    fun `buildRequest Azure uses api-key header`() {
        val openai = AiProviderCatalog.getProvider("openai")!!
        val azureProfile = openai.getProfile("azure")!!
        val config = mapOf("resource" to "myresource", "deployment" to "gpt-4o")
        val (url, headers, body) = RequestBuilder.buildRequest(
            profile = azureProfile,
            model = "gpt-4o",
            messages = sampleMessages,
            apiKey = "azure-key",
            temperature = 0.5,
            maxTokens = 2048,
            extraConfig = config
        )
        assertTrue(url.contains("myresource"))
        assertTrue(url.contains("gpt-4o"))
        assertEquals("azure-key", headers["api-key"])
    }

    @Test
    fun `buildRequest DashScope Native resolves URL correctly`() {
        val qwen = AiProviderCatalog.getProvider("qwen")!!
        val nativeProfile = qwen.getProfile("dashscope_native")!!
        val (url, _, body) = RequestBuilder.buildRequest(
            profile = nativeProfile,
            model = "qwen-max",
            messages = sampleMessages,
            apiKey = "test-key",
            temperature = 0.7,
            maxTokens = 1024
        )
        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation", url)
        val json = JSONObject(body)
        assertEquals("qwen-max", json.getString("model"))
    }

    @Test
    fun `buildRequest Qianfan V1 resolves model in URL`() {
        val ernie = AiProviderCatalog.getProvider("ernie")!!
        val v1Profile = ernie.getProfile("qianfan_v1")!!
        val (url, _, body) = RequestBuilder.buildRequest(
            profile = v1Profile,
            model = "ernie-speed-128k",
            messages = sampleMessages,
            apiKey = "test-token",
            temperature = 0.7,
            maxTokens = null
        )
        assertTrue(url.contains("ernie-speed-128k"))
        assertFalse(url.contains("{model}"))
    }

    @Test
    fun `buildRequest Gemini AI Studio appends key query parameter to URL`() {
        val gemini = AiProviderCatalog.getProvider("gemini")!!
        val aiStudioProfile = gemini.getProfile("ai_studio")!!
        val (url, headers, body) = RequestBuilder.buildRequest(
            profile = aiStudioProfile,
            model = "gemini-2.5-flash",
            messages = sampleMessages,
            apiKey = "test-gemini-key",
            temperature = 0.8,
            maxTokens = 2048
        )
        // URL 应包含 &key= 查询参数
        assertTrue(
            "URL 应包含 API key 作为查询参数",
            url.contains("&key=test-gemini-key")
        )
        // URL 基础部分应正确解析 {model}
        assertTrue(
            "URL 应包含正确的模型名",
            url.contains("models/gemini-2.5-flash:streamGenerateContent")
        )
        // GeminiApiKeyQuery 不应添加任何认证 header
        assertFalse("不应有 Authorization header", headers.containsKey("Authorization"))
        // 请求体应为正确的 Gemini 格式
        val json = JSONObject(body)
        assertTrue("应包含 contents", json.has("contents"))
        assertTrue("应包含 systemInstruction", json.has("systemInstruction"))
    }

    @Test
    fun `buildRequest Gemini Vertex AI uses OAuth2 bearer header`() {
        val gemini = AiProviderCatalog.getProvider("gemini")!!
        val vertexProfile = gemini.getProfile("vertex_ai")!!
        val config = mapOf("region" to "us-central1", "project" to "my-project")
        val (url, headers, _) = RequestBuilder.buildRequest(
            profile = vertexProfile,
            model = "gemini-2.5-flash",
            messages = sampleMessages,
            apiKey = "oauth2-access-token",
            temperature = null,
            maxTokens = null,
            extraConfig = config
        )
        // URL 应正确解析所有占位符
        assertTrue("URL 应包含 region", url.contains("us-central1"))
        assertTrue("URL 应包含 project", url.contains("my-project"))
        assertFalse("URL 不应包含未解析的占位符", url.contains("{"))
        // Vertex AI 使用 GcpOAuth2Bearer，应有 Bearer token header
        assertEquals("Bearer oauth2-access-token", headers["Authorization"])
        // URL 不应包含 key 查询参数（Vertex 使用 OAuth2）
        assertFalse("Vertex URL 不应包含 key 参数", url.contains("key="))
    }

    // ══════════════════════════════════════════════
    // messagesToJsonArray
    // ══════════════════════════════════════════════

    @Test
    fun `messagesToJsonArray preserves all keys`() {
        val messages = listOf(
            mapOf("role" to "user", "content" to "Hello", "name" to "test-user")
        )
        val array = RequestBuilder.messagesToJsonArray(messages)
        assertEquals(1, array.length())
        val obj = array.getJSONObject(0)
        assertEquals("user", obj.getString("role"))
        assertEquals("Hello", obj.getString("content"))
        assertEquals("test-user", obj.getString("name"))
    }

    @Test
    fun `messagesToJsonArray handles empty list`() {
        val array = RequestBuilder.messagesToJsonArray(emptyList())
        assertEquals(0, array.length())
    }
}
