package com.ifautofab

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
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
        
        // Add Quick Commands at the top
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Commands...")
                .setOnClickListener { screenManager.push(CommandScreen(carContext)) }
                .build()
        )

        synchronized(TextOutputInterceptor) {
            val history = TextOutputInterceptor.getHistory()
            if (history.isEmpty()) {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("Welcome to IFAutoFab")
                        .addText("Pick a game on your phone to start.")
                        .build()
                )
            } else {
                // Show the last 15 paragraphs, reversed so newest is at the top (avoiding scroll reset issues)
                history.filter { it.isNotBlank() && it.trim() != ">" }.takeLast(15).reversed().forEach { para ->
                    val lines = para.trim().split("\n")
                    val titleText = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: "..."
                    val bodyText = if (lines.size > 1) lines.drop(1).joinToString("\n") else null
                    
                    val row = Row.Builder()
                        .setTitle(titleText)
                    
                    if (!bodyText.isNullOrBlank()) {
                        row.addText(bodyText)
                    } else if (titleText.length > 40) {
                        row.addText(titleText)
                    }
                    
                    listBuilder.addItem(row.build())
                }
            }
        }

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_mic)).build())
                    .setOnClickListener {
                        screenManager.push(ListeningScreen(carContext))
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Look")
                    .setOnClickListener { GLKGameEngine.sendInput("look") }
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
