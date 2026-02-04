package com.ifautofab

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*

class CommandScreen(carContext: CarContext) : Screen(carContext) {

    private val commonCommands = listOf(
        "Look", "Inventory", "Score", "Wait",
        "North", "South", "East", "West",
        "Northeast", "Northwest", "Southeast", "Southwest",
        "Up", "Down", "In", "Out"
    )

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        
        commonCommands.forEach { cmd ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(cmd)
                    .setOnClickListener {
                        GLKGameEngine.sendInput(cmd.lowercase())
                        screenManager.pop()
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle("Select Command")
            .setHeaderAction(Action.BACK)
            .build()
    }
}
