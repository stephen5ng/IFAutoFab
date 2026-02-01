package com.ifautofab

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.*

class TTSManager(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isReady = false

    private val pendingMessages = mutableListOf<String>()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isReady = true
            synchronized(pendingMessages) {
                pendingMessages.forEach { speak(it) }
                pendingMessages.clear()
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        
        if (isReady) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
        } else {
            synchronized(pendingMessages) {
                pendingMessages.add(text)
            }
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
