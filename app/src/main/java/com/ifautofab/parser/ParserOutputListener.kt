package com.ifautofab.parser

import android.util.Log
import com.ifautofab.TextOutputInterceptor
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Listens to game output to detect parser failures and trigger rewrites.
 * Integrates with TextOutputInterceptor to receive game output.
 */
class ParserOutputListener(
    private val onParserError: (originalCommand: String, error: ErrorInfo) -> Unit
) : TextOutputInterceptor.OutputListener {

    private val TAG = "ParserOutputListener"

    private var pendingCommand: String? = null
    private val outputBuffer = StringBuilder()
    private var isProcessing = false

    /**
     * Called before a command is sent, to associate it with the response.
     */
    fun onCommandSent(command: String) {
        pendingCommand = command
        outputBuffer.clear()
        isProcessing = true
        Log.d(TAG, "Command pending: '$command'")
    }

    override fun onTextAppended(text: String) {
        if (!isProcessing) return

        outputBuffer.append(text)
        val completeOutput = outputBuffer.toString()

        // Check if we have a complete response
        if (isCompleteResponse(completeOutput)) {
            processCompleteResponse(completeOutput)
            // Clear buffer but keep processing state until next command
        }
    }

    override fun onStatusUpdated(status: String) {
        // Status line updates don't affect parser error detection
    }

    override fun onClear() {
        outputBuffer.clear()
        pendingCommand = null
        isProcessing = false
    }

    /**
     * Heuristic to determine if a response is complete.
     * A response is considered complete if:
     * - It contains a blank line (typical Z-machine turn separator)
     * - It ends with a recognized prompt pattern
     * - It contains a parser error pattern
     */
    private fun isCompleteResponse(output: String): Boolean {
        // Check for blank line (most common turn separator)
        if (output.contains("\n\n")) {
            return true
        }

        // Check for parser error
        if (ParserWrapper.detectParserFailure(output) != null) {
            return true
        }

        // Check for common prompt patterns
        val promptPatterns = listOf(
            ">\n$",      // Ends with > followed by newline
            "\n>$",      // Ends with newline followed by >
            ">$"         // Ends with >
        )

        for (pattern in promptPatterns) {
            if (Regex(pattern).containsMatchIn(output.takeLast(10))) {
                return true
            }
        }

        return false
    }

    /**
     * Process a complete response to detect and handle parser errors.
     */
    private fun processCompleteResponse(output: String) {
        val command = pendingCommand
        if (command == null) {
            Log.d(TAG, "No pending command, skipping error detection")
            return
        }

        val error = ParserWrapper.detectParserFailure(output)

        if (error != null) {
            Log.d(TAG, "Parser error detected: ${error.type}")
            Log.d(TAG, "Original: '$command'")
            Log.d(TAG, "Output: ${output.take(200)}")

            // Notify callback for error handling
            onParserError(command, error)
        } else {
            // No error - command succeeded
            Log.d(TAG, "No error detected for command: '$command'")
        }

        // Reset for next command
        outputBuffer.clear()
    }

    /**
     * Resets the listener state.
     */
    fun reset() {
        outputBuffer.clear()
        pendingCommand = null
        isProcessing = false
    }
}
