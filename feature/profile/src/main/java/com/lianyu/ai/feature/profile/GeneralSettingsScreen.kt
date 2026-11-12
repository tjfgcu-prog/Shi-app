package com.lianyu.ai.feature.profile





import androidx.compose.foundation.background

import androidx.compose.foundation.layout.Arrangement

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.layout.statusBars

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.material.icons.filled.Brush



import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette

import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings

import androidx.compose.material.icons.filled.Token

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable

import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.common.AppSettingsStore
import com.lianyu.ai.common.FrameRateManager
import com.lianyu.ai.uicommon.component.ChatBackgroundPickerDialog
import com.lianyu.ai.uicommon.component.getChatBackgroundKey
import com.lianyu.ai.uicommon.component.setChatBackgroundKey

import kotlinx.coroutines.launch

/**
 * 总设置页 — 收纳全部次要设置项，按功能领域分组。
 *
 *   0. 自己设定    — 我的信息、各角色专属设定
 *   1. 记忆与管理  — 记忆管理、上下文记忆
 *   2. AI 与互动  — API 设置、语音、病娇模式
 *   3. 外观与数据  — 主题模式、聊天背景、帧率、思考设置、Token 统计、数据备份
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onNavigateBack: () -> Unit,
        // 自己设定
    onSelfProfileClick: () -> Unit,
    
    // 记忆与管理
    onMemoryClick: () -> Unit,
    onContextMemoryClick: () -> Unit,
    // AI配置
    onSettingsClick: () -> Unit,
    // 外观
    onThemeClick: () -> Unit,
    onFrameRateClick: () -> Unit,
    onTtsSettingsClick: () -> Unit,
    onTokenUsageClick: () -> Unit,
    
    onDataBackupClick: () -> Unit,
    
    onYandereModeClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    var isVisible by remember { mutableStateOf(true) }
    var showBackgroundDialog by remember { mutableStateOf(false) }

    

    Scaffold(
        modifier = Modifier.fillMaxSize().background(colorScheme.background).windowInsetsPadding(WindowInsets.statusBars),
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.general_settings), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // === 自己设定（最顶部，记忆管理上方）===
                SelfSettingsCard(
                isVisible = isVisible,
                onSelfProfileClick = onSelfProfileClick
            )

            Spacer(modifier = Modifier.height(12.dp))
            
            // === 记忆与管理 ===
            SolidMenuGroup(
                items = listOf(
                    MenuItemData(Icons.Filled.Memory, stringResource(R.string.memory_management), stringResource(R.string.memory_management_desc), onMemoryClick),
                    MenuItemData(Icons.Filled.Memory, stringResource(R.string.context_memory), stringResource(R.string.context_memory_desc), onContextMemoryClick)
                ),
                isVisible = isVisible, delayMillis = 20
            )

            Spacer(modifier = Modifier.height(12.dp))

            // === AI 与互动：API 设置 / 语音 / 病娇模式 ===
            SolidMenuGroup(
                items = listOf(
                    MenuItemData(Icons.Filled.Settings, stringResource(R.string.api_settings), stringResource(R.string.api_settings_desc), onSettingsClick),
                    MenuItemData(Icons.Filled.RecordVoiceOver, stringResource(R.string.tts_settings), stringResource(R.string.tts_settings_desc), onTtsSettingsClick),
                    MenuItemData(Icons.Filled.Science, stringResource(R.string.yandere_mode), stringResource(R.string.yandere_mode_desc), onYandereModeClick)
                ),
                isVisible = isVisible, delayMillis = 60
            )

            Spacer(modifier = Modifier.height(12.dp))

            // === 外观与数据：主题 / 聊天背景 / 帧率 / 思考设置 / Token 统计 / 数据备份 ===
            val currentFrameRate = FrameRateManager.getSavedFrameRate(context)
            SolidMenuGroup(
                items = listOf(
                    MenuItemData(Icons.Filled.Brush, stringResource(R.string.theme_mode), stringResource(R.string.theme_mode_desc), onThemeClick),
                    MenuItemData(Icons.Filled.Palette, stringResource(R.string.chat_background), stringResource(R.string.chat_background_desc), onClick = { showBackgroundDialog = true }),
                    MenuItemData(Icons.Filled.Refresh, stringResource(R.string.framerate), currentFrameRate.label, onFrameRateClick),
                    ThinkingSettingsEntry(),
                    MenuItemData(Icons.Filled.Token, stringResource(R.string.token_usage), stringResource(R.string.token_usage_desc), onTokenUsageClick),
                    MenuItemData(Icons.Filled.SaveAlt, stringResource(R.string.data_backup), stringResource(R.string.data_backup_desc), onDataBackupClick)
                ),
                isVisible = isVisible, delayMillis = 100
            )

            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    if (showBackgroundDialog) {
        ChatBackgroundPickerDialog(
            currentKey = getChatBackgroundKey(context),
            onDismiss = { showBackgroundDialog = false },
            onSelect = { key -> setChatBackgroundKey(context, key); showBackgroundDialog = false }
        )
    }
}

// ============================================================================
// 思考设置入口（带弹窗）
// ============================================================================

@Composable
private fun ThinkingSettingsEntry(): MenuItemData {
    val context = LocalContext.current
    val settingsStore = remember { AppSettingsStore(context) }
    val showReasoning by settingsStore.showReasoningFlow.collectAsState(initial = false)
    val showDialog = remember { mutableStateOf(false) }

    if (showDialog.value) {
        ThinkingSettingsDialog(showDialog, settingsStore)
    }

    return MenuItemData(
        icon = Icons.Filled.Psychology,
        title = stringResource(R.string.thinking_settings),
        subtitle = if (showReasoning) stringResource(R.string.thinking_enabled) else stringResource(R.string.thinking_disabled),
        onClick = { showDialog.value = true }
    )
}

// 简化：仅保留一个开关，默认关闭，切换即时生效，不再有自动折叠/字段名等易出 bug 的子设置。
@Composable
private fun ThinkingSettingsDialog(showDialog: MutableState<Boolean>, settingsStore: AppSettingsStore) {
    val scope = rememberCoroutineScope()
    val showReasoning by settingsStore.showReasoningFlow.collectAsState(initial = false)

    AlertDialog(
        onDismissRequest = { showDialog.value = false },
        title = { Text("思考设置", fontWeight = FontWeight.Bold) },
        text = {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("思考过程默认关闭", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(
                    checked = showReasoning,
                    onCheckedChange = { checked -> scope.launch { settingsStore.setShowReasoning(checked) } }
                )
            }
        },
        confirmButton = { TextButton(onClick = { showDialog.value = false }) { Text("关闭") } }
    )
}
