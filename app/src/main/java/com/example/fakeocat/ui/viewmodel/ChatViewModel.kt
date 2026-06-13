package com.example.fakeocat.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.fakeocat.data.PreferencesManager
import com.example.fakeocat.data.db.DatabaseHelper
import com.example.fakeocat.data.db.entity.BookmarkEntity
import com.example.fakeocat.data.db.entity.MessageEntity
import com.example.fakeocat.network.AiModel
import com.example.fakeocat.network.AiProviderCatalog
import com.example.fakeocat.network.EndpointProfile
import com.example.fakeocat.network.LlmClient
import com.example.fakeocat.network.ModelCache
import com.example.fakeocat.network.ModelFetchException
import com.example.fakeocat.network.ModelFetcher
import com.example.fakeocat.network.ResponseCache
import com.example.fakeocat.network.TtsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** ViewModel 的 UI 状态密封类 */
sealed class ChatUiState {
    object Idle : ChatUiState()
    object Loading : ChatUiState()
    data class Generating(val partialMessage: String) : ChatUiState()
    data class Error(val message: String) : ChatUiState()
}

/** 模型获取错误类型，用于 UI 端映射本地化错误消息 */
enum class ModelFetchError {
    /** 未输入 API Key */
    NO_API_KEY,
    /** API Key 无效或已过期 */
    INVALID_API_KEY,
    /** API Key 无权访问 */
    ACCESS_DENIED,
    /** 请求频率限制 */
    RATE_LIMITED,
    /** 未知的服务商 */
    UNKNOWN_PROVIDER,
    /** 网络错误 */
    NETWORK_ERROR,
    /** 其他未知错误 */
    UNKNOWN
}

/** 模型列表获取状态 */
sealed class ModelFetchState {
    /** 未发起过请求 */
    object Idle : ModelFetchState()
    /** 正在请求中 */
    object Loading : ModelFetchState()
    /** 成功获取到模型列表 */
    data class Success(val models: List<AiModel>) : ModelFetchState()
    /** 请求失败 */
    data class Error(val error: ModelFetchError) : ModelFetchState()
    /** 当前 Provider 不支持动态模型列表（如 Anthropic） */
    object Unsupported : ModelFetchState()
}

