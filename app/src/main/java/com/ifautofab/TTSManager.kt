package com.ifautofab

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.preference.PreferenceManager
import java.util.*

/**
 * Text-to-Speech Manager with support for multiple engines.
 *
 * Supported engines:
 * - SYSTEM: Android's built-in TextToSpeech (default, fully functional)
 * - SHERPA: Sherpa-ONNX TTS with Kokoro model (fully functional)
 *
 * Note: Sherpa-ONNX requires:
 * 1. Static-link AAR (sherpa-onnx-static-link-onnxruntime-*.aar)
 * 2. Null AssetManager when loading from file system
 * 3. Model files in app's private files directory
 */
class TTSManager private constructor(
    private val sherpaTTS: SherpaTTSManager?,
    private val engine: Engine,
    context: Context
) : TextToSpeech.OnInitListener {
    private val tag = "TTSManager"

    private var systemTTS: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false
    private val toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)

    private val pendingMessages = mutableListOf<String>()

    enum class Engine {
        SYSTEM,
        SHERPA
    }

    companion object {
        /**
         * Factory method to create TTSManager based on user preferences.
         */
        fun create(context: Context): TTSManager {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val engineStr = prefs.getString("tts_engine", "sherpa") ?: "sherpa"
            Log.d("TTSManager", "Read tts_engine preference: '$engineStr'")

            // NOTE: When loading Sherpa-ONNX from file system, pass null AssetManager
            // See: https://github.com/k2-fsa/sherpa-onnx/issues/2562
            val engine = when (engineStr) { "sherpa" -> Engine.SHERPA else -> Engine.SYSTEM }

            Log.d("TTSManager", "Using TTS engine: $engine")

            return when (engine) {
                Engine.SHERPA -> {
                    Log.d("TTSManager", "Creating Sherpa-ONNX TTS engine")
                    val sherpa = SherpaTTSManager(context.applicationContext)
                    TTSManager(sherpa, Engine.SHERPA, context)
                }
                Engine.SYSTEM -> {
                    Log.d("TTSManager", "Creating system TTS engine")
                    TTSManager(null, Engine.SYSTEM, context)
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            systemTTS?.language = Locale.US
            systemTTS?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "beep_trigger") {
                        toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 100)
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

        Log.d(tag, "TTSManager.speak() using engine: $engine")
        val hasPrompt = text.contains(">")
        val textToSpeak = text.replace(">", "")

        when (engine) {
            Engine.SHERPA -> {
                try {
                    if (textToSpeak.isNotBlank()) {
                        sherpaTTS?.speak(textToSpeak)
                    }
                    if (hasPrompt) {
                        // Play beep after Sherpa speech
                        toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 100)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Sherpa TTS failed", e)
                }
            }
            Engine.SYSTEM -> {
                if (isReady) {
                    if (textToSpeak.isNotBlank()) {
                        systemTTS?.speak(textToSpeak, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
                    }
                    if (hasPrompt) {
                        systemTTS?.playSilentUtterance(50, TextToSpeech.QUEUE_ADD, "beep_trigger")
                    }
                } else {
                    synchronized(pendingMessages) {
                        pendingMessages.add(text)
                    }
                }
            }
        }
    }

    fun stop() {
        systemTTS?.stop()
        sherpaTTS?.stop()
    }

    fun shutdown() {
        sherpaTTS?.shutdown()
        systemTTS?.shutdown()
        systemTTS = null
        isReady = false
        toneGenerator.release()
    }
}
