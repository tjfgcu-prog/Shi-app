@file:OptIn(ExperimentalMaterial3Api::class)

package com.lianyu.ai.feature.settings.ui.screen

import android.content.Context
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.network.tts.*
import com.lianyu.ai.uicommon.theme.*

import kotlinx.coroutines.launch

@Composable
fun TtsSettingsScreen(
    onNavigateBack: () -> Unit,
    isDarkTheme: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val ttsService = remember { TtsService.getInstance(context) }

    var isVisible by remember { mutableStateOf(true) }
    var ttsEnabled by remember { mutableStateOf(false) }
    var selectedProvider by remember { mutableStateOf(TtsProvider.ANDROID) }
    var isTesting by remember { mutableStateOf(false) }
    var isSynthesizing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var previewAudioPath by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
            previewAudioPath?.let { path -> runCatching { java.io.File(path).delete() } }
            previewAudioPath = null
        }
    }

    val config = remember { TtsConfig.fromSharedPreferences(context) }
    var minimaxApiKey by remember { mutableStateOf(config.minimaxApiKey) }
    var minimaxGroupId by remember { mutableStateOf(config.minimaxGroupId) }
    var minimaxVoiceId by remember { mutableStateOf(config.minimaxVoiceId) }
    var localTtsSpeed by remember { mutableStateOf(config.localTtsSpeed) }
    var localTtsSid by remember { mutableStateOf(config.localTtsSid) }
    var minimaxEmotion by remember { mutableStateOf(config.minimaxEmotion) }

    val localTtsManager = remember { ttsService.localTtsManager }
    val localTtsState by localTtsManager.state.collectAsState()

    val chatTtsCfg = remember { ChatTtsConfig.fromSharedPreferences(context) }
    var chatTtsMode by remember { mutableStateOf(chatTtsCfg.mode) }
    var skipParentheses by remember { mutableStateOf(chatTtsCfg.skipParentheses) }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
        ttsEnabled = prefs.getBoolean("tts_enabled", false)
        val providerName = prefs.getString("tts_provider", TtsProvider.ANDROID.name)
        selectedProvider = TtsProvider.entries.find { it.name == providerName } ?: TtsProvider.ANDROID
    }

    val colorScheme = MaterialTheme.colorScheme
    val backgroundColor = colorScheme.background
    val textPrimaryColor = colorScheme.onSurface
    val textSecondaryColor = colorScheme.onSurfaceVariant
    val cardBg = colorScheme.surfaceVariant

    fun saveSettings() {
        val newConfig = TtsConfig(
            localTtsSpeed = localTtsSpeed,
            localTtsSid = localTtsSid,
            minimaxApiKey = minimaxApiKey,
            minimaxGroupId = minimaxGroupId,
            minimaxVoiceId = minimaxVoiceId,
            minimaxEmotion = minimaxEmotion
        )
        TtsConfig.saveToSharedPreferences(context, newConfig)
        ttsService.updateConfig(newConfig)
        val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("tts_enabled", ttsEnabled)
            putString("tts_provider", selectedProvider.name)
            commit()
        }
        ttsService.setProvider(selectedProvider)
    }

    fun saveChatTtsSettings() {
        ChatTtsConfig.saveToSharedPreferences(
            context,
            ChatTtsConfig(mode = chatTtsMode, skipParentheses = skipParentheses)
        )
    }

    fun runTest() {
        scope.launch {
            isSynthesizing = true
            testResult = null
            saveSettings()
            if (selectedProvider == TtsProvider.ANDROID) ttsService.setProvider(TtsProvider.ANDROID)
            val audioPath = ttsService.testWithSampleText(selectedProvider, "你好，这是一个语音合成测试。")
            isSynthesizing = false
            when {
                selectedProvider == TtsProvider.ANDROID -> {
                    val diagnostic = AndroidTtsProvider.lastDiagnostic
                    testResult = when {
                        audioPath != null && diagnostic != null -> "⚠ 已播放，但可能听不到声音：$diagnostic"
                        audioPath != null -> "✓ 已播放（系统语音引擎）"
                        else -> "✗ 合成失败${diagnostic?.let { "：$it" } ?: ""}"
                    }
                }
                audioPath != null -> {
                    try {
                        mediaPlayer?.release()
                        previewAudioPath = audioPath
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(audioPath)
                            setOnCompletionListener { mp ->
                                mp.release(); mediaPlayer = null
                                runCatching { java.io.File(audioPath).delete() }
                                previewAudioPath = null
                            }
                            setOnErrorListener { mp, _, _ ->
                                mp.release(); mediaPlayer = null
                                runCatching { java.io.File(audioPath).delete() }
                                previewAudioPath = null
                                true
                            }
                            prepare(); start()
                        }
                        testResult = "✓ 合成成功，正在播放"
                    } catch (e: Exception) {
                        runCatching { java.io.File(audioPath).delete() }
                        previewAudioPath = null
                        testResult = "✗ 播放失败: ${e.message ?: "未知错误"}"
                    }
                }
                else -> {
    val diagnostic = when (selectedProvider) {
        TtsProvider.LOCAL -> SherpaLocalTtsProvider.lastError
        TtsProvider.BREEZE -> CrispAsrTtsProvider.lastError
        else -> null
    }
    testResult = "✗ 合成失败${diagnostic?.let { "：$it" } ?: ""}"
                }
            }
            snackbarHostState.showSnackbar(testResult!!)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
        ) {
            // ── 顶部栏 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = PetalPrimary, modifier = Modifier.size(24.dp))
                }
                Text("语音", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = textPrimaryColor)
                Box(modifier = Modifier.size(40.dp))
            }

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 4 }
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ── ① 总开关 ──
                    TtsToggleCard(
                        enabled = ttsEnabled,
                        onToggle = { ttsEnabled = it; saveSettings() },
                        cardBg = cardBg, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor
                    )

                    AnimatedVisibility(
                        visible = ttsEnabled,
                        enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                        exit = shrinkVertically(tween(300)) + fadeOut(tween(300))
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            // ── ② 三段模式选择 ──
                            ModeSegmentedControl(
                                selected = selectedProvider,
                                onSelect = { selectedProvider = it; saveSettings() },
                                cardBg = cardBg, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor
                            )

                            // ── ③ 对应模式的配置区（带切换动画）──
                            AnimatedContent(targetState = selectedProvider, label = "tts_mode_config") { provider ->
                                when (provider) {
                                    TtsProvider.ANDROID -> Column(
                                        modifier = Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(20.dp)).background(cardBg).padding(20.dp)
                                    ) {
                                        Text(
                                            "使用手机自带的语音引擎，无需任何配置。\n建议在系统设置里安装一个质量更好的语音包以获得更好效果。",
                                            fontSize = 13.sp, color = textSecondaryColor
                                        )
                                    }
                                    TtsProvider.LOCAL -> LocalModeCard(
                                        localTtsState = localTtsState,
                                        localTtsSpeed = localTtsSpeed,
                                        onSpeedChange = { localTtsSpeed = it; saveSettings() },
                                        localTtsSid = localTtsSid,
                                        onSidChange = { localTtsSid = it; saveSettings() },
                                        onDownload = { scope.launch { localTtsManager.startDownload(localTtsState.modelId) } },
                                        onCancelDownload = { scope.launch { localTtsManager.cancelDownload() } },
                                        onEnable = { scope.launch { localTtsManager.enable() } },
                                        onDisable = { scope.launch { localTtsManager.disable() } },
                                        onDelete = { scope.launch { localTtsManager.deleteDownloadedModel() } },
                                        cardBg = cardBg, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor,
                                        context = context
                                    )
                                    TtsProvider.BREEZE -> BreezeModeCard(
                                        cardBg = cardBg, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor,
                                        context = context
                                    )
                                    TtsProvider.CLOUD -> CloudModeCard(
                                        apiKey = minimaxApiKey, onApiKeyChange = { minimaxApiKey = it },
                                        groupId = minimaxGroupId, onGroupIdChange = { minimaxGroupId = it },
                                        voiceId = minimaxVoiceId, onVoiceIdChange = { minimaxVoiceId = it; saveSettings() },
                                        emotion = minimaxEmotion, onEmotionChange = { minimaxEmotion = it; saveSettings() },
                                        speed = localTtsSpeed, onSpeedChange = { localTtsSpeed = it; saveSettings() },
                                        cardBg = cardBg, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor
                                    )
                                }
                            }

                            // ── ④ 常驻底部操作条：测试连接（仅云端） + 试听 ──
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (selectedProvider == TtsProvider.CLOUD) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                isTesting = true; testResult = null; saveSettings()
                                                val ok = ttsService.testProvider(selectedProvider)
                                                isTesting = false
                                                testResult = if (ok) "✓ 连接成功" else "✗ 连接失败，请检查配置"
                                                snackbarHostState.showSnackbar(testResult!!)
                                            }
                                        },
                                        enabled = !isTesting,
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = PetalGreen.copy(alpha = 0.15f), contentColor = PetalGreen)
                                    ) {
                                        if (isTesting) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = PetalGreen, strokeWidth = 2.dp)
                                        else { Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("测试连接", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                                    }
                                }
                                Button(
                                    onClick = { runTest() },
                                    enabled = !isSynthesizing && !isTesting,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PetalPrimaryContainer, contentColor = PetalOnPrimaryContainer)
                                ) {
                                    if (isSynthesizing) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = PetalOnPrimaryContainer, strokeWidth = 2.dp)
                                    else { Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("试听", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                                }
                            }

                            testResult?.let {
                                Text(it, fontSize = 13.sp, color = if (it.contains("成功")) PetalGreen else PetalError, fontWeight = FontWeight.Medium)
                            }

                            // ── ⑤ 回复行为分组 ──
                            ChatReadAloudSettingsCard(
                                chatTtsMode = chatTtsMode,
                                onModeSelect = { chatTtsMode = it; saveChatTtsSettings() },
                                skipParentheses = skipParentheses,
                                onSkipParenthesesChange = { skipParentheses = it; saveChatTtsSettings() },
                                cardBg = cardBg, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TtsToggleCard(
    enabled: Boolean,onToggle: (Boolean) -> Unit,
    cardBg: Color, textPrimaryColor: Color, textSecondaryColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cardBg).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text("启用语音", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = textPrimaryColor)
            Text("开启后 AI 回复将使用语音", fontSize = 12.sp, color = textSecondaryColor)
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** 四段式模式选择：系统 / 本地 / Breeze / 云端 */
@Composable
private fun ModeSegmentedControl(
    selected: TtsProvider, onSelect: (TtsProvider) -> Unit,
    cardBg: Color, textPrimaryColor: Color, textSecondaryColor: Color
) {
    val options = listOf(
        Triple(TtsProvider.ANDROID, "系统", Icons.Filled.PhoneAndroid),
        Triple(TtsProvider.LOCAL, "本地", Icons.Filled.SdStorage),
        Triple(TtsProvider.BREEZE, "Breeze", Icons.Filled.GraphicEq),
        Triple(TtsProvider.CLOUD, "云端", Icons.Filled.CloudQueue)
    )
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cardBg).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (provider, label, icon) ->
            val isSelected = provider == selected
            Column(
                modifier = Modifier.weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) PetalPrimaryContainer else Color.Transparent)
                    .clickable { onSelect(provider) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, null, tint = if (isSelected) PetalOnPrimaryContainer else textSecondaryColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(4.dp))
                Text(label, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) PetalOnPrimaryContainer else textSecondaryColor)
            }
        }
    }
}

