package com.lianyu.ai.uicommon.component

import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局唯一的语音播放器：同一时间只播一条，点新的会自动停掉旧的。
 *
 * 播放器不再归某个气泡所有，所以气泡滑出屏幕也不会打断正在播放的语音。
 * 所有方法都应在主线程调用。
 */
object VoicePlayer {
    private var player: MediaPlayer? = null
    private var prepared = false

    private val _currentPath = MutableStateFlow<String?>(null)
    /** 当前"占用播放器"的音频路径（播放中或暂停中），没有则为 null */
    val currentPath: StateFlow<String?> = _currentPath.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    fun positionMs(): Int = try {
        if (prepared) player?.currentPosition ?: 0 else 0
    } catch (_: Exception) {
        0
    }

    fun durationMs(): Int = try {
        if (prepared) player?.duration ?: 0 else 0
    } catch (_: Exception) {
        0
    }

    /** 点击气泡：同一条 → 暂停/继续；另一条 → 停掉旧的、播新的。 */
    fun toggle(path: String, onComplete: (() -> Unit)? = null) {
        val current = player
        if (current != null && _currentPath.value == path) {
            if (!prepared) {
                // 还在准备中又点了一次：当作取消
                stop()
                return
            }
            try {
                if (current.isPlaying) {
                    current.pause()
                    _isPlaying.value = false
                } else {
                    current.start()
                    _isPlaying.value = true
                }
            } catch (_: Exception) {
                stop()
            }
            return
        }
        play(path, onComplete)
    }

    private fun play(path: String, onComplete: (() -> Unit)?) {
        stop()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(path)
            mp.setOnPreparedListener { ready ->
                if (player !== ready) return@setOnPreparedListener
                prepared = true
                ready.start()
                _isPlaying.value = true
            }
            mp.setOnCompletionListener { done ->
                if (player !== done) return@setOnCompletionListener
                stop()
                onComplete?.invoke()
            }
            mp.setOnErrorListener { failed, _, _ ->
                if (player === failed) stop()
                true
            }
            player = mp
            prepared = false
            _currentPath.value = path
            mp.prepareAsync()
        } catch (_: Exception) {
            runCatching { mp.release() }
            player = null
            prepared = false
            _currentPath.value = null
            _isPlaying.value = false
        }
    }

    fun stop() {
        val old = player
        player = null
        prepared = false
        _isPlaying.value = false
        _currentPath.value = null
        if (old != null) {
            runCatching {
                old.setOnPreparedListener(null)
                old.setOnCompletionListener(null)
                old.setOnErrorListener(null)
                old.release()
            }
        }
    }
}
