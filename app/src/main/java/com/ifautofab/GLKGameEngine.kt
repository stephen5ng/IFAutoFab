package com.ifautofab

import android.app.Application
import androidx.preference.PreferenceManager
import com.luxlunae.glk.GLKConstants
import com.luxlunae.glk.controller.GLKController
import com.luxlunae.glk.controller.GLKEvent
import com.luxlunae.glk.model.GLKModel
import com.luxlunae.glk.model.stream.window.GLKTextBufferM
import java.io.File
import java.nio.ByteBuffer

/**
 * Singleton engine to manage the GLK interpreter lifecycle.
 */
object GLKGameEngine {
    private var model: GLKModel? = null
    private var lastTextLength = 0
    private var workerThread: Thread? = null

    fun startGame(application: Application, gamePath: String) {
        if (workerThread != null && workerThread!!.isAlive) {
            return // Already running
        }

        val gameFile = File(gamePath)
        if (!gameFile.exists()) {
            throw IllegalArgumentException("Game file not found: $gamePath")
        }

        val m = GLKModel(application)
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(application)
        val ifid = gameFile.name.hashCode().toString()
        val gameExt = gameFile.extension.lowercase()
        
        m.initialise(sharedPref, gamePath, gameExt, ifid)
        
        model = m
        lastTextLength = 0
        
        m.setViewUpdateListener {
            val window = m.mStreamMgr.getFirstTextWindow()
            if (window is GLKTextBufferM) {
                val buffer = window.getBuffer()
                if (buffer.length > lastTextLength) {
                    val newText = buffer.substring(lastTextLength)
                    TextOutputInterceptor.appendText(newText)
                    lastTextLength = buffer.length
                }
            }
        }

        workerThread = GLKController.create(m)
        workerThread?.start()
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
    
    fun isRunning(): Boolean {
        return workerThread?.isAlive ?: false
    }
}
