package com.example.fakeocat.network.auth

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 腾讯云 TC3-HMAC-SHA256 签名器。
 *
 * 签名流程：
 * 1. 拼接规范请求串
 * 2. 拼接待签名字符串
 * 3. 计算签名（HMAC-SHA256）
 * 4. 拼接 Authorization 头
 *
 * @see <a href="https://cloud.tencent.com/document/api/1729/101843">腾讯云 TC3 签名文档</a>
 */
object TencentCloudTC3Signer {

    private const val ALGORITHM = "TC3-HMAC-SHA256"
    private const val HOST = "hunyuan.tencentcloudapi.com"
    private const val VERSION = "2023-09-01"

    /**
     * 为腾讯云 API 请求签名。
     *
     * @param service 服务名（如 "hunyuan"）
     * @param action API 操作名（如 "ChatCompletions"）
     * @param payload JSON 请求体
     * @param secretId 腾讯云 SecretId
     * @param secretKey 腾讯云 SecretKey
     * @param region 地域（如 "ap-guangzhou"）
     * @return 包含 Authorization 头和其他必要头的 Map
     */
    fun sign(
        service: String,
        action: String,
        payload: String,
        secretId: String,
        secretKey: String,
        region: String = "ap-guangzhou"
    ): Map<String, String> {
        val timestamp = System.currentTimeMillis() / 1000
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(timestamp * 1000)

        // Step 1: 拼接规范请求串
        val contentType = "application/json; charset=utf-8"
        val canonicalUri = "/"
        val canonicalQueryString = ""
        val canonicalHeaders = "content-type:$contentType\nhost:$HOST\n"
        val signedHeaders = "content-type;host"
        val hashedPayload = sha256Hex(payload)
        val canonicalRequest = listOf(
            "POST",
            canonicalUri,
            canonicalQueryString,
            canonicalHeaders,
            signedHeaders,
            hashedPayload
        ).joinToString("\n")

        // Step 2: 拼接待签名字符串
        val credentialScope = "$date/$service/tc3_request"
        val hashedCanonicalRequest = sha256Hex(canonicalRequest)
        val stringToSign = listOf(
            ALGORITHM,
            timestamp.toString(),
            credentialScope,
            hashedCanonicalRequest
        ).joinToString("\n")

        // Step 3: 计算签名
        val secretDate = hmacSha256("TC3$secretKey".toByteArray(Charsets.UTF_8), date)
        val secretService = hmacSha256(secretDate, service)
        val secretSigning = hmacSha256(secretService, "tc3_request")
        val signature = hmacSha256Hex(secretSigning, stringToSign)

        // Step 4: 拼接 Authorization 头
        val authorization = "$ALGORITHM Credential=$secretId/$credentialScope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"

        return mapOf(
            "Authorization" to authorization,
            "Content-Type" to contentType,
            "Host" to HOST,
            "X-TC-Action" to action,
            "X-TC-Version" to VERSION,
            "X-TC-Timestamp" to timestamp.toString(),
            "X-TC-Region" to region
        )
    }

    internal fun sha256Hex(data: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray(Charsets.UTF_8))
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
}
