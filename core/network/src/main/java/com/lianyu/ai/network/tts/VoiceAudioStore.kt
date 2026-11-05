package com.lianyu.ai.network.tts

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 语音文件的专用存放目录（filesDir/voices）。
 *
 * 以前放在 cacheDir，系统/手机管家清缓存就会丢；现在放专用目录，
 * 总量超过 [MAX_TOTAL_BYTES] 时从最旧的开始删（连同 `.wf` 波形伴随文件）。
 * 文件丢了不会崩：气泡会显示"点击重新合成"。
 */
object VoiceAudioStore {
    private const val DIR_NAME = "voices"
    private const val MAX_TOTAL_BYTES = 300L * 1024 * 1024
    private val seq = AtomicInteger(0)

    fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR_NAME).also { it.mkdirs() }

    fun newFile(context: Context, prefix: String, ext: String): File =
        File(dir(context), "${prefix}_${System.currentTimeMillis()}_${seq.incrementAndGet()}.$ext")

    private fun sidecarOf(file: File) = File(file.path + ".wf")

    /** 超出容量上限就从最旧的文件开始删。 */
    fun trim(context: Context) {
        runCatching {
            val files = dir(context).listFiles()?.filter { it.isFile && !it.name.endsWith(".wf") } ?: return
            var total = 0L
            for (f in files) {
                total += f.length()
                val wf = sidecarOf(f)
                if (wf.exists()) total += wf.length()
            }
            if (total <= MAX_TOTAL_BYTES) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= MAX_TOTAL_BYTES) break
                val wf = sidecarOf(f)
                total -= f.length() + (if (wf.exists()) wf.length() else 0L)
                f.delete()
                wf.delete()
            }
        }
    }
}
