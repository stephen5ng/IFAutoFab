package com.ifautofab

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

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val file = copyUriToInternalStorage(it)
            if (file != null) {
                GLKGameEngine.startGame(application, file.absolutePath)
                showRunningGameUI()
                outputText.append("\nStarting game: ${file.name}\n")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        outputText = findViewById(R.id.outputText)
        inputText = findViewById(R.id.inputText)
        scrollView = findViewById(R.id.scrollView)

        findViewById<Button>(R.id.pickFileButton).setOnClickListener {
            pickFileLauncher.launch("*/*")
        }
        
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
        
        val gameSelectionLayout = findViewById<LinearLayout>(R.id.gameSelectionLayout)
        val newGameButton = findViewById<Button>(R.id.newGameButton)
        
        newGameButton.setOnClickListener {
            gameSelectionLayout.visibility = android.view.View.VISIBLE
            findViewById<Button>(R.id.cancelSelectionButton).visibility = android.view.View.VISIBLE
            // Don't hide the menu button in new layout, or if we do, we need to bring it back
            // In the consolidated bar, we probably want to keep it visible or maybe disable it
        }
        
        findViewById<Button>(R.id.cancelSelectionButton).setOnClickListener {
            gameSelectionLayout.visibility = android.view.View.GONE
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

        setupBundledGames()
        startOutputPolling()
    }
    
    private fun showRunningGameUI() {
        findViewById<LinearLayout>(R.id.gameSelectionLayout).visibility = android.view.View.GONE
    }

    private fun setupBundledGames() {
        val container = findViewById<LinearLayout>(R.id.bundledGamesContainer)
        val games = assets.list("games") ?: emptyArray()
        games.forEach { assetName ->
            val btn = Button(this).apply {
                text = assetName.removeSuffix(".z3").uppercase()
                setOnClickListener {
                    launchBundledGame(assetName)
                }
            }
            container.addView(btn)
        }
    }

    private fun launchBundledGame(assetName: String) {
        val file = File(cacheDir, assetName)
        assets.open("games/$assetName").use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        GLKGameEngine.startGame(application, file.absolutePath)
        showRunningGameUI()
        outputText.append("\nStarting bundled game: $assetName\n")
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
    }

    private fun startOutputPolling() {
        // Clear previous content if any, relying on full history catch-up
        outputText.text = ""
        TextOutputInterceptor.addListener(outputListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        TextOutputInterceptor.removeListener(outputListener)
    }

    private fun copyUriToInternalStorage(uri: Uri): File? {
        var fileName = "game_file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex)
            }
        }

        val file = File(cacheDir, fileName)
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
