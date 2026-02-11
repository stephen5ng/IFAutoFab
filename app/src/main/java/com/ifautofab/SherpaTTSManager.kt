package com.ifautofab

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.preference.PreferenceManager
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Text-to-Speech Manager using Sherpa-ONNX.
 *
 * Uses official AAR from https://github.com/k2-fsa/sherpa-onnx/releases
 *
 * Supported models:
 * - Kokoro (v1.0 English): requires model.onnx, voices.bin, tokens.txt, espeak-ng-data, lexicon
 * - VITS (Piper): simpler model, fewer dependencies
 */
class SherpaTTSManager(private val context: Context) {
    private val tag = "SherpaTTSManager"

    private var ttsEngine: OfflineTts? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioTrack: AudioTrack? = null
    private var currentVoiceId: Int = 0 // Default voice ID for Kokoro (0 = af_heart)

    // Job to track current TTS operation for cancellation
    private var currentSpeechJob: Job? = null

    companion object {
        private const val SAMPLE_RATE = 24000 // Standard TTS sample rate

        // Voice names for Kokoro (index corresponds to voice ID)
        val AVAILABLE_VOICES = listOf(
            "af_heart", "af_alloy", "af_aoede", "af_bella", "af_jessica", "af_kore",
            "af_nicole", "af_nova", "af_river", "af_sarah", "af_sky",
            "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael",
            "am_onyx", "am_puck", "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
            "bm_daniel", "bm_fable", "bm_george", "bm_lewis"
        )

        // Required model files for Kokoro v1.0 multilingual INT8
        private const val MODEL_NAME = "model.int8.onnx" // INT8 quantized (76MB)
        private const val VOICES_NAME = "voices.bin" // 27 MB
        private const val TOKENS_NAME = "tokens.txt"
        private const val DATA_DIR = "espeak-ng-data"
        private const val LEXICON_NAME = "lexicon-us-en.txt"
    }

    sealed class State {
        object Uninitialized : State()
        object Loading : State()
        data class Ready(val engine: OfflineTts) : State()
        data class Error(val message: String) : State()
    }

    private var state: State = State.Uninitialized
    private val lock = ReentrantLock()

    /**
     * Initialize the Sherpa-ONNX TTS engine (async).
     * Loads models if they exist in the filesystem.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.Main) {
        lock.withLock {
            if (state is State.Ready || state is State.Loading) {
                Log.d(tag, "Already initialized or loading")
                return@withContext true
            }

            state = State.Loading
            Log.d(tag, "Starting Sherpa-ONNX TTS initialization on main thread...")
        }

        try {
            // Check if model files exist
            val modelDir = File(context.filesDir, "tts/sherpa")
            val modelFile = File(modelDir, MODEL_NAME)
            val voicesFile = File(modelDir, VOICES_NAME)
            val tokensFile = File(modelDir, TOKENS_NAME)
            val dataDir = File(modelDir, DATA_DIR)
            val lexiconFile = File(modelDir, LEXICON_NAME)

            if (!modelFile.exists()) {
                state = State.Error("Model file not found. Please download models first.")
                Log.e(tag, "Model file not found: ${modelFile.absolutePath}")
                return@withContext false
            }

            // Verify required files
            val missingFiles = mutableListOf<String>()
            if (!voicesFile.exists()) missingFiles.add(VOICES_NAME)
            if (!tokensFile.exists()) missingFiles.add(TOKENS_NAME)
            if (!dataDir.exists()) missingFiles.add(DATA_DIR)
            if (!lexiconFile.exists()) missingFiles.add(LEXICON_NAME)

            if (missingFiles.isNotEmpty()) {
                state = State.Error("Missing required files: ${missingFiles.joinToString()}")
                Log.e(tag, "Missing files: ${missingFiles.joinToString()}")
                return@withContext false
            }

            // Log file sizes for verification
            Log.d(tag, "Model files found:")
            Log.d(tag, "  - ${modelFile.name} (${modelFile.length() / 1024 / 1024} MB)")
            Log.d(tag, "  - ${voicesFile.name} (${voicesFile.length() / 1024} KB)")
            Log.d(tag, "  - ${tokensFile.name} (${tokensFile.length()} bytes)")
            Log.d(tag, "  - ${dataDir.name}/ (directory)")
            Log.d(tag, "  - ${lexiconFile.name} (${lexiconFile.length()} bytes)")

            // Create Kokoro model configuration
            val kokoroConfig = OfflineTtsKokoroModelConfig(
                model = modelFile.absolutePath,
                voices = voicesFile.absolutePath,
                tokens = tokensFile.absolutePath,
                dataDir = dataDir.absolutePath,
                lexicon = lexiconFile.absolutePath
            )

            // Create model configuration
            val modelConfig = OfflineTtsModelConfig(
                kokoro = kokoroConfig,
                numThreads = 2,
                debug = true
            )

            // Create TTS configuration
            val config = OfflineTtsConfig(
                model = modelConfig
            )

            // Initialize TTS engine
            // NOTE: When loading from file system with absolute paths, pass null for AssetManager
            // See: https://github.com/k2-fsa/sherpa-onnx/issues/2562
            Log.d(tag, "Creating OfflineTts instance...")
            val engine = OfflineTts(null, config)

            state = State.Ready(engine)
            Log.d(tag, "Sherpa-ONNX TTS initialization complete")
            true

        } catch (e: Exception) {
            val error = "Initialization error: ${e.message}"
            Log.e(tag, error, e)
            state = State.Error(error)
            false
        }
    }

    /**
     * Split text into sentences for chunked TTS processing.
     * Splits on periods, newlines, and common punctuation.
     */
    private fun splitIntoSentences(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val currentSentence = StringBuilder()

        for (char in text) {
            currentSentence.append(char)

            // Split on sentence-ending punctuation
            if (char == '.' || char == '!' || char == '?' || char == '\n') {
                sentences.add(currentSentence.toString().trim())
                currentSentence.clear()
            } else if (char == ',') {
                // Split on commas if followed by space (likely clause boundary)
                if (currentSentence.length > 50) {
                    sentences.add(currentSentence.toString().trim())
                    currentSentence.clear()
                } else if (currentSentence.length >= 200) {
                    // Hard limit at 200 chars to prevent too-long sentences
                    sentences.add(currentSentence.toString().trim())
                    currentSentence.clear()
                }
            }
        }

        // Add remaining text
        if (currentSentence.isNotEmpty()) {
            sentences.add(currentSentence.toString().trim())
        }

        // Filter out empty sentences
        return sentences.filter { it.isNotEmpty() }
    }

