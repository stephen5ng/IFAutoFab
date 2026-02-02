package com.ifautofab

import android.app.Application
import android.graphics.Point
import androidx.preference.PreferenceManager
import com.luxlunae.glk.GLKConstants
import com.luxlunae.glk.controller.GLKController
import com.luxlunae.glk.controller.GLKEvent
import com.luxlunae.glk.model.GLKModel
import com.luxlunae.glk.model.stream.window.GLKTextBufferM
import android.util.Log
import android.os.Handler
import android.os.Looper
import java.io.File
import java.nio.ByteBuffer

/**
 * Singleton engine to manage the GLK interpreter lifecycle.
 */
object GLKGameEngine {
    private var model: GLKModel? = null
    private var lastTextLength = 0
    private var workerThread: Thread? = null
    private var ttsManager: TTSManager? = null
    var isTtsEnabled: Boolean = true
    private var isGameStarting: Boolean = false

    var onGameFinishedListener: (() -> Unit)? = null
    
    // Handler for main thread operations (like UI callbacks)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val ttsBuffer = StringBuilder()
    private val ttsHandler = Handler(Looper.getMainLooper())
    private val speakRunnable = Runnable {
        val text = synchronized(ttsBuffer) {
            val result = ttsBuffer.toString()
            ttsBuffer.clear()
            result
        }
        if (text.isNotBlank()) {
            ttsManager?.speak(text)
        }
    }

    // Save game path and app for restart functionality
    private var lastGamePath: String? = null
    private var lastApplication: Application? = null
    private var skipAutosave: Boolean = false

    fun startGame(application: Application, gamePath: String) {
        // Save for restart functionality
        lastApplication = application
        lastGamePath = gamePath
        isGameStarting = true

        Thread {
            stopGame()

            val gameFile = File(gamePath)
            if (!gameFile.exists()) {
                Log.e("GLKGameEngine", "Game file not found: $gamePath")
                return@Thread
            }

            // Start new game session
            GLKController.flushEvents()
            TextOutputInterceptor.appendText("\n[Starting new game: ${gameFile.name}]\n")

            ttsManager = TTSManager(application)

            val m = GLKModel(application)
            val sharedPref = PreferenceManager.getDefaultSharedPreferences(application)
            
            // Fix for Android 10+ and multi-user Automotive: redirect storage to internal FilesDir
            val internalAppDir = application.filesDir.absolutePath + "/IFAutoFab/"
            val dir = File(internalAppDir)
            if (!dir.exists()) {
                val created = dir.mkdirs()
                Log.d("GLK_DEBUG", "Created internalAppDir $internalAppDir: $created")
            }
            sharedPref.edit().putString("", internalAppDir).apply()

            val ifid = gameFile.name.hashCode().toString()
            val gameExt = gameFile.extension.lowercase()
            val format = mapExtensionToFormat(gameExt)
            
            m.initialise(sharedPref, gamePath, format, ifid)
            m.setScreenSize(Point(1024, 768))
            
            // Set up exit listener
            m.setGameStatusListener {
                mainHandler.post { 
                    // Verify this notification is for the currently active game to avoid race conditions
                    // where an old thread finishes after a restart and triggers the UI.
                    if (model == m) {
                        Log.d("GLKGameEngine", "Game finished notification received.")
                        onGameFinishedListener?.invoke()
                        stopGame(false) // Preserve output for reading
                    } else {
                        Log.d("GLKGameEngine", "Ignoring finish notification for old game instance.")
                    }
                }
            }
            
            model = m
            lastTextLength = 0
            
            m.setViewUpdateListener {
                val window = m.mStreamMgr.getFirstTextWindow()
                if (window is GLKTextBufferM) {
                    val buffer = window.getBuffer()
                    if (buffer.length > lastTextLength) {
                        val newText = buffer.substring(lastTextLength).toString()
                        TextOutputInterceptor.appendText(newText)
                        if (isTtsEnabled) {
                            // Split by line and filter out boilerplate so we don't speak it
                            val lines = newText.split("\n")
                            val cleanLines = lines.filter { !isIgnoredText(it) }
                            
                            if (cleanLines.isNotEmpty()) {
                                synchronized(ttsBuffer) {
                                    ttsBuffer.append(cleanLines.joinToString("\n"))
                                }
                                ttsHandler.removeCallbacks(speakRunnable)
                                ttsHandler.postDelayed(speakRunnable, 200)
                            }
                        }
                        lastTextLength = buffer.length
                    }
                }
            }

            workerThread = GLKController.create(m)
            workerThread?.start()

            // Auto-restore if autosave exists
            val autosavePath = m.mGameDataPath + "autosave" + GLKConstants.GLK_SAVE_EXT
            if (File(autosavePath).exists()) {
                Log.d("GLKGameEngine", "Autosave found at $autosavePath, attempting to restore...")
                Handler(Looper.getMainLooper()).postDelayed({
                    TextOutputInterceptor.appendText("\n[Restoring autosave...]\n")
                    m.isAutosaving = true
                    sendInput("restore")
                    Handler(Looper.getMainLooper()).postDelayed({
                        m.isAutosaving = false
                    }, 500)
                }, 1000)
            }
        }.start()
    }

