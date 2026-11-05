package com.lianyu.ai.uicommon.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.common.VoiceWaveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 语音气泡。
 *
 * - 播放交给全局 [VoicePlayer]：同一时间只播一条，滑出屏幕不会中断。
 * - 波形来自合成时保存的真实振幅（[VoiceWaveform]），播放时按进度逐条点亮；
 *   没有数据（如云端 mp3）时用按路径固定的伪波形兜底。
 * - 不再有常驻动画：只有正在播放的那一个气泡每 80ms 刷新一次进度。
 * - 音频文件丢失（被清理）时显示"点击重新合成"，点击触发 [onResynthesize]。
 */
@Composable
fun VoiceMessageBubble(
    audioPath: String,
    duration: Int,
    isUser: Boolean,
    text: String? = null,
    onPlayComplete: (() -> Unit)? = null,
    onResynthesize: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val iconColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val waveColor = if (isUser) Color(0xFF34C759).copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)

    val currentPath by VoicePlayer.currentPath.collectAsState()
    val globalPlaying by VoicePlayer.isPlaying.collectAsState()
    val isCurrent = currentPath == audioPath
    val isPlaying = isCurrent && globalPlaying

    // 文件是否还在 / 波形数据：都在后台线程读，默认"在"以免首帧闪一下
    val fileExists by produceState(true, audioPath) {
        value = withContext(Dispatchers.IO) { File(audioPath).exists() }
    }
    val waveform by produceState<ByteArray?>(null, audioPath) {
        value = withContext(Dispatchers.IO) { VoiceWaveform.load(audioPath) }
    }

    var positionMs by remember { mutableIntStateOf(0) }
    LaunchedEffect(isCurrent, isPlaying) {
        if (!isCurrent) {
            positionMs = 0
        } else if (isPlaying) {
            while (true) {
                positionMs = VoicePlayer.positionMs()
                delay(80)
            }
        }
    }

    val totalMs = if (isCurrent) VoicePlayer.durationMs().takeIf { it > 0 } ?: (duration.coerceAtLeast(1) * 1000) else duration.coerceAtLeast(1) * 1000
    val progress = if (isCurrent) (positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 1f
    val barCount = (12 + duration * 3).coerceIn(16, 34)

    fun handleTap() {
        if (!fileExists) {
            onResynthesize?.invoke()
            return
        }
        VoicePlayer.toggle(audioPath, onPlayComplete)
    }

    Column(
        modifier = modifier
            .widthIn(max = 230.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { handleTap() }
            .background(bubbleColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                imageVector = when {
                    !fileExists -> Icons.Filled.Refresh
                    isPlaying -> Icons.Filled.Pause
                    else -> Icons.Filled.PlayArrow
                },
                contentDescription = when {
                    !fileExists -> "重新合成"
                    isPlaying -> "暂停"
                    else -> "播放"
                },
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(4.dp))

            if (fileExists) {
                VoiceBars(
                    bars = waveform,
                    seed = audioPath.hashCode(),
                    barCount = barCount,
                    progress = progress,
                    playedColor = waveColor,
                    idleColor = waveColor.copy(alpha = 0.3f)
                )
            } else {
                Text(
                    text = "语音已被清理，点击重新合成",
                    fontSize = 12.sp,
                    color = iconColor.copy(alpha = 0.8f),
                    maxLines = 1
                )
            }

            if (fileExists) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = formatDuration(if (isCurrent) (totalMs / 1000).coerceAtLeast(1) else duration),
                    fontSize = 12.sp,
                    color = iconColor.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
        }

        // 语音转文字内容：与语音条一并展示在同一气泡内
        if (!text.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = text,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                color = iconColor
            )
        }
    }
}

@Composable
private fun VoiceBars(
    bars: ByteArray?,
    seed: Int,
    barCount: Int,
    progress: Float,
    playedColor: Color,
    idleColor: Color
) {
    Canvas(modifier = Modifier.width((barCount * 4).dp).height(24.dp)) {
        val slot = size.width / barCount
        val barWidth = slot * 0.62f
        for (i in 0 until barCount) {
            val amplitude = amplitudeAt(bars, seed, i, barCount)
            val barHeight = size.height * (0.18f + 0.82f * amplitude)
            val x = slot * i + (slot - barWidth) / 2f
            val y = (size.height - barHeight) / 2f
            val played = (i + 1).toFloat() / barCount <= progress + 0.0001f
            drawRoundRect(
                color = if (played) playedColor else idleColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

/** 第 i 根条的高度（0..1）：优先用真实波形，没有就用按 seed 固定的伪随机值。 */
private fun amplitudeAt(bars: ByteArray?, seed: Int, i: Int, barCount: Int): Float {
    if (bars != null && bars.isNotEmpty()) {
        val from = i * bars.size / barCount
        val to = ((i + 1) * bars.size / barCount).coerceAtLeast(from + 1).coerceAtMost(bars.size)
        var max = 0
        for (k in from until to) {
            val v = bars[k].toInt() and 0xFF
            if (v > max) max = v
        }
        return max / 255f
    }
    val h = (seed xor (i * 0x45d9f3b)) * 0x2c1b3c6d
    return 0.25f + 0.6f * (((h ushr 16) and 0xFF) / 255f)
}

private fun formatDuration(seconds: Int): String {
    return if (seconds < 60) "${seconds}\"" else "${seconds / 60}'${seconds % 60}\""
}
