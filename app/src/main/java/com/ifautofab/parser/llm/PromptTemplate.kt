package com.ifautofab.parser.llm

import com.ifautofab.parser.ErrorInfo
import com.ifautofab.parser.ErrorType
import com.ifautofab.parser.GameContext
import com.ifautofab.parser.ZMachineVocabulary

/**
 * Constructs prompts for LLM command rewriting.
 *
 * Design Principles:
 * - Strict rewrite-only behavior (no hints, no puzzle solving)
 * - Include relevant game vocabulary
 * - Explicit <NO_VALID_REWRITE> instruction
 * - Minimal context (last game output only)
 *
 * The system prompt establishes the guardrails, while the user prompt
 * provides the specific failure context and vocabulary constraints.
 */
object PromptTemplate {

    /**
     * System prompt that establishes strict rewrite-only behavior.
     *
     * This prompt is critical for safety - it prevents the LLM from:
     * - Providing hints or solutions
     * - Bypassing puzzles
     * - Inferring game state
     * - Inventing objects or actions
     */
    private val SYSTEM_PROMPT = """
You are a text adventure game parser assistant. Your only job is to rewrite failed player commands into valid commands that the game's parser will understand.

CRITICAL CONSTRAINTS:
1. Rewrite ONLY to fix parser errors (typos, synonyms, word order)
2. NEVER provide hints, solutions, or puzzle help
3. NEVER invent objects or actions not in the game
4. If unsure, return exactly: <NO_VALID_REWRITE>
5. Output ONLY the rewritten command (no explanations, no quotes)

Rewrite Strategy:
- Unknown verbs → Use game's valid verbs
- Unknown nouns → Use visible objects from context
- Syntax errors → Restructure to: VERB [NOUN] [PREPOSITION NOUN]
- Typos → Fix spelling while preserving meaning

Remember: You are helping with PARSE errors, not GAME logic. If the command is logically impossible (e.g., "unlock door with invisible key"), return <NO_VALID_REWRITE>.
""".trimIndent()

    /**
     * Constructs the full prompt for LLM rewriting.
     *
     * @param failedCommand The command that failed
     * @param lastOutput The game's response (parser error message)
     * @param errorType Type of parser error detected
     * @param vocabulary Current game vocabulary (extracted from game file)
     * @param context Observable game context (visible objects, etc.)
     * @return LLM request with system and user prompts
     */
    fun buildPrompt(
        failedCommand: String,
        lastOutput: String,
        errorType: ErrorType,
        vocabulary: ZMachineVocabulary,
        context: GameContext
    ): LLMRequest {
        val userPrompt = buildUserPrompt(failedCommand, lastOutput, errorType, vocabulary, context)

        return LLMRequest(
            system = SYSTEM_PROMPT,
            user = userPrompt,
            maxTokens = 50,
            temperature = 0.3
        )
    }

    /**
     * Builds the user prompt with context-specific information.
     */
    private fun buildUserPrompt(
        failedCommand: String,
        lastOutput: String,
        errorType: ErrorType,
        vocabulary: ZMachineVocabulary,
        context: GameContext
    ): String {
        val prompt = StringBuilder()

        // Game output (error message)
        prompt.appendLine("## Game Output")
        prompt.appendLine(lastOutput.trim().take(500))
        prompt.appendLine()

        // Failed command
        prompt.appendLine("## Failed Command")
        prompt.appendLine(failedCommand)
        prompt.appendLine()

        // Error type
        prompt.appendLine("## Error Type")
        prompt.appendLine(errorType.name)
        prompt.appendLine()

        // Relevant vocabulary (subset based on context and error type)
        val vocabContext = selectRelevantVocabulary(vocabulary, context, errorType)
        if (vocabContext.hasContent()) {
            prompt.appendLine("## Valid Game Vocabulary")
            prompt.appendLine(buildVocabularySection(vocabContext))
            prompt.appendLine()
        }

        // Visible objects (if known from context)
        if (context.visibleObjects.isNotEmpty()) {
            prompt.appendLine("## Visible Objects")
            prompt.appendLine(context.visibleObjects.sorted().joinToString(", "))
            prompt.appendLine()
        }

        // Exits (if known)
        if (context.exits.isNotEmpty()) {
            prompt.appendLine("## Available Exits")
            prompt.appendLine(context.exits.sorted().joinToString(", "))
            prompt.appendLine()
        }

        prompt.appendLine("## Task")
        prompt.appendLine("Rewrite this command (or return <NO_VALID_REWRITE>):")

        return prompt.toString()
    }

