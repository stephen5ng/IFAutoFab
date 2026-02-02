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

    fun startGame(application: Application, gamePath: String) {
        stopGame()
        
        ttsManager = TTSManager(application)
        val gameFile = File(gamePath)
        if (!gameFile.exists()) {
            throw IllegalArgumentException("Game file not found: $gamePath")
        }

        val m = GLKModel(application)
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(application)
        
        // Fix for Android 10+ and multi-user Automotive: redirect storage to internal FilesDir
        val internalAppDir = application.filesDir.absolutePath + "/IFAutoFab/"
        val dir = File(internalAppDir)
        if (!dir.exists()) {
            val created = dir.mkdirs()
            Log.d("GLK_DEBUG", "Created internalAppDir $internalAppDir: $created")
        }
        Log.d("GLK_DEBUG", "internalAppDir = $internalAppDir")
        sharedPref.edit().putString("", internalAppDir).apply()

        val ifid = gameFile.name.hashCode().toString()
        val gameExt = gameFile.extension.lowercase()
        val format = mapExtensionToFormat(gameExt)
        Log.d("GLK_DEBUG", "gamePath = $gamePath, ext = $gameExt, format = $format")
        
        m.initialise(sharedPref, gamePath, format, ifid)
        
        // Fix: initialize screen size to avoid null pointer in getScreenSize()
        m.setScreenSize(Point(1024, 768))
        
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
                        ttsManager?.speak(newText)
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
            // We need to wait a bit for the terp to be ready for input
            // In a better implementation, we'd wait for a line input request event
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                m.isAutosaving = true // reuse flag to bypass prompt in restore too
                sendInput("restore")
                // Wait another short bit for the restore to complete
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    m.isAutosaving = false
                }, 500)
            }, 1000)
        }
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
        val m = model ?: return
        val window = m.mStreamMgr.getFirstTextWindow()
        if (window is GLKTextBufferM) {
            val inputBB = window.mInputBB
            if (inputBB != null) {
                val unicode = window.mIs32Bit
                val len = m.mCharsetMgr?.putGLKString(input, inputBB, unicode, false) ?: 0
                
                // Echo the input to the output buffer as if the user typed it
                window.putString(input + "\n")
                
                val ev = GLKEvent()
                ev.lineEvent(window.getStreamId(), len, 0)
                
                // Clear the window's input state
                window.mInputBB = null
                
                GLKController.postEvent(ev)
            }
        }
    }
    
    fun stopGame() {
        val m = model
        if (m != null && workerThread != null && workerThread!!.isAlive) {
            // Attempt to autosave
            Log.d("GLKGameEngine", "Attempting autosave...")
            m.isAutosaving = true
            sendInput("save")
            
            // Give it a tiny bit of time to process the save before sending quit
            // This is slightly hacky due to the asynchronous nature of events
            try { 
                Thread.sleep(300) 
            } catch (e: Exception) {}
            m.isAutosaving = false

            // Attempt to quit gracefully first
            sendInput("quit")
            sendInput("y")
            sendInput("yes")
            
            try {
                workerThread?.join(500)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            
            if (workerThread != null && workerThread!!.isAlive) {
                Log.w("GLKGameEngine", "Worker thread did not stop gracefully. Interrupting.")
                workerThread?.interrupt()
            }
        }
        workerThread = null
        TextOutputInterceptor.clear()
        
        // Ensure model is reset or recreated for next run (handled in startGame)
        model = null 
    }

    fun isRunning(): Boolean {
        return workerThread?.isAlive ?: false
    }
}
