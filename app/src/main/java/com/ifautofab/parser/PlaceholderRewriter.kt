package com.ifautofab.parser

import android.util.Log

/**
 * Placeholder rewrite function for Phase 1.
 * In Phase 1, this simulates a rewrite without calling an LLM.
 * Phase 2 will replace this with actual LLM calls.
 */
object PlaceholderRewriter {

    private const val TAG = "PlaceholderRewriter"

    /**
     * Attempts to rewrite a failed command using simple rule-based transformations.
     *
     * For Phase 1, this applies basic normalization rules.
     * Phase 2 will call the actual LLM service here.
     *
     * @param command The original command that failed
     * @param error The parser error that was detected
     * @param context Current game context
     * @return A rewritten command, or null if no rewrite is possible
     */
    fun attemptRewrite(
        command: String,
        error: ErrorInfo,
        context: GameContext
    ): String? {
        if (command.isBlank()) return null

        Log.i(TAG, "Analyzing: '$command' with error: ${error.type}")

        // Phase 1: Simple rule-based transformations
        // This validates the pipeline without needing an LLM

        // Rule 1: Multiple spaces to single space
        if (command.contains(Regex("""\s{2,}"""))) {
            val normalized = command.replace(Regex("""\s+"""), " ").trim()
            Log.d(TAG, "Rule 1: Normalized whitespace: '$normalized'")
            return normalized
        }

        // Rule 2: Leading/trailing punctuation cleanup
        val leadingPunctuation = setOf(',', '.', ';', ':', '!', '?', '"', '\'')
        if (command.firstOrNull()?.let { it in leadingPunctuation } == true) {
            val cleaned = command.trimStart(*leadingPunctuation.toCharArray()).trim()
            if (cleaned.isNotBlank()) {
                Log.d(TAG, "Rule 2: Trimmed leading punctuation: '$cleaned'")
                return cleaned
            }
        }

        // Rule 3: Trailing punctuation cleanup (except for sentences)
        val trailingPunctuation = setOf(',', ';', ':')
        if (command.lastOrNull()?.let { it in trailingPunctuation } == true) {
            val cleaned = command.trimEnd(*trailingPunctuation.toCharArray()).trim()
            if (cleaned.isNotBlank()) {
                Log.d(TAG, "Rule 3: Trimmed trailing punctuation: '$cleaned'")
                return cleaned
            }
        }

        // Rule 4: Common directional synonyms
        val directionSynonyms = mapOf(
            "forward" to "north",
            "backwards" to "south",
            "backward" to "south",
            "right" to "east",
            "left" to "west",
            "upwards" to "up",
            "upward" to "up",
            "downwards" to "down",
            "downward" to "down",
            "forwards" to "north"
        )

        val lowerCommand = command.lowercase().trim()
        for ((synonym, standard) in directionSynonyms) {
            if (lowerCommand == synonym) {
                Log.d(TAG, "Rule 4: Direction synonym '$synonym' → '$standard'")
                return standard
            }
        }

        // Rule 5: Common verb synonyms (very basic)
        val verbSynonyms = mapOf(
            Regex("""^grab\b""") to "take",
            Regex("""^pick up\b""") to "take",
            Regex("""^examine\b""") to "look at",
            Regex("""^inspect\b""") to "look at",
            Regex("""^check\b""") to "look at",
            Regex("""^open door\b""") to "open door",
            Regex("""^shut\b""") to "close"
        )

        for ((pattern, replacement) in verbSynonyms) {
            if (pattern.containsMatchIn(lowerCommand)) {
                val rewritten = pattern.replace(lowerCommand, replacement)
                Log.d(TAG, "Rule 5: Verb synonym: '$rewritten'")
                return rewritten
            }
        }

        // Rule 6: Handle "go <direction>" → just "<direction>"
        val goPattern = Regex("""^go\s+(.+)$""", RegexOption.IGNORE_CASE)
        val goMatch = goPattern.find(command)
        if (goMatch != null) {
            val direction = goMatch.groupValues[1].trim()
            // Verify it's a direction
            val validDirections = setOf("north", "south", "east", "west", "northeast",
                "northwest", "southeast", "southwest", "up", "down", "n", "s", "e", "w",
                "ne", "nw", "se", "sw", "in", "out")
            if (direction.lowercase() in validDirections) {
                val rewritten = direction.lowercase()
                Log.d(TAG, "Rule 6: Simplified 'go $direction' → '$rewritten'")
                return rewritten
            }
        }

        // No rule-based rewrite available
        Log.d(TAG, "No rule-based rewrite available for: '$command'")
        return null
    }

    /**
     * Returns a simulated LLM response for testing.
     * Only used in test mode to validate the retry flow.
     */
    fun mockRewriteForTesting(command: String): String {
        // Test mock that simulates what Phase 2 LLM might return
        return when {
            command.contains("pick", ignoreCase = true) -> "take $command"
            command.contains("unlock door", ignoreCase = true) -> "unlock door with key"
            command.contains("forward", ignoreCase = true) -> "north"
            command.contains("backward", ignoreCase = true) -> "south"
            command.contains("right", ignoreCase = true) -> "east"
            command.contains("left", ignoreCase = true) -> "west"
            command.contains("examine", ignoreCase = true) -> "look at ${command.substringAfter("examine")}"
            else -> command.lowercase().trim()
        }
    }

    /**
     * Checks if the rewriter is available (always true for placeholder).
     * In Phase 2, this will check network/LLM availability.
     */
    fun isAvailable(): Boolean {
        return true
    }

    /**
     * Gets the name of the rewrite backend.
     */
    fun getBackendName(): String {
        return "Phase 1 Placeholder (Rule-based)"
    }
}
