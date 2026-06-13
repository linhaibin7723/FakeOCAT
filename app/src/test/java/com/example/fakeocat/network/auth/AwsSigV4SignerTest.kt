package com.example.fakeocat.network.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AwsSigV4SignerTest {

    @Test
    fun `sign returns required headers`() {
        val result = AwsSigV4Signer.sign(
            method = "POST",
            url = "https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-3/invoke-with-response-stream",
            headers = mapOf("Content-Type" to "application/json"),
            body = """{"prompt":"test"}""".toByteArray(),
            region = "us-east-1",
            accessKeyId = "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        )

        assertNotNull("Authorization header should be present", result["Authorization"])
        assertNotNull("x-amz-date header should be present", result["x-amz-date"])
        assertNotNull("x-amz-content-sha256 header should be present", result["x-amz-content-sha256"])
        assertEquals("UNSIGNED-PAYLOAD", result["x-amz-content-sha256"])
    }

    @Test
    fun `sign Authorization header has correct format`() {
        val result = AwsSigV4Signer.sign(
            method = "POST",
            url = "https://bedrock-runtime.us-east-1.amazonaws.com/model/test/invoke-with-response-stream",
            headers = emptyMap(),
            body = ByteArray(0),
            region = "us-east-1",
            accessKeyId = "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        )

        val auth = result["Authorization"]!!
        assertTrue(
            "Authorization should start with AWS4-HMAC-SHA256",
            auth.startsWith("AWS4-HMAC-SHA256")
        )
        assertTrue(
            "Authorization should contain Credential",
            auth.contains("Credential=AKIAIOSFODNN7EXAMPLE/")
        )
        assertTrue(
            "Authorization should contain credential scope",
            auth.contains("/us-east-1/bedrock/aws4_request")
        )
        assertTrue("Authorization should contain SignedHeaders", auth.contains("SignedHeaders="))
        assertTrue("Authorization should contain Signature", auth.contains("Signature="))
    }

    @Test
    fun `sign includes session token when provided`() {
        val result = AwsSigV4Signer.sign(
            method = "POST",
            url = "https://bedrock-runtime.us-east-1.amazonaws.com/model/test/invoke-with-response-stream",
            headers = emptyMap(),
            body = ByteArray(0),
            region = "us-west-2",
            accessKeyId = "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            sessionToken = "FwoGZXIvYXdzEBYaDHqa0AP"
        )

        assertEquals("FwoGZXIvYXdzEBYaDHqa0AP", result["x-amz-security-token"])
        val auth = result["Authorization"]!!
        assertTrue(auth.contains("/us-west-2/bedrock/aws4_request"))
    }

    @Test
    fun `sign preserves original headers`() {
        val result = AwsSigV4Signer.sign(
            method = "POST",
            url = "https://bedrock-runtime.us-east-1.amazonaws.com/model/test/invoke-with-response-stream",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream"
            ),
            body = ByteArray(0),
            region = "us-east-1",
            accessKeyId = "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        )

        assertEquals("application/json", result["Content-Type"])
        assertEquals("text/event-stream", result["Accept"])
    }

    @Test
    fun `sha256Hex produces correct hash`() {
        val hash = AwsSigV4Signer.sha256Hex("")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hash
        )
    }

    @Test
    fun `sha256Hex produces correct hash for data`() {
        val hash = AwsSigV4Signer.sha256Hex("hello")
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            hash
        )
    }

    @Test
    fun `getSignatureKey derives correct key chain`() {
        val key = AwsSigV4Signer.getSignatureKey(
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            "20150830",
            "us-east-1",
            "iam"
        )
        assertNotNull(key)
        assertTrue("Signing key should not be empty", key.isNotEmpty())
        // The derived key should be 32 bytes (256 bits) for HMAC-SHA256
        assertEquals(32, key.size)
    }

    @Test
    fun `sign with different regions produces different credential scopes`() {
        val result1 = AwsSigV4Signer.sign(
            method = "POST",
            url = "https://bedrock-runtime.us-east-1.amazonaws.com/model/test/invoke-with-response-stream",
            headers = emptyMap(),
            body = ByteArray(0),
            region = "us-east-1",
            accessKeyId = "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        )

        val result2 = AwsSigV4Signer.sign(
            method = "POST",
            url = "https://bedrock-runtime.eu-west-1.amazonaws.com/model/test/invoke-with-response-stream",
            headers = emptyMap(),
            body = ByteArray(0),
            region = "eu-west-1",
            accessKeyId = "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        )

        val auth1 = result1["Authorization"]!!
        val auth2 = result2["Authorization"]!!
        assertTrue("Auth1 should contain us-east-1", auth1.contains("/us-east-1/bedrock/aws4_request"))
        assertTrue("Auth2 should contain eu-west-1", auth2.contains("/eu-west-1/bedrock/aws4_request"))
    }
}
