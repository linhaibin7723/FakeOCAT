package com.example.fakeocat.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fakeocat.R
import com.example.fakeocat.network.AiProviderCatalog
import com.example.fakeocat.network.ConnectionPrewarmer
import com.example.fakeocat.network.EndpointProfile
import com.example.fakeocat.network.ExtraConfigField
import com.example.fakeocat.ui.viewmodel.ChatViewModel
import com.example.fakeocat.ui.viewmodel.ModelFetchError
import com.example.fakeocat.ui.viewmodel.ModelFetchState
import com.example.fakeocat.ui.viewmodel.PromptBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 根据字符串资源名称解析为 Android 资源 ID。
 * 用于将 [EndpointProfile.displayNameRes] 和 [ExtraConfigField.displayNameRes]
 * 这样的字符串资源名（如 "endpoint_openai_direct"）转换为 [stringResource] 可用的 Int ID。
 */
@Composable
private fun resolveStringRes(name: String): Int {
    val context = LocalContext.current
    return remember(name) {
        context.resources.getIdentifier(name, "string", context.packageName)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = viewModel.prefs
    val themeMode by viewModel.themeMode.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    val nativeLang by viewModel.nativeLanguage.collectAsState()
    val targetLang by viewModel.targetLanguage.collectAsState()
    val appLang by viewModel.appLanguage.collectAsState()

    // 当前所选服务商的 API Key
    var apiKey by remember { mutableStateOf("") }
    var apiKeyLoaded by remember { mutableStateOf(false) }
    var lastLoadedProvider by remember { mutableStateOf("") }

    // 模型选择相关状态
    val modelFetchState by viewModel.modelFetchState.collectAsState()
    val selectedModelName by viewModel.selectedModel.collectAsState()
    var modelExpanded by remember { mutableStateOf(false) }
    var manualModelInput by remember { mutableStateOf("") }
    var showManualInput by remember { mutableStateOf(false) }

    // ── 端点选择与额外配置状态 ──
    val selectedEndpoint by viewModel.selectedEndpoint.collectAsState()
    val endpointExtraConfig by viewModel.endpointExtraConfig.collectAsState()
    /** 本地额外配置编辑状态，用于即时 UI 反馈 */
    var localExtraConfig by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // 当服务商变化时重新加载 API Key（使用 LaunchedEffect 自动管理生命周期）
    LaunchedEffect(selectedProvider) {
        apiKey = prefs.apiKeyFlowFor(selectedProvider).first()
        apiKeyLoaded = true
        lastLoadedProvider = selectedProvider
        // 切换服务商时重置手动输入状态
        showManualInput = false
        manualModelInput = ""
        // 加载该 Provider 当前端点的额外配置
        val providerInfo = AiProviderCatalog.getProvider(selectedProvider)
        val profileId = prefs.getSelectedEndpoint(selectedProvider).ifBlank {
            providerInfo?.defaultProfile?.id ?: ""
        }
        localExtraConfig = prefs.getEndpointConfigMap(selectedProvider, profileId)
    }

    // 端点切换时重新加载额外配置
    LaunchedEffect(selectedEndpoint) {
        val providerId = selectedProvider
        val profileId = selectedEndpoint.ifBlank {
            AiProviderCatalog.getProvider(providerId)?.defaultProfile?.id ?: ""
        }
        localExtraConfig = prefs.getEndpointConfigMap(providerId, profileId)
    }

    // 当前 Provider 信息和选中的 Profile
    val providerInfo = AiProviderCatalog.getProvider(selectedProvider)
    val currentProfile: EndpointProfile? = if (selectedEndpoint.isNotBlank()) {
        providerInfo?.getProfile(selectedEndpoint) ?: providerInfo?.defaultProfile
    } else {
        providerInfo?.defaultProfile
    }

    val providers = AiProviderCatalog.providers.map { it.id to it.displayName }

    val languages = PromptBuilder.supportedLanguages.map {
        it to PromptBuilder.langDisplayName(it)
    }

    val appLanguages = listOf(
        "system" to stringResource(R.string.settings_language_system)
    ) + languages

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── 主题 ──
            SectionTitle(stringResource(R.string.settings_theme))
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "auto" to stringResource(R.string.settings_theme_auto),
                        "light" to stringResource(R.string.settings_theme_light),
                        "dark" to stringResource(R.string.settings_theme_dark)
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = themeMode == key,
                            onClick = { scope.launch { prefs.setThemeMode(key) } },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── AI 服务商 + API Key + 端点 + 额外配置 ──
            SectionTitle(stringResource(R.string.settings_ai_provider))
            SettingsCard {
                // 服务商下拉选择
                var providerExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = it }
                ) {
                    OutlinedTextField(
                        value = providers.find { it.first == selectedProvider }?.second ?: "OpenAI",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                        shape = RoundedCornerShape(12.dp),
                        label = { Text(stringResource(R.string.settings_ai_provider)) }
                    )
                    ExposedDropdownMenu(
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false }
                    ) {
                        providers.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    scope.launch {
                                        prefs.setSelectedProvider(key)
                                        apiKey = prefs.apiKeyFlowFor(key).first()
                                        lastLoadedProvider = key
                                    }
                                    providerExpanded = false
                                }
                            )
                        }
                    }
                }

                // 6.1 端点选择器（仅当 Provider 有多个 Profile 时显示）
                if (providerInfo != null && providerInfo.profiles.size > 1) {
                    Spacer(modifier = Modifier.height(12.dp))
                    EndpointSelector(
                        profiles = providerInfo.profiles,
                        selectedProfileId = selectedEndpoint.ifBlank {
                            providerInfo.defaultProfile.id
                        },
                        onProfileSelected = { profileId ->
                            viewModel.setSelectedEndpoint(profileId)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 接口密钥输入框
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.settings_api_key)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        if (apiKey.isNotEmpty()) {
                            IconButton(onClick = { apiKey = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = stringResource(R.string.action_clear)
                                )
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_api_key_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )

                // 6.2 额外配置字段（根据当前 Profile 动态渲染）
                currentProfile?.extraConfigFields?.forEach { field ->
                    ExtraConfigFieldInput(
                        field = field,
                        value = localExtraConfig[field.key] ?: "",
                        onValueChange = { newValue ->
                            localExtraConfig = localExtraConfig.toMutableMap().apply {
                                put(field.key, newValue)
                            }
                            viewModel.setEndpointExtraConfig(field.key, newValue)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── 模型选择 ──
                ModelSelectionSection(
                    viewModel = viewModel,
                    selectedModelName = selectedModelName,
                    modelFetchState = modelFetchState,
                    modelExpanded = modelExpanded,
                    onModelExpandedChange = { modelExpanded = it },
                    showManualInput = showManualInput,
                    onShowManualInputChange = { showManualInput = it },
                    manualModelInput = manualModelInput,
                    onManualModelInputChange = { manualModelInput = it }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── 语言设置 ──
            SectionTitle(stringResource(R.string.settings_app_language))
            SettingsCard {
                var appLangExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = appLangExpanded,
                    onExpandedChange = { appLangExpanded = it }
                ) {
                    OutlinedTextField(
                        value = appLanguages.find { it.first == appLang }?.second ?: stringResource(R.string.settings_language_system),
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = appLangExpanded) },
                        shape = RoundedCornerShape(12.dp),
                        label = { Text(stringResource(R.string.settings_app_language)) }
                    )
                    ExposedDropdownMenu(
                        expanded = appLangExpanded,
                        onDismissRequest = { appLangExpanded = false }
                    ) {
                        appLanguages.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    scope.launch { prefs.setAppLanguage(key) }
                                    appLangExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── 母语 ──
            SectionTitle(stringResource(R.string.settings_native_language))
            SettingsCard {
                var nativeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = nativeExpanded,
                    onExpandedChange = { nativeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = languages.find { it.first == nativeLang }?.second ?: "",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = nativeExpanded) },
                        shape = RoundedCornerShape(12.dp),
                        label = { Text(stringResource(R.string.settings_native_language)) }
                    )
                    ExposedDropdownMenu(
                        expanded = nativeExpanded,
                        onDismissRequest = { nativeExpanded = false }
                    ) {
                        languages.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    scope.launch { prefs.setNativeLanguage(key) }
                                    nativeExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── 目标语言 ──
            SectionTitle(stringResource(R.string.settings_target_language))
            SettingsCard {
                var targetExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = targetExpanded,
                    onExpandedChange = { targetExpanded = it }
                ) {
                    OutlinedTextField(
                        value = languages.find { it.first == targetLang }?.second ?: "",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetExpanded) },
                        shape = RoundedCornerShape(12.dp),
                        label = { Text(stringResource(R.string.settings_target_language)) }
                    )
                    ExposedDropdownMenu(
                        expanded = targetExpanded,
                        onDismissRequest = { targetExpanded = false }
                    ) {
                        languages.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    scope.launch { prefs.setTargetLanguage(key) }
                                    targetExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 保存按钮
            Button(
                onClick = {
                    scope.launch {
                        // 保存 API Key（加密存储）
                        prefs.setApiKeyFor(selectedProvider, apiKey)
                        // 从 DataStore 读取已持久化的端点 Profile（避免 Compose state 传播延迟导致读到空值）
                        val resolvedProfile = prefs.resolveEndpointProfile(selectedProvider)
                        val profileId = resolvedProfile.id
                        // 保存额外配置字段值（已通过 viewModel.setEndpointExtraConfig 实时持久化，此处确保同步）
                        currentProfile?.extraConfigFields?.forEach { field ->
                            val value = localExtraConfig[field.key] ?: ""
                            prefs.setEndpointConfig(selectedProvider, profileId, field.key, value, field.isSecret)
                        }
                        // API Key 变更后清除旧缓存，避免使用过期权限的模型列表
                        viewModel.clearModelCache(selectedProvider)
                        // 保存后异步预热连接，降低首次请求 TTFT
                        ConnectionPrewarmer.resetForProvider(selectedProvider)
                        ConnectionPrewarmer.warmUp(selectedProvider, apiKey)
                        // 保存 API Key 后强制刷新模型列表（跳过缓存）
                        viewModel.fetchModels(forceRefresh = true)
                        Toast.makeText(context, context.getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.settings_save), fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * 6.1 端点选择器：当 Provider 有多个 Profile 时显示的下拉选择器。
 * 使用 ExposedDropdownMenuBox 实现，显示每个 Profile 的本地化 displayName。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndpointSelector(
    profiles: List<EndpointProfile>,
    selectedProfileId: String,
    onProfileSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedProfile = profiles.find { it.id == selectedProfileId } ?: profiles.first()
    val selectedDisplayNameRes = resolveStringRes(selectedProfile.displayNameRes)
    val selectedDisplayName = if (selectedDisplayNameRes != 0) {
        stringResource(selectedDisplayNameRes)
    } else {
        selectedProfile.displayNameRes
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedDisplayName,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(12.dp),
            label = { Text(stringResource(R.string.endpoint_selector_label)) }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            profiles.forEach { profile ->
                val displayNameRes = resolveStringRes(profile.displayNameRes)
                val displayName = if (displayNameRes != 0) {
                    stringResource(displayNameRes)
                } else {
                    profile.displayNameRes
                }
                DropdownMenuItem(
                    text = { Text(displayName) },
                    onClick = {
                        onProfileSelected(profile.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * 6.2 额外配置字段输入框：根据 ExtraConfigField 定义动态渲染。
 * 敏感字段（isSecret=true）使用 PasswordVisualTransformation。
 */
@Composable
private fun ExtraConfigFieldInput(
    field: ExtraConfigField,
    value: String,
    onValueChange: (String) -> Unit
) {
    var localValue by remember(value) { mutableStateOf(value) }
    val labelResId = resolveStringRes(field.displayNameRes)
    val label = if (labelResId != 0) stringResource(labelResId) else field.displayNameRes

    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = localValue,
        onValueChange = {
            localValue = it
            onValueChange(it)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        placeholder = { Text(field.placeholder) },
        visualTransformation = if (field.isSecret) PasswordVisualTransformation()
            else VisualTransformation.None,
        trailingIcon = {
            if (localValue.isNotEmpty()) {
                IconButton(onClick = {
                    localValue = ""
                    onValueChange("")
                }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = stringResource(R.string.action_clear)
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectionSection(
    viewModel: ChatViewModel,
    selectedModelName: String,
    modelFetchState: ModelFetchState,
    modelExpanded: Boolean,
    onModelExpandedChange: (Boolean) -> Unit,
    showManualInput: Boolean,
    onShowManualInputChange: (Boolean) -> Unit,
    manualModelInput: String,
    onManualModelInputChange: (String) -> Unit
) {
    val providerInfo = AiProviderCatalog.getProvider(
        viewModel.selectedProvider.collectAsState().value
    )
    val defaultModelName = providerInfo?.model ?: ""
    val displayModel = selectedModelName.ifBlank { defaultModelName }

    // 模型名 + 刷新按钮
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showManualInput) {
            // 手动输入模式
            OutlinedTextField(
                value = manualModelInput,
                onValueChange = onManualModelInputChange,
                label = { Text(stringResource(R.string.settings_model)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text(defaultModelName) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = {
                onShowManualInputChange(false)
                if (manualModelInput.isNotBlank()) {
                    viewModel.setSelectedModel(manualModelInput.trim())
                }
            }) {
                Text(stringResource(R.string.settings_save))
            }
        } else {
            // 下拉选择模式
            ExposedDropdownMenuBox(
                expanded = modelExpanded,
                onExpandedChange = { onModelExpandedChange(it) },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = when (modelFetchState) {
                        is ModelFetchState.Loading -> stringResource(R.string.settings_model_loading)
                        else -> displayModel
                    },
                    onValueChange = {},
                    readOnly = true,
                    enabled = modelFetchState !is ModelFetchState.Loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(stringResource(R.string.settings_model)) }
                )

                val models = when (modelFetchState) {
                    is ModelFetchState.Success -> modelFetchState.models
                    is ModelFetchState.Unsupported -> AiProviderCatalog.ANTHROPIC_MODELS
                    else -> emptyList()
                }

                if (models.isNotEmpty()) {
                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { onModelExpandedChange(false) }
                    ) {
                        models.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        model.displayName,
                                        fontWeight = if (model.id == selectedModelName || (selectedModelName.isBlank() && model.id == defaultModelName))
                                            FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    viewModel.setSelectedModel(model.id)
                                    onModelExpandedChange(false)
                                }
                            )
                        }
                        // 手动输入选项
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.settings_model_manual_input),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            },
                            onClick = {
                                onModelExpandedChange(false)
                                onShowManualInputChange(true)
                                onManualModelInputChange(selectedModelName)
                            }
                        )
                    }
                }
            }

            // 刷新按钮（强制绕过缓存）
            IconButton(
                onClick = { viewModel.fetchModels(forceRefresh = true) },
                enabled = modelFetchState !is ModelFetchState.Loading
            ) {
                if (modelFetchState is ModelFetchState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.settings_model_retry)
                    )
                }
            }
        }
    }

    // 状态提示
    when (modelFetchState) {
        is ModelFetchState.Error -> {
            val errorText = when (modelFetchState.error) {
                ModelFetchError.NO_API_KEY -> stringResource(R.string.settings_model_no_api_key)
                ModelFetchError.INVALID_API_KEY,
                ModelFetchError.ACCESS_DENIED,
                ModelFetchError.RATE_LIMITED,
                ModelFetchError.UNKNOWN_PROVIDER,
                ModelFetchError.UNKNOWN -> stringResource(R.string.settings_model_error)
                ModelFetchError.NETWORK_ERROR -> stringResource(R.string.error_network)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = errorText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { viewModel.fetchModels(forceRefresh = true) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(stringResource(R.string.settings_model_retry), fontSize = 12.sp)
                }
            }
        }
        is ModelFetchState.Unsupported -> {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_model_unsupported),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        else -> {}
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}
