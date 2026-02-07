package com.ifautofab.terminal

/**
 * Categories of parser failures.
 * Based on Infocom, Inform 6, and Inform 7 error messages.
 */
enum class FailureType {
    /** Parser doesn't understand the verb: "I don't know the word 'grab'" */
    UNKNOWN_VERB,

    /** Parser doesn't recognize the object: "You can't see any such thing" */
    UNKNOWN_NOUN,

    /** Syntax error in command structure: "I don't understand that sentence" */
    SYNTAX,

    /** Multiple objects match: "Which do you mean, the red book or the blue book?" */
    AMBIGUITY,

    /** Game logic refusal (NOT rewritable): "You can't do that" */
    GAME_REFUSAL,

    /** Catch-all for errors that don't match known patterns */
    CATCH_ALL
}

/**
 * Detailed information about a detected parser failure.
 */
data class FailureInfo(
    val type: FailureType,
    val matchedText: String,
    val isRewritable: Boolean
)

/**
 * Detects parser failures in Z-machine game output.
 *
 * Uses a two-tier approach:
 * 1. Regex catalog for known compiler families (Infocom, Inform 6/7)
 * 2. Catch-all heuristic for unknown compilers
 *
 * Reference: app/.../parser/ParserWrapper.kt error patterns (lines 241-283)
 */
object ParserFailureDetector {

    /**
     * Room description markers that indicate normal output (not an error).
     * If output starts with these, it's likely a room description, not a parser error.
     * Must be specific to avoid matching error messages like "You're not holding the key."
     */
    private val ROOM_MARKERS = listOf(
        "You are in ", "You're in ", "You are at ", "You're at ",
        "You are on ", "You're on ", "You are inside ", "You're inside ",
        "You have ", "You see ", "You can see ",
        "You are standing", "You're standing",
        "You are sitting", "You're sitting",
        "North of ", "South of ", "East of ", "West of ",
        "Exits:", "Obvious exits:", "You can go ",
        "The room contains", "You notice", "You spot"
    )

    /**
     * Patterns that indicate normal game output (should NOT be treated as errors).
     */
    private val NORMAL_OUTPUT_PATTERNS = listOf(
        Regex("""^Score: """),                     // Status line
        Regex("""^Moves: \d+"""),
        Regex("""^\s*\d+\.\s+"""),                  // Numbered lists
        Regex("""^>?\s*$"""),                        // Empty prompt
        Regex("""^>"""),                             // Prompt character
        Regex("""^\[.*\]""")                        // Bracketed text
    )

    /**
     * Game refusals that are NOT parser errors (should not be rewritten).
     * These represent valid game logic responses to player actions.
     */
    private val GAME_REFUSAL_PATTERNS = listOf(
        // General refusals
        Regex("""You can'?t do that""", RegexOption.IGNORE_CASE),
        Regex("""Nothing to \w+""", RegexOption.IGNORE_CASE),
        Regex("""That'?s nothing to \w+""", RegexOption.IGNORE_CASE),  // More specific to avoid matching verb errors

        // Environmental/state conditions
        // "It's too dark to see." but NOT "It's too dark to see anything."
        Regex("""^It('s| is) (too dark|pitch dark|dark) to see\.$""", RegexOption.IGNORE_CASE),
        Regex("""^It('s| is) (too dark|pitch dark|dark)\.$""", RegexOption.IGNORE_CASE),

        // Inventory/possession conditions
        Regex("""You( are not|'re not) holding (the |that |any )?""", RegexOption.IGNORE_CASE),
        Regex("""You don'?t have (the |that |any )?""", RegexOption.IGNORE_CASE),
        Regex("""There( is|'s) nothing (here|there)""", RegexOption.IGNORE_CASE),

        // Locked/closed conditions
        Regex("""It'?s locked""", RegexOption.IGNORE_CASE),
        Regex("""It'?s (already )?(open|closed|locked)""", RegexOption.IGNORE_CASE),
        Regex("""(The |That )?.*'?s locked""", RegexOption.IGNORE_CASE),
        Regex("""You can'?t (go|open|close|take)""", RegexOption.IGNORE_CASE)
    )

