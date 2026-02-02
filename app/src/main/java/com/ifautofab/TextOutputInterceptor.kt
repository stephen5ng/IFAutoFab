package com.ifautofab

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Thread-safe bridge for capturing game output from GLK worker thread.
 */
object TextOutputInterceptor {
    interface OutputListener {
        fun onTextAppended(text: String)
        fun onStatusUpdated(status: String)
        fun onClear()
    }

    private val fullOutput = StringBuilder()
    private var statusLine: String = ""
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<OutputListener>()

    fun addListener(listener: OutputListener) {
        listeners.add(listener)
        // Send current state to new listener
        synchronized(fullOutput) {
            if (fullOutput.isNotEmpty()) {
                listener.onTextAppended(fullOutput.toString())
            }
        }
        listener.onStatusUpdated(statusLine)
    }

    fun removeListener(listener: OutputListener) {
        listeners.remove(listener)
    }

    fun appendText(text: String) {
        if (text.isEmpty()) return
        synchronized(fullOutput) {
            fullOutput.append(text)
        }
        for (listener in listeners) {
            listener.onTextAppended(text)
        }
    }

    fun updateStatusLine(text: String) {
        synchronized(this) {
            statusLine = text
        }
        for (listener in listeners) {
            listener.onStatusUpdated(text)
        }
    }

    fun getStatusLine(): String {
        synchronized(this) {
            return statusLine
        }
    }
    
    fun getFullOutput(): String {
        synchronized(fullOutput) {
            return fullOutput.toString()
        }
    }
    
    fun clear() {
        synchronized(fullOutput) {
            fullOutput.setLength(0)
        }
        for (listener in listeners) {
            listener.onClear()
        }
    }
}
