package com.ifautofab

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.*

class TTSManager(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isReady = false
    private val toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)

    private val pendingMessages = mutableListOf<String>()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "beep_trigger") {
                        toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_ACK)
                    }
                }
                override fun onError(utteranceId: String?) {}
            })
            isReady = true
            synchronized(pendingMessages) {
                pendingMessages.forEach { speak(it) }
                pendingMessages.clear()
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        
        val hasPrompt = text.contains(">")
        val textToSpeak = text.replace(">", "")
        
        if (isReady) {
            if (textToSpeak.isNotBlank()) {
                tts?.speak(textToSpeak, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
            }
            if (hasPrompt) {
                tts?.playSilentUtterance(50, TextToSpeech.QUEUE_ADD, "beep_trigger")
            }
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
        toneGenerator.release()
    }
}
