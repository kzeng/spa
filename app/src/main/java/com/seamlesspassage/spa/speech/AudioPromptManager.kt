package com.seamlesspassage.spa.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class AudioPromptManager(context: Context) {
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINA
            }
        }
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, System.currentTimeMillis().toString())
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
