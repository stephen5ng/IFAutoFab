package com.ifautofab.parser

import android.util.Log
import java.util.UUID

/**
 * Simple logger interface to allow testing without Android dependencies.
 */
interface Logger {
    fun d(tag: String, msg: String): Int
    fun i(tag: String, msg: String): Int = d(tag, msg)
    fun w(tag: String, msg: String): Int = d(tag, msg)
    fun e(tag: String, msg: String, e: Throwable? = null): Int
}

/**
 * Android logger implementation.
 */
private class AndroidLogger : Logger {
    override fun d(tag: String, msg: String): Int = Log.d(tag, msg)
    override fun i(tag: String, msg: String): Int = Log.i(tag, msg)
    override fun w(tag: String, msg: String): Int = Log.w(tag, msg)
    override fun e(tag: String, msg: String, e: Throwable?): Int = Log.e(tag, msg, e)
}

/**
 * No-op logger for testing.
 */
object NoOpLogger : Logger {
    override fun d(tag: String, msg: String): Int = 0
    override fun i(tag: String, msg: String): Int = 0
    override fun w(tag: String, msg: String): Int = 0
    override fun e(tag: String, msg: String, e: Throwable?): Int = 0
}

/**
 * Wrapper for Z-machine parser that enables command rewriting on failure.
 *
 * Constraints:
 * - Maximum ONE rewrite attempt per command
 * - Always falls back to original parser error on retry failure
 * - No state inference or puzzle solving
 * - Transparent operation (logs all actions)
 */
object ParserWrapper {

    private const val TAG = "ParserWrapper"
    private var logger: Logger = AndroidLogger()

    // Parser state tracking
    private var lastCommand: String = ""
    private var lastAttemptedRewrite: String? = null
    private var retryAvailable: Boolean = true

    // Configuration
    const val MAX_RETRIES = 1  // Single retry only

    // Enable/disable parser assistance
    var isEnabled: Boolean = true

    // Debug mode for verbose logging
    var isDebugMode: Boolean = false

    /**
     * Sets the logger implementation (for testing).
     */
    fun setLogger(l: Logger) {
        logger = l
    }

    /**
     * Intercepts input before sending to native interpreter.
     * Returns the command to send (either original or rewritten).
     */
    fun interceptInput(command: String, context: GameContext): String {
        if (!isEnabled) {
            return command
        }

        // Reset state for new command
        if (command != lastCommand) {
            lastCommand = command
            lastAttemptedRewrite = null
            retryAvailable = true
        }

        if (isDebugMode) {
            logger.d(TAG, "Intercepting input: '$command'")
        }

        // If this is a retry attempt (already rewritten once), fail fast
        if (!retryAvailable) {
            logger.w(TAG, "Retry exhausted, returning original: $command")
            return command
        }

        return command
    }

    /**
     * Analyzes output to detect parser failures.
     * Returns null if no error, or ErrorInfo if detected.
     */
    fun detectParserFailure(output: String): ErrorInfo? {
        if (output.isBlank()) return null

        for ((pattern, type) in ERROR_PATTERNS) {
            val matcher = Regex(pattern, RegexOption.IGNORE_CASE).find(output)
            if (matcher != null) {
                val error = ErrorInfo(type, matcher.value, output)
                if (isDebugMode) {
                    logger.d(TAG, "Detected error type: $type, matched: '${matcher.value}'")
                }
                return error
            }
        }

        return null
    }

    /**
     * Determines if a rewrite should be attempted for this error type.
     */
    fun shouldAttemptRewrite(error: ErrorInfo): Boolean {
        val shouldRewrite = when (error.type) {
            // Rewritable errors
            ErrorType.UNKNOWN_VERB,
            ErrorType.UNKNOWN_NOUN,
            ErrorType.AMBIGUOUS,
            ErrorType.SYNTAX -> true

            // Do NOT rewrite - these are genuine game responses
            ErrorType.CANT_DO_THAT,
            ErrorType.NO_SUCH_THING,
            ErrorType.DARKNESS,
            ErrorType.NOT_HERE -> false
        }

        if (isDebugMode) {
            logger.d(TAG, "Should attempt rewrite for ${error.type}: $shouldRewrite")
        }

        return shouldRewrite
    }

    /**
     * Marks a rewrite attempt (consumes the single retry).
     */
    fun markRewriteAttempted(rewrite: String) {
        lastAttemptedRewrite = rewrite
        retryAvailable = false
        logger.i(TAG, "Rewrite attempted: '$lastCommand' → '$rewrite'")
        ParserLogger.logRewriteAttempted(lastCommand, rewrite, ErrorInfo(ErrorType.UNKNOWN_VERB, "", ""))
    }

