package com.lianyu.ai.feature.settings.ui.screen

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.shape.RoundedCornerShape


import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore




import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults



import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme








import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight



import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.database.model.ApiConfig
import com.lianyu.ai.database.model.ApiProvider
import com.lianyu.ai.feature.settings.ui.viewmodel.SettingsViewModel




// ============================================================
// Petal Color Constants
// ============================================================

internal val PetalBackgroundStart = Color(0xFFFBF9F8)

internal val PetalPrimary = Color(0xFF894C5C)
internal val PetalPrimaryContainer = Color(0xFFF4A7B9)
internal val PetalOnPrimaryContainer = Color(0xFF733949)
internal val PetalSurface = Color(0xFFFFFFFF)
internal val PetalSurfaceContainer = Color(0xFFEFEDED)
internal val PetalSurfaceContainerLow = Color(0xFFF5F3F3)
internal val PetalSecondaryContainer = Color(0xFFEBDCDF)
internal val PetalOnSurface = Color(0xFF1B1C1C)
internal val PetalOnSurfaceVariant = Color(0xFF524346)
internal val PetalOutlineVariant = Color(0xFFD6C1C5)
internal val PetalError = Color(0xFFBA1A1A)
internal val PetalErrorContainer = Color(0xFFFFDAD6)
internal val PetalGreen = Color(0xFF10A37F)
internal val PetalGreenLight = Color(0xFFE8F5E9)
internal val PetalOrange = Color(0xFFFFA726)

// ═══ 自定义中继 error code mapping ═══
private fun mapErrorCode(code: String): String = when (code) {
    "upstream_unreachable" -> "上游不通"
    "account_blocked" -> "已冻结"
    "key_disabled" -> "密钥已禁用"
    "network_error" -> "网络不通"
    "timeout" -> "超时"
    else -> "失败"
}

// ============================================================
// PetalStatChip - 状态标签小组件
// ============================================================

@Composable
fun PetalStatChip(icon: String, text: String, color: Color, isDarkTheme: Boolean) {
    val bgColor = if (isDarkTheme) color.copy(alpha = 0.12f) else PetalSurfaceContainerLow

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = icon,
                color = color,
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}





// ============================================================
// PetalSavedApiCard - 已保存的其他 API 配置卡片
// ============================================================