/** 本地模式卡片：状态优先，按钮只显示当前能点的那个 */
@Composable
private fun LocalModeCard(
    localTtsState: LocalTtsUiState,
    localTtsSpeed: Float, onSpeedChange: (Float) -> Unit,
    localTtsSid: Int, onSidChange: (Int) -> Unit,
    onDownload: () -> Unit, onCancelDownload: () -> Unit,
    onEnable: () -> Unit, onDisable: () -> Unit, onDelete: () -> Unit,
    cardBg: Color, textPrimaryColor: Color, textSecondaryColor: Color, context: Context
) {
    val model = localTtsState.model
    val status = localTtsState.status
    val isDownloading = status == LocalTtsUiStatus.DOWNLOADING
    val isReady = status == LocalTtsUiStatus.READY
    val isEnabled = status == LocalTtsUiStatus.ENABLED

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cardBg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 状态卡：颜色区分三种状态
        val (statusText, statusColor, statusBg) = when (status) {
            LocalTtsUiStatus.NOT_DOWNLOADED -> Triple("尚未下载 · ${model.displayName}", textSecondaryColor, textSecondaryColor.copy(alpha = 0.08f))
            LocalTtsUiStatus.DOWNLOADING -> Triple("下载中 ${localTtsState.progressPercent}% (${localTtsState.currentFileIndex + 1}/${localTtsState.totalFiles})", PetalPrimary, PetalPrimaryContainer.copy(alpha = 0.4f))
            LocalTtsUiStatus.READY -> Triple("已下载 · 点击启用", PetalPrimary, PetalPrimaryContainer.copy(alpha = 0.4f))
            LocalTtsUiStatus.ENABLED -> Triple("已启用 · ${model.displayName}", PetalGreen, PetalGreen.copy(alpha = 0.12f))
            LocalTtsUiStatus.FAILED -> Triple("失败: ${localTtsState.errorMessage ?: "未知"}", PetalError, PetalError.copy(alpha = 0.1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(statusBg).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isDownloading) {
                CircularProgressIndicator(
                    progress = { localTtsState.progressPercent / 100f },
                    modifier = Modifier.size(20.dp), color = statusColor, strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(statusText, fontSize = 14.sp, color = statusColor, fontWeight = FontWeight.Medium)
        }

        // 只显示当前能点的那一个主按钮
        when {
            status == LocalTtsUiStatus.NOT_DOWNLOADED && model.files.any { it.downloadUrl.isNotBlank() } ->
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PetalPrimaryContainer, contentColor = PetalOnPrimaryContainer)) {
                    Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("下载漫剧女声模型（约110MB）")
                }
            isDownloading ->
                Button(onClick = onCancelDownload, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PetalError.copy(alpha = 0.15f), contentColor = PetalError)) { Text("取消下载") }
            isReady || isEnabled ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDisable, enabled = isEnabled,
                        modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, if (isEnabled) PetalPrimary else textSecondaryColor.copy(alpha = 0.25f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = PetalPrimary,
                            disabledContentColor = textSecondaryColor.copy(alpha = 0.4f)
                        )
                    ) { Text("禁用", fontSize = 13.sp) }
                    OutlinedButton(
                        onClick = onEnable, enabled = isReady,
                        modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, if (isReady) PetalGreen else textSecondaryColor.copy(alpha = 0.25f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = PetalGreen,
                            disabledContentColor = textSecondaryColor.copy(alpha = 0.4f)
                        )
                    ) { Text("启用", fontSize = 13.sp) }
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, PetalError),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PetalError)
                    ) { Text("删除", fontSize = 13.sp) }
                }
        }

        if (isEnabled || isReady) {
            HorizontalDivider(color = textSecondaryColor.copy(alpha = 0.15f))

            // 拖动时只更新本地草稿值（即时显示数字），松手才真正下发 onSidChange/onSpeedChange，
            // 避免拖动过程中每帧都触发保存+引擎参数更新，导致听感上"调了跟没调一样"。
            var draftSid by remember(localTtsSid) { mutableFloatStateOf(localTtsSid.toFloat()) }
            Text("音色: ${draftSid.toInt()} / ${model.numSpeakers - 1}", fontSize = 13.sp, color = textSecondaryColor)
            Slider(
                value = draftSid,
                onValueChange = { draftSid = it },
                onValueChangeFinished = { onSidChange(draftSid.toInt()) },
                valueRange = 0f..(model.numSpeakers - 1).toFloat(), modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(thumbColor = PetalPrimary, activeTrackColor = PetalPrimary)
            )

            var draftSpeed by remember(localTtsSpeed) { mutableFloatStateOf(localTtsSpeed) }
            Text("语速: ${String.format("%.1f", draftSpeed)}", fontSize = 13.sp, color = textSecondaryColor)
            Slider(
                value = draftSpeed,
                onValueChange = { draftSpeed = it },
                onValueChangeFinished = { onSpeedChange(String.format("%.1f", draftSpeed).toFloat()) },
                valueRange = 0.5f..2.0f, modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(thumbColor = PetalPrimary, activeTrackColor = PetalPrimary)
            )
        }

        Text("离线端上推理，无需联网，隐私更好。", fontSize = 12.sp, color = textSecondaryColor)
    }
}

