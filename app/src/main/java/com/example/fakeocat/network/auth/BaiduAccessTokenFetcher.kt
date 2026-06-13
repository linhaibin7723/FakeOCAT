package com.example.fakeocat.network.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 百度 OAuth 2.0 access_token 获取器。
 *
 * 用于百度千帆 v1 API 的认证。
 * 通过 API Key 和 Secret Key 获取 access_token，并缓存直到过期。
 */
object BaiduAccessTokenFetcher {

    private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
    private const val TOKEN_EXPIRY_BUFFER_MS = 5 * 60 * 1000L // 5 分钟提前量

    @Volatile
    private var cachedAccessToken: String? = null

    @Volatile
    private var tokenExpiryTimeMs: Long = 0L

    @Volatile
    private var cachedKey: String? = null

    private val mutex = Mutex()

    /**
     * 获取百度 access_token。
     *
     * @param apiKey 百度 API Key
     * @param secretKey 百度 Secret Key
     * @param httpClient OkHttpClient
     * @return access_token 字符串
     */
    suspend fun getAccessToken(
        apiKey: String,
        secretKey: String,
        httpClient: OkHttpClient
    ): String = mutex.withLock {
        val keyPair = "$apiKey:$secretKey"

        // 检查缓存（仅当相同的 key pair 时才使用缓存）
        val now = System.currentTimeMillis()
        if (cachedAccessToken != null && now < tokenExpiryTimeMs && cachedKey == keyPair) {
            return cachedAccessToken!!
        }

        // 请求 access_token
        val tokenResponse = fetchToken(apiKey, secretKey, httpClient)
        val accessToken = tokenResponse.first
        val expiresIn = tokenResponse.second

        // 缓存 token
        cachedAccessToken = accessToken
        cachedKey = keyPair
        tokenExpiryTimeMs = now + (expiresIn * 1000L) - TOKEN_EXPIRY_BUFFER_MS

        return accessToken
    }

    /**
     * 清除缓存的 token。
     */
    fun clearCache() {
        cachedAccessToken = null
        tokenExpiryTimeMs = 0L
        cachedKey = null
    }

    /**
     * 向百度 OAuth 端点请求 access_token。
     *
     * @return Pair(accessToken, expiresIn)
     */
    private suspend fun fetchToken(
        apiKey: String,
        secretKey: String,
        httpClient: OkHttpClient
    ): Pair<String, Long> = withContext(Dispatchers.IO) {
        val url = "$TOKEN_URL?grant_type=client_credentials" +
            "&client_id=$apiKey" +
            "&client_secret=$secretKey"

        val request = Request.Builder()
            .url(url)
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Baidu token request failed: HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw RuntimeException("Baidu token request returned empty body")
            val json = JSONObject(body)

            if (json.has("error")) {
                throw RuntimeException(
                    "Baidu token error: ${json.optString("error")} - ${json.optString("error_description")}"
                )
            }

            val accessToken = json.getString("access_token")
            val expiresIn = json.optLong("expires_in", 2592000) // 默认30天

            Pair(accessToken, expiresIn)
        }
    }
}