    private fun mapExtensionToFormat(ext: String): String {
        return when (ext) {
            "z3", "z4", "z5", "z6", "z8" -> "zcode"
            "ulx", "gblorb" -> "glulx"
            "a3c" -> "alan"
            "t3", "t2" -> "tads3"
            "gam" -> "tads2"
            "h30", "hex" -> "hugo"
            "l9" -> "level9"
            "mag" -> "magscrolls"
            "cas" -> "scott"
            else -> ext
        }
    }

    fun sendInput(input: String) {
        // Any user input (excluding internal auto-restore) clears the startup filtering phase
        if (!input.equals("restore", ignoreCase = true) && !input.equals("save", ignoreCase = true)) {
            isGameStarting = false
        }
        
        // Intercept "restart" command - the game's Y/N confirmation is broken,
        // so we restart at the app level instead
        if (input.trim().equals("restart", ignoreCase = true)) {
            Log.d("GLKGameEngine", "Intercepting restart command - restarting game at app level")
            val app = lastApplication
            val path = lastGamePath
            if (app != null && path != null) {
                Thread {
                    // Skip autosave when restarting
                    skipAutosave = true

                    stopGame()
                    Thread.sleep(500) // Let cleanup finish

                    // Delete autosave file so we start fresh
                    val gameFile = File(path)
                    val ifid = gameFile.name.hashCode().toString()
                    val autosavePath = app.filesDir.absolutePath + "/IFAutoFab/$ifid/autosave.glksave"
                    val autosaveFile = File(autosavePath)
                    if (autosaveFile.exists()) {
                        autosaveFile.delete()
                        Log.d("GLKGameEngine", "Deleted autosave file for fresh restart")
                    }

                    skipAutosave = false
                    isGameStarting = true
                    startGame(app, path)
                }.start()
            }
            return
        }

        val m = model ?: return
        val window = m.mStreamMgr.getFirstTextWindow()
        Log.d("GLKGameEngine", "sendInput: '$input', window=$window")
        if (window is GLKTextBufferM) {
            val charRequested = window.charRequested()
            val lineRequested = window.mInputBB != null
            Log.d("GLKGameEngine", "sendInput: charRequested=$charRequested, lineRequested=$lineRequested")

            // Check for character input mode first (e.g., restart confirmation)
            if (charRequested && input.isNotEmpty()) {
                val ch = input[0].code
                Log.d("GLKGameEngine", "sendInput: sending charEvent ch=$ch ('${input[0]}')")
                val ev = GLKEvent()
                ev.charEvent(window.streamId, ch)
                window.cancelCharEvent()
                GLKController.postEvent(ev)
                return
            }

            // Handle line input mode
            val inputBB = window.mInputBB
            if (inputBB != null) {
                // Set mInput (the StringBuilder) - this is what gets written to mInputBB
                // when the event is processed
                window.mInput.setLength(0)
                window.mInput.append(input)

                val unicode = window.mIs32Bit

                // Reset buffer position before writing
                inputBB.limit(inputBB.capacity()).rewind()
                val len = m.mCharsetMgr?.putGLKString(input, inputBB, unicode, false) ?: 0
                Log.d("GLKGameEngine", "sendInput: sending lineEvent len=$len, buffer pos=${inputBB.position()}")

                val ev = GLKEvent()
                ev.lineEvent(window.streamId, len, 0)

                // Don't clear mInputBB - the interpreter will clear it via cancelLineEvent()
                // after it reads the input

                GLKController.postEvent(ev)
            }
        }
    }
    