/** Breeze 本地模式卡片：手动选 GGUF 复制进 App，开关控制加载/卸载 */
@Composable
private fun BreezeModeCard(
    cardBg: Color, textPrimaryColor: Color, textSecondaryColor: Color, context: Context
) {
    val state by CrispAsrTtsEngine.state.collectAsState()
    LaunchedEffect(Unit) { CrispAsrTtsEngine.refresh(context) }

    // 自定义音色：文字稿 + 同意确认；picker 回调里读到的永远是最新值
    var refText by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    LaunchedEffect(state.voiceText) { if (refText.isEmpty()) refText = state.voiceText }
    val voicePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) CrispAsrTtsEngine.importVoice(context, uri, refText, consent)
    }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) CrispAsrTtsEngine.startImport(context, uri, CrispAsrTtsEngine.Kind.MODEL)
    }
    val codecPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) CrispAsrTtsEngine.startImport(context, uri, CrispAsrTtsEngine.Kind.CODEC)
    }

    val copying = state.copyingLabel != null
    val busy = copying || state.loading
    val copiedMb = state.copiedBytes / 1024 / 1024
    val totalMb = state.totalBytes / 1024 / 1024

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cardBg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 状态条
        val (statusText, statusColor, statusBg) = when {
            copying -> Triple(
                "正在复制${state.copyingLabel}… " + if (totalMb > 0) "$copiedMb / $totalMb MB" else "$copiedMb MB",
                PetalPrimary, PetalPrimaryContainer.copy(alpha = 0.4f)
            )
            state.loading -> Triple("正在加载模型到内存…（可能要几十秒）", PetalPrimary, PetalPrimaryContainer.copy(alpha = 0.4f))
            state.error != null -> Triple("失败: ${state.error}", PetalError, PetalError.copy(alpha = 0.1f))
            state.loaded -> Triple("已加载 · ${state.backend ?: "Breeze TTS 2"}", PetalGreen, PetalGreen.copy(alpha = 0.12f))
            state.filesReady -> Triple("文件已就绪 · 打开下面的开关加载", PetalPrimary, PetalPrimaryContainer.copy(alpha = 0.4f))
            else -> Triple("请先选择模型文件和 codec 文件", textSecondaryColor, textSecondaryColor.copy(alpha = 0.08f))
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(statusBg).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (copying || state.loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = statusColor, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(statusText, fontSize = 14.sp, color = statusColor, fontWeight = FontWeight.Medium)
        }
        if (copying) {
            if (state.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { (state.copiedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        // 两个文件
        BreezeFileRow(
            label = "模型文件", hint = "例如 breeze-tts-2-q8_0.gguf", fileName = state.modelName,
            enabled = !busy, onPick = { modelPicker.launch(arrayOf("*/*")) },
            textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor
        )
        BreezeFileRow(
            label = "codec 文件", hint = "例如 qwen3-tts-tokenizer-12hz.gguf", fileName = state.codecName,
            enabled = !busy, onPick = { codecPicker.launch(arrayOf("*/*")) },
            textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor
        )

        HorizontalDivider(color = textSecondaryColor.copy(alpha = 0.15f))

        // 加载开关：开 = 加载进内存，关 = 释放内存（不删文件）
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("加载到内存", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textPrimaryColor)
                Text("关闭只释放内存，不会删除文件；重启 App 后，第一次用到语音时会自动加载", fontSize = 12.sp, color = textSecondaryColor)
            }
            Switch(
                checked = state.loaded || state.loading,
                enabled = state.filesReady && !copying && !state.loading,
                onCheckedChange = { on ->
                    if (on) CrispAsrTtsEngine.load(context) else CrispAsrTtsEngine.unload()
                }
            )
        }

        OutlinedButton(
            onClick = { CrispAsrTtsEngine.deleteFiles(context) },
            enabled = (state.modelName != null || state.codecName != null) && !busy,
            modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, if (!busy) PetalError else textSecondaryColor.copy(alpha = 0.25f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = PetalError,
                disabledContentColor = textSecondaryColor.copy(alpha = 0.4f)
            )
        ) { Text("删除已导入的模型文件", fontSize = 13.sp) }

        HorizontalDivider(color = textSecondaryColor.copy(alpha = 0.15f))

        // ── 自定义音色 ──
        Text("自定义音色（可选）", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textPrimaryColor)
        Text(
            "上传一段已获得本人同意的录音，之后合成就一直用这个音色。" +
                "要求：WAV 格式，单人说话、干净无噪音，2–30 秒（5–15 秒效果最好），并填写录音的逐字文字稿。",
            fontSize = 12.sp, color = textSecondaryColor
        )
        OutlinedTextField(
            value = refText,
            onValueChange = { refText = it },
            label = { Text("录音的逐字文字稿（必须和录音一致）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = !busy
        )
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { consent = !consent },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !busy)
            Text(
                "我确认这段声音是我自己的，或其所有者已明确同意用于语音合成",
                fontSize = 12.sp, color = textPrimaryColor, modifier = Modifier.weight(1f)
            )
        }
        OutlinedButton(
            onClick = { voicePicker.launch(arrayOf("*/*")) },
            enabled = !busy && consent && refText.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(14.dp)
        ) { Text(if (state.voiceName == null) "选择参考录音（WAV）并保存" else "更换参考录音（WAV）", fontSize = 13.sp) }
        if (state.voiceName != null) {
            Text(
                "当前音色：${state.voiceName}（${"%.1f".format(state.voiceSeconds)} 秒）" +
                    if (state.loaded && state.voiceActive) " · 使用中" else " · 加载模型后生效",
                fontSize = 13.sp, color = textPrimaryColor
            )
            OutlinedButton(
                onClick = { CrispAsrTtsEngine.deleteVoice(context) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (!busy) PetalError else textSecondaryColor.copy(alpha = 0.25f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = PetalError,
                    disabledContentColor = textSecondaryColor.copy(alpha = 0.4f)
                )
            ) { Text("删除自定义音色（恢复默认音色）", fontSize = 13.sp) }
        }

        Text(
            "需要同时导入两个 GGUF 文件。模型约 3GB，加载后占用大量内存，" +
                "和本地聊天模型一起开可能被系统杀掉进程。Breeze 模型仅限非商业使用。",
            fontSize = 12.sp, color = textSecondaryColor
        )
    }
}

@Composable
private fun BreezeFileRow(
    label: String, hint: String, fileName: String?, enabled: Boolean, onPick: () -> Unit,
    textPrimaryColor: Color, textSecondaryColor: Color
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = textSecondaryColor)
            Text(
                fileName ?: "未选择（$hint）",
                fontSize = 14.sp,
                color = if (fileName != null) textPrimaryColor else textSecondaryColor.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = onPick, enabled = enabled,
            shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp)
        ) { Text(if (fileName == null) "选择" else "更换", fontSize = 13.sp) }
    }
}

