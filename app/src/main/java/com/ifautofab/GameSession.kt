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
                    android.util.Log.d("IFAutoFab", "Media button: SkipToNext received")
                    // Navigate to listening screen on "Next" button press
                    // MUST be on main thread
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    handler.post {
                        try {
                            carContext.getCarService(androidx.car.app.ScreenManager::class.java)
                                .push(ListeningScreen(carContext))
                        } catch (e: Exception) {
                            android.util.Log.e("IFAutoFab", "Error pushing ListeningScreen: ${e.message}")
                        }
                    }
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
