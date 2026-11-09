package com.lianyu.ai.network.tts

enum class TtsProvider(val displayName: String, val description: String) {
    ANDROID("系统TTS", "使用系统内置语音引擎，无需配置"),
    LOCAL("本地漫剧女声", "sherpa-onnx 端上推理，离线可用，AI漫剧风格音色"),
    BREEZE("本地 Breeze", "CrispASR 端上推理，手动导入 Breeze TTS 2 的 GGUF 文件，离线可用"),
    CLOUD("云端漫剧女声", "MiniMax Speech-02 大模型，AI漫剧级情感语音，支持声音克隆")
}