    /**
     * Resets state (e.g., on new turn or game restart).
     */
    fun reset() {
        lastCommand = ""
        lastAttemptedRewrite = null
        retryAvailable = true
        logger.d(TAG, "State reset")
    }

    /**
     * Gets the last command that was processed.
     */
    fun getLastCommand(): String = lastCommand

    /**
     * Gets the last attempted rewrite (if any).
     */
    fun getLastAttemptedRewrite(): String? = lastAttemptedRewrite

    /**
     * Checks if a retry is still available for the current command.
     */
    fun isRetryAvailable(): Boolean = retryAvailable
}

/**
 * Error type classification for parser failures.
 */
enum class ErrorType {
    /** Parser doesn't understand the verb: "I don't understand that sentence" */
    UNKNOWN_VERB,

    /** Parser doesn't recognize the object: "You can't see any such thing" */
    UNKNOWN_NOUN,

    /** Multiple objects match: "Which do you mean..." */
    AMBIGUOUS,

    /** Syntax error in command structure: "I only understood you as far as..." */
    SYNTAX,

    /** Game logic refusal (not a parser error): "You can't do that" */
    CANT_DO_THAT,

    /** World state response (not a parser error): object doesn't exist */
    NO_SUCH_THING,

    /** Environmental condition: "It's too dark to see" */
    DARKNESS,

    /** Object not present in current location: "You don't have the key" */
    NOT_HERE
}

/**
 * Detailed information about a detected parser error.
 */
data class ErrorInfo(
    val type: ErrorType,
    val matchedText: String,
    val fullOutput: String
)

/**
 * Observable game context for parser use.
 * Does NOT infer state - only contains what's directly observable.
 */
data class GameContext(
    val currentRoom: String? = null,
    val visibleObjects: Set<String> = emptySet(),
    val inventory: Set<String> = emptySet(),
    val recentCommands: List<String> = emptyList(),
    val exits: Set<String> = emptySet()
) {
    companion object {
        /** Empty context for when no game state is available */
        val EMPTY = GameContext()
    }
}

/**
 * Error patterns compiled from Infocom/Z-machine conventions.
 * Order matters: more specific patterns should be checked first.
 */
private val ERROR_PATTERNS: List<Pair<String, ErrorType>> = listOf(
    // Ambiguity errors (check first as they contain other error text)
    """Which do you mean, """ to ErrorType.AMBIGUOUS,
    """Do you mean the """ to ErrorType.AMBIGUOUS,
    """The word ["']([^"']+)["'] (should be|is) (not|unused)""" to ErrorType.AMBIGUOUS,

    // Darkness/environmental
    """It['']s (too dark|pitch dark|dark) to see""" to ErrorType.DARKNESS,
    """It is (too dark|pitch dark|dark) to see""" to ErrorType.DARKNESS,

    // Verb errors
    """I don['']t understand that sentence""" to ErrorType.UNKNOWN_VERB,
    """I don['']t understand ["'].*?["'] sentence""" to ErrorType.UNKNOWN_VERB,
    """I don['']t understand the word""" to ErrorType.UNKNOWN_VERB,
    """I don['']t know the word ["']([^"']+)["']""" to ErrorType.UNKNOWN_VERB,
    """You used the word ["']([^"']+)["'] in a way that I don['']t understand""" to ErrorType.UNKNOWN_VERB,
    """I didn['']t understand that sentence""" to ErrorType.UNKNOWN_VERB,
    """I can['']t see that""" to ErrorType.UNKNOWN_VERB,
    """I don['']t know how to""" to ErrorType.UNKNOWN_VERB,

    // Noun/Object errors
    """You can['']t see any such thing""" to ErrorType.UNKNOWN_NOUN,
    """I don['']t see (that|the|any) ["']?([^"']+)["']?""" to ErrorType.UNKNOWN_NOUN,
    """There is (no|none of that) (here|here now)""" to ErrorType.UNKNOWN_NOUN,
    """You don['']t see that here""" to ErrorType.UNKNOWN_NOUN,

    // Syntax errors
    """I only understood you as far as""" to ErrorType.SYNTAX,
    """You seem to have said too much""" to ErrorType.SYNTAX,
    """I understood ["']([^"']+)["'] as far as""" to ErrorType.SYNTAX,

    // Game-state responses (NOT parser errors)
    """You can['']t do that""" to ErrorType.CANT_DO_THAT,
    """Nothing to """ to ErrorType.CANT_DO_THAT,

    // Object not available (game state, not parser error)
    """You don['']t have (the|that|any)""" to ErrorType.NOT_HERE,
    """You['']re not holding (the|that|any)""" to ErrorType.NOT_HERE,
    """(There is no|That['']s not available)""" to ErrorType.NOT_HERE,

    // Nothing here
    """There['']s nothing (here|there)""" to ErrorType.NO_SUCH_THING,
)
