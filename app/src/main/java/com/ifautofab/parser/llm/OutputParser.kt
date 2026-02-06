package com.ifautofab.parser.llm

/**
 * Parses LLM responses to extract rewritten commands.
 *
 * LLM responses can come in various formats:
 * - Plain text: "take the key"
 * - Quoted: '"take the key"'
 * - With explanation: "Rewrite: take the key"
 * - Multi-line: "Here's a better version:\ntake the key"
 * - NO_VALID_REWRITE marker: "<NO_VALID_REWRITE>"
 *
 * This parser handles all these formats and extracts the clean command.
 */
object OutputParser {

    /** Marker that LLM should return when no valid rewrite exists */
    private const val NO_REWRITE_MARKER = "<NO_VALID_REWRITE>"

    /** Maximum reasonable command length */
    private const val MAX_COMMAND_LENGTH = 100

    /**
     * Parses LLM response to extract the rewritten command.
     *
     * @param response The raw LLM response
     * @return The cleaned rewritten command, or null if NO_VALID_REWRITE
     */
    fun parse(response: String): String? {
        if (response.isBlank()) return null

        val trimmed = response.trim()

        // Check for NO_VALID_REWRITE marker (case-insensitive)
        if (trimmed.contains(NO_REWRITE_MARKER, ignoreCase = true)) {
            return null
        }

        // Extract command from various formats
        val command = when {
            // Format: "take the key" (plain command)
            isPlainCommand(trimmed) -> trimmed

            // Format: "Rewrite: take the key" or "Command: take the key"
            trimmed.contains(":") -> extractAfterColon(trimmed)

            // Format: '"take the key"' (quoted)
            trimmed.startsWith("\"") || trimmed.startsWith("'") -> extractQuoted(trimmed)

            // Multi-line or complex format
            else -> extractFromMultiline(trimmed)
        }

        // Clean up and validate the command
        return cleanAndValidateCommand(command)
    }

    /**
     * Checks if a string looks like a plain command.
     */
    private fun isPlainCommand(text: String): Boolean {
        // Plain commands are lowercase, alphanumeric with spaces
        // No quotes, no colons (except in multi-word phrases), no special characters
        return text.matches(Regex("""^[a-z\s'-]+$""")) &&
               looksLikeCommand(text)
    }

    /**
     * Checks if text looks like a valid IF command.
     */
    private fun looksLikeCommand(text: String): Boolean {
        val words = text.split(Regex("""\s+"""))
        if (words.isEmpty()) return false

        // Commands typically start with a verb (2-10 letters)
        val firstWord = words[0]
        if (firstWord.length !in 2..10) return false
        if (!firstWord.all { it.isLetter() || it == '\'' }) return false

        // Total length should be reasonable
        if (text.length > MAX_COMMAND_LENGTH) return false

        // Should contain at least one verb-like word
        return words.any { it.length in 2..10 && it.all { char -> char.isLetter() } }
    }

    /**
     * Extracts text after a colon marker.
     */
    private fun extractAfterColon(text: String): String {
        val parts = text.split(":", limit = 2)
        if (parts.size < 2) return text

        val extracted = parts[1].trim()
        return if (extracted.isNotBlank()) extracted else text
    }

    /**
     * Extracts text from quotes.
     */
    private fun extractQuoted(text: String): String {
        val quote = if (text.startsWith("\"")) "\"" else "'"

        val start = text.indexOf(quote)
        val end = text.lastIndexOf(quote)

        return if (start >= 0 && end > start) {
            text.substring(start + 1, end).trim()
        } else {
            text
        }
    }

    /**
     * Extracts command from multi-line or complex responses.
     */
    private fun extractFromMultiline(text: String): String {
        val lines = text.lines()

        for (line in lines) {
            val trimmed = line.trim()

            // Skip empty lines
            if (trimmed.isEmpty()) continue

            // Skip obvious non-command lines
            if (isExplanationLine(trimmed)) continue

            // Check if this looks like a command
            if (looksLikeCommand(trimmed)) {
                return trimmed
            }
        }

        // Fallback: return first non-empty line
        return lines.firstOrNull { it.isNotBlank() } ?: text
    }

    /**
     * Checks if a line is an explanation rather than a command.
     */
    private fun isExplanationLine(line: String): Boolean {
        val lower = line.lowercase()

        return lower.startsWith("here") ||
               lower.startsWith("the correct") ||
               lower.startsWith("you should") ||
               lower.startsWith("try") ||
               lower.startsWith("i suggest") ||
               lower.startsWith("instead") ||
               lower.startsWith("better:") ||
               lower.startsWith("rewrite:")
    }

    /**
     * Cleans up and validates the extracted command.
     */
    private fun cleanAndValidateCommand(command: String): String? {
        val cleaned = command
            .trim()
            .replace(Regex("""\s+"""), " ")  // Normalize whitespace
            .removeSuffix(".")              // Remove trailing period
            .removeSuffix("!")              // Remove trailing exclamation
            .removeSuffix("?")              // Remove trailing question mark
            .removeSuffix(",")              // Remove trailing comma
            .lowercase()

        // Validate the cleaned command
        if (cleaned.isBlank()) return null
        if (cleaned.length > MAX_COMMAND_LENGTH) return null
        if (!looksLikeCommand(cleaned)) return null

        return cleaned
    }

    /**
     * Batch parse multiple responses (for testing/benchmarking).
     */
    fun parseAll(responses: List<String>): List<String?> {
        return responses.map { parse(it) }
    }
}
