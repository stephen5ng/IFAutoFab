package com.ifautofab

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class GameScreen(carContext: CarContext) : Screen(carContext) {

    private val history = mutableListOf<String>()
    
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val outputListener = object : TextOutputInterceptor.OutputListener {
        override fun onTextAppended(text: String) {
            handler.post {
                val paragraphs = text.split("\n\n")
                synchronized(history) {
                    paragraphs.forEach { 
                        if (it.isNotBlank()) history.add(it.trim()) 
                    }
                    while (history.size > 50) history.removeAt(0)
                }
                invalidate()
            }
        }

        override fun onStatusUpdated(status: String) {
        }

        override fun onClear() {
            handler.post {
                synchronized(history) {
                    history.clear()
                }
                invalidate()
            }
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                TextOutputInterceptor.addListener(outputListener)
            }
            override fun onStop(owner: LifecycleOwner) {
                TextOutputInterceptor.removeListener(outputListener)
            }
        })
    }
    
    // Removed pollOutput and related runnable

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
