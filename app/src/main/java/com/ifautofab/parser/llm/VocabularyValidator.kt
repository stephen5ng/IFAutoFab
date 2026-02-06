package com.ifautofab.parser.llm

import android.util.Log
import com.ifautofab.parser.GameContext
import com.ifautofab.parser.Logger
import com.ifautofab.parser.ZMachineVocabulary

/**
 * Android logger implementation for VocabularyValidator.
 */
private class VocabularyValidatorLogger : Logger {
    override fun d(tag: String, msg: String): Int = Log.d(tag, msg)
    override fun i(tag: String, msg: String): Int = Log.i(tag, msg)
    override fun w(tag: String, msg: String): Int = Log.w(tag, msg)
    override fun e(tag: String, msg: String, e: Throwable?): Int = Log.e(tag, msg, e)
}

/**
 * Validates LLM rewrites against extracted game vocabulary.
 *
 * Validation ensures that LLM outputs only use valid game vocabulary,
 * preventing hallucinations and enforcing the rewrite-only constraint.
 *
 * Validation Rules:
 * 1. All verbs must be in game's verb list
 * 2. All nouns must be in game's dictionary OR visible objects
 * 3. Prepositions must be in game's preposition list
 * 4. Syntax must match: VERB [NOUN] [PREPOSITION NOUN]
 * 5. No invented words or actions
 */
