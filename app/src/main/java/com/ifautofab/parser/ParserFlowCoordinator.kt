package com.ifautofab.parser

import android.util.Log
import com.ifautofab.TextOutputInterceptor
import com.luxlunae.glk.model.GLKModel

/**
 * Coordinates the complete parser flow:
 * 1. Intercept input
 * 2. Track command in state machine
 * 3. Detect parser failure from output
 * 4. Attempt single retry via PlaceholderRewriter
 * 5. Fallback to original error
 *
 * This class is the main entry point for the Phase 1 parser assistance system.
 */
object ParserFlowCoordinator {

    private const val TAG = "ParserFlowCoordinator"

    /**
     * Callback interface for retry injection.
     * Implement this to receive retry commands when a rewrite is ready.
     */
    interface OnRetryListener {
        /**
         * Called when a rewritten command is ready to be sent to the interpreter.
         * The implementation should send this command directly, bypassing parser processing.
         *
         * @param originalCommand The command that failed
         * @param retryCommand The rewritten command to send
         */
        fun onRetryReady(originalCommand: String, retryCommand: String)
    }

    private val stateMachine = RetryStateMachine()
    private var outputListener: ParserOutputListener? = null
    private var retryListener: OnRetryListener? = null
    private var isInitialized = false

    // Reference to GLKModel for context extraction
    private var model: GLKModel? = null

    // Track if we're currently processing a retry (to avoid re-processing)
    @Volatile
    private var isProcessingRetry = false

    /**
     * Initializes the coordinator and registers the output listener.
     */
    fun initialize() {
        if (isInitialized) {
            Log.w(TAG, "Already initialized")
            return
        }

        outputListener = ParserOutputListener { command, error ->
            handleParserError(command, error)
        }

        TextOutputInterceptor.addListener(outputListener!!)

        // Also register as a listener for command tracking
        // Note: We need to be notified of commands being sent
        isInitialized = true
        Log.i(TAG, "Initialized")
    }

    /**
     * Sets the GLKModel for context extraction.
     */
    fun setModel(model: GLKModel?) {
        this.model = model
    }

    /**
     * Sets the retry listener for automatic retry injection.
     * When a rewrite is ready, the listener will be called to send it.
     */
    fun setRetryListener(listener: OnRetryListener?) {
        this.retryListener = listener
    }

    /**
     * Processes input before sending to the interpreter.
     * Call this from GLKGameEngine.sendInput().
     *
     * @param input The raw user input
     * @param isRetry True if this is a retry command (skip further processing)
     * @return The command to send (possibly rewritten)
     */
    fun processInput(input: String, isRetry: Boolean = false): String {
        if (!isInitialized) {
            Log.w(TAG, "Not initialized, passing through: '$input'")
            return input
        }

        val command = input.trim()

        // If this is a retry command, just pass it through and track for output
        if (isRetry || isProcessingRetry) {
            Log.i(TAG, "Processing retry command: '$command'")
            // Notify output listener to watch for retry result
            outputListener?.onCommandSent(command)
            return command
        }

        // Track command in state machine
        stateMachine.onCommandSent(command)

        // Notify output listener that a command was sent
        outputListener?.onCommandSent(command)

        // Log the command
        ParserLogger.logCommandSent(command)

        // Pass through parser wrapper
        val context = extractGameContext()
        val processedInput = ParserWrapper.interceptInput(command, context)

        if (processedInput != command) {
            Log.i(TAG, "Input processed: '$command' → '$processedInput'")
        }

        return processedInput
    }

    /**
     * Processes output to check for parser errors.
     * This is called automatically via the output listener.
     */
    private fun handleParserError(command: String, error: ErrorInfo) {
        Log.w(TAG, "Parser error detected: ${error.type} for command: '$command'")

        // Log the error
        ParserLogger.logErrorDetected(command, error)

        // Update state machine
        val shouldRetry = stateMachine.onParserError(error)

        if (!shouldRetry) {
            Log.d(TAG, "No retry available (state: ${stateMachine.getState()})")
            return
        }

        // Check if this error type is rewritable
        if (!ParserWrapper.shouldAttemptRewrite(error)) {
            Log.i(TAG, "Error type ${error.type} not rewritable - this is a game response")
            ParserLogger.logFallback(command, error, "Non-rewritable error type (game logic)")
            return
        }

        // Check if retry is available
        if (!stateMachine.canRetry()) {
            Log.d(TAG, "Retry not available in current state")
            return
        }

        // Attempt rewrite
        val context = extractGameContext()
        val rewritten = PlaceholderRewriter.attemptRewrite(command, error, context)

        if (rewritten != null) {
            // Mark the rewrite attempt
            stateMachine.onRetrySent(rewritten)
            ParserWrapper.markRewriteAttempted(rewritten)
            ParserLogger.logRewriteAttempted(command, rewritten, error)

            Log.i(TAG, "Rewrite ready: '$command' → '$rewritten'")

            // Invoke the retry listener to send the command
            val listener = retryListener
            if (listener != null) {
                isProcessingRetry = true
                try {
                    listener.onRetryReady(command, rewritten)
                } finally {
                    isProcessingRetry = false
                }
            } else {
                Log.w(TAG, "No retry listener registered - retry will not be sent")
            }
        } else {
            Log.i(TAG, "No rewrite available, falling back to original error")
            ParserLogger.logFallback(command, error, "No rewrite available")
            stateMachine.reset()
        }
    }

    /**
     * Notifies the coordinator that output was received without error.
     * Call this when the turn completes successfully.
     */
    fun onOutputSuccess() {
        stateMachine.onSuccess()
    }

    /**
     * Extracts current game context for parser use.
     * Phase 1: Minimal context (room name, objects not extracted yet)
     * Phase 2+: Will include visible objects, inventory, etc.
     */
    private fun extractGameContext(): GameContext {
        // Phase 1: Return empty context
        // Future: Parse the GLKModel buffer for room info, objects, etc.

        // Basic info we can get:
        // - Current room would require parsing the buffer
        // - Visible objects would require parsing the buffer
        // - Inventory would require parsing the buffer

        return GameContext.EMPTY
    }

    /**
     * Resets the coordinator state.
     * Call this when starting a new game.
     */
    fun reset() {
        stateMachine.reset()
        outputListener?.reset()
        isProcessingRetry = false
        Log.d(TAG, "Reset")
    }

    /**
     * Shuts down the coordinator and unregisters listeners.
     */
    fun shutdown() {
        outputListener?.let {
            TextOutputInterceptor.removeListener(it)
        }
        stateMachine.reset()
        retryListener = null
        isProcessingRetry = false
        isInitialized = false
        Log.i(TAG, "Shutdown")
    }

    /**
     * Checks if a retry is currently being processed.
     */
    fun isProcessingRetry(): Boolean {
        return isProcessingRetry
    }

    /**
     * Gets the current state for debugging/testing.
     */
    fun getState(): RetryState {
        return stateMachine.getState()
    }

    /**
     * Checks if the coordinator is initialized.
     */
    fun isInitialized(): Boolean {
        return isInitialized
    }

    /**
     * Gets statistics from the current session.
     */
    fun getStats(): ParserStats {
        return ParserLogger.getStats()
    }
}
