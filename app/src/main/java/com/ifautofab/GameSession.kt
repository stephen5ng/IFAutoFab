package com.ifautofab

import android.content.Intent
import androidx.car.app.Session
import androidx.car.app.Screen

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class GameSession : Session() {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                GLKGameEngine.isCarConnected = true
                MediaSessionHelper.init(carContext)
                MediaSessionHelper.onSkipToNextListener = {
                    // Navigate to voice input screen on "Next" button press
                    carContext.getCarService(androidx.car.app.ScreenManager::class.java)
                        .push(VoiceInputScreen(carContext))
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                GLKGameEngine.isCarConnected = false
                MediaSessionHelper.shutdown()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        return if (GLKGameEngine.isRunning()) {
            GameScreen(carContext)
        } else {
            GameSelectionScreen(carContext)
        }
    }
}
