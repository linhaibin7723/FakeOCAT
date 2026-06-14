package com.example.fakeocat.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderCatalogTest {

    @Test
    fun providers_match_required_order() {
        val expected = listOf(
            "openai", "anthropic", "gemini", "grok",
            "mimo", "deepseek", "qwen", "hunyuan",
            "ernie", "zhipu", "kimi", "minimax"
        )

        val actual = AiProviderCatalog.providers.map { it.id }
        assertEquals(expected, actual)
    }

    @Test
    fun default_profiles_have_urls() {
        val byId = AiProviderCatalog.providers.associateBy { it.id }

        // All providers now have non-null chatCompletionsUrl via their default profile
        for ((id, provider) in byId) {
            assertNotNull("$id should have chatCompletionsUrl via default profile", provider.chatCompletionsUrl)
        }
    }

    @Test
    fun anthropic_does_not_support_model_list() {
        val anthropic = AiProviderCatalog.getProvider("anthropic")
        assertNotNull(anthropic)
        assertEquals(false, anthropic!!.supportsModelList)
        assertNull(anthropic.modelsEndpoint)
    }

    @Test
    fun openai_compatible_providers_have_models_endpoints() {
        val openaiCompat = listOf(
            "openai", "grok", "mimo", "deepseek", "qwen",
            "hunyuan", "ernie", "zhipu", "kimi", "minimax"
        )
        val byId = AiProviderCatalog.providers.associateBy { it.id }
        for (id in openaiCompat) {
            assertNotNull("$id should have modelsEndpoint", byId[id]?.modelsEndpoint)
            assertEquals("$id should supportModelList", true, byId[id]?.supportsModelList)
        }
    }

    @Test
    fun gemini_has_models_endpoint() {
        val gemini = AiProviderCatalog.getProvider("gemini")
        assertNotNull(gemini)
        assertNotNull(gemini!!.modelsEndpoint)
        assertEquals(true, gemini.supportsModelList)
    }

    @Test
    fun ids_are_unique() {
        val ids = AiProviderCatalog.providers.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.size == 12)
    }

    // ── 新增：EndpointProfile 机制测试 ──────────────────────────

    @Test
    fun multi_endpoint_providers_have_multiple_profiles() {
        val multiEndpointIds = listOf("openai", "anthropic", "gemini", "mimo", "qwen", "ernie", "hunyuan", "kimi")
        for (id in multiEndpointIds) {
            val provider = AiProviderCatalog.getProvider(id)
            assertNotNull("Provider $id should exist", provider)
            assertTrue("Provider $id should have multiple profiles", provider!!.hasMultipleProfiles)
            assertTrue("Provider $id should have at least 2 profiles", provider.profiles.size >= 2)
        }
    }

    @Test
    fun single_endpoint_providers_have_one_profile() {
        val singleEndpointIds = listOf("grok", "deepseek", "zhipu", "minimax")
        for (id in singleEndpointIds) {
            val provider = AiProviderCatalog.getProvider(id)
            assertNotNull("Provider $id should exist", provider)
            assertFalse("Provider $id should not have multiple profiles", provider!!.hasMultipleProfiles)
            assertEquals("Provider $id should have exactly 1 profile", 1, provider.profiles.size)
        }
    }

    @Test
    fun getEndpoint_returns_correct_profile() {
        // Test getting a specific profile
        val mimoPayg = AiProviderCatalog.getEndpoint("mimo", "pay_as_you_go")
        assertNotNull(mimoPayg)
        assertEquals("pay_as_you_go", mimoPayg!!.id)

        val mimoToken = AiProviderCatalog.getEndpoint("mimo", "token_plan")
        assertNotNull(mimoToken)
        assertEquals("token_plan", mimoToken!!.id)

        // Test default profile (null profileId)
        val mimoDefault = AiProviderCatalog.getEndpoint("mimo", null)
        assertNotNull(mimoDefault)
        assertEquals("pay_as_you_go", mimoDefault!!.id)
    }

    @Test
    fun getEndpoint_returns_null_for_nonexistent() {
        assertNull(AiProviderCatalog.getEndpoint("nonexistent", null))
        assertNull(AiProviderCatalog.getEndpoint("mimo", "nonexistent"))
    }

    @Test
    fun getDefaultProfile_returns_first_profile() {
        val anthropic = AiProviderCatalog.getDefaultProfile("anthropic")
        assertNotNull(anthropic)
        assertEquals("direct", anthropic!!.id)

        val gemini = AiProviderCatalog.getDefaultProfile("gemini")
        assertNotNull(gemini)
        assertEquals("ai_studio", gemini!!.id)
    }

    @Test
    fun getProfile_by_provider_and_profile_id() {
        val profile = AiProviderCatalog.getProfile("openai", "azure")
        assertNotNull(profile)
        assertEquals("azure", profile!!.id)
        assertEquals(AiAuthScheme.AzureApiKey, profile.authScheme)

        assertNull(AiProviderCatalog.getProfile("openai", "nonexistent"))
        assertNull(AiProviderCatalog.getProfile("nonexistent", "direct"))
    }

    @Test
    fun backward_compat_auth_scheme_delegates_to_default_profile() {
        val anthropic = AiProviderCatalog.getProvider("anthropic")!!
        assertEquals(AiAuthScheme.AnthropicApiKeyHeader, anthropic.authScheme)

        val gemini = AiProviderCatalog.getProvider("gemini")!!
        assertEquals(AiAuthScheme.GeminiApiKeyQuery, gemini.authScheme)

        val openai = AiProviderCatalog.getProvider("openai")!!
        assertEquals(AiAuthScheme.BearerToken, openai.authScheme)
    }

    @Test
    fun all_profiles_have_unique_ids_within_provider() {
        for (provider in AiProviderCatalog.providers) {
            val profileIds = provider.profiles.map { it.id }
            assertEquals(
                "Profile IDs should be unique within provider ${provider.id}",
                profileIds.size, profileIds.toSet().size
            )
        }
    }

    @Test
    fun endpoint_profile_resolve_url_works() {
        val azure = AiProviderCatalog.getEndpoint("openai", "azure")!!
        val resolved = azure.resolveUrl(
            configValues = mapOf("resource" to "my-resource", "deployment" to "gpt-4o"),
            model = "gpt-4o"
        )
        assertEquals(
            "https://my-resource.openai.azure.com/openai/deployments/gpt-4o/chat/completions?api-version=2024-10-21",
            resolved
        )
    }

    @Test
    fun endpoint_profile_resolve_url_with_model_placeholder() {
        val anthropicDirect = AiProviderCatalog.getEndpoint("anthropic", "direct")!!
        // direct profile has no placeholders, so resolveUrl should return original URL
        val resolved = anthropicDirect.resolveUrl(emptyMap(), "claude-haiku-4-5")
        assertEquals("https://api.anthropic.com/v1/messages", resolved)

        // aws_bedrock has {region} and {model} placeholders
        val bedrock = AiProviderCatalog.getEndpoint("anthropic", "aws_bedrock")!!
        val bedrockResolved = bedrock.resolveUrl(
            configValues = mapOf("region" to "us-east-1"),
            model = "claude-haiku-4-5"
        )
        assertEquals(
            "https://bedrock-runtime.us-east-1.amazonaws.com/model/claude-haiku-4-5/invoke-with-response-stream",
            bedrockResolved
        )
    }
}
