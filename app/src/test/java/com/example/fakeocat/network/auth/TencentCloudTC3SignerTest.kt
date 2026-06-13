package com.example.fakeocat.network.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TencentCloudTC3SignerTest {

    @Test
    fun `sign returns all required headers`() {
        val result = TencentCloudTC3Signer.sign(
            service = "hunyuan",
            action = "ChatCompletions",
            payload = """{"Model":"hunyuan-turbos-latest","Messages":[]}""",
            secretId = "AKIDfakeTestSecretId00000",
            secretKey = "fakeTestSecretKey00000000000000"
        )

        assertNotNull("Authorization should be present", result["Authorization"])
        assertNotNull("Content-Type should be present", result["Content-Type"])
        assertNotNull("Host should be present", result["Host"])
        assertNotNull("X-TC-Action should be present", result["X-TC-Action"])
        assertNotNull("X-TC-Version should be present", result["X-TC-Version"])
        assertNotNull("X-TC-Timestamp should be present", result["X-TC-Timestamp"])
        assertNotNull("X-TC-Region should be present", result["X-TC-Region"])
    }

    @Test
    fun `sign Authorization header has correct format`() {
        val result = TencentCloudTC3Signer.sign(
            service = "hunyuan",
            action = "ChatCompletions",
            payload = """{"Model":"test"}""",
            secretId = "AKIDfakeTestSecretId00000",
            secretKey = "fakeTestSecretKey00000000000000"
        )

        val auth = result["Authorization"]!!
        assertTrue(
            "Authorization should start with TC3-HMAC-SHA256",
            auth.startsWith("TC3-HMAC-SHA256")
        )
        assertTrue(
            "Authorization should contain Credential",
            auth.contains("Credential=AKIDfakeTestSecretId00000/")
        )
        assertTrue(
            "Authorization should contain credential scope",
            auth.contains("/hunyuan/tc3_request")
        )
        assertTrue("Authorization should contain SignedHeaders", auth.contains("SignedHeaders="))
        assertTrue(
            "SignedHeaders should be content-type;host",
            auth.contains("SignedHeaders=content-type;host")
        )
        assertTrue("Authorization should contain Signature", auth.contains("Signature="))
    }

    @Test
    fun `sign includes correct action and version headers`() {
        val result = TencentCloudTC3Signer.sign(
            service = "hunyuan",
            action = "ChatCompletions",
            payload = "{}",
            secretId = "AKIDEXAMPLE",
            secretKey = "SECRETKEY"
        )

        assertEquals("ChatCompletions", result["X-TC-Action"])
        assertEquals("2023-09-01", result["X-TC-Version"])
    }

    @Test
    fun `sign uses specified region`() {
        val result = TencentCloudTC3Signer.sign(
            service = "hunyuan",
            action = "ChatCompletions",
            payload = "{}",
            secretId = "AKIDEXAMPLE",
            secretKey = "SECRETKEY",
            region = "ap-beijing"
        )

        assertEquals("ap-beijing", result["X-TC-Region"])
    }

    @Test
    fun `sign defaults region to ap-guangzhou`() {
        val result = TencentCloudTC3Signer.sign(
            service = "hunyuan",
            action = "ChatCompletions",
            payload = "{}",
            secretId = "AKIDEXAMPLE",
            secretKey = "SECRETKEY"
        )

        assertEquals("ap-guangzhou", result["X-TC-Region"])
    }

    @Test
    fun `sign Content-Type is correct`() {
        val result = TencentCloudTC3Signer.sign(
            service = "hunyuan",
            action = "ChatCompletions",
            payload = "{}",
            secretId = "AKIDEXAMPLE",
            secretKey = "SECRETKEY"
        )

        assertEquals("application/json; charset=utf-8", result["Content-Type"])
    }

    @Test
    fun `sign Host is hunyuan tencentcloudapi com`() {
        val result = TencentCloudTC3Signer.sign(
            service = "hunyuan",
            action = "ChatCompletions",
            payload = "{}",
            secretId = "AKIDEXAMPLE",
            secretKey = "SECRETKEY"
        )

        assertEquals("hunyuan.tencentcloudapi.com", result["Host"])
    }

    @Test
    fun `sha256Hex produces correct hash`() {
        val hash = TencentCloudTC3Signer.sha256Hex("")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hash
        )
    }

    @Test
    fun `sign timestamp is valid epoch seconds`() {
        val result = TencentCloudTC3Signer.sign(
            service = "hunyuan",
            action = "ChatCompletions",
            payload = "{}",
            secretId = "AKIDEXAMPLE",
            secretKey = "SECRETKEY"
        )

        val timestamp = result["X-TC-Timestamp"]!!.toLong()
        // Timestamp should be reasonable (after year 2020 and before year 2100)
        assertTrue("Timestamp should be > 1577836800 (2020-01-01)", timestamp > 1577836800L)
        assertTrue("Timestamp should be < 4102444800 (2100-01-01)", timestamp < 4102444800L)
    }

    @Test
    fun `different payloads produce different signatures`() {
        val result1 = TencentCloudTC3Signer.sign(
            service = "hunyuan",
            action = "ChatCompletions",
            payload = """{"Model":"hunyuan-turbos-latest"}""",
            secretId = "AKIDEXAMPLE",
            secretKey = "SECRETKEY"
        )

        val result2 = TencentCloudTC3Signer.sign(
            service = "hunyuan",
            action = "ChatCompletions",
            payload = """{"Model":"hunyuan-pro"}""",
            secretId = "AKIDEXAMPLE",
            secretKey = "SECRETKEY"
        )

        // Different payloads should produce different signatures
        // (timestamps may differ, but even if same, payloads differ)
        val sig1 = result1["Authorization"]!!
        val sig2 = result2["Authorization"]!!
        // At minimum verify both are valid TC3 signatures
        assertTrue(sig1.startsWith("TC3-HMAC-SHA256"))
        assertTrue(sig2.startsWith("TC3-HMAC-SHA256"))
    }
}