    /**
     * Ambiguity patterns (NOT errors - player needs to clarify).
     * These should not be rewritten; the game is asking for clarification.
     */
    private val AMBIGUITY_PATTERNS = listOf(
        Regex("""Which do you mean, """, RegexOption.IGNORE_CASE),
        Regex("""Do you mean the """, RegexOption.IGNORE_CASE),
        Regex("""The word ["'].*?["'] (should be|is) (not|unused)""", RegexOption.IGNORE_CASE)
    )

    /**
     * Unknown verb patterns from Infocom, Inform 6, and Inform 7 parsers.
     * These are rewritable - the LLM can suggest synonym verbs.
     */
    private val UNKNOWN_VERB_PATTERNS = listOf(
        // Infocom patterns
        Regex("""I don'?t know the word ["'].*?["']""", RegexOption.IGNORE_CASE),
        Regex("""I don'?t understand (that|this) sentence""", RegexOption.IGNORE_CASE),
        Regex("""I don'?t understand the word""", RegexOption.IGNORE_CASE),
        Regex("""I didn'?t understand (that|this) sentence""", RegexOption.IGNORE_CASE),
        Regex("""You used the word ["'].*?["'] in a way that I don'?t understand""", RegexOption.IGNORE_CASE),
        Regex("""I don'?t know how to (do that|do|)""", RegexOption.IGNORE_CASE),

        // Inform 6/7 patterns
        Regex("""That'?s not a verb I recognise""", RegexOption.IGNORE_CASE),
        Regex("""That'?s not a verb I recognize""", RegexOption.IGNORE_CASE),
        Regex("""That sentence (is not|isn'?t) one I recognise""", RegexOption.IGNORE_CASE),
        Regex("""I can'?t see that (as a verb|)""", RegexOption.IGNORE_CASE),

        // General syntax/verb confusion
        Regex("""I only understood you as far as""", RegexOption.IGNORE_CASE),
        Regex("""You seem to have said too much""", RegexOption.IGNORE_CASE)
    )

    /**
     * Unknown noun/object patterns.
     * These are rewritable - the LLM can suggest corrected object names.
     */
    private val UNKNOWN_NOUN_PATTERNS = listOf(
        // Infocom patterns
        Regex("""You can['']t see any such thing""", RegexOption.IGNORE_CASE),
        Regex("""I don['']t see (that|the|any)? ?["']?.*?["']?""", RegexOption.IGNORE_CASE),
        Regex("""There is (no |none of that )?(here|here now)""", RegexOption.IGNORE_CASE),
        Regex("""You don['']t see that here""", RegexOption.IGNORE_CASE),

        // Inform 6/7 patterns
        Regex("""You can['']t see (a|the|any) .*(here|there|now)""", RegexOption.IGNORE_CASE),
        Regex("""There (is|are) no .*(here|there|available)""", RegexOption.IGNORE_CASE),
        Regex("""I can['']t find (a|the|any)""", RegexOption.IGNORE_CASE),

        // General "not found" patterns
        Regex("""What do you want to""", RegexOption.IGNORE_CASE),
        Regex("""(That|This) is not (available|here|present)""", RegexOption.IGNORE_CASE)
    )

    /**
     * Detects if the given output represents a parser failure.
     *
     * @param output The game output to analyze
     * @return FailureInfo if a parser error is detected, null otherwise
     */
    fun detect(output: String): FailureInfo? {
        val trimmed = output.trim()

        // Skip empty output or prompts
        if (trimmed.isEmpty() || trimmed == ">") {
            return null
        }

        // Skip bracketed annotations (e.g., "[LLM Rewrite: ...]")
        if (trimmed.startsWith("[")) {
            return null
        }

        // Check for room description markers (normal output)
        if (ROOM_MARKERS.any { trimmed.startsWith(it, ignoreCase = true) }) {
            return null
        }

        // Check for normal output patterns
        if (NORMAL_OUTPUT_PATTERNS.any { it.containsMatchIn(trimmed) }) {
            return null
        }

        // Check for game refusals (NOT rewritable)
        for (pattern in GAME_REFUSAL_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                return FailureInfo(
                    type = FailureType.GAME_REFUSAL,
                    matchedText = extractMatch(trimmed, pattern),
                    isRewritable = false
                )
            }
        }

