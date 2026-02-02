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
        Thread {
            stopGame()
            
            val gameFile = File(gamePath)
            if (!gameFile.exists()) {
                Log.e("GLKGameEngine", "Game file not found: $gamePath")
                return@Thread
            }
            
            // Start new game session
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
        val m = model ?: return
        val window = m.mStreamMgr.getFirstTextWindow()
        if (window is GLKTextBufferM) {
            val inputBB = window.mInputBB
            if (inputBB != null) {
                val unicode = window.mIs32Bit
                val len = m.mCharsetMgr?.putGLKString(input, inputBB, unicode, false) ?: 0

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
        val thread = workerThread
        if (m != null && thread != null && thread.isAlive) {
            // Attempt to autosave
            Log.d("GLKGameEngine", "Attempting autosave...")
            TextOutputInterceptor.appendText("\n[Autosaving progress...]\n")
            m.isAutosaving = true
            sendInput("save")
            
            // Give it time to process the save
            try { 
                Thread.sleep(500) 
            } catch (e: Exception) {}
            m.isAutosaving = false

            // Attempt to quit gracefully
            sendInput("quit")
            sendInput("y")
            sendInput("yes")
            
            try {
                thread.join(1000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            
            if (thread.isAlive) {
                Log.w("GLKGameEngine", "Worker thread did not stop gracefully. Interrupting.")
                thread.interrupt()
            }
        }
        model = null
        workerThread = null
        TextOutputInterceptor.clear()
    }

    fun isRunning(): Boolean {
        return workerThread?.isAlive ?: false
    }
}
