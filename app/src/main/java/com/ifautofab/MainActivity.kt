package com.ifautofab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var outputText: TextView
    private lateinit var inputText: EditText
    private lateinit var scrollView: ScrollView
    private lateinit var debugReceiver: BroadcastReceiver
    private lateinit var ynButtonContainer: LinearLayout
    private lateinit var textInputContainer: LinearLayout

    private val gameSelectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val path = result.data?.getStringExtra("game_path")
            val name = result.data?.getStringExtra("game_name")
            if (path != null) {
                if (name != null) {
                    getSharedPreferences("IFAutoFab", MODE_PRIVATE)
                        .edit().putString("last_game", name).apply()
                    updateTitle(name)
                }
                GLKGameEngine.startGame(application, path)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        outputText = findViewById(R.id.outputText)
        inputText = findViewById(R.id.inputText)
        scrollView = findViewById(R.id.scrollView)
        ynButtonContainer = findViewById(R.id.ynButtonContainer)
        textInputContainer = findViewById(R.id.textInputContainer)

        val keyboardToggle = findViewById<Button>(R.id.keyboardToggle)
        
        keyboardToggle.setOnClickListener {
            if (textInputContainer.visibility == android.view.View.VISIBLE) {
                textInputContainer.visibility = android.view.View.GONE
            } else {
                textInputContainer.visibility = android.view.View.VISIBLE
            }
        }

        findViewById<android.widget.ToggleButton>(R.id.ttsQuickToggle).setOnCheckedChangeListener { _, isChecked ->
            GLKGameEngine.isTtsEnabled = isChecked
            if (!isChecked) {
                GLKGameEngine.stopSpeech()
            }
        }

        findViewById<Button>(R.id.sendButton).setOnClickListener {
            val command = inputText.text.toString()
            if (command.isNotEmpty()) {
                GLKGameEngine.sendInput(command)
                inputText.setText("")

                // Dismiss keyboard and hide input container
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(inputText.windowToken, 0)
                findViewById<LinearLayout>(R.id.textInputContainer).visibility = android.view.View.GONE
            }
        }
        
        val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val data = result.data
                val matches = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    val command = matches[0]
                    GLKGameEngine.sendInput(command)
                }
            }
        }

        findViewById<Button>(R.id.voiceInputButton).setOnClickListener {
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak command...")
            }
            try {
                voiceLauncher.launch(intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Speech recognition not available", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Return to Menu button
        val returnToMenuButton = findViewById<Button>(R.id.returnToMenuButton)
        returnToMenuButton.setOnClickListener {
             // Go to Game Selection screen
             val intent = android.content.Intent(this, GameSelectionActivity::class.java)
             intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK
             startActivity(intent)
             finish() 
        }

        // Y/N button handlers
        findViewById<Button>(R.id.yesButton).setOnClickListener {
            GLKGameEngine.sendInput("y")
        }

        findViewById<Button>(R.id.noButton).setOnClickListener {
            GLKGameEngine.sendInput("n")
        }

        // Quick shortcut handlers
        findViewById<Button>(R.id.lookButton).setOnClickListener {
            GLKGameEngine.sendInput("look")
        }

        findViewById<Button>(R.id.inventoryButton).setOnClickListener {
            GLKGameEngine.sendInput("i")
        }

        startOutputPolling()

        debugReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.hasExtra("load_game")) {
                    val gameName = intent.getStringExtra("load_game")
                    if (gameName != null) {
                        returnToMenuButton.visibility = android.view.View.GONE
                        startBundledGame(gameName)
                    }
                    return
                }
                val command = intent.getStringExtra("command") ?: return
                GLKGameEngine.sendInput(command)
            }
        }
        registerReceiver(debugReceiver, IntentFilter("com.ifautofab.DEBUG_INPUT"), Context.RECEIVER_EXPORTED)

        if (intent.hasExtra("game_path")) {
            val path = intent.getStringExtra("game_path")
            val name = intent.getStringExtra("game_name")
            if (path != null) {
                if (name != null) {
                    getSharedPreferences("IFAutoFab", MODE_PRIVATE)
                            .edit().putString("last_game", name).apply()
                    updateTitle(name)
                }
                returnToMenuButton.visibility = android.view.View.GONE
                GLKGameEngine.startGame(application, path)
            }
        } else if (!GLKGameEngine.isRunning()) {
            returnToMenuButton.visibility = android.view.View.GONE
            resumeOrStartDefaultGame()
        }

        setupGameEndListener()
    }

    private fun setupGameEndListener() {
        GLKGameEngine.onGameFinishedListener = {
             runOnUiThread {
                 // Hide other controls
                 ynButtonContainer.visibility = android.view.View.GONE
                 textInputContainer.visibility = android.view.View.GONE
                 
                 // Show "Return to Menu" button
                 findViewById<Button>(R.id.returnToMenuButton).visibility = android.view.View.VISIBLE
             }
        }
    }
    
    private val outputListener = object : TextOutputInterceptor.OutputListener {
        override fun onTextAppended(text: String) {
            runOnUiThread {
                outputText.append(text)
                // Post scroll to outputText's queue to ensure it runs after TextView layout
                outputText.post {
                    scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                }
                // Check if we should show Y/N buttons
                updateInputMode()
            }
        }

        override fun onStatusUpdated(status: String) {
            // Optional: update status bar or title if needed
        }

        override fun onClear() {
            runOnUiThread {
                outputText.text = ""
            }
        }
    }

    private fun updateInputMode() {
        // Check if the interpreter is waiting for confirmation (Char input OR text prompt)
        val isConfirmationMode = GLKGameEngine.isWaitingForConfirmation()

        if (isConfirmationMode) {
            // Show Y/N buttons, hide text input
            ynButtonContainer.visibility = android.view.View.VISIBLE
            textInputContainer.visibility = android.view.View.GONE
        } else {
            // Hide Y/N buttons
            ynButtonContainer.visibility = android.view.View.GONE
            // Keep text input hidden unless user toggles it
        }
    }

    private fun startOutputPolling() {
        // Clear previous content if any, relying on full history catch-up
        outputText.text = ""
        TextOutputInterceptor.addListener(outputListener)
    }

    private fun resumeOrStartDefaultGame() {
        val lastGame = getSharedPreferences("IFAutoFab", MODE_PRIVATE).getString("last_game", null)
        val gameName = lastGame ?: assets.list("games")?.firstOrNull() ?: return

        // If already cached, start directly
        val cached = File(cacheDir, gameName)
        if (cached.exists()) {
            updateTitle(gameName)
            GLKGameEngine.startGame(application, cached.absolutePath)
            return
        }

        // If it's a bundled asset, extract and start
        val bundled = assets.list("games") ?: emptyArray()
        if (gameName in bundled) {
            startBundledGame(gameName)
            return
        }

        // Last game was external and no longer available; fall back to first bundled
        val first = bundled.firstOrNull() ?: return
        startBundledGame(first)
    }

    private fun startBundledGame(assetName: String) {
        // Stop any existing game
        GLKGameEngine.stopGame()
        
        // Hide "Game Ended" button if visible
        runOnUiThread {
            findViewById<Button>(R.id.returnToMenuButton).visibility = android.view.View.GONE
        }
        
        // Copy asset to internal storage
        val outFile = File(filesDir, assetName)
        if (!outFile.exists()) {
            assets.open("games/$assetName").use { input -> // Changed to "games/$assetName" to match original logic
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        
        getSharedPreferences("IFAutoFab", MODE_PRIVATE) // Added back shared preferences update
            .edit().putString("last_game", assetName).apply()
        updateTitle(assetName)
        GLKGameEngine.startGame(application, outFile.absolutePath)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select_game -> {
                val intent = android.content.Intent(this, GameSelectionActivity::class.java)
                gameSelectionLauncher.launch(intent)
                true
            }
            R.id.action_restart -> {
                GLKGameEngine.sendInput("restart")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateTitle(gameName: String) {
        // Strip extension and format for display
        val displayName = gameName.replace(Regex("\\.(z3|z5|z8|ulx|gblorb)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[._-]"), " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        supportActionBar?.title = displayName
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(debugReceiver)
        GLKGameEngine.stopGame()
        TextOutputInterceptor.removeListener(outputListener)
    }


}
