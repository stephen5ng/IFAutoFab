package com.ifautofab.parser

import android.util.Log
import com.ifautofab.TextOutputInterceptor
import com.ifautofab.parser.llm.CloudLLMCommandRewriter
import com.ifautofab.parser.llm.LLMConfig
import com.ifautofab.parser.llm.LLMConfigManager
import com.ifautofab.parser.llm.LLMProvider
import com.luxlunae.glk.model.GLKModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android logger implementation for ParserFlowCoordinator.
 */
private class ParserFlowCoordinatorLogger : Logger {
    override fun d(tag: String, msg: String): Int = Log.d(tag, msg)
    override fun i(tag: String, msg: String): Int = Log.i(tag, msg)
    override fun w(tag: String, msg: String): Int = Log.w(tag, msg)
    override fun e(tag: String, msg: String, e: Throwable?): Int = Log.e(tag, msg, e)
}

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
    private var logger: Logger = ParserFlowCoordinatorLogger()

    /**
     * Sets the logger implementation (for testing).
     */
    fun setLogger(l: Logger) {
        logger = l
    }

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

    // LLM rewriter (initialized when game loads with vocabulary)
    private var llmRewriter: CloudLLMCommandRewriter? = null

    // Coroutine scope for async LLM calls
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Track if we're currently processing a retry (to avoid re-processing)
    @Volatile
    private var isProcessingRetry = false

    /**
     * Initializes the coordinator and registers the output listener.
     */
    fun initialize() {
        if (isInitialized) {
            logger.w(TAG, "Already initialized")
            return
        }

        outputListener = ParserOutputListener { command, error ->
            handleParserError(command, error)
        }

        TextOutputInterceptor.addListener(outputListener!!)

        // Also register as a listener for command tracking
        // Note: We need to be notified of commands being sent
        isInitialized = true
        logger.i(TAG, "Initialized")
    }

    /**
     * Sets the GLKModel for context extraction.
     */
    fun setModel(model: GLKModel?) {
        this.model = model
    }

    /**
     * Initializes the LLM rewriter with the game's vocabulary.
     * Call this when a game is loaded.
     *
     * @param vocabulary The extracted game vocabulary
     * @param apiKey The Groq API key (or other provider key)
     */
    fun initializeLLM(vocabulary: ZMachineVocabulary, apiKey: String) {
        try {
            val config = LLMConfig(
                provider = LLMProvider.GROQ,
                apiKey = apiKey,
                model = "llama-3.1-8b-instant",  // Fast model
                maxTokens = 50,
                temperature = 0.3
            )

            llmRewriter = CloudLLMCommandRewriter(config, vocabulary)

            logger.i(TAG, "LLM rewriter initialized with ${vocabulary.verbs.size} verbs, " +
                    "${vocabulary.nouns.size} nouns, ${vocabulary.prepositions.size} prepositions")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to initialize LLM rewriter: ${e.message}", e)
            llmRewriter = null
        }
    }

    /**
     * Initializes the LLM rewriter using LLMConfigManager.
     * Call this before starting a game if you've configured LLM globally.
     */
    fun initializeLLM() {
        try {
            if (!LLMConfigManager.isConfigured()) {
                logger.w(TAG, "LLM not configured in LLMConfigManager - skipping LLM initialization")
                return
            }

            val config = LLMConfigManager.getConfig()

            // Note: We need vocabulary which will be extracted from the game file
            // This is a placeholder - vocabulary should be passed when game loads
            logger.i(TAG, "LLM configured with ${config.provider} ${config.model}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to initialize LLM: ${e.message}", e)
        }
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
            logger.w(TAG, "Not initialized, passing through: '$input'")
            return input
        }

        val command = input.trim()

        // If this is a retry command, just pass it through and track for output
        if (isRetry || isProcessingRetry) {
            logger.i(TAG, "Processing retry command: '$command'")
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
            logger.i(TAG, "Input processed: '$command' → '$processedInput'")
        }

        return processedInput
    }

    /**
     * Processes output to check for parser errors.
     * This is called automatically via the output listener.
     */
    private fun handleParserError(command: String, error: ErrorInfo) {
        logger.w(TAG, "Parser error detected: ${error.type} for command: '$command'")

        // Log the error
        ParserLogger.logErrorDetected(command, error)

        // Update state machine
        val shouldRetry = stateMachine.onParserError(error)

        if (!shouldRetry) {
            logger.d(TAG, "No retry available (state: ${stateMachine.getState()})")
            return
        }

        // Check if this error type is rewritable
        if (!ParserWrapper.shouldAttemptRewrite(error)) {
            logger.i(TAG, "Error type ${error.type} not rewritable - this is a game response")
            ParserLogger.logFallback(command, error, "Non-rewritable error type (game logic)")
            return
        }

        // Check if retry is available
        if (!stateMachine.canRetry()) {
            logger.d(TAG, "Retry not available in current state")
            return
        }

        // Attempt rewrite
        val context = extractGameContext()

        // Use LLM rewriter if available, otherwise fallback to PlaceholderRewriter
        if (llmRewriter != null && llmRewriter!!.isAvailable()) {
            logger.d(TAG, "Attempting LLM rewrite")

            // Launch coroutine for async API call
            coroutineScope.launch {
                try {
                    val rewritten = llmRewriter!!.attemptRewrite(command, error, context)

                    if (rewritten != null) {
                        // Mark the rewrite attempt
                        stateMachine.onRetrySent(rewritten)
                        ParserWrapper.markRewriteAttempted(rewritten)
                        ParserLogger.logRewriteAttempted(command, rewritten, error)

                        logger.i(TAG, "LLM rewrite ready: '$command' → '$rewritten'")

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
                            logger.w(TAG, "No retry listener registered - LLM rewrite will not be sent")
                        }
                    } else {
                        logger.i(TAG, "LLM could not rewrite command, falling back")
                        ParserLogger.logFallback(command, error, "LLM returned null")
                        stateMachine.reset()
                    }
                } catch (e: Exception) {
                    logger.e(TAG, "LLM rewrite failed: ${e.message}", e)
                    ParserLogger.logFallback(command, error, "LLM API error: ${e.message}")
                    stateMachine.reset()
                }
            }
        } else {
            // Fallback to PlaceholderRewriter
            logger.d(TAG, "LLM not available, using PlaceholderRewriter")
            val rewritten = PlaceholderRewriter.attemptRewrite(command, error, context)

            if (rewritten != null) {
                // Mark the rewrite attempt
                stateMachine.onRetrySent(rewritten)
                ParserWrapper.markRewriteAttempted(rewritten)
                ParserLogger.logRewriteAttempted(command, rewritten, error)

                logger.i(TAG, "Rewrite ready: '$command' → '$rewritten'")

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
                    logger.w(TAG, "No retry listener registered - retry will not be sent")
                }
            } else {
                logger.i(TAG, "No rewrite available, falling back to original error")
                ParserLogger.logFallback(command, error, "No rewrite available")
                stateMachine.reset()
            }
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
        logger.d(TAG, "Reset")
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

        // Clear LLM rewriter
        llmRewriter = null

        logger.i(TAG, "Shutdown")
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
