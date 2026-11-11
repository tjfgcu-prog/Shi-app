package com.lianyu.ai.feature.chat.ui.screen

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack


import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlinx.coroutines.flow.first

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

import com.lianyu.ai.feature.chat.data.ChatDetailSettingsStore
import com.lianyu.ai.common.AppSettingsStore
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatViewModel
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatViewModelFactory
import com.lianyu.ai.uicommon.component.ChatBackgroundPickerDialog
import com.lianyu.ai.uicommon.component.getChatBackgroundKey


@Composable
fun ChatDetailScreen(
    companionId: Long,
    onNavigateBack: () -> Unit,
    // 传入聊天页的 ViewModelStoreOwner 即可复用同一个 ChatViewModel，
    // 避免打开详情页时再整套创建一遍（Room 监听、消息消费协程、用户资料等）
    chatViewModelStoreOwner: androidx.lifecycle.ViewModelStoreOwner? = null
) {
    val context = LocalContext.current
    val store = remember { ChatDetailSettingsStore(context) }
    val scope = rememberCoroutineScope()
    val viewModel: ChatViewModel = viewModel(
        viewModelStoreOwner = chatViewModelStoreOwner
            ?: checkNotNull(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current),
        factory = ChatViewModelFactory(context.applicationContext as Application, companionId)
    )
    val companionData by viewModel.companionData.collectAsState()
    // 进入聊天页就预热 Breeze 模型（只在 Breeze + 语音回复开启时才会真的加载）
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.warmUpVoiceIfNeeded() }
    val settings by store.settingsFlow(companionId).collectAsState(initial = com.lianyu.ai.feature.chat.data.CompanionChatDetailSettings())
    val appSettingsStore = remember { AppSettingsStore(context) }
    val innerThoughtEnabled by appSettingsStore.innerThoughtEnabledFlow(companionId).collectAsState(initial = false)

    // 当前语音引擎：只有「本地漫剧女声」才有音色编号可选，Breeze/系统/云端都没有
    val ttsProvider = remember { com.lianyu.ai.network.tts.TtsService.getInstance(context).getCurrentProvider() }

    // 当前本地语音模型的最大音色编号（用于音色滑块范围）
    val maxSid by androidx.compose.runtime.produceState(0) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val prefs = com.lianyu.ai.network.tts.LocalTtsPreferences(context)
                com.lianyu.ai.network.tts.LocalTtsCatalog.findById(prefs.selectedModelId.first()).numSpeakers - 1
            }.getOrDefault(0)
        }.coerceAtLeast(0)
    }

    var showBgPicker by remember { mutableStateOf(false) }
    var showBlockConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    

    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onBackground)
                }
                Text(
                    text = "聊天详情",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
            }

            // Companion info card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (companionData?.avatarUrl != null) {
                        AsyncImage(
                            model = companionData?.avatarUrl,
                            contentDescription = companionData?.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = companionData?.name?.firstOrNull()?.toString() ?: "?",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = companionData?.name ?: "",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!companionData?.personality.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = companionData?.personality ?: "",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                // Status tags
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (settings.blocked) {
                        StatusTag("已拉黑", Color(0xFFFF3B30))
                    } else if (settings.doNotDisturbEnabled) {
                        StatusTag("免打扰", Color(0xFFFF9500))
                    } else if (!settings.proactiveEnabled) {
                        StatusTag("主动消息关闭", MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        StatusTag("正常", Color(0xFF34C759))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Chat appearance section
            SectionTitle("聊天外观")
            SettingsCard {
                SettingsRow(title = "当前聊天背景", subtitle = backgroundName(settings.backgroundKey, context)) {
                    showBgPicker = true
                }
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SettingsToggleRow(
                    title = "使用全局背景",
                    checked = settings.useGlobalBackground,
                    onCheckedChange = { checked ->
                        scope.launch {
                            store.updateSettings(companionId) { it.copy(useGlobalBackground = checked) }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Proactive message section
            SectionTitle("主动消息")
            SettingsCard {
                SettingsToggleRow(
                    title = "允许主动发消息",
                    subtitle = "没有聊天时，每 60 分钟有 70% 概率触发",
                    checked = settings.proactiveEnabled,
                    onCheckedChange = { checked ->
                        scope.launch {
                            store.updateSettings(companionId) { it.copy(proactiveEnabled = checked) }
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                
                SettingsToggleRow(
                    title = "允许主动开启新话题",
                    checked = settings.allowNewTopic,
                    onCheckedChange = { checked ->
                        scope.launch {
                            store.updateSettings(companionId) { it.copy(allowNewTopic = checked) }
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SettingsToggleRow(
                    title = "允许深夜消息",
                    checked = settings.allowLateNightMessage,
                    onCheckedChange = { checked ->
                        scope.launch {
                            store.updateSettings(companionId) { it.copy(allowLateNightMessage = checked) }
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SettingsToggleRow(
                    title = "允许连续追问",
                    checked = settings.allowFollowUpMessage,
                    onCheckedChange = { checked ->
                        scope.launch {
                            store.updateSettings(companionId) { it.copy(allowFollowUpMessage = checked) }
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                
                SettingsToggleRow(
                    title = "精确时间感知",
                    subtitle = "通过NTP网络校时获取精确时间，避免设备时钟不准",
                    checked = settings.ntpTimeEnabled,
                    onCheckedChange = { checked ->
                        scope.launch {
                            store.updateSettings(companionId) { it.copy(ntpTimeEnabled = checked) }
                        }
                    }
                )
                            HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SettingsToggleRow(
                    title = "剧情模式",
                    subtitle = "开启后记录每轮对话，每5轮自动生成剧情摘要；关闭后不再新增，已有摘要仍会提供给角色",
                    checked = settings.storyModeEnabled,
                    onCheckedChange = { checked ->
                        scope.launch {
                            store.updateSettings(companionId) { it.copy(storyModeEnabled = checked) }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Dialogue style section
            SectionTitle("对话风格")
            SettingsCard {
                SettingsToggleRow(
                    title = "心理活动描写",
                    subtitle = if (innerThoughtEnabled) "已开启 - AI 回复中可能包含括号内的心理活动" else "已关闭 - AI 不会输出括号内的心理活动",
                    checked = innerThoughtEnabled,
                    onCheckedChange = { checked ->
                        scope.launch { appSettingsStore.setInnerThoughtEnabled(companionId, checked) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            
            // Sticker section
            SectionTitle("表情包")
            SettingsCard {
                SettingsSliderRow(
                    title = "AI发送表情包概率",
                    subtitle = "${settings.stickerProbability}%",
                    value = settings.stickerProbability / 100f,
                    onValueChange = { value ->
                        scope.launch {
                            store.updateSettings(companionId) { it.copy(stickerProbability = (value * 100).toInt()) }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 本角色语音
            SectionTitle("语音（本角色）")
            SettingsCard {
                CompanionVoiceSettings(
                    sid = settings.voiceSid,
                    speed = settings.voiceSpeed,
                    maxSid = maxSid,
                    provider = ttsProvider,
                    onSidChange = { v -> scope.launch { store.updateSettings(companionId) { it.copy(voiceSid = v) } } },
                    onSpeedChange = { v -> scope.launch { store.updateSettings(companionId) { it.copy(voiceSpeed = v) } } },
                    onPreview = { s, sp -> viewModel.previewVoice(s, sp) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Do not disturb section
            SectionTitle("免打扰")
            SettingsCard {
                SettingsToggleRow(
                    title = "免打扰",
                    checked = settings.doNotDisturbEnabled,
                    onCheckedChange = { checked ->
                        scope.launch {
                            store.updateSettings(companionId) { it.copy(doNotDisturbEnabled = checked) }
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SettingsToggleRow(
                    title = "夜间免打扰 (23:00-08:00)",
                    checked = settings.dndStartMinutes == 23 * 60 && settings.dndEndMinutes == 8 * 60,
                    onCheckedChange = { checked ->
                        scope.launch {
                            store.updateSettings(companionId) {
                                if (checked) it.copy(dndStartMinutes = 23 * 60, dndEndMinutes = 8 * 60)
                                else it.copy(dndStartMinutes = 0, dndEndMinutes = 0)
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Relationship section
            SectionTitle("关系与隐私")
            SettingsCard {
                if (settings.blocked) {
                    DangerRow(title = "取消拉黑", color = Color(0xFF34C759)) {
                        scope.launch {
                            store.updateSettings(companionId) { it.copy(blocked = false) }
                        }
                    }
                } else {
                    DangerRow(title = "拉黑", color = Color(0xFFFF3B30)) {
                        showBlockConfirm = true
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                DangerRow(title = "清空聊天记录", color = Color(0xFFFF3B30)) {
                    showClearConfirm = true
                }
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                DangerRow(title = "重置聊天设置", color = Color(0xFFFF9500)) {
                    showResetConfirm = true
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Background picker dialog
    if (showBgPicker) {
        val currentKey = if (settings.useGlobalBackground) getChatBackgroundKey(context) else (settings.backgroundKey ?: "default")
        ChatBackgroundPickerDialog(
            currentKey = currentKey,
            onDismiss = { showBgPicker = false },
            onSelect = { key ->
                scope.launch {
                    store.updateSettings(companionId) { it.copy(backgroundKey = key, useGlobalBackground = false) }
                }
                showBgPicker = false
            }
        )
    }

    // Block confirm dialog
    if (showBlockConfirm) {
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text("确认拉黑") },
            text = { Text("拉黑后对方将不再主动发消息。你仍可以手动发送消息。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            store.updateSettings(companionId) { it.copy(blocked = true) }
                        }
                        showBlockConfirm = false
                    }
                ) { Text("拉黑", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirm = false }) { Text("取消") }
            }
        )
    }

    // Clear messages confirm dialog
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("确认清空") },
            text = { Text("清空后将无法恢复，确定要清空聊天记录吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            viewModel.clearChatHistory()
                        }
                        showClearConfirm = false
                    }
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    // Reset settings confirm dialog
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("确认重置") },
            text = { Text("所有聊天设置将恢复默认值，包括背景、主动消息、免打扰等。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            store.resetSettings(companionId)
                            appSettingsStore.setInnerThoughtEnabled(companionId, false)
                        }
                        showResetConfirm = false
                    }
                ) { Text("重置", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            }
        )
    }

    
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(text = title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(text = subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(text = "›", fontSize = 18.sp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun SettingsToggleRow(title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(text = subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun SettingsSliderRow(
    title: String,
    subtitle: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(text = subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            valueRange = 0f..1f,
            steps = 9,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun DangerRow(title: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, fontSize = 15.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = text, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

private fun backgroundName(key: String?, context: android.content.Context): String {
    return when (key) {
        null, "default" -> "默认白色"
        "warm_pink" -> "暖粉色"
        "lavender" -> "薰衣草"
        "ocean" -> "海洋蓝"
        "forest" -> "森林绿"
        "sunset" -> "日落橙"
        "night" -> "夜空"
        else -> if (key.startsWith("custom_")) "自定义图片" else "未知"
    }
}



/** 本角色的语音设置：音色编号 / 语速，可单独设置，也可以沿用全局；带试听。 */
@Composable
private fun CompanionVoiceSettings(
    sid: Int,
    speed: Float,
    maxSid: Int,
    provider: com.lianyu.ai.network.tts.TtsProvider,
    onSidChange: (Int) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onPreview: (Int?, Float?) -> Unit
) {
    var draftSid by remember(sid) { mutableStateOf(sid.coerceAtLeast(0).toFloat()) }
    var draftSpeed by remember(speed) { mutableStateOf(if (speed > 0f) speed else 1.0f) }
    val isBreeze = provider == com.lianyu.ai.network.tts.TtsProvider.BREEZE
    val supportsSid = provider == com.lianyu.ai.network.tts.TtsProvider.LOCAL
    val supportsSpeed = !isBreeze

    if (supportsSid) {
        SettingsToggleRow(
            title = "单独设置音色",
            subtitle = if (sid >= 0) "本角色使用音色 #$sid" else "使用语音设置里的全局音色",
            checked = sid >= 0,
            onCheckedChange = { enabled -> onSidChange(if (enabled) draftSid.toInt().coerceIn(0, maxSid) else -1) }
        )
    } else {
        Text(
            text = if (isBreeze) {
                "当前语音引擎是 Breeze：它没有音色编号，所有角色共用同一个音色，" +
                    "在「语音设置 → Breeze → 自定义音色」里设置。"
            } else {
                "当前语音引擎没有音色编号可选，音色在语音设置里统一设置。"
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
    if (supportsSid && sid >= 0 && maxSid > 0) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                text = "音色 #${draftSid.toInt()} / $maxSid",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.material3.Slider(
                value = draftSid,
                onValueChange = { draftSid = it },
                onValueChangeFinished = { onSidChange(draftSid.toInt().coerceIn(0, maxSid)) },
                valueRange = 0f..maxSid.toFloat(),
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }

    if (supportsSpeed) SettingsToggleRow(
        title = "单独设置语速",
        subtitle = if (speed > 0f) "本角色语速 ${String.format("%.1f", speed)}" else "使用语音设置里的全局语速",
        checked = speed > 0f,
        onCheckedChange = { enabled -> onSpeedChange(if (enabled) String.format("%.1f", draftSpeed).toFloat() else 0f) }
    )
    if (supportsSpeed && speed > 0f) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                text = "语速 ${String.format("%.1f", draftSpeed)}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.material3.Slider(
                value = draftSpeed,
                onValueChange = { draftSpeed = it },
                onValueChangeFinished = { onSpeedChange(String.format("%.1f", draftSpeed).toFloat()) },
                valueRange = 0.5f..2.0f,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isBreeze) "Breeze 暂不支持调语速" else "仅「本地」语音能选音色；语速本地和云端都生效",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = {
            onPreview(
                if (supportsSid && sid >= 0) draftSid.toInt().coerceIn(0, maxSid) else null,
                if (supportsSpeed && speed > 0f) draftSpeed else null
            )
        }) { Text("试听") }
    }
}
