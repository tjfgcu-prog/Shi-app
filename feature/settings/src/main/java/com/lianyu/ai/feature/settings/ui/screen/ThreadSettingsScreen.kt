package com.lianyu.ai.feature.settings.ui.screen

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.feature.settings.R
import com.lianyu.ai.uicommon.theme.PetalPrimary

/**
 * 线程页：语音合成、记忆向量、本地大模型的线程数设置。
 * 从原来的 AI 设置页搬过来，选项和保存逻辑不变。
 */
@Composable
fun ThreadSettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val textPrimaryColor = MaterialTheme.colorScheme.onSurface
    val textSecondaryColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        // ====== Petal Soft Top App Bar（和 AI 页一致）======
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
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onNavigateBack() }
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = PetalPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Text(
                text = "线程",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimaryColor
            )

            Spacer(modifier = Modifier.size(40.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        var ttsThreads by remember { mutableStateOf(com.lianyu.ai.common.PerfPrefs.ttsThreads(context)) }
        var embeddingThreads by remember { mutableStateOf(com.lianyu.ai.common.PerfPrefs.embeddingThreads(context)) }
        var llmThreads by remember { mutableStateOf(com.lianyu.ai.common.PerfPrefs.llmThreads(context)) }
        val cpuCores = remember { com.lianyu.ai.common.PerfPrefs.cpuCores() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "线程数（高级）",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = textPrimaryColor
            )
            Text(
                text = "线程越多通常越快，但会和大模型抢 CPU、更耗电。本机共 $cpuCores 个核心。",
                fontSize = 11.sp,
                color = textSecondaryColor
            )
            Text(text = "语音合成线程（默认 2，下一句话生效）", fontSize = 12.sp, color = textSecondaryColor)
            ThreadChipRow(
                options = (1..cpuCores).toList().take(8),
                selected = ttsThreads,
                labelOf = { it.toString() },
                textSecondaryColor = textSecondaryColor,
                onSelect = {
                    ttsThreads = it
                    com.lianyu.ai.common.PerfPrefs.setTtsThreads(context, it)
                }
            )
            Text(text = "记忆向量线程（默认自动，重启 App 后生效）", fontSize = 12.sp, color = textSecondaryColor)
            ThreadChipRow(
                options = listOf(0) + (1..cpuCores).toList().take(7),
                selected = embeddingThreads,
                labelOf = { if (it == 0) "自动" else it.toString() },
                textSecondaryColor = textSecondaryColor,
                onSelect = {
                    embeddingThreads = it
                    com.lianyu.ai.common.PerfPrefs.setEmbeddingThreads(context, it)
                }
            )
            Text(text = "大模型线程（默认自动，改完下次生成回复时会重新加载模型）", fontSize = 12.sp, color = textSecondaryColor)
            ThreadChipRow(
                options = listOf(0) + (1..cpuCores).toList().take(7),
                selected = llmThreads,
                labelOf = { if (it == 0) "自动" else it.toString() },
                textSecondaryColor = textSecondaryColor,
                onSelect = {
                    llmThreads = it
                    com.lianyu.ai.common.PerfPrefs.setLlmThreads(context, it)
                }
            )
            Text(
                text = "自动 = 交给 llama 库决定（按它的来源，8 核手机上通常是 4 个）。线程不是越多越快：超过大核数量后反而会变慢、更烫，建议从 4 开始试。改线程数会让下一次回复多等一次模型加载（约 15 到 20 秒）。",
                fontSize = 11.sp,
                color = textSecondaryColor
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/** 一排可选的线程数按钮（与"上下文长度"按钮同样式）。 */
@Composable
private fun ThreadChipRow(
    options: List<Int>,
    selected: Int,
    labelOf: (Int) -> String,
    textSecondaryColor: Color,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSelected = selected == option
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) PetalPrimary else MaterialTheme.colorScheme.surface)
                    .clickable { onSelect(option) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = labelOf(option),
                    fontSize = 12.sp,
                    color = if (isSelected) Color.White else textSecondaryColor
                )
            }
        }
    }
}