/**
 * ChatViewModel —— 精简后仅保留 UI 状态管理和流程编排。
 *
 * Prompt 构建 → [PromptBuilder]
 * 流式请求编排 → [StreamOrchestrator]
 * 语言配置管理 → [LanguageConfigManager]
 * 会话/书签 CRUD → 直接委托 [DatabaseHelper]
 *
 * 生产环境通过 [Factory] 创建，测试可直接传入 mock 依赖。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel internal constructor(
    val prefs: PreferencesManager,
    private val dbHelper: DatabaseHelper,
    private val llmClient: LlmClient,
    private val ttsManager: TtsManager,
    private val responseCache: ResponseCache,
    private val modelFetcher: ModelFetcher = ModelFetcher(llmClient.httpClient)
) : ViewModel() {
    private val TAG = "ChatViewModel"

    // ══════════════════════════════════════════════
    // 辅助类（纯工具类在 init 中直接创建）
    // ══════════════════════════════════════════════
    private val promptBuilder = PromptBuilder()
    private val languageConfigManager = LanguageConfigManager(prefs, viewModelScope)
    private val streamOrchestrator = StreamOrchestrator(
        llmClient, dbHelper, prefs, responseCache
    )

    // ══════════════════════════════════════════════
    // 语言与主题配置（通过 LanguageConfigManager 中转）
    // ══════════════════════════════════════════════
    val appLanguage: StateFlow<String> = languageConfigManager.appLanguage
    val nativeLanguage: StateFlow<String> = languageConfigManager.nativeLanguage
    val targetLanguage: StateFlow<String> = languageConfigManager.targetLanguage

    val themeMode = prefs.themeModeFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "auto")
    val selectedProvider = prefs.selectedProviderFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "openai")

    // ── 端点选择 ──
    /** 当前选中 Provider 的 Profile ID（空字符串 = 使用默认） */
    val selectedEndpoint: StateFlow<String> = selectedProvider
        .flatMapLatest { prefs.selectedEndpointFlowFor(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** 当前选中 Profile 的额外配置值 Map */
    private val _endpointExtraConfig = MutableStateFlow<Map<String, String>>(emptyMap())
    val endpointExtraConfig: StateFlow<Map<String, String>> = _endpointExtraConfig

    // ── 模型选择 ──
    private val _modelFetchState = MutableStateFlow<ModelFetchState>(ModelFetchState.Idle)
    val modelFetchState: StateFlow<ModelFetchState> = _modelFetchState

    /** 当前选中 Provider 的用户选择模型名（空字符串 = 使用默认） */
    val selectedModel: StateFlow<String> = selectedProvider
        .flatMapLatest { prefs.selectedModelFlowFor(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // ══════════════════════════════════════════════
    // 会话与书签（DB 操作调度到 IO 线程，避免首次创建数据库时阻塞主线程）
    // ══════════════════════════════════════════════
    val chatHistory: StateFlow<List<MessageEntity>> = dbHelper.getAllMessagesFlow()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentSessionMessageIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _showHistory = MutableStateFlow(false)
    val showHistory: StateFlow<Boolean> = _showHistory

    val displayMessages: StateFlow<List<MessageEntity>> = kotlinx.coroutines.flow.combine(
        chatHistory, _currentSessionMessageIds, _showHistory
    ) { history, sessionIds, showFull ->
        if (showFull) history else history.filter { it.id in sessionIds }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bookmarkedMessages: StateFlow<List<BookmarkEntity>> = dbHelper.getBookmarkedMessagesFlow()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadHistoryIntoDisplay() { _showHistory.value = true }
    fun startNewChat() { _showHistory.value = false; _currentSessionMessageIds.value = emptySet() }

    // ══════════════════════════════════════════════
    // UI 状态
    // ══════════════════════════════════════════════
    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState

    private val _isRequestInFlight = MutableStateFlow(false)
    val isRequestInFlight: StateFlow<Boolean> = _isRequestInFlight

    private val _uiEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val uiEvents: kotlinx.coroutines.flow.Flow<String> = _uiEvents

    private var generationJob: Job? = null
    private var fetchModelsJob: Job? = null

    // ══════════════════════════════════════════════
    // 模式与语言覆盖
    // ══════════════════════════════════════════════
    private val _currentMode = MutableStateFlow("HowToSay")
    val currentMode: StateFlow<String> = _currentMode

    private val _howToSayLang = MutableStateFlow<String?>(null)
    val howToSayLang: StateFlow<String?> = _howToSayLang

    fun setMode(mode: String) { _currentMode.value = mode }
    fun setHowToSayLang(lang: String) { _howToSayLang.value = lang }
    fun getEffectiveTargetLang(): String = _howToSayLang.value ?: targetLanguage.value

    // ══════════════════════════════════════════════
    // sendMessage —— 流程编排入口
    // ══════════════════════════════════════════════
    fun sendMessage(userText: String) {
        if (userText.isBlank()) return

        generationJob?.cancel()
        _isRequestInFlight.value = true
        generationJob = viewModelScope.launch {
            val mode = _currentMode.value
            val native = nativeLanguage.value
            val target = if (mode == "HowToSay") getEffectiveTargetLang() else targetLanguage.value

            // 持久化用户消息
            val userMsg = MessageEntity(text = userText, isUser = true, mode = mode)
            val userId = dbHelper.insertMessage(userMsg)
            _currentSessionMessageIds.value += userId

            _uiState.value = ChatUiState.Loading

            // 构建 Prompt
            val systemPrompt = promptBuilder.buildSystemPrompt(mode, native, target)
            val userPrompt = promptBuilder.buildUserPrompt(mode, userText, target)

            // 委托 StreamOrchestrator 执行流式请求
            streamOrchestrator.executeStreamRequest(
                mode = mode,
                userId = userId,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                userText = userText,
                onToken = { fullText ->
                    _uiState.value = ChatUiState.Generating(fullText)
                },
                onFallback = { msg ->
                    _uiEvents.tryEmit(msg)
                },
                onComplete = { assistantMsg ->
                    _currentSessionMessageIds.value += assistantMsg.id
                    _uiState.value = ChatUiState.Idle
                    _isRequestInFlight.value = false
                    generationJob = null
                },
                onCancelled = {
                    _uiEvents.tryEmit("已停止")
                    _uiState.value = ChatUiState.Idle
                    _isRequestInFlight.value = false
                },
                onError = { e ->
                    _uiState.value = ChatUiState.Error(e.message ?: "Unexpected error")
                    _isRequestInFlight.value = false
                    generationJob = null
                }
            )
        }
    }

    // ══════════════════════════════════════════════
    // 模型列表获取与选择
    // ══════════════════════════════════════════════

    /**
     * 获取当前选中 Provider 的可用模型列表。
     * 优先使用当前选中的 EndpointProfile，支持 Profile 级别的额外配置。
     *
     * @param forceRefresh true 时跳过缓存，强制从网络获取（刷新按钮场景）
     */
    fun fetchModels(forceRefresh: Boolean = false) {
        fetchModelsJob?.cancel()
        fetchModelsJob = viewModelScope.launch(Dispatchers.IO) {
            val providerId = selectedProvider.value
            val provider = AiProviderCatalog.getProvider(providerId)
            if (provider == null) {
                android.util.Log.e(TAG, "ModelFetch: Unknown provider '$providerId'")
                _modelFetchState.value = ModelFetchState.Error(ModelFetchError.UNKNOWN_PROVIDER)
                return@launch
            }

            // 使用当前选中的 Profile
            val profile = prefs.resolveEndpointProfile(providerId)

            if (!profile.supportsModelList) {
                _modelFetchState.value = ModelFetchState.Unsupported
                return@launch
            }

            val apiKey = prefs.apiKeyFlowFor(providerId).first()
            if (apiKey.isBlank()) {
                android.util.Log.w(TAG, "ModelFetch: API key is blank for $providerId")
                _modelFetchState.value = ModelFetchState.Error(ModelFetchError.NO_API_KEY)
                return@launch
            }
            val endpointConfig = prefs.getEndpointConfigMap(providerId, profile.id)

            _modelFetchState.value = ModelFetchState.Loading
            val result = modelFetcher.fetchModels(
                profile = profile,
                apiKey = apiKey,
                endpointConfig = endpointConfig,
                providerId = providerId,
                forceRefresh = forceRefresh
            )
            _modelFetchState.value = result.fold(
                onSuccess = { ModelFetchState.Success(it) },
                onFailure = { e ->
                    val error = when {
                        e is ModelFetchException -> when (e.httpStatusCode) {
                            401 -> ModelFetchError.INVALID_API_KEY
                            403 -> ModelFetchError.ACCESS_DENIED
                            429 -> ModelFetchError.RATE_LIMITED
                            else -> ModelFetchError.NETWORK_ERROR
                        }
                        else -> ModelFetchError.NETWORK_ERROR
                    }
                    ModelFetchState.Error(error)
                }
            )
        }
    }

    /**
     * 清除指定 Provider 的模型缓存。
     * 使用 Profile 级别的缓存键（providerId:profileId）。
     */
    fun clearModelCache(providerId: String) {
        // 清除 provider 级别的旧缓存
        ModelCache.clear(providerId)
        // 清除所有 profile 级别的缓存
        AiProviderCatalog.getProvider(providerId)?.profiles?.forEach { profile ->
            ModelCache.clear("$providerId:${profile.id}")
        }
    }

    /**
     * 设置用户选择的模型名并持久化。
     * @param model 模型标识符，空字符串表示使用 Provider 默认模型。
     */
    fun setSelectedModel(model: String) {
        viewModelScope.launch {
            prefs.setSelectedModelFor(selectedProvider.value, model)
        }
    }

    /**
     * 设置当前 Provider 的端点选择并持久化。
     * 切换端点时自动清除模型缓存并重新获取模型列表。
     * @param profileId Profile ID，空字符串表示使用默认端点。
     */
    fun setSelectedEndpoint(profileId: String) {
        viewModelScope.launch {
            val providerId = selectedProvider.value
            prefs.setSelectedEndpointFor(providerId, profileId)
            // 切换端点时清除模型缓存
            clearModelCache(providerId)
            // 重新加载额外配置
            loadEndpointExtraConfig()
            // 重新获取模型列表
            fetchModels(forceRefresh = true)
        }
    }

    /**
     * 设置当前 Profile 的额外配置字段值并持久化。
     * @param fieldKey 配置字段键名
     * @param value 配置值
     */
    fun setEndpointExtraConfig(fieldKey: String, value: String) {
        viewModelScope.launch {
            val providerId = selectedProvider.value
            val profileId = selectedEndpoint.value.ifBlank {
                AiProviderCatalog.getProvider(providerId)?.defaultProfile?.id ?: ""
            }
            val profile = AiProviderCatalog.getEndpoint(providerId, profileId)
            val isSecret = profile?.extraConfigFields
                ?.firstOrNull { it.key == fieldKey }?.isSecret ?: false
            prefs.setEndpointConfig(providerId, profileId, fieldKey, value, isSecret)
            // 更新本地 StateFlow
            _endpointExtraConfig.value = _endpointExtraConfig.value.toMutableMap().apply {
                put(fieldKey, value)
            }
        }
    }

    /**
     * 加载当前 Profile 的额外配置到 StateFlow。
     * 在端点切换或 Provider 切换时调用。
     */
    private suspend fun loadEndpointExtraConfig() {
        val providerId = selectedProvider.value
        val profileId = selectedEndpoint.value.ifBlank {
            AiProviderCatalog.getProvider(providerId)?.defaultProfile?.id ?: ""
        }
        val configMap = prefs.getEndpointConfigMap(providerId, profileId)
        _endpointExtraConfig.value = configMap
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        _isRequestInFlight.value = false
        _uiState.value = ChatUiState.Idle
    }

    // ══════════════════════════════════════════════
    // 书签 & 消息管理
    // ══════════════════════════════════════════════
    fun toggleBookmark(message: MessageEntity) {
        viewModelScope.launch {
            if (message.id <= 0) return@launch
            val isBookmarked = dbHelper.isMessageBookmarked(message.id)
            if (isBookmarked) {
                dbHelper.removeBookmarkBySourceMessageId(message.id)
            } else {
                dbHelper.addBookmarkFromMessage(message)
            }
        }
    }

    fun removeBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch { dbHelper.removeBookmarkById(bookmark.id) }
    }

    fun deleteMessage(message: MessageEntity) {
        viewModelScope.launch { dbHelper.deleteMessage(message) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            dbHelper.clearHistory()
            startNewChat()
        }
    }

    // ══════════════════════════════════════════════
    // TTS
    // ══════════════════════════════════════════════
    fun speak(text: String, lang: String? = null) {
        ttsManager.speak(text, lang ?: getEffectiveTargetLang())
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        ttsManager.shutdown()
    }

    /**
     * 生产环境使用的 ViewModelProvider.Factory。
     * 从 [Application] 创建所有依赖并注入 [ChatViewModel]。
     */
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val prefs = PreferencesManager(application)
            val dbHelper = DatabaseHelper(application)
            val llmClient = LlmClient()
            val ttsManager = TtsManager(application)
            val responseCache = ResponseCache(application)
            val modelFetcher = ModelFetcher(llmClient.httpClient)
            return ChatViewModel(prefs, dbHelper, llmClient, ttsManager, responseCache, modelFetcher) as T
        }
    }
}
