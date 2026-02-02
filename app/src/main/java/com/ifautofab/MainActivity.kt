package com.ifautofab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
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

    private val gameSelectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val path = result.data?.getStringExtra("game_path")
            val name = result.data?.getStringExtra("game_name")
            if (path != null) {
                if (name != null) {
                    getSharedPreferences("IFAutoFab", MODE_PRIVATE)
                        .edit().putString("last_game", name).apply()
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


        
        val textInputContainer = findViewById<LinearLayout>(R.id.textInputContainer)
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
        }
        
        val newGameButton = findViewById<Button>(R.id.newGameButton)
        newGameButton.setOnClickListener {
            val intent = android.content.Intent(this, GameSelectionActivity::class.java)
            gameSelectionLauncher.launch(intent)
        }

        findViewById<Button>(R.id.sendButton).setOnClickListener {
            val command = inputText.text.toString()
            if (command.isNotEmpty()) {
                GLKGameEngine.sendInput(command)
                inputText.setText("")
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


        startOutputPolling()

        debugReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val command = intent.getStringExtra("command") ?: return
                GLKGameEngine.sendInput(command)
            }
        }
        registerReceiver(debugReceiver, IntentFilter("com.ifautofab.DEBUG_INPUT"), Context.RECEIVER_EXPORTED)

        if (!GLKGameEngine.isRunning()) {
            resumeOrStartDefaultGame()
        }
    }
    






    private val outputListener = object : TextOutputInterceptor.OutputListener {
        override fun onTextAppended(text: String) {
            runOnUiThread {
                outputText.append(text)
                scrollView.post {
                    scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                }
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
        val file = File(cacheDir, assetName)
        try {
            assets.open("games/$assetName").use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            getSharedPreferences("IFAutoFab", MODE_PRIVATE)
                .edit().putString("last_game", assetName).apply()
            GLKGameEngine.startGame(application, file.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(debugReceiver)
        GLKGameEngine.stopGame()
        TextOutputInterceptor.removeListener(outputListener)
    }


}