class VocabularyValidator(
    private val strictMode: Boolean = true
) {

    private val TAG = "VocabularyValidator"
    private var logger: Logger = VocabularyValidatorLogger()

    /**
     * Sets the logger implementation (for testing).
     */
    fun setLogger(l: Logger) {
        logger = l
    }

    // Valid syntax patterns for IF commands
    private val syntaxPatterns = listOf(
        // VERB
        Regex("""^\s*(\w+)\s*$"""),

        // VERB NOUN
        Regex("""^\s*(\w+)\s+(\w+)\s*$"""),

        // VERB ADJECTIVE NOUN
        Regex("""^\s*(\w+)\s+(\w+)\s+(\w+)\s*$"""),

        // VERB NOUN PREP NOUN
        Regex("""^\s*(\w+)\s+(\w+)\s+(\w+)\s+(\w+)\s*$"""),

        // VERB ADJECTIVE NOUN PREP NOUN
        Regex("""^\s*(\w+)\s+(\w+)\s+(\w+)\s+(\w+)\s+(\w+)\s*$""")
    )

    /**
     * Validates a rewrite against game vocabulary.
     *
     * @param rewrite The LLM-generated rewrite
     * @param vocabulary The game's extracted vocabulary
     * @param context Current observable game context
     * @return true if valid, false otherwise
     */
    fun isValid(
        rewrite: String,
        vocabulary: ZMachineVocabulary,
        context: GameContext
    ): Boolean {
        if (rewrite.isBlank()) return false

        // Parse command structure
        val parts = rewrite.trim().split(Regex("""\s+"""))
        if (parts.isEmpty() || parts.size > 5) {
            logger.d(TAG, "Invalid structure (wrong word count): $rewrite")
            return false
        }

        // Validate verb (first word)
        val verb = parts[0].lowercase()
        if (verb !in vocabulary.verbs) {
            logger.d(TAG, "Unknown verb: '$verb' (not in ${vocabulary.verbs.size} verbs)")
            if (strictMode) return false
        }

        // Validate remaining words based on position and type
        return validateRemainingWords(parts.drop(1), vocabulary, context)
    }

    /**
     * Validates non-verb words in the command.
     */
    private fun validateRemainingWords(
        words: List<String>,
        vocabulary: ZMachineVocabulary,
        context: GameContext
    ): Boolean {
        if (words.isEmpty()) return true

        // Check if first word is a preposition (indicates different structure)
        if (words[0].lowercase() in vocabulary.prepositions) {
            return validatePrepositionalPhrase(words, vocabulary, context)
        }

        // Otherwise, expect: [ADJECTIVE?] NOUN [PREP NOUN]
        return validateNounPhrase(words, vocabulary, context)
    }

    /**
     * Validates a noun phrase: [ADJECTIVE?] NOUN [PREP NOUN]
     */
    private fun validateNounPhrase(
        words: List<String>,
        vocabulary: ZMachineVocabulary,
        context: GameContext
    ): Boolean {
        if (words.isEmpty()) return true

        var index = 0

        // Optional adjective
        if (index < words.size) {
            val word = words[index].lowercase()
            if (word in vocabulary.adjectives) {
                index++
            }
        }

        // Required noun
        if (index < words.size) {
            val noun = words[index].lowercase()
            if (!isValidNoun(noun, vocabulary, context)) {
                logger.d(TAG, "Unknown noun: '$noun'")
                if (strictMode) return false
            }
            index++
        }

        // Optional prepositional phrase
        if (index < words.size) {
            return validatePrepositionalPhrase(words.drop(index), vocabulary, context)
        }

        return true
    }

    /**
     * Validates a prepositional phrase: PREP [ADJECTIVE?] NOUN
     */
    private fun validatePrepositionalPhrase(
        words: List<String>,
        vocabulary: ZMachineVocabulary,
        context: GameContext
    ): Boolean {
        if (words.isEmpty()) {
            logger.d(TAG, "Preposition without object")
            return false
        }

        // First word must be a preposition
        val prep = words[0].lowercase()
        if (prep !in vocabulary.prepositions) {
            logger.d(TAG, "Unknown preposition: '$prep'")
            if (strictMode) return false
        }

        // Remaining words: [ADJECTIVE?] NOUN
        val remaining = words.drop(1)
        if (remaining.isEmpty()) {
            logger.d(TAG, "Preposition without object")
            return false
        }

        return validateNounPhrase(remaining, vocabulary, context)
    }

    /**
     * Checks if a word is a valid noun (in dictionary or visible objects).
     */
    private fun isValidNoun(
        word: String,
        vocabulary: ZMachineVocabulary,
        context: GameContext
    ): Boolean {
        val lower = word.lowercase()

        // Check if in game dictionary
        if (lower in vocabulary.nouns) return true

        // Check if it's a visible object (might not be in global dictionary)
        if (lower in context.visibleObjects.map { it.lowercase() }) return true

        // Check if it's in inventory
        if (lower in context.inventory.map { it.lowercase() }) return true

        return false
    }

    /**
     * Returns detailed validation errors for debugging.
     *
     * @return List of validation error messages
     */
    fun getValidationErrors(
        rewrite: String,
        vocabulary: ZMachineVocabulary,
        context: GameContext
    ): List<String> {
        val errors = mutableListOf<String>()

        if (rewrite.isBlank()) {
            errors.add("Rewrite is blank")
            return errors
        }

        val parts = rewrite.trim().split(Regex("""\s+"""))

        // Check word count
        if (parts.isEmpty()) {
            errors.add("No words found")
            return errors
        }
        if (parts.size > 5) {
            errors.add("Too many words (${parts.size} > 5)")
        }

        // Check verb
        if (parts.isNotEmpty()) {
            val verb = parts[0].lowercase()
            if (verb !in vocabulary.verbs) {
                errors.add("Unknown verb: '$verb'")
            }
        }

        // Check remaining words
        parts.drop(1).forEach { word ->
            val lower = word.lowercase()
            val category = when {
                lower in vocabulary.verbs -> "verb"
                lower in vocabulary.adjectives -> "adjective"
                lower in vocabulary.nouns -> "noun (dictionary)"
                lower in vocabulary.prepositions -> "preposition"
                lower in context.visibleObjects.map { it.lowercase() } -> "noun (visible)"
                lower in context.inventory.map { it.lowercase() } -> "noun (inventory)"
                else -> "unknown"
            }

            if (category == "verb") {
                errors.add("Verb in noun position: '$lower'")
            } else if (category == "unknown") {
                errors.add("Unknown word: '$lower'")
            }
        }

        return errors
    }

    /**
     * Validates a batch of rewrites.
     *
     * @return Map of rewrite to validation result
     */
    fun validateAll(
        rewrites: List<String>,
        vocabulary: ZMachineVocabulary,
        context: GameContext
    ): Map<String, Boolean> {
        return rewrites.associateWith { rewrite ->
            isValid(rewrite, vocabulary, context)
        }
    }
}