/** 云端模式卡片：只有 MiniMax，不再暴露"选供应商"这一层 */
@Composable
private fun CloudModeCard(
    apiKey: String, onApiKeyChange: (String) -> Unit,
    groupId: String, onGroupIdChange: (String) -> Unit,
    voiceId: String, onVoiceIdChange: (String) -> Unit,
    emotion: String, onEmotionChange: (String) -> Unit,
    speed: Float, onSpeedChange: (Float) -> Unit,
    cardBg: Color, textPrimaryColor: Color, textSecondaryColor: Color
) {
    val emotions = listOf(
        "" to "自动（推荐，按文本判断）",
        "happy" to "开心",
        "calm" to "平静",
        "sad" to "悲伤",
        "angry" to "生气",
        "surprised" to "惊讶"
    )
    var showEmotionDropdown by remember { mutableStateOf(false) }
    val voices = listOf(
        "female-shaonv" to "少女音（漫剧女主，推荐）",
        "female-tianmei" to "甜美女声",
        "female-yujie" to "御姐音",
        "male-qn-qingse" to "青涩青年音"
    )
    var showVoiceDropdown by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cardBg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("MiniMax Speech-02", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = textPrimaryColor)
        Text("情感表现力最强，需要自行申请密钥", fontSize = 12.sp, color = textSecondaryColor)
        OutlinedTextField(
            value = apiKey, onValueChange = onApiKeyChange, label = { Text("API Key") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = groupId, onValueChange = onGroupIdChange, label = { Text("Group ID") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Text("音色", fontSize = 13.sp, color = textSecondaryColor)
        Box {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { showVoiceDropdown = true }.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Text(voices.find { it.first == voiceId }?.second ?: voiceId, fontSize = 14.sp, color = textPrimaryColor)
            }
            DropdownMenu(expanded = showVoiceDropdown, onDismissRequest = { showVoiceDropdown = false }) {
                voices.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label, fontSize = 14.sp) }, onClick = { onVoiceIdChange(id); showVoiceDropdown = false })
                }
            }
        }
        Text("情绪", fontSize = 13.sp, color = textSecondaryColor)
        Box {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { showEmotionDropdown = true }.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Text(emotions.find { it.first == emotion }?.second ?: emotion, fontSize = 14.sp, color = textPrimaryColor)
            }
            DropdownMenu(expanded = showEmotionDropdown, onDismissRequest = { showEmotionDropdown = false }) {
                emotions.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label, fontSize = 14.sp) }, onClick = { onEmotionChange(id); showEmotionDropdown = false })
                }
            }
        }
        var draftSpeed by remember(speed) { mutableFloatStateOf(speed) }
        Text("语速: ${String.format("%.1f", draftSpeed)}", fontSize = 13.sp, color = textSecondaryColor)
        Slider(
            value = draftSpeed,
            onValueChange = { draftSpeed = it },
            onValueChangeFinished = { onSpeedChange(String.format("%.1f", draftSpeed).toFloat()) },
            valueRange = 0.5f..2.0f, modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(thumbColor = PetalPrimary, activeTrackColor = PetalPrimary)
        )
    }
}

@Composable
private fun ChatReadAloudSettingsCard(
    chatTtsMode: ChatTtsMode, onModeSelect: (ChatTtsMode) -> Unit,
    skipParentheses: Boolean, onSkipParenthesesChange: (Boolean) -> Unit,
    cardBg: Color, textPrimaryColor: Color, textSecondaryColor: Color
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cardBg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("回复行为", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = textSecondaryColor)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("语音回复", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = textPrimaryColor)
                Text("开启后 AI 发送的每一条消息都会合成为语音气泡（最长60秒）", fontSize = 12.sp, color = textSecondaryColor)
            }
            Switch(
                checked = chatTtsMode == ChatTtsMode.VOICE_BAR,
                onCheckedChange = { onModeSelect(if (it) ChatTtsMode.VOICE_BAR else ChatTtsMode.SILENT) }
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("跳过括号内心戏", fontSize = 14.sp, color = textPrimaryColor)
                Text("不朗读 <...> (...) 内的内容", fontSize = 12.sp, color = textSecondaryColor)
            }
            Switch(checked = skipParentheses, onCheckedChange = onSkipParenthesesChange)
        }
    }
}
