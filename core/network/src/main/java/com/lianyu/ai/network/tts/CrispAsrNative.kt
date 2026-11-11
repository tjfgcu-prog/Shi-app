package com.lianyu.ai.network.tts

import com.sun.jna.Library
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * libcrispasr.so 的 C 接口（用 JNA 直连，不需要写 JNI/C++）。
 *
 * 函数名和参数与 CrispASR 官方 C-ABI 一一对应：
 *   crispasr_session_open(const char* model_path, int n_threads) -> session*
 *   crispasr_session_set_codec_path(session*, const char* path) -> int
 *   crispasr_session_set_voice(session*, const char* ref_wav_path, const char* ref_text_or_null) -> int（声音克隆）
 *   crispasr_session_synthesize(session*, const char* text, int* out_n_samples) -> float*（malloc 出来的单声道 PCM）
 *   crispasr_pcm_free(float*)
 *
 * 注意：JNA 按方法名找符号，所以这里的名字不能改。
 * 调用某个函数时才会解析符号，so 里缺这个函数只会在调用时报错，不影响加载。
 */
internal interface CrispAsrNative : Library {
    fun crispasr_session_open(modelPath: String, nThreads: Int): Pointer?
    fun crispasr_session_close(session: Pointer)
    fun crispasr_session_set_codec_path(session: Pointer, path: String): Int
    fun crispasr_session_set_voice(session: Pointer, path: String, refText: String?): Int
    fun crispasr_session_synthesize(session: Pointer, text: String, outNSamples: IntByReference): Pointer?
    fun crispasr_session_output_sample_rate(session: Pointer): Int
    fun crispasr_session_backend(session: Pointer): String?
    fun crispasr_session_last_synth_error(session: Pointer): String?
    fun crispasr_pcm_free(pcm: Pointer)
}