@Composable
fun PetalSavedApiCard(
    config: ApiConfig,
    connectionResult: SettingsViewModel.ConnectionResult,
    isExpanded: Boolean,
    isActive: Boolean,
    onExpandToggle: () -> Unit,
    onEdit: (ApiConfig) -> Unit,
    onDelete: () -> Unit,
    onTest: (ApiConfig) -> Unit,
    onToggleEnabled: () -> Unit,
    onFetchModels: (String, String, String, Boolean) -> Unit,
    fetchedModels: Map<String, List<String>>,
    modelFetchStates: Map<String, SettingsViewModel.ModelFetchState>,
    testedConfigs: Map<String, ApiConfig>,
    connectionKey: String,
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    balanceInfo: com.lianyu.ai.network.AiService.BalanceInfo? = null,
    balanceQueryFailed: Boolean = false,
    onQueryBalance: (() -> Unit)? = null
) {
    var editingConfig by remember { mutableStateOf<ApiConfig?>(null) }
    val cardColor = if (isDarkTheme) PetalPrimaryContainer.copy(alpha = 0.08f) else PetalSecondaryContainer

    // 余额查询：连接成功后才自动查一次
    var balanceQueried by remember { mutableStateOf(false) }
    val isConnected = connectionResult.status == SettingsViewModel.ConnectionStatus.CONNECTED
    LaunchedEffect(isConnected) {
        if (isConnected && !balanceQueried) {
            onQueryBalance?.invoke()
            balanceQueried = true
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { onExpandToggle() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.provider.displayName,
                        color = textPrimaryColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    if (config.name.isNotEmpty()) {
                        Text(
                            text = config.name,
                            color = textSecondaryColor,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
    // Active indicator：始终显示，启用中为绿色，未启用为灰色
    PetalStatChip(
        icon = if (isActive) "\u2713" else "\u25CB",
        text = if (isActive) "启用中" else "未启用",
        color = if (isActive) PetalGreen else textSecondaryColor,
        isDarkTheme = isDarkTheme
    )
    Spacer(modifier = Modifier.width(4.dp))

                    // Connection status indicator
                    val statusColor = when (connectionResult.status) {
                        SettingsViewModel.ConnectionStatus.CONNECTED -> PetalGreen
                        SettingsViewModel.ConnectionStatus.FAILED -> PetalError
                        SettingsViewModel.ConnectionStatus.TESTING -> PetalOrange
                        SettingsViewModel.ConnectionStatus.UNKNOWN -> textSecondaryColor
                    }
                    val statusText = when (connectionResult.status) {
                        SettingsViewModel.ConnectionStatus.CONNECTED ->
                            if (connectionResult.latencyMs > 0) "已连接 ${connectionResult.latencyMs}ms" else "已连接"
                        SettingsViewModel.ConnectionStatus.FAILED -> {
                            val err = connectionResult.errorCode
                            if (err != null) mapErrorCode(err) else "失败"
                        }
                        SettingsViewModel.ConnectionStatus.TESTING -> "测试中"
                        SettingsViewModel.ConnectionStatus.UNKNOWN -> "未测试"
                    }
                    PetalStatChip(
                        icon = "\u25CF",
                        text = statusText,
                        color = statusColor,
                        isDarkTheme = isDarkTheme
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isExpanded) "收起" else "展开",
                        tint = textSecondaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Expanded content
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))

                // Model info
                if (config.provider == ApiProvider.PARTNER) {
                    Text("模型: 自动分配", color = PetalGreen, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (connectionResult.status == SettingsViewModel.ConnectionStatus.CONNECTED) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Column {
                            Text("延迟: ${connectionResult.latencyMs}ms | 状态: 已连接",
                                color = PetalGreen, fontSize = 12.sp)
                            connectionResult.clientId?.let { cid ->
                                Text("Client ID: $cid",
                                    color = PetalGreen.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                            connectionResult.groupName?.let { gn ->
                                Text("组: $gn | 剩余: $${String.format("%.2f", connectionResult.remainingQuota)}",
                                    color = PetalGreen.copy(alpha = 0.8f), fontSize = 11.sp)
                            }
                            if (connectionResult.rpmLimit > 0) {
                                Text("RPM: ${connectionResult.rpmLimit} | 日限额: $${connectionResult.dailyLimit ?: "∞"}",
                                    color = PetalGreen.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                        }
                    } else if (connectionResult.status == SettingsViewModel.ConnectionStatus.FAILED) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val errCode = connectionResult.errorCode ?: "unknown"
                        val errLabel = mapErrorCode(errCode)
                        Column {
                            Text("$errLabel | error=${errCode} | latency=${connectionResult.latencyMs}ms",
                                color = PetalError, fontSize = 12.sp)
                            connectionResult.clientId?.let { cid ->
                                Text("Client ID: $cid",
                                    color = PetalError.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                            connectionResult.errorMessage?.let { msg ->
                                Text(msg, color = PetalError.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                } else if (config.model.isNotEmpty()) {
                    Text(
                        text = "模型: ${config.model}",
                        color = textSecondaryColor,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Base URL
                if (config.baseUrl.isNotEmpty() && config.provider != ApiProvider.PARTNER) {
                    Text(
                        text = "地址: ${config.baseUrl}",
                        color = textSecondaryColor.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Balance info
                if (balanceInfo != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(PetalSurfaceContainerLow)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "余额查询",
                            color = textSecondaryColor,
                            fontSize = 12.sp
                        )
                        if (balanceInfo.remainingBalance != null) {
                            val bal = balanceInfo.remainingBalance
                            Text(
                                text = "$${"%.2f".format(bal)}",
                                color = PetalGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text = "查询中...",
                                color = textSecondaryColor,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                } else if (balanceQueryFailed) {
                    Text(
                        text = "余额查询失败",
                        color = PetalError,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = PetalError, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = {
                        editingConfig = testedConfigs[connectionKey] ?: config
                    }) {
                        Text("编辑", color = PetalPrimary, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = { onTest(config) }) {
                        Text("测试", color = PetalPrimary, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = onToggleEnabled) {
                        Text(if (isActive) "停止" else "启用", color = if (isActive) PetalError else PetalGreen, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    // 编辑弹窗 - INLINE inside PetalSavedApiCard
    editingConfig?.let { editing ->
        val providerModels = fetchedModels[editing.provider.name] ?: emptyList()
        val fetchState = modelFetchStates[editing.provider.name] ?: SettingsViewModel.ModelFetchState()
        ApiConfigEditDialog(
            config = editing,
            connectionResult = connectionResult,
            onDismiss = { editingConfig = null },
            onSave = { updated: ApiConfig ->
                onEdit(updated)
                editingConfig = null
            },
            onTest = { testConfig: ApiConfig -> onTest(testConfig) },
            isDarkTheme = isDarkTheme,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor,
            onFetchModels = { baseUrl: String, apiKey: String, skipCertVerify: Boolean ->
                onFetchModels(baseUrl, apiKey, editing.provider.name, skipCertVerify)
            },
            availableModels = providerModels,
            modelFetchState = fetchState
        )
    }
}

// ============================================================
// PetalAddApiVisionCard - 「添加API配置」与「视觉模型设置」合并卡片
// ============================================================

@Composable
fun PetalAddApiVisionCard(
    visionSubtitle: String,
    onAddApiClick: () -> Unit,
    onVisionClick: () -> Unit,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        PetalAiEntryRow(
            iconContent = {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "添加API",
                    tint = PetalPrimary,
                    modifier = Modifier.size(24.dp)
                )
            },
            title = "添加API配置",
            subtitle = null,
            onClick = onAddApiClick,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor
        )

        // 两行之间的分隔线（从文字起点开始，不贯穿图标区）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 80.dp, end = 20.dp)
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outline)
        )

        PetalAiEntryRow(
            iconContent = { Text(text = "👁", fontSize = 22.sp) },
            title = "视觉模型设置",
            subtitle = visionSubtitle,
            onClick = onVisionClick,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor
        )
    }
}

/** 合并卡片里的一行：左侧圆角图标块 + 标题（可带副标题）+ 右侧箭头。 */
@Composable
private fun PetalAiEntryRow(
    iconContent: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PetalPrimaryContainer.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            iconContent()
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = textPrimaryColor
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = textSecondaryColor
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = textSecondaryColor,
            modifier = Modifier.size(20.dp)
        )
    }
}
