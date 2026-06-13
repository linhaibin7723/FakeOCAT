package com.example.fakeocat.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.example.fakeocat.network.AiProviderCatalog
import com.example.fakeocat.network.EndpointProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesManager(
    private val context: Context
) {

    // 使用 lazy 延迟初始化，避免在主线程构造时阻塞
    private val encryptedPrefs: android.content.SharedPreferences by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "secure_settings",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val TAG = "PreferencesManager"
        val SELECTED_PROVIDER = stringPreferencesKey("selected_provider")
        val API_KEY_OPENAI = stringPreferencesKey("api_key_openai")
        val API_KEY_ANTHROPIC = stringPreferencesKey("api_key_anthropic")
        val API_KEY_GEMINI = stringPreferencesKey("api_key_gemini")
        val API_KEY_DEEPSEEK = stringPreferencesKey("api_key_deepseek")
        val API_KEY_GROK = stringPreferencesKey("api_key_grok")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val NATIVE_LANGUAGE = stringPreferencesKey("native_language")
        val TARGET_LANGUAGE = stringPreferencesKey("target_language")

        fun selectedModelKey(provider: String) =
            stringPreferencesKey("selected_model_$provider")

        /** 端点选择：selected_endpoint_{provider} → profile ID */
        fun selectedEndpointKey(provider: String) =
            stringPreferencesKey("selected_endpoint_$provider")

        /** 端点额外配置：endpoint_config_{provider}_{profileId}_{fieldKey} */
        fun endpointConfigKey(provider: String, profileId: String, fieldKey: String) =
            stringPreferencesKey("endpoint_config_${provider}_${profileId}_$fieldKey")
    }

    val selectedProviderFlow: Flow<String> = context.dataStore.data.map { 
        val p = it[SELECTED_PROVIDER] ?: "openai"
        Log.d(TAG, "Current selected provider: $p")
        p
    }
    val themeModeFlow: Flow<String> = context.dataStore.data.map { it[THEME_MODE] ?: "auto" }
    val appLanguageFlow: Flow<String> = context.dataStore.data.map { it[APP_LANGUAGE] ?: "system" }
    val nativeLanguageFlow: Flow<String> = context.dataStore.data.map { it[NATIVE_LANGUAGE] ?: "zh" }
    val targetLanguageFlow: Flow<String> = context.dataStore.data.map { it[TARGET_LANGUAGE] ?: "ja" }

    fun apiKeyFlowFor(provider: String): Flow<String> {
        // 仅从 EncryptedSharedPreferences 读取 API Key（不在 DataStore 中存储明文 Key）
        return context.dataStore.data.map {
            val k = encryptedPrefs.getString("api_key_$provider", "") ?: ""
            Log.d(TAG, "Reading API Key for $provider: ${if (k.isEmpty()) "EMPTY" else "EXISTS(length=${k.length})"}")
            k
        }
    }

    /**
     * 当当前服务商缺少密钥时，回退查找任意可用密钥。
     * 返回 (ProviderName, Key) 二元组。
     */
    suspend fun findAnyAvailableKey(): Pair<String, String>? {
        val providers = AiProviderCatalog.providers.map { it.id }
        // 仅从 EncryptedSharedPreferences 查找可用密钥
        for (provider in providers) {
            val epKey = encryptedPrefs.getString("api_key_$provider", "")
            if (!epKey.isNullOrBlank()) {
                Log.d(TAG, "Fallback: Found available key in EncryptedPrefs for $provider")
                return provider to epKey
            }
        }
        return null
    }

    suspend fun setSelectedProvider(provider: String) {
        Log.d(TAG, "Setting selected provider: $provider")
        context.dataStore.edit { it[SELECTED_PROVIDER] = provider }
    }

    suspend fun setApiKeyFor(provider: String, apiKey: String) {
        Log.d(TAG, "Setting API Key for $provider (Secure only)")
        // 仅保存到 EncryptedSharedPreferences（使用硬件保护加密存储）
        encryptedPrefs.edit().putString("api_key_$provider", apiKey).apply()
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }

    suspend fun setAppLanguage(lang: String) {
        context.dataStore.edit { it[APP_LANGUAGE] = lang }
        // 同时写入 SharedPreferences，供 attachBaseContext 同步读取
        context.getSharedPreferences("settings_locale", Context.MODE_PRIVATE)
            .edit()
            .putString("app_language", lang)
            .apply()
    }

    suspend fun setNativeLanguage(lang: String) {
        context.dataStore.edit { it[NATIVE_LANGUAGE] = lang }
    }

    suspend fun setTargetLanguage(lang: String) {
        context.dataStore.edit { it[TARGET_LANGUAGE] = lang }
    }

    // ══════════════════════════════════════════════
    // 模型选择持久化
    // ══════════════════════════════════════════════

    /** 获取用户为指定 Provider 手动选择的模型名（空字符串表示未设置） */
    fun selectedModelFlowFor(provider: String): Flow<String> {
        return context.dataStore.data.map { prefs ->
            prefs[selectedModelKey(provider)] ?: ""
        }
    }

    /** 保存用户选择的模型名 */
    suspend fun setSelectedModelFor(provider: String, model: String) {
        Log.d(TAG, "Setting selected model for $provider: $model")
        context.dataStore.edit { it[selectedModelKey(provider)] = model }
    }

    /**
     * 获取实际使用的模型名。
     * 优先使用用户为该 Provider 手动选择的模型；
     * 其次使用 AiProviderInfo 中的硬编码默认模型。
     */
    suspend fun resolveModel(providerId: String): String {
        val saved = context.dataStore.data.first()[selectedModelKey(providerId)] ?: ""
        if (saved.isNotBlank()) {
            Log.d(TAG, "Resolved model for $providerId: $saved (user selected)")
            return saved
        }
        val default = AiProviderCatalog.getProvider(providerId)?.model ?: "gpt-5.4-mini"
        Log.d(TAG, "Resolved model for $providerId: $default (default)")
        return default
    }

    // ══════════════════════════════════════════════
    // 端点选择持久化
    // ══════════════════════════════════════════════

    /** 获取用户为指定 Provider 选择的端点 Profile ID（空字符串表示使用默认） */
    fun selectedEndpointFlowFor(provider: String): Flow<String> {
        return context.dataStore.data.map { prefs ->
            prefs[selectedEndpointKey(provider)] ?: ""
        }
    }

    /** 保存用户选择的端点 Profile ID */
    suspend fun setSelectedEndpointFor(provider: String, profileId: String) {
        Log.d(TAG, "Setting selected endpoint for $provider: $profileId")
        context.dataStore.edit { it[selectedEndpointKey(provider)] = profileId }
    }

    /** 获取用户为指定 Provider 选择的端点 Profile ID（非 Flow 版本） */
    suspend fun getSelectedEndpoint(providerId: String): String {
        return context.dataStore.data.first()[selectedEndpointKey(providerId)] ?: ""
    }

    // ══════════════════════════════════════════════
    // 端点额外配置持久化
    // ══════════════════════════════════════════════

    /** 获取端点额外配置值（Flow 版本，供 UI 订阅） */
    fun endpointConfigFlowFor(providerId: String, profileId: String, fieldKey: String): Flow<String> {
        return context.dataStore.data.map { prefs ->
            prefs[endpointConfigKey(providerId, profileId, fieldKey)] ?: ""
        }
    }

    /** 获取端点额外配置值（suspend 版本） */
    suspend fun getEndpointConfig(
        provider: String, profileId: String, fieldKey: String
    ): String {
        val field = AiProviderCatalog.getEndpoint(provider, profileId)
            ?.extraConfigFields?.firstOrNull { it.key == fieldKey }

        return if (field?.isSecret == true) {
            // 敏感信息从 EncryptedSharedPreferences 读取
            encryptedPrefs.getString(
                "endpoint_config_${provider}_${profileId}_$fieldKey", ""
            ) ?: ""
        } else {
            // 非敏感信息从 DataStore 读取
            context.dataStore.data.first()[
                endpointConfigKey(provider, profileId, fieldKey)
            ] ?: ""
        }
    }

    /** 获取端点所有额外配置值的 Map */
    suspend fun getEndpointConfigMap(
        provider: String, profileId: String
    ): Map<String, String> {
        val profile = AiProviderCatalog.getEndpoint(provider, profileId)
            ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (field in profile.extraConfigFields) {
            val value = getEndpointConfig(provider, profileId, field.key)
            if (value.isNotBlank()) {
                result[field.key] = value
            }
        }
        return result
    }

    /** 保存端点额外配置值 */
    suspend fun setEndpointConfig(
        provider: String, profileId: String, fieldKey: String, value: String,
        isSecret: Boolean = false
    ) {
        Log.d(TAG, "Setting endpoint config for $provider/$profileId: $fieldKey")
        if (isSecret) {
            encryptedPrefs.edit().putString(
                "endpoint_config_${provider}_${profileId}_$fieldKey", value
            ).apply()
        } else {
            context.dataStore.edit {
                it[endpointConfigKey(provider, profileId, fieldKey)] = value
            }
        }
    }

    /**
     * 解析当前有效的 EndpointProfile。
     * 优先使用用户选择的 profile ID；其次使用 Provider 的默认 profile。
     */
    suspend fun resolveEndpointProfile(providerId: String): EndpointProfile {
        val savedProfileId = context.dataStore.data.first()[
            selectedEndpointKey(providerId)
        ] ?: ""
        return if (savedProfileId.isNotBlank()) {
            AiProviderCatalog.getEndpoint(providerId, savedProfileId)
                ?: AiProviderCatalog.getProvider(providerId)!!.defaultProfile
        } else {
            AiProviderCatalog.getProvider(providerId)!!.defaultProfile
        }
    }

}
