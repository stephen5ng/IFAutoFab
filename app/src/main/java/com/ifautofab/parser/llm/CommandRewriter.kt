package com.ifautofab.parser.llm

import com.ifautofab.parser.ErrorInfo
import com.ifautofab.parser.GameContext

/**
 * Interface for command rewriters.
 *
 * Implementations can include:
 * - Cloud LLM-based rewriter (Phase 2)
 * - Local model rewriter (Phase 4)
 * - Rule-based placeholder rewriter (Phase 1)
 */
interface CommandRewriter {
    /**
     * Attempts to rewrite a failed command.
     *
     * @param command The original command that failed
     * @param error The parser error that was detected
     * @param context Current observable game context
     * @return A rewritten command, or null if no rewrite is possible
     */
    suspend fun attemptRewrite(
        command: String,
        error: ErrorInfo,
        context: GameContext
    ): String?

    /**
     * Checks if the rewriter is available.
     *
     * For cloud LLM: requires API configuration and network
     * For local model: requires model file and sufficient resources
     * For placeholder: always available
     */
    fun isAvailable(): Boolean

    /**
     * Gets the name of the rewrite backend.
     *
     * Examples: "Cloud LLM (OPENAI gpt-4)", "Local Model (Phi-3)", "Phase 1 Placeholder"
     */
    fun getBackendName(): String
}
