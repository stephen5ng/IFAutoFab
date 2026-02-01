package com.ifautofab

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*

class VoiceInputScreen(carContext: CarContext) : Screen(carContext), SearchTemplate.SearchCallback {

    override fun onSearchTextChanged(searchText: String) {
        // Not needed for final submission
    }

    override fun onSearchSubmitted(searchText: String) {
        if (searchText.isNotBlank()) {
            GLKGameEngine.sendInput(searchText)
            screenManager.pop()
        }
    }

    override fun onGetTemplate(): Template {
        return SearchTemplate.Builder(this)
            .setHeaderAction(Action.BACK)
            .setInitialSearchText("")
            .setSearchHint("Speak command...")
            .setShowKeyboardByDefault(false)
            .build()
    }
}
