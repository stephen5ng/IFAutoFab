package com.ifautofab

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class GameScreen(carContext: CarContext) : Screen(carContext) {

    private val history = mutableListOf<String>()

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
            // Split by double newlines to treat as paragraphs
            val paragraphs = newText.split("\n\n")
            synchronized(history) {
                paragraphs.forEach { if (it.isNotBlank()) history.add(it.trim()) }
                // Keep only last 50 paragraphs
                while (history.size > 50) history.removeAt(0)
            }
            invalidate()
        }
        
        // Continue polling
        carContext.mainExecutor.execute {
            pollOutput()
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        
        synchronized(history) {
            if (history.isEmpty()) {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("No output yet")
                        .addText("Select a game on your phone to begin.")
                        .build()
                )
            } else {
                history.forEach { para ->
                    listBuilder.addItem(
                        Row.Builder()
                            .setTitle(para)
                            .build()
                    )
                }
            }
        }

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Commands")
                    .setOnClickListener {
                        screenManager.push(CommandScreen(carContext))
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Voice")
                    .setOnClickListener {
                        screenManager.push(VoiceInputScreen(carContext))
                    }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle("IFAutoFab")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(actionStrip)
            .build()
    }
}
