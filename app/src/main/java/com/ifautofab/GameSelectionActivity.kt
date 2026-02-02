package com.ifautofab

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class GameSelectionActivity : AppCompatActivity() {

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val file = copyUriToInternalStorage(it)
            if (file != null) {
                returnResult(file.absolutePath, file.name)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_selection)

        setupBundledGames()

        findViewById<Button>(R.id.pickFileButton).setOnClickListener {
            pickFileLauncher.launch("*/*")
        }

        findViewById<Button>(R.id.cancelButton).setOnClickListener {
            finish()
        }
    }

    private fun setupBundledGames() {
        val container = findViewById<LinearLayout>(R.id.bundledGamesContainer)
        val games = assets.list("games") ?: emptyArray()
        games.forEach { assetName ->
            val btn = Button(this).apply {
                text = assetName.replace(Regex("\\.(z3|z5|z8|ulx)$", RegexOption.IGNORE_CASE), "").uppercase()
                setOnClickListener {
                    launchBundledGame(assetName)
                }
            }
            container.addView(btn)
        }
    }

    private fun launchBundledGame(assetName: String) {
        val file = File(cacheDir, assetName)
        try {
            assets.open("games/$assetName").use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            returnResult(file.absolutePath, assetName)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun returnResult(path: String, name: String) {
        if (callingActivity != null) {
            // We were started for a result (e.g. from valid MainActivity "New Game")
            val intent = Intent().apply {
                putExtra("game_path", path)
                putExtra("game_name", name)
            }
            setResult(Activity.RESULT_OK, intent)
            finish()
        } else {
            // We were started standalone (e.g. after App Quit, or as Launcher)
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("game_path", path)
                putExtra("game_name", name)
            }
            startActivity(intent)
            finish()
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
