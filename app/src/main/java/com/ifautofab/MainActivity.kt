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

        findViewById<Switch>(R.id.ttsSwitch).setOnCheckedChangeListener { _, isChecked ->
            GLKGameEngine.isTtsEnabled = isChecked
        }

        findViewById<Button>(R.id.sendButton).setOnClickListener {
            val command = inputText.text.toString()
            if (command.isNotEmpty()) {
                GLKGameEngine.sendInput(command)
                inputText.setText("")
            }
        }

        setupBundledGames()
        startOutputPolling()
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
        outputText.append("\nStarting bundled game: $assetName\n")
    }

    private fun startOutputPolling() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                val newText = TextOutputInterceptor.awaitNewText(100)
                if (newText != null) {
                    withContext(Dispatchers.Main) {
                        outputText.append(newText)
                        // Post delay to allow layout update before scrolling
                        scrollView.post {
                            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                        }
                    }
                }
            }
        }
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
