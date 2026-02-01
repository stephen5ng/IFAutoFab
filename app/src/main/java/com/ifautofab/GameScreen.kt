package com.ifautofab

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class GameScreen(carContext: CarContext) : Screen(carContext) {

    private var gameOutput = "Welcome to IFAutoFab.\nSelect a game on your phone to begin."

    init {
        // Observe output from the interceptor
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                pollOutput()
            }
        })
    }

    private fun pollOutput() {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return

        val newText = TextOutputInterceptor.awaitNewText(100)
        if (newText != null) {
            gameOutput = TextOutputInterceptor.getFullOutput()
            invalidate()
        }
        
        // Continue polling
        carContext.mainExecutor.execute {
            pollOutput()
        }
    }

    override fun onGetTemplate(): Template {
        val row = Row.Builder()
            .setTitle("Game Output")
            .addText(gameOutput)
            .build()

        val list = ItemList.Builder()
            .addItem(row)
            .build()

        return ListTemplate.Builder()
            .setSingleList(list)
            .setTitle("IFAutoFab")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
