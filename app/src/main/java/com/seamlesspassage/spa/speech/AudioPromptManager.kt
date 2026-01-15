package com.seamlesspassage.spa.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 语音提示管理：仅使用本地 Android TextToSpeech。
 */
class AudioPromptManager(context: Context) {
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context) { status ->
            Log.d(TAG, "TTS onInit status=$status")
            Log.d(TAG, "TTS defaultEngine=${tts?.defaultEngine}")
            val engines = tts?.engines
            Log.d(TAG, "TTS available engines=${engines?.joinToString { it.name }}")
            if (status == TextToSpeech.SUCCESS) {
                // 直接设置为简体中文（普通话）
                val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                Log.d(TAG, "Set Simplified Chinese result=$result")
            } else {
                Log.e(TAG, "TTS init failed: status=$status")
            }
        }
    }

    fun speak(text: String) {
        val engine = tts
        if (engine == null) {
            Log.e(TAG, "Local TTS engine is null, cannot speak")
            return
        }
        Log.d(TAG, "Local TTS speak: $text")
        engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            System.currentTimeMillis().toString()
        )
    }

    fun shutdown() {
        Log.d(TAG, "shutdown TTS")
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "AudioPromptManager"
    }
}