        // Check for ambiguity (NOT rewritable - player needs to clarify)
        for (pattern in AMBIGUITY_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                return FailureInfo(
                    type = FailureType.AMBIGUITY,
                    matchedText = extractMatch(trimmed, pattern),
                    isRewritable = false
                )
            }
        }

        // Check for unknown verb patterns (rewritable)
        for (pattern in UNKNOWN_VERB_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                return FailureInfo(
                    type = FailureType.UNKNOWN_VERB,
                    matchedText = extractMatch(trimmed, pattern),
                    isRewritable = true
                )
            }
        }

        // Check for unknown noun patterns (rewritable)
        for (pattern in UNKNOWN_NOUN_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                return FailureInfo(
                    type = FailureType.UNKNOWN_NOUN,
                    matchedText = extractMatch(trimmed, pattern),
                    isRewritable = true
                )
            }
        }

        // Catch-all heuristic: short, non-empty output that's not a room description
        // This catches unknown compiler error messages
        if (isShortError(trimmed)) {
            return FailureInfo(
                type = FailureType.CATCH_ALL,
                matchedText = trimmed,
                isRewritable = true
            )
        }

        return null
    }

    /**
     * Determines if output looks like a short error message.
     * Catch-all heuristic for unknown compilers.
     *
     * Criteria:
     * - Length < 80 characters
     * - Single line (no newlines)
     * - Doesn't start with room description markers
     * - Not a status line
     * - Contains error-like patterns (not just any short text)
     */
    private fun isShortError(output: String): Boolean {
        val trimmed = output.trim()

        // Must be short
        if (trimmed.length >= 80) return false

        // Must be single line
        if (trimmed.contains("\n")) return false

        // Skip room descriptions
        if (ROOM_MARKERS.any { trimmed.startsWith(it, ignoreCase = true) }) {
            return false
        }

        // Skip status lines
        if (trimmed.startsWith("Score:") || trimmed.startsWith("Moves:")) {
            return false
        }

        // Skip numbered lists or multi-turn output
        if (trimmed.matches(Regex("""^\s*\d+\..*"""))) {
            return false
        }

        // Skip empty or just prompt
        if (trimmed.isEmpty() || trimmed == ">") {
            return false
        }

        // Check for clear error indicators first
        val strongErrorIndicators = listOf(
            "error", "invalid", "unknown", "impossible", "sorry", "try again",
            "no way", "huh", "what", "pardon"
        )
        val hasStrongError = strongErrorIndicators.any { trimmed.contains(it, ignoreCase = true) }
        if (hasStrongError) {
            return true
        }

        // Skip sentences starting with "The " + past tense verb (narrative)
        if (trimmed.matches(Regex("""^The [A-Z][a-z]+ed\.?$"""))) {
            return false
        }

        // Skip "You " + past tense verb (action result)
        if (trimmed.matches(Regex("""^You [a-z]+ed (the |a |an )?"""))) {
            return false
        }

        // Skip sentences starting with "A " or "An " (descriptive)
        if (trimmed.matches(Regex("""^A[n]? [a-zA-Z][a-z]+.*\.$"""))) {
            return false
        }

        // Skip sentences that look like normal narrative
        if (trimmed.matches(Regex("""^[A-Z][a-z]+ [a-z]+.*\.$"""))) {
            // But check for error words first
            val errorWords = listOf("can'?t", "don'?t", "didn'?t", "not", "what", "huh", "pardon")
            val hasErrorWord = errorWords.any { trimmed.contains(it, ignoreCase = true) }
            if (!hasErrorWord) {
                return false
            }
        }

        return true
    }

    /**
     * Extracts the matching portion of text for a given regex pattern.
     * Returns the full input if pattern doesn't capture groups.
     */
    private fun extractMatch(input: String, pattern: Regex): String {
        val match = pattern.find(input) ?: return input
        return match.value
    }
}