    /**
     * Speak text using Sherpa-ONNX TTS.
     * Thread-safe, queues if not ready.
     */
    fun speak(text: String) {
        if (text.isBlank()) return

        // Cancel any previous TTS job immediately
        currentSpeechJob?.cancel()
        Log.d(tag, "Cancelled previous TTS job (if any)")

        // Read voice preference each time (may have changed in settings)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val voiceName = prefs.getString("tts_voice", "af_heart") ?: "af_heart"
        val voiceId = AVAILABLE_VOICES.indexOf(voiceName).takeIf { it >= 0 } ?: 0

        // Start new TTS job
        currentSpeechJob = scope.launch {
            // Update voice ID inside coroutine
            if (voiceId != currentVoiceId) {
                currentVoiceId = voiceId
                Log.d(tag, "Voice updated from preference: $voiceName (ID: $currentVoiceId)")
            }

            when (val s = state) {
                is State.Ready -> {
                    try {
                        // Split text into sentences for faster processing
                        // This prevents long text blocks from taking too long
                        val sentences = splitIntoSentences(text)
                        Log.d(tag, "Split into ${sentences.size} sentences, total ${text.length} chars")

                        for ((index, sentence) in sentences.withIndex()) {
                            // Check if job was cancelled while processing
                            ensureActive()

                            Log.d(tag, "Speaking sentence ${index + 1}/${sentences.size}: ${sentence.take(50)}...")
                            val startTime = System.currentTimeMillis()

                            // Generate speech audio
                            // generate() returns GeneratedAudio object with sampleRate and samples
                            val audio = s.engine.generate(sentence, currentVoiceId)
                            val generationTimeMs = System.currentTimeMillis() - startTime
                            Log.d(tag, "TTS generation took ${generationTimeMs}ms for ${sentence.length} chars (${sentence.length.toFloat() * 1000 / generationTimeMs} chars/sec)")

                            // Play the audio
                            playAudio(audio.sampleRate, audio.samples)

                            // Wait for audio to finish before processing next sentence
                            val durationMs = ((audio.samples.size.toFloat() / SAMPLE_RATE) * 1000).toLong()
                            val waitTimeMs = durationMs + 100  // Small gap between sentences
                            delay(waitTimeMs)
                        }

                    } catch (e: CancellationException) {
                        Log.d(tag, "TTS job cancelled by user")
                    } catch (e: Exception) {
                        Log.e(tag, "Speech generation error", e)
                    }
                }
                is State.Uninitialized -> {
                    Log.d(tag, "Not initialized, initializing now...")
                    initialize()
                    delay(100)
                    speak(text)
                }
                is State.Loading -> {
                    Log.d(tag, "Still loading, queuing...")
                    delay(500)
                    speak(text)
                }
                is State.Error -> {
                    Log.e(tag, "Cannot speak: ${(s as State.Error).message}")
                }
            }
        }
    }

