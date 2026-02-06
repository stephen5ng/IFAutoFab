package com.ifautofab.parser

import android.util.Log

/**
 * State machine for tracking command retries.
 * Enforces single-retry constraint to prevent infinite loops.
 */
enum class RetryState {
    /** No command pending */
    IDLE,

    /** Command sent, awaiting response */
    COMMAND_SENT,

    /** Parser error detected, retry available */
    ERROR_DETECTED,

    /** Retry attempted, no more retries allowed */
    RETRY_SENT,

    /** Retry also failed, showing original error */
    FAILED
}

/**
 * State machine that enforces the single-retry constraint.
 * Thread-safe for use across the UI and worker threads.
 */
class RetryStateMachine {

    private val TAG = "RetryStateMachine"

    @Volatile
    private var state: RetryState = RetryState.IDLE

    @Volatile
    private var originalCommand: String = ""

    @Volatile
    private var rewrittenCommand: String = ""

    @Volatile
    private var lastError: ErrorInfo? = null

    /**
     * Called when a command is sent to the interpreter.
     * Transitions from IDLE/FAILED to COMMAND_SENT.
     */
    fun onCommandSent(command: String) {
        synchronized(this) {
            when (state) {
                RetryState.IDLE, RetryState.FAILED -> {
                    state = RetryState.COMMAND_SENT
                    originalCommand = command
                    rewrittenCommand = ""
                    lastError = null
                    Log.d(TAG, "Command sent: '$command', state → COMMAND_SENT")
                }

                RetryState.RETRY_SENT -> {
                    // This is the retry result coming back
                    Log.d(TAG, "Retry response received for: '$command'")
                }

                else -> {
                    Log.w(TAG, "Unexpected command in state: $state")
                }
            }
        }
    }

    /**
     * Called when a parser error is detected in the output.
     * Transitions from COMMAND_SENT to ERROR_DETECTED.
     * Transitions from RETRY_SENT to FAILED (retry failed too).
     *
     * @return true if a retry should be attempted, false otherwise
     */
    fun onParserError(error: ErrorInfo): Boolean {
        synchronized(this) {
            return when (state) {
                RetryState.COMMAND_SENT -> {
                    state = RetryState.ERROR_DETECTED
                    lastError = error
                    Log.d(TAG, "Parser error detected: ${error.type}, state → ERROR_DETECTED")
                    true  // Retry is available
                }

                RetryState.RETRY_SENT -> {
                    state = RetryState.FAILED
                    Log.w(TAG, "Retry also failed: ${error.type}, state → FAILED")
                    true  // Signal that retry failed
                }

                else -> {
                    Log.d(TAG, "Parser error in state $state - ignoring")
                    false
                }
            }
        }
    }

    /**
     * Checks if a retry is currently available.
     */
    fun canRetry(): Boolean {
        synchronized(this) {
            return state == RetryState.ERROR_DETECTED
        }
    }

    /**
     * Called when a rewritten command is about to be sent.
     * Transitions from ERROR_DETECTED to RETRY_SENT.
     */
    fun onRetrySent(rewrittenCommand: String) {
        synchronized(this) {
            state = RetryState.RETRY_SENT
            this.rewrittenCommand = rewrittenCommand
            Log.i(TAG, "Retry sent: '$rewrittenCommand', state → RETRY_SENT")
        }
    }

    /**
     * Called when a command succeeds (no error detected).
     * Resets state to IDLE.
     */
    fun onSuccess() {
        synchronized(this) {
            if (state != RetryState.IDLE) {
                Log.d(TAG, "Command succeeded, state → IDLE")
                state = RetryState.IDLE
                originalCommand = ""
                rewrittenCommand = ""
                lastError = null
            }
        }
    }

    /**
     * Gets the original command that was sent.
     */
    fun getOriginalCommand(): String {
        synchronized(this) {
            return originalCommand
        }
    }

    /**
     * Gets the rewritten command (if a retry was attempted).
     */
    fun getRewrittenCommand(): String {
        synchronized(this) {
            return rewrittenCommand
        }
    }

    /**
     * Gets the last error that was detected.
     */
    fun getLastError(): ErrorInfo? {
        synchronized(this) {
            return lastError
        }
    }

    /**
     * Gets the current state.
     */
    fun getState(): RetryState {
        synchronized(this) {
            return state
        }
    }

    /**
     * Resets the state machine to initial state.
     * Call this when starting a new game or clearing state.
     */
    fun reset() {
        synchronized(this) {
            state = RetryState.IDLE
            originalCommand = ""
            rewrittenCommand = ""
            lastError = null
            Log.d(TAG, "State machine reset")
        }
    }

    /**
     * Returns a human-readable description of the current state.
     */
    fun getStateDescription(): String {
        return state.name
    }
}
