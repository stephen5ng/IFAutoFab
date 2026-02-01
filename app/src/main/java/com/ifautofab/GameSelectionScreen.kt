package com.ifautofab

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import java.io.File
import java.io.FileOutputStream

class GameSelectionScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        
        try {
            val games = carContext.assets.list("games") ?: emptyArray()
            if (games.isEmpty()) {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("No bundled games found")
                        .addText("Add games to assets/games/")
                        .build()
                )
            } else {
                games.forEach { gameName ->
                    listBuilder.addItem(
                        Row.Builder()
                            .setTitle(gameName.removeSuffix(".z3").uppercase())
                            .setOnClickListener {
                                launchGame(gameName)
                            }
                            .build()
                    )
                }
            }
        } catch (e: Exception) {
            listBuilder.addItem(Row.Builder().setTitle("Error loading games").addText(e.message ?: "Unknown error").build())
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle("Select a Game")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun launchGame(assetName: String) {
        val application = carContext.applicationContext as android.app.Application
        val internalFile = File(carContext.cacheDir, assetName)
        
        // Copy asset to cache so GLK can read it as a normal file
        carContext.assets.open("games/$assetName").use { input ->
            FileOutputStream(internalFile).use { output ->
                input.copyTo(output)
            }
        }
        
        GLKGameEngine.startGame(application, internalFile.absolutePath)
        screenManager.push(GameScreen(carContext))
    }
}
