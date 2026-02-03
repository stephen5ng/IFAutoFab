package com.ifautofab

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*

class ListeningScreen(carContext: CarContext) : Screen(carContext) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var listeningStatus = "Listening..."
    private var isListening = false

    init {
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                startListening()
            }

            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                speechRecognizer?.destroy()
                speechRecognizer = null
                isListening = false
            }
        })

        // Prepare the recognizer
        if (SpeechRecognizer.isRecognitionAvailable(carContext)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(carContext)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d("ListeningScreen", "Ready for speech")
                    isListening = true
                }
                override fun onBeginningOfSpeech() {
                    listeningStatus = "Listening..."
                    invalidate()
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    listeningStatus = "Processing..."
                    invalidate()
                }
                override fun onError(error: Int) {
                    Log.e("ListeningScreen", "Error: $error")
                    // If error is no match or timeout, just pop
                    screenManager.pop()
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val command = matches[0]
                        Log.d("ListeningScreen", "Result: $command")
                        GLKGameEngine.sendInput(command)
                    }
                    screenManager.pop()
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        if (speechRecognizer != null && !isListening) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            }
            speechRecognizer?.startListening(intent)
        } else if (speechRecognizer == null) {
            // Fallback: pop if speech not available
            screenManager.pop()
        }
    }

    override fun onGetTemplate(): Template {
        return MessageTemplate.Builder(listeningStatus)
            .setTitle("Voice Control")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Cancel")
                    .setOnClickListener { screenManager.pop() }
                    .build()
            )
            .build()
    }
}
