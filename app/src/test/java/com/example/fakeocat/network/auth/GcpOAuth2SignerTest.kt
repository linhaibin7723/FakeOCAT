package com.example.fakeocat.network.auth

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

class GcpOAuth2SignerTest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        GcpOAuth2Signer.clearCache()
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `createJwt produces valid three-part JWT`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(keyPair.private.encoded) +
            "\n-----END PRIVATE KEY-----"

        val jwt = GcpOAuth2Signer.createJwt(
            clientEmail = "test@test.iam.gserviceaccount.com",
            privateKeyPem = privateKeyPem,
            tokenUri = "https://oauth2.googleapis.com/token",
            issuedAt = 1609459200L,
            expirySeconds = 3600L
        )

        val parts = jwt.split(".")
        assertEquals("JWT should have 3 parts", 3, parts.size)
    }

    @Test
    fun `createJwt header contains correct algorithm and type`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(keyPair.private.encoded) +
            "\n-----END PRIVATE KEY-----"

        val jwt = GcpOAuth2Signer.createJwt(
            clientEmail = "test@test.iam.gserviceaccount.com",
            privateKeyPem = privateKeyPem,
            tokenUri = "https://oauth2.googleapis.com/token",
            issuedAt = 1609459200L,
            expirySeconds = 3600L
        )

        val headerJson = decodeBase64Url(jwt.split(".")[0])
        val header = JSONObject(headerJson)
        assertEquals("RS256", header.getString("alg"))
        assertEquals("JWT", header.getString("typ"))
    }

    @Test
    fun `createJwt payload contains correct claims`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(keyPair.private.encoded) +
            "\n-----END PRIVATE KEY-----"

        val jwt = GcpOAuth2Signer.createJwt(
            clientEmail = "test@test.iam.gserviceaccount.com",
            privateKeyPem = privateKeyPem,
            tokenUri = "https://oauth2.googleapis.com/token",
            issuedAt = 1609459200L,
            expirySeconds = 3600L
        )

        val payloadJson = decodeBase64Url(jwt.split(".")[1])
        val payload = JSONObject(payloadJson)
        assertEquals("test@test.iam.gserviceaccount.com", payload.getString("iss"))
        assertEquals(
            "https://www.googleapis.com/auth/cloud-platform",
            payload.getString("scope")
        )
        assertEquals("https://oauth2.googleapis.com/token", payload.getString("aud"))
        assertEquals(1609459200L, payload.getLong("iat"))
        assertEquals(1609462800L, payload.getLong("exp"))
    }

    @Test
    fun `base64UrlEncode produces URL-safe encoding without padding`() {
        val data = "Hello, World!".toByteArray()
        val encoded = GcpOAuth2Signer.base64UrlEncode(data)

        // Should not contain standard Base64 chars that are different in URL-safe
        assertTrue("Should not contain +", !encoded.contains("+"))
        assertTrue("Should not contain /", !encoded.contains("/"))
        assertTrue("Should not contain =", !encoded.contains("="))

        // Verify round-trip
        val decoded = Base64.getUrlDecoder().decode(encoded)
        assertEquals("Hello, World!", String(decoded))
    }

    @Test
    fun `getAccessToken fetches token from mock server`() = runTest {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(keyPair.private.encoded) +
            "\n-----END PRIVATE KEY-----"

        val serviceAccountJson = JSONObject().apply {
            put("client_email", "test@test.iam.gserviceaccount.com")
            put("private_key", privateKeyPem)
            put("token_uri", mockWebServer.url("/token").toString())
        }.toString()

        mockWebServer.enqueue(
            MockResponse()
                .setBody("""{"access_token":"ya29.test-token-123","token_type":"Bearer","expires_in":3600}""")
                .setHeader("Content-Type", "application/json")
        )

        val client = OkHttpClient()
        val token = GcpOAuth2Signer.getAccessToken(serviceAccountJson, client)

        assertEquals("ya29.test-token-123", token)

        // Verify the request was made
        val request = mockWebServer.takeRequest()
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue("Body should contain grant_type parameter", body.contains("grant_type"))
        assertTrue("Body should contain assertion parameter", body.contains("assertion"))
    }

    @Test
    fun `getAccessToken caches token on second call`() = runTest {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(keyPair.private.encoded) +
            "\n-----END PRIVATE KEY-----"

        val serviceAccountJson = JSONObject().apply {
            put("client_email", "test@test.iam.gserviceaccount.com")
            put("private_key", privateKeyPem)
            put("token_uri", mockWebServer.url("/token").toString())
        }.toString()

        mockWebServer.enqueue(
            MockResponse()
                .setBody("""{"access_token":"ya29.cached-token","token_type":"Bearer","expires_in":3600}""")
                .setHeader("Content-Type", "application/json")
        )

        val client = OkHttpClient()
        val token1 = GcpOAuth2Signer.getAccessToken(serviceAccountJson, client)
        val token2 = GcpOAuth2Signer.getAccessToken(serviceAccountJson, client)

        assertEquals("ya29.cached-token", token1)
        assertEquals("ya29.cached-token", token2)
        // Only one request should have been made due to caching
        assertEquals(1, mockWebServer.requestCount)
    }

    private fun decodeBase64Url(encoded: String): String {
        val decoded = Base64.getUrlDecoder().decode(encoded)
        return String(decoded, Charsets.UTF_8)
    }
}
