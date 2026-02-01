package com.ifautofab

import android.content.Intent
import androidx.car.app.Session
import androidx.car.app.Screen

class GameSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return if (GLKGameEngine.isRunning()) {
            GameScreen(carContext)
        } else {
            GameSelectionScreen(carContext)
        }
    }
}