    fun stopGame(clearOutput: Boolean = true) {
        val m = model
        val thread = workerThread
        
        if (m != null) {
            // Prevent "Game Finished" callback from firing during manual stop/restart.
            // If we don't do this, the clean shutdown triggered by interrupt() will
            // notify the listener, which causes MainActivity to finish().
            m.setGameStatusListener(null)
        }

        if (m != null && thread != null && thread.isAlive) {
            // Attempt to autosave (unless skipped for restart)
            if (!skipAutosave) {
                Log.d("GLKGameEngine", "Attempting autosave...")
                TextOutputInterceptor.appendText("\n[Autosaving progress...]\n")
                m.isAutosaving = true
                sendInput("save")

                // Give it time to process the save
                try {
                    Thread.sleep(500)
                } catch (e: Exception) {}
                m.isAutosaving = false
            } else {
                Log.d("GLKGameEngine", "Skipping autosave for restart")
            }

            // Attempt to quit gracefully by interrupting the thread immediately.
            // Sending "quit" commands blindly causes input buffer race conditions
            // and corruption/garbage messages in the log.
            thread.interrupt()

            try {
                // Wait briefly for it to die
                thread.join(1000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            
            if (thread.isAlive) {
                Log.w("GLKGameEngine", "Worker thread did not stop. Force interrupting again.")
                thread.interrupt()
            }
        }
        // Ensure strictly no old events remain for the next game
        GLKController.flushEvents()
        
        model = null
        workerThread = null
        
        // Abort any pending speech from the previous session
        ttsManager?.stop()
        ttsManager?.shutdown()
        ttsManager = null
        
        ttsHandler.removeCallbacks(speakRunnable)
        synchronized(ttsBuffer) {
            ttsBuffer.clear()
        }
        if (clearOutput) {
            TextOutputInterceptor.clear()
        }
    }

    fun stopSpeech() {
        ttsManager?.stop()
        ttsHandler.removeCallbacks(speakRunnable)
        synchronized(ttsBuffer) {
            ttsBuffer.clear()
        }
    }

    fun isRunning(): Boolean {
        return workerThread?.isAlive ?: false
    }

    fun isWaitingForCharInput(): Boolean {
        val m = model ?: return false
        val window = m.mStreamMgr.getFirstTextWindow()
        if (window is GLKTextBufferM) {
            return window.charRequested()
        }
        return false
    }

    fun isWaitingForConfirmation(): Boolean {
        // 1. Check if explicit char input is requested (often used for Y/N)
        if (isWaitingForCharInput()) return true

        // 2. Check if line input is requested BUT the last text looks like a confirmation prompt
        val m = model ?: return false
        val window = m.mStreamMgr.getFirstTextWindow() ?: return false
        
        if (window is GLKTextBufferM && window.mInputBB != null) {
            val buffer = window.getBuffer().toString()
            val trimmed = buffer.trim()
            if (trimmed.isEmpty()) return false
            
            // Get the last line(s) to check context
            val lastOutput = trimmed.takeLast(100).lowercase() 
            
            // Common patterns for Quit/Restart confirmation in Infocom/Z-machine games
            return lastOutput.contains("affirmative") || 
                   lastOutput.contains("wish to leave") ||
                   lastOutput.contains("are you sure") ||
                   lastOutput.contains("(y/n)") ||
                   lastOutput.contains("restart the game")
        }
        return false
    }

    private fun isIgnoredText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        
        // 1. Bracketed system messages: [Starting new game], [Restoring...]
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) return true
        
        // 2. Interactive Fiction / Infocom technical boilerplate
        // ONLY filter these at the very start of the game to avoid missing mid-game narrative
        if (!isGameStarting) return false

        val lower = trimmed.lowercase()
        val technicalMarkers = listOf(
            "copyright", 
            "rights reserved", 
            "trademark of", 
            "serial number",
            "release",
            "interactive fiction",
            "infocom",
            "registered trademark"
        )
        
        // If it looks like a technical line (and contains at least one marker), skip it
        if (technicalMarkers.any { lower.contains(it) }) {
            // Additional check: technical lines usually have specific metadata markers
            if (lower.contains("copyright") || lower.contains("(c)") || lower.contains("/") || Regex("\\d{5,8}").containsMatchIn(lower)) {
                return true
            }
        }
        
        return false
    }
}
