package com.lianyu.ai.network.tts

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 合成语音的后处理：去掉首尾静音 + 统一响度。
 * 纯函数，只作用于本地合成得到的 PCM（-1..1）。
 */
object AudioPostProcess {
    /** 低于峰值的 3% 视为静音 */
    private const val SILENCE_RELATIVE = 0.03f
    /** 开头保留 120ms、结尾保留 200ms，避免吞字 */
    private const val HEAD_KEEP_SECONDS = 0.12f
    private const val TAIL_KEEP_SECONDS = 0.20f
    /** 有声部分的目标 RMS（约 -20 dBFS） */
    private const val TARGET_RMS = 0.10f
    private const val MIN_GAIN = 0.5f
    private const val MAX_GAIN = 4.0f
    private const val PEAK_LIMIT = 0.95f

    fun process(samples: FloatArray, sampleRate: Int): FloatArray {
        if (samples.isEmpty() || sampleRate <= 0) return samples

        var peak = 0f
        for (v in samples) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak < 1e-4f) return samples

        val threshold = peak * SILENCE_RELATIVE
        var first = 0
        while (first < samples.size && abs(samples[first]) < threshold) first++
        var last = samples.size - 1
        while (last > first && abs(samples[last]) < threshold) last--
        if (first >= last) return samples

        val start = (first - (HEAD_KEEP_SECONDS * sampleRate).toInt()).coerceAtLeast(0)
        val end = (last + (TAIL_KEEP_SECONDS * sampleRate).toInt()).coerceAtMost(samples.size - 1)
        val trimmed = samples.copyOfRange(start, end + 1)

        // 只按"有声部分"算响度，静音和停顿不拉低平均值
        var sum = 0.0
        var count = 0
        for (v in trimmed) {
            if (abs(v) >= threshold) {
                sum += v * v
                count++
            }
        }
        if (count == 0) return trimmed
        val rms = sqrt(sum / count).toFloat()
        var gain = (TARGET_RMS / rms).coerceIn(MIN_GAIN, MAX_GAIN)
        gain = minOf(gain, PEAK_LIMIT / peak)
        if (abs(gain - 1f) < 0.05f) return trimmed

        for (i in trimmed.indices) {
            trimmed[i] = (trimmed[i] * gain).coerceIn(-1f, 1f)
        }
        return trimmed
    }
}
