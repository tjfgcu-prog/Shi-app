package com.lianyu.ai.common

import java.io.File
import kotlin.math.sqrt

/**
 * 语音气泡的真实波形数据。
 *
 * 合成语音时由样本算出 [BARS] 个 0..255 的振幅条，保存为与音频同名的 `.wf` 伴随文件
 * （不改数据库）。老语音没有伴随文件时，WAV 会现算并补写；其他格式（如云端 mp3）返回 null，
 * 由界面用稳定的伪波形兜底。
 */
object VoiceWaveform {
    const val BARS = 64
    private const val WAV_HEADER_BYTES = 44

    private val cache = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 200
    }

    private fun sidecar(audioPath: String) = File("$audioPath.wf")

    /** 由归一化样本（-1..1）计算 [BARS] 个振幅条（0..255，按 RMS 并相对最大值归一）。 */
    fun fromSamples(samples: FloatArray): ByteArray {
        val out = ByteArray(BARS)
        if (samples.isEmpty()) return out
        val rmsValues = FloatArray(BARS)
        var maxRms = 0f
        for (b in 0 until BARS) {
            val start = (b.toLong() * samples.size / BARS).toInt()
            val end = ((b + 1).toLong() * samples.size / BARS).toInt()
                .coerceAtLeast(start + 1).coerceAtMost(samples.size)
            var sum = 0.0
            for (i in start until end) {
                val v = samples[i]
                sum += v * v
            }
            val rms = sqrt(sum / (end - start)).toFloat()
            rmsValues[b] = rms
            if (rms > maxRms) maxRms = rms
        }
        if (maxRms <= 0f) return out
        for (b in 0 until BARS) {
            out[b] = ((rmsValues[b] / maxRms).coerceIn(0f, 1f) * 255f).toInt().toByte()
        }
        return out
    }

    fun write(audioPath: String, bars: ByteArray) {
        runCatching { sidecar(audioPath).writeBytes(bars) }
    }

    /** 读取波形；拿不到返回 null。会做文件 IO，请在后台线程调用。 */
    fun load(audioPath: String): ByteArray? {
        synchronized(cache) { cache[audioPath] }?.let { return it }
        var result: ByteArray? = null
        val side = sidecar(audioPath)
        if (side.exists()) {
            result = runCatching { side.readBytes() }.getOrNull()?.takeIf { it.size == BARS }
        }
        if (result == null && audioPath.endsWith(".wav", ignoreCase = true)) {
            result = fromWavFile(File(audioPath))
            if (result != null) write(audioPath, result)
        }
        if (result != null) synchronized(cache) { cache[audioPath] = result }
        return result
    }

    /** 读 16-bit 单声道 PCM WAV（44 字节头）并计算波形。 */
    private fun fromWavFile(file: File): ByteArray? {
        return try {
            if (!file.exists() || file.length() <= WAV_HEADER_BYTES) return null
            val bytes = file.readBytes()
            val count = (bytes.size - WAV_HEADER_BYTES) / 2
            val samples = FloatArray(count)
            var p = WAV_HEADER_BYTES
            for (i in 0 until count) {
                val lo = bytes[p].toInt() and 0xFF
                val hi = bytes[p + 1].toInt()
                samples[i] = ((hi shl 8) or lo).toShort() / 32768f
                p += 2
            }
            fromSamples(samples)
        } catch (_: Exception) {
            null
        }
    }
}
