package com.lianyu.ai.feature.profile





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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.layout.statusBars

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.BugReport



import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person

import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings

import androidx.compose.material.icons.filled.TheaterComedy
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lianyu.ai.common.AppSettingsStore
import com.lianyu.ai.common.FrameRateManager
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.uicommon.component.ChatBackgroundPickerDialog
import com.lianyu.ai.uicommon.component.getChatBackgroundKey
import com.lianyu.ai.uicommon.component.setChatBackgroundKey

import kotlinx.coroutines.launch

/**
 * 总设置页 — 收纳全部次要设置项，按功能领域分组。
 *
 *   0. 自己设定    — 我的信息、各角色专属设定
 *   1. 记忆与管理  — 记忆管理、上下文记忆，下面同一个框里是各角色头像（点头像设置该角色的角色扮演）
 *   2. AI 与互动  — API 设置、语音、病娇模式
 *   3. 实时日志    — 应用所有日志集中在这里
 *   4. 外观与数据  — 主题模式、聊天背景、帧率、思考设置、Token 统计、数据备份
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
    
    onYandereModeClick: () -> Unit = {},
    // 点角色头像 → 该角色的角色扮演设置
    onRoleplayCompanionClick: (Long) -> Unit = {},
    // 实时日志
    onLiveLogClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val companions by viewModel<SelfProfileViewModel>().companions.collectAsState()
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
                isVisible = isVisible, delayMillis = 20,
                extraContent = {
                    RoleplayAvatarBlock(companions = companions, onCompanionClick = onRoleplayCompanionClick)
                }
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

            // === 实时日志 ===
            SolidMenuGroup(
                items = listOf(
                    MenuItemData(Icons.Filled.BugReport, "实时日志", "查看应用运行日志：崩溃、本地模型、语音、记忆、网络…", onLiveLogClick)
                ),
                isVisible = isVisible, delayMillis = 80
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
// 角色扮演设置：各角色头像一排（放在"记忆管理 / 上下文记忆"同一个框里）
// ============================================================================

@Composable
private fun RoleplayAvatarBlock(
    companions: List<CompanionEntity>,
    onCompanionClick: (Long) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.TheaterComedy, "角色扮演设置", Modifier.size(24.dp), tint = Color(0xFF07C160))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "角色扮演设置",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp),
                    color = colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    if (companions.isEmpty()) "还没有角色" else "点头像，设置该角色的剧情/恋爱比例、回复长度、状态卡和世界书",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colorScheme.onSurfaceVariant
                )
            }
        }
        if (companions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(companions, key = { it.id }) { c ->
                    Column(
                        modifier = Modifier
                            .width(60.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onCompanionClick(c.id) }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE5E5E5)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (c.avatarUrl != null) {
                                AsyncImage(
                                    model = c.avatarUrl,
                                    contentDescription = c.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(Icons.Filled.Person, c.name, tint = Color(0xFFAAAAAA), modifier = Modifier.size(24.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            c.name,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
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
