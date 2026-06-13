package com.example.fakeocat.network.auth

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 签名器。
 *
 * 实现完整的 SigV4 签名流程，用于 AWS Bedrock Anthropic API。
 * 1. 创建规范请求（Canonical Request）
 * 2. 创建待签名字符串（String to Sign）
 * 3. 计算签名密钥（Signing Key）
 * 4. 计算最终签名
 * 5. 添加 Authorization 头
 *
 * @see <a href="https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html">AWS SigV4 文档</a>
 */
object AwsSigV4Signer {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val SERVICE = "bedrock"

    /**
     * 为 AWS Bedrock 请求签名。
     *
     * @param method HTTP 方法（POST）
     * @param url 请求 URL
     * @param headers 原始请求头
     * @param body 请求体
     * @param region AWS 区域
     * @param accessKeyId AWS Access Key ID
     * @param secretAccessKey AWS Secret Access Key
     * @param sessionToken AWS Session Token（可选，用于临时凭证）
     * @return 签名后的请求头 Map
     */
    fun sign(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        region: String,
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String? = null
    ): Map<String, String> {
        val uri = URI(url)
        val host = uri.host + if (uri.port > 0) ":${uri.port}" else ""
        val canonicalUri = uri.rawPath.ifEmpty { "/" }
        val canonicalQueryString = uri.rawQuery ?: ""

        // 时间戳
        val now = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(System.currentTimeMillis())
        val dateStamp = now.substring(0, 8)

        // Step 1: 构建规范请求头
        val allHeaders = mutableMapOf<String, String>()
        allHeaders.putAll(headers.mapKeys { it.key.lowercase() })
        allHeaders["host"] = host
        allHeaders["x-amz-date"] = now
        allHeaders["x-amz-content-sha256"] = "UNSIGNED-PAYLOAD"

        if (sessionToken != null) {
            allHeaders["x-amz-security-token"] = sessionToken
        }

        val canonicalHeaders = allHeaders.entries
            .sortedBy { it.key }
            .joinToString("") { "${it.key}:${it.value.trim()}\n" }
        val signedHeaders = allHeaders.keys.sorted().joinToString(";")

        // Step 2: 构建规范请求
        val payloadHash = "UNSIGNED-PAYLOAD"
        val canonicalRequest = listOf(
            method,
            canonicalUri,
            canonicalQueryString,
            canonicalHeaders,
            signedHeaders,
            payloadHash
        ).joinToString("\n")

        // Step 3: 创建待签名字符串
        val credentialScope = "$dateStamp/$region/$SERVICE/aws4_request"
        val stringToSign = listOf(
            ALGORITHM,
            now,
            credentialScope,
            sha256Hex(canonicalRequest)
        ).joinToString("\n")

        // Step 4: 计算签名密钥和签名
        val signingKey = getSignatureKey(secretAccessKey, dateStamp, region, SERVICE)
        val signature = hmacSha256Hex(signingKey, stringToSign)

        // Step 5: 构建 Authorization 头
        val authorization = "$ALGORITHM Credential=$accessKeyId/$credentialScope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"

        // 构建结果
        val result = mutableMapOf<String, String>()
        result.putAll(headers)
        result["Authorization"] = authorization
        result["x-amz-date"] = now
        result["x-amz-content-sha256"] = "UNSIGNED-PAYLOAD"
        if (sessionToken != null) {
            result["x-amz-security-token"] = sessionToken
        }

        return result
    }

    internal fun sha256Hex(data: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    internal fun sha256Hex(data: ByteArray): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(data)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    internal fun hmacSha256Hex(key: ByteArray, data: String): String {
        return hmacSha256(key, data).joinToString("") { "%02x".format(it) }
    }

    internal fun getSignatureKey(
        secretKey: String, dateStamp: String, region: String, service: String
    ): ByteArray {
        val kSecret = "AWS4$secretKey".toByteArray(Charsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        return hmacSha256(kService, "aws4_request")
    }
}