    /**
     * Play audio samples using AudioTrack.
     */
    private fun playAudio(sampleRate: Int, samples: FloatArray) {
        // Release any existing audio track
        audioTrack?.release()

        // Calculate buffer size for the entire audio (MODE_STATIC)
        val bytesPerSample = 2 // 16-bit PCM = 2 bytes per sample
        val bufferSizeInBytes = samples.size * bytesPerSample

        // Ensure minimum buffer size requirement
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize == AudioTrack.ERROR || minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
            Log.e(tag, "Invalid buffer size for audio playback")
            return
        }

        // Use larger of our data size or minimum buffer size
        val actualBufferSize = maxOf(bufferSizeInBytes, minBufferSize)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(actualBufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        // Convert float samples (-1.0 to 1.0) to 16-bit PCM
        val pcmData = ShortArray(samples.size) { i ->
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            (clamped * Short.MAX_VALUE).toInt().toShort()
        }

        audioTrack?.let { track ->
            // For MODE_STATIC, write all data first, then play
            val written = track.write(pcmData, 0, pcmData.size)
            Log.d(tag, "Wrote $written samples (${pcmData.size * 2} bytes) to AudioTrack")

            if (written > 0) {
                track.play()
                Log.d(tag, "Playing ${samples.size} audio samples (~${samples.size.toFloat() / sampleRate} seconds)")

                // Wait for playback to complete
                val durationMs = ((samples.size.toFloat() / sampleRate) * 1000).toLong()
                var remainingMs = durationMs + 200
                while (remainingMs > 0 && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    Thread.sleep(50)
                    remainingMs -= 50
                }
                Log.d(tag, "Playback complete")
            } else {
                Log.e(tag, "Failed to write audio data to AudioTrack")
            }
        }
    }

    /**
     * Stop current audio playback.
     */
    fun stop() {
        audioTrack?.stop()
        audioTrack?.flush()
    }

    /**
     * Shutdown and release resources.
     */
    fun shutdown() {
        Log.d(tag, "Shutting down Sherpa-ONNX TTS...")
        scope.cancel()
        stop()

        lock.withLock {
            when (val s = state) {
                is State.Ready -> {
                    s.engine.release()
                }
                else -> {}
            }
            state = State.Uninitialized
        }
    }

    /**
     * Set voice for TTS by voice name.
     */
    fun setVoice(voiceName: String) {
        val index = AVAILABLE_VOICES.indexOf(voiceName)
        if (index >= 0) {
            currentVoiceId = index
            Log.d(tag, "Voice changed to: $voiceName (ID: $currentVoiceId)")
        } else {
            Log.w(tag, "Unknown voice: $voiceName")
        }
    }

    /**
     * Get current voice name.
     */
    fun getVoice(): String = AVAILABLE_VOICES.getOrNull(currentVoiceId) ?: "af_heart"

    /**
     * Check if TTS is ready to speak.
     */
    fun isReady(): Boolean = state is State.Ready

    /**
     * Get current state for debugging.
     */
    fun getState(): State = state

    /**
     * Check if model files are downloaded.
     */
    fun areModelsDownloaded(): Boolean {
        val modelDir = File(context.filesDir, "tts/sherpa")
        val requiredFiles = listOf(
            MODEL_NAME, VOICES_NAME, TOKENS_NAME, LEXICON_NAME
        )
        val dataDir = File(modelDir, DATA_DIR)

        return requiredFiles.all { File(modelDir, it).exists() } && dataDir.exists()
    }

    /**
     * Get total size of downloaded model files in bytes.
     */
    fun getModelSize(): Long {
        val modelDir = File(context.filesDir, "tts/sherpa")
        val modelFile = File(modelDir, MODEL_NAME)
        val voicesFile = File(modelDir, VOICES_NAME)
        val tokensFile = File(modelDir, TOKENS_NAME)
        val lexiconFile = File(modelDir, LEXICON_NAME)
        return (modelFile.length() + voicesFile.length() +
                tokensFile.length() + lexiconFile.length())
    }
}
