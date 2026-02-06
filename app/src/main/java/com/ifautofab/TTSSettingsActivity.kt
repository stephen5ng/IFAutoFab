package com.ifautofab

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.ListPreference
import kotlinx.coroutines.*

/**
 * Settings activity for Text-to-Speech configuration.
 *
 * Features:
 * - Engine selection (Kokoro TTS vs System TTS)
 * - Voice selection (for Kokoro TTS)
 * - Model download management
 * - Model status display
 */
class TTSSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle("Text-to-Speech Settings")

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, TTSSettingsFragment())
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Fragment for TTS preferences.
     */
    class TTSSettingsFragment : PreferenceFragmentCompat() {
        private lateinit var enginePreference: ListPreference
        private lateinit var voicePreference: ListPreference
        private lateinit var statusText: TextView
        private lateinit var progressBar: ProgressBar
        private lateinit var downloadButton: Button
        private lateinit var deleteButton: Button

        private val downloader by lazy { SherpaModelDownloader(requireContext()) }
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.tts_preferences, rootKey)

            // Find preferences
            enginePreference = findPreference("tts_engine")!!
            voicePreference = findPreference("tts_voice")!!

            // Find views (will be added to layout later)
            // For now, we'll use the preference UI only

            // Setup engine selection listener
            enginePreference.setOnPreferenceChangeListener { _, newValue ->
                val engine = newValue as String
                if (engine == "sherpa" && !downloader.isModelAvailable()) {
                    // Show download dialog
                    showDownloadDialog()
                    false // Don't switch yet
                } else {
                    // Enable voice selection only for Sherpa-ONNX
                    voicePreference.isEnabled = (engine == "sherpa")
                    true
                }
            }

            // Setup voice selection listener
            voicePreference.setOnPreferenceChangeListener { _, newValue ->
                // Voice will be applied on next TTS via preference read
                Log.d("TTSSettings", "Voice preference changed to: $newValue")
                true
            }

            // Update UI based on current state
            updateUI()
        }

        private fun updateUI() {
            val isSherpaAvailable = downloader.isModelAvailable()
            val currentEngine = enginePreference.value

            voicePreference.isEnabled = (currentEngine == "sherpa" && isSherpaAvailable)

            // Update model status preference
            val statusPreference = findPreference<androidx.preference.Preference>("tts_model_status")
            if (statusPreference != null) {
                if (isSherpaAvailable) {
                    val modelSizeMB = downloader.getModelSize() / (1024 * 1024)
                    statusPreference.summary = "Sherpa-ONNX Kokoro v1.0 INT8\n${modelSizeMB} MB • Ready"
                } else {
                    statusPreference.summary = "Not downloaded"
                }
            }

            // Update download/delete preferences
            val downloadPreference = findPreference<androidx.preference.Preference>("tts_download")
            val deletePreference = findPreference<androidx.preference.Preference>("tts_delete")

            downloadPreference?.isEnabled = !isSherpaAvailable
            downloadPreference?.setOnPreferenceClickListener {
                showDownloadDialog()
                true
            }

            deletePreference?.isEnabled = isSherpaAvailable
            deletePreference?.setOnPreferenceClickListener {
                showDeleteDialog()
                true
            }
        }

        private fun showDownloadDialog() {
            AlertDialog.Builder(requireContext())
                .setTitle("Enable Sherpa-ONNX TTS")
                .setMessage(
                    "High-quality text-to-speech requires downloading 125 MB of model data.\n\n" +
                    "Benefits:\n" +
                    "✓ Natural, expressive voices\n" +
                    "✓ Works offline after download\n" +
                    "✓ 26 voice styles to choose from\n\n" +
                    "Download time: ~1 min on WiFi"
                )
                .setPositiveButton("Download") { _, _ ->
                    startDownload()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun startDownload() {
            // Show progress dialog
            val progressDialog = AlertDialog.Builder(requireContext())
                .setTitle("Downloading Sherpa-ONNX")
                .setView(R.layout.dialog_download_progress)
                .setCancelable(false)
                .create()

            progressDialog.show()

            val progressBar = progressDialog.findViewById<ProgressBar>(R.id.downloadProgressBar)
            val progressText = progressDialog.findViewById<TextView>(R.id.downloadProgressText)

            downloader.startDownload(
                onProgress = { progress ->
                    progressBar?.progress = progress
                    progressText?.text = "Downloading... $progress%"
                },
                onComplete = { success ->
                    progressDialog.dismiss()
                    if (success) {
                        Toast.makeText(requireContext(), "Download complete!", Toast.LENGTH_SHORT).show()
                        enginePreference.value = "sherpa"
                        updateUI()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "Download failed. Please check your connection and try again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )

            // Allow cancellation
            progressDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel") { _, _ ->
                downloader.cancelDownload()
                progressDialog.dismiss()
            }
        }

        private fun showDeleteDialog() {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete Sherpa-ONNX Models")
                .setMessage(
                    "This will free up ~280 MB of space.\n\n" +
                    "You'll need to download again to use Sherpa-ONNX TTS.\n\n" +
                    "The app will fall back to system TTS."
                )
                .setPositiveButton("Delete") { _, _ ->
                    if (downloader.deleteModels()) {
                        Toast.makeText(requireContext(), "Models deleted", Toast.LENGTH_SHORT).show()
                        enginePreference.value = "system"
                        voicePreference.isEnabled = false
                        updateUI()
                    } else {
                        Toast.makeText(requireContext(), "Failed to delete models", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        override fun onDestroy() {
            super.onDestroy()
            scope.cancel()
        }
    }
}
