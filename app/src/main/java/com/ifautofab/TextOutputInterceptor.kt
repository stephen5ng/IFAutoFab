package com.ifautofab

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Thread-safe bridge for capturing game output from GLK worker thread.
 */
object TextOutputInterceptor {
    private val outputQueue = LinkedBlockingQueue<String>()
    private val fullOutput = StringBuilder()

    fun appendText(text: String) {
        if (text.isEmpty()) return
        synchronized(fullOutput) {
            fullOutput.append(text)
        }
        outputQueue.offer(text)
    }

    /**
     * Wait for new text to be available. Returns null if timeout reached.
     */
    fun awaitNewText(timeoutMs: Long): String? {
        return outputQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
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
        outputQueue.clear()
    }
}