    /**
     * Formats vocabulary for the prompt.
     */
    private fun buildVocabularySection(vocab: ZMachineVocabulary): String {
        val section = StringBuilder()

        if (vocab.verbs.isNotEmpty()) {
            section.appendLine("Verbs: ${vocab.verbs.sorted().take(30).joinToString(", ")}")
        }

        if (vocab.nouns.isNotEmpty()) {
            section.appendLine("Nouns: ${vocab.nouns.sorted().take(30).joinToString(", ")}")
        }

        if (vocab.adjectives.isNotEmpty()) {
            section.appendLine("Adjectives: ${vocab.adjectives.sorted().take(20).joinToString(", ")}")
        }

        if (vocab.prepositions.isNotEmpty()) {
            section.appendLine("Prepositions: ${vocab.prepositions.sorted().joinToString(", ")}")
        }

        return section.toString().trim()
    }

    /**
     * Selects vocabulary subset relevant to current context and error type.
     *
     * Phase 2: Simple heuristics based on error type
     * Phase 3+: ML-based relevance scoring, room-specific vocabulary
     *
     * Strategy:
     * - UNKNOWN_VERB: Show verbs only (reduce noise)
     * - UNKNOWN_NOUN: Show nouns and adjectives
     * - SYNTAX: Show all vocabulary (structure issue)
     * - AMBIGUOUS: Show nouns (need to disambiguate)
     */
    private fun selectRelevantVocabulary(
        vocabulary: ZMachineVocabulary,
        context: GameContext,
        errorType: ErrorType
    ): ZMachineVocabulary {
        return when (errorType) {
            ErrorType.UNKNOWN_VERB -> {
                // For verb errors, focus on verbs and prepositions
                vocabulary.copy(
                    nouns = emptySet(),
                    adjectives = emptySet()
                )
            }

            ErrorType.UNKNOWN_NOUN -> {
                // For noun errors, focus on nouns, adjectives, and prepositions
                vocabulary.copy(
                    verbs = emptySet()
                )
            }

            ErrorType.AMBIGUOUS -> {
                // For ambiguity, show nouns and adjectives to help disambiguate
                vocabulary.copy(
                    verbs = emptySet(),
                    prepositions = emptySet()
                )
            }

            ErrorType.SYNTAX -> {
                // For syntax errors, show all vocabulary (structure issue)
                vocabulary
            }

            else -> {
                // For other errors (not rewritable), don't show vocabulary
                vocabulary.copy(
                    verbs = emptySet(),
                    nouns = emptySet(),
                    adjectives = emptySet(),
                    prepositions = emptySet()
                )
            }
        }
    }
}

/**
 * LLM request containing system and user prompts.
 */
data class LLMRequest(
    val system: String,
    val user: String,
    val maxTokens: Int,
    val temperature: Double
) {
    /**
     * Returns the total approximate token count.
     * Rough estimate: 1 token ≈ 4 characters.
     */
    fun estimatedTokenCount(): Int {
        val totalChars = system.length + user.length
        return (totalChars / 4).coerceAtLeast(1)
    }
}

/**
 * Extension function to check if vocabulary has content.
 */
private fun ZMachineVocabulary.hasContent(): Boolean {
    return verbs.isNotEmpty() || nouns.isNotEmpty() ||
           adjectives.isNotEmpty() || prepositions.isNotEmpty()
}

/**
 * Extension function to create a copy of ZMachineVocabulary with modified fields.
 */
private fun ZMachineVocabulary.copy(
    verbs: Set<String> = this.verbs,
    nouns: Set<String> = this.nouns,
    adjectives: Set<String> = this.adjectives,
    prepositions: Set<String> = this.prepositions
): ZMachineVocabulary {
    return ZMachineVocabulary(
        version = this.version,
        verbs = verbs.toMutableSet(),
        nouns = nouns.toMutableSet(),
        adjectives = adjectives.toMutableSet(),
        prepositions = prepositions.toMutableSet(),
        misc = this.misc
    )
}
