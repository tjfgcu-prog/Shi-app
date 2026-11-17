package com.lianyu.ai.feature.chat.ui.screen

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.database.repository.CompanionRepository
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.feature.chat.data.ChatDetailSettingsStore
import com.lianyu.ai.feature.chat.roleplay.RoleplayPromptBuilder
import com.lianyu.ai.feature.chat.roleplay.WorldBookStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 某个角色的角色扮演设置页（从 总设置 → 点角色头像 进入）：
 * 剧情 ↔ 恋爱比例、回复长度、状态卡、世界书入口。
 *
 * 「角色扮演模式」总开关仍在聊天详情页里；没开的时候这里的设置也能改，但要开了才会生效。
 */
@Composable
fun RoleplaySettingsScreen(
    companionId: Long,
    onNavigateBack: () -> Unit,
    onOpenWorldBook: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ChatDetailSettingsStore(context) }
    // 设置读出来之前是 null：这一瞬间什么都不画，避免先闪一下默认值 / "还没有开启"的提示再跳成真实值
    val loadedSettings by store.settingsFlow(companionId).collectAsState(initial = null)
    val settings = loadedSettings

    var companionName by remember { mutableStateOf("") }
    LaunchedEffect(companionId) {
        companionName = withContext(Dispatchers.IO) {
            runCatching {
                ServiceRegistry.get(CompanionRepository::class.java)?.getCompanionById(companionId)?.name
            }.getOrNull().orEmpty()
        }
    }

    // 世界书条数（首次进入时从文件加载）
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { WorldBookStore.init(context) } }
    val worldBook by WorldBookStore.entries.collectAsState()
    val worldBookCount = worldBook.count { it.companionId == 0L || it.companionId == companionId }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
                    text = if (companionName.isBlank()) "角色扮演设置" else "${companionName}的角色扮演设置",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            if (settings != null) {
                if (!settings.roleplayMode) {
                    Text(
                        text = "这个角色还没有开启「角色扮演模式」。到该角色的聊天详情页打开开关后，下面的设置才会生效。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    RatioSetting(
                        ratio = settings.romanceRatio,
                        onRatioChange = { v -> scope.launch { store.updateSettings(companionId) { it.copy(romanceRatio = v) } } }
                    )
                    LengthSetting(
                        lengthLevel = settings.replyLength,
                        onLengthChange = { v -> scope.launch { store.updateSettings(companionId) { it.copy(replyLength = v) } } }
                    )
                    StateCardSetting(
                        stateCard = settings.stateCard,
                        onStateCardChange = { v -> scope.launch { store.updateSettings(companionId) { it.copy(stateCard = v) } } }
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenWorldBook)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "世界书", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                text = "共 $worldBookCount 条，点击管理（含洪荒起始条目）",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(text = "›", fontSize = 18.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 剧情 ↔ 恋爱 比例滑块（0 = 纯剧情，10 = 纯恋爱） */
@Composable
private fun RatioSetting(ratio: Int, onRatioChange: (Int) -> Unit) {
    var draftRatio by remember(ratio) { mutableStateOf(ratio.toFloat()) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "剧情 ← ${draftRatio.toInt()} → 恋爱：${RoleplayPromptBuilder.ratioLabel(draftRatio.toInt())}",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = draftRatio,
            onValueChange = { draftRatio = it },
            onValueChangeFinished = { onRatioChange(draftRatio.toInt().coerceIn(0, 10)) },
            valueRange = 0f..10f,
            steps = 9,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

/** 回复长度档位 */
@Composable
private fun LengthSetting(lengthLevel: Int, onLengthChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = "回复长度（本地模型生成很慢，字数越多等得越久）",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("简短 40-80" to 0, "标准 80-150" to 1, "长篇 150-300" to 2).forEach { (label, level) ->
                val selected = lengthLevel == level
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onLengthChange(level) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 状态卡：每轮都会带给 AI，可随时手改 */
@Composable
private fun StateCardSetting(stateCard: String, onStateCardChange: (String) -> Unit) {
    var draftCard by remember(stateCard) { mutableStateOf(stateCard) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "状态卡（境界、所在地、持有物、关系、目标、感情进度；每轮都会带给 AI，可随时手改）",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = draftCard,
            onValueChange = { draftCard = it },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = {
                draftCard = "境界：\n所在地：\n持有：\n同行关系：\n当前目标：\n感情进度："
            }) { Text("填入模板") }
            TextButton(
                enabled = draftCard != stateCard,
                onClick = { onStateCardChange(draftCard) }
            ) { Text("保存") }
        }
    }
}
