package com.example.fakeocat.network.auth

import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

/**
 * GCP Service Account OAuth 2.0 Bearer Token 获取器。
 *
 * 实现流程：
 * 1. 解析 Service Account JSON（提取 client_email、private_key、token_uri）
 * 2. 创建 JWT（RS256 签名）
 * 3. 向 token_uri 发起 POST 请求换取 access_token
 * 4. 缓存 token 直到过期前5分钟
 */
object GcpOAuth2Signer {

    private const val TOKEN_EXPIRY_BUFFER_MS = 5 * 60 * 1000L // 5 分钟
    private const val JWT_EXPIRY_SECONDS = 3600L // 1 小时

    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var tokenExpiryTimeMs: Long = 0L

    private val mutex = Mutex()

    /**
     * 从 Service Account JSON 获取 access token。
     *
     * @param serviceAccountJson GCP Service Account JSON 内容
     * @param httpClient OkHttpClient 用于发起 HTTP 请求
     * @return access token 字符串
     */
    suspend fun getAccessToken(
        serviceAccountJson: String,
        httpClient: OkHttpClient
    ): String = mutex.withLock {
        // 检查缓存
        val now = System.currentTimeMillis()
        if (cachedAccessToken != null && now < tokenExpiryTimeMs) {
            return cachedAccessToken!!
        }

        // 解析 Service Account JSON
        val saJson = JSONObject(serviceAccountJson)
        val clientEmail = saJson.getString("client_email")
        val privateKeyPem = saJson.getString("private_key")
        val tokenUri = saJson.optString("token_uri", "https://oauth2.googleapis.com/token")

        // 创建并签名 JWT
        val nowSeconds = now / 1000
        val jwt = createJwt(
            clientEmail = clientEmail,
            privateKeyPem = privateKeyPem,
            tokenUri = tokenUri,
            issuedAt = nowSeconds,
            expirySeconds = JWT_EXPIRY_SECONDS
        )

        // 用 JWT 换取 access token
        val accessToken = exchangeJwtForToken(jwt, tokenUri, httpClient)

        // 缓存 token（提前5分钟过期）
        cachedAccessToken = accessToken
        tokenExpiryTimeMs = now + (JWT_EXPIRY_SECONDS * 1000) - TOKEN_EXPIRY_BUFFER_MS

        return accessToken
    }

    /**
     * 清除缓存的 token，用于测试或凭证刷新。
     */
    fun clearCache() {
        cachedAccessToken = null
        tokenExpiryTimeMs = 0L
    }

    /**
     * 创建并签名 JWT。
     */
    internal fun createJwt(
        clientEmail: String,
        privateKeyPem: String,
        tokenUri: String,
        issuedAt: Long,
        expirySeconds: Long
    ): String {
        // JWT Header
        val header = JSONObject().apply {
            put("alg", "RS256")
            put("typ", "JWT")
        }

        // JWT Payload
        val payload = JSONObject().apply {
            put("iss", clientEmail)
            put("scope", "https://www.googleapis.com/auth/cloud-platform")
            put("aud", tokenUri)
            put("iat", issuedAt)
            put("exp", issuedAt + expirySeconds)
        }

        val encodedHeader = base64UrlEncode(header.toString().toByteArray(Charsets.UTF_8))
        val encodedPayload = base64UrlEncode(payload.toString().toByteArray(Charsets.UTF_8))
        val signInput = "$encodedHeader.$encodedPayload"

        // 使用 RS256 私钥签名
        val privateKey = parsePrivateKey(privateKeyPem)
        val signature = signRS256(privateKey, signInput)
        val encodedSignature = base64UrlEncode(signature)

        return "$signInput.$encodedSignature"
    }

    /**
     * 用 JWT 向 token_uri 换取 access_token。
     */
    private suspend fun exchangeJwtForToken(
        jwt: String,
        tokenUri: String,
        httpClient: OkHttpClient
    ): String = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", jwt)
            .build()

        val request = Request.Builder()
            .url(tokenUri)
            .post(formBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("GCP token exchange failed: HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw RuntimeException("GCP token exchange returned empty body")
            val json = JSONObject(body)
            json.getString("access_token")
        }
    }

    /**
     * 解析 PEM 格式的私钥。
     */
    private fun parsePrivateKey(privateKeyPem: String): PrivateKey {
        val cleanPem = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s+".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(cleanPem)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    /**
     * 使用 RS256 签名。
     */
    private fun signRS256(privateKey: PrivateKey, data: String): ByteArray {
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(data.toByteArray(Charsets.UTF_8))
        return signature.sign()
    }

    /**
     * Base64 URL 编码（无填充）。
     */
    internal fun base64UrlEncode(data: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }
}
