package com.ifautofab.terminal

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * LLM-based command rewriter for Z-machine games.
 *
 * Uses Groq API with llama-3.1-8b-instant for fast, local-friendly inference.
 * Rewrites failed parser commands into valid game commands using vocabulary-aware prompting.
 */
class LLMRewriter(private val apiKey: String) {

    private val apiUrl = "https://api.groq.com/openai/v1/chat/completions"
    private val model = "llama-3.1-8b-instant"

    /**
     * System prompt that establishes strict rewrite-only behavior.
     * Prevents LLM from providing hints, solving puzzles, or bypassing game logic.
     */
    private val systemPrompt = """
You are a text adventure game parser assistant. Your only job is to rewrite failed player commands into valid commands that the game's parser will understand.

CRITICAL CONSTRAINTS:
1. Rewrite ONLY to fix parser errors (typos, synonyms, word order)
2. NEVER provide hints, solutions, or puzzle help
3. NEVER invent objects or actions not in the game
4. If unsure, return exactly: <NO_VALID_REWRITE>
5. Output ONLY the rewritten command (no explanations, no quotes)

Rewrite Strategy:
- Unknown verbs → Use game's valid verbs
- Unknown nouns → Use game's valid nouns (may be truncated to 6 chars)
- Syntax errors → Restructure to: VERB [NOUN] [PREPOSITION NOUN]
- Typos → Fix spelling while preserving meaning

Remember: You are helping with PARSE errors, not GAME logic. If the command is logically impossible (e.g., "unlock door with invisible key"), return <NO_VALID_REWRITE>.
""".trimIndent()

    /**
     * Rewrites a failed command using the LLM.
     *
     * @param command The original command that failed
     * @param gameOutput The game's error message
     * @param failureType Type of parser failure detected
     * @param vocabulary Game vocabulary for validation
     * @return Rewritten command, or null if no valid rewrite exists
     */
    fun rewrite(command: String, gameOutput: String, failureType: FailureType, vocabulary: Vocabulary): String? {
        val userPrompt = buildUserPrompt(command, gameOutput, failureType, vocabulary)
        val jsonBody = buildJson(systemPrompt, userPrompt)

        try {
            val url = URL(apiUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            // Send request
            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(jsonBody)
            }

            // Check response code
            if (conn.responseCode != 200) {
                val errorMsg = conn.errorStream?.bufferedReader()?.use { it.readText() }
                println("LLM API Error: ${conn.responseCode} - $errorMsg")
                return null
            }

            // Read response
            val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }

            // Extract and clean content
            val extracted = extractContent(response) ?: return null
            val cleaned = cleanRewrite(extracted) ?: return null

            // Validate against vocabulary
            return validateRewrite(cleaned, failureType, vocabulary)

        } catch (e: Exception) {
            println("LLM Connection Error: ${e.message}")
            return null
        }
    }

    /**
     * Builds user prompt with error-specific context and vocabulary.
     */
    private fun buildUserPrompt(
        command: String,
        gameOutput: String,
        failureType: FailureType,
        vocabulary: Vocabulary
    ): String {
        val prompt = StringBuilder()

        // Game output (error message)
        prompt.appendLine("## Game Output")
        prompt.appendLine(gameOutput.trim().take(500))
        prompt.appendLine()

        // Failed command
        prompt.appendLine("## Failed Command")
        prompt.appendLine(command)
        prompt.appendLine()

        // Error type
        prompt.appendLine("## Error Type")
        prompt.appendLine(failureType.name)
        prompt.appendLine()

        // Relevant vocabulary (subset based on error type)
        val relevantVocab = selectRelevantVocabulary(vocabulary, failureType)
        if (relevantVocab.isNotEmpty()) {
            prompt.appendLine("## Valid Game Vocabulary")
            prompt.appendLine(formatVocabulary(relevantVocab))
            prompt.appendLine()
        }

        prompt.appendLine("## Task")
        prompt.appendLine("Rewrite this command (or return <NO_VALID_REWRITE>):")

        return prompt.toString()
    }

    /**
     * Selects vocabulary subset relevant to error type.
     */
    private fun selectRelevantVocabulary(vocabulary: Vocabulary, failureType: FailureType): Map<String, List<String>> {
        return when (failureType) {
            FailureType.UNKNOWN_VERB -> mapOf(
                "Verbs" to vocabulary.verbs.toList().sorted().take(50),
                "Prepositions" to vocabulary.prepositions.toList().sorted()
            )
            FailureType.UNKNOWN_NOUN -> mapOf(
                "Nouns" to vocabulary.nouns.toList().sorted().take(50),
                "Adjectives" to vocabulary.adjectives.toList().sorted().take(30)
            )
            FailureType.SYNTAX -> mapOf(
                "Verbs" to vocabulary.verbs.toList().sorted().take(30),
                "Nouns" to vocabulary.nouns.toList().sorted().take(30),
                "Prepositions" to vocabulary.prepositions.toList().sorted()
            )
            FailureType.CATCH_ALL -> mapOf(
                "Verbs" to vocabulary.verbs.toList().sorted().take(30),
                "Nouns" to vocabulary.nouns.toList().sorted().take(30)
            )
            else -> emptyMap()  // AMBIGUITY, GAME_REFUSAL - not rewritable
        }
    }

    /**
     * Formats vocabulary for prompt display.
     */
    private fun formatVocabulary(vocab: Map<String, List<String>>): String {
        return vocab.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("\n") { (category, words) ->
                "$category: ${words.joinToString(", ")}"
            }
    }

    /**
     * Builds JSON request body with proper escaping.
     */
    private fun buildJson(systemPrompt: String, userPrompt: String): String {
        val escapedSystem = escapeJsonString(systemPrompt)
        val escapedUser = escapeJsonString(userPrompt)

        return """{
  "model": "$model",
  "messages": [
    {
      "role": "system",
      "content": "$escapedSystem"
    },
    {
      "role": "user",
      "content": "$escapedUser"
    }
  ],
  "temperature": 0.3,
  "max_tokens": 50
}"""
    }

    /**
     * Escapes string for JSON format.
     * Handles: quotes, backslashes, newlines, tabs, carriage returns.
     */
    private fun escapeJsonString(s: String): String {
        return s
            .replace("\\", "\\\\")   // Backslashes first
            .replace("\"", "\\\"")   // Quotes
            .replace("\n", "\\n")    // Newlines
            .replace("\r", "\\r")    // Carriage returns
            .replace("\t", "\\t")    // Tabs
    }

    /**
     * Extracts content field from LLM JSON response.
     */
    private fun extractContent(json: String): String? {
        // Find "content": "..." in the response
        val contentKey = "\"content\":\""
        val startIndex = json.indexOf(contentKey)
        if (startIndex == -1) return null

        val valueStart = startIndex + contentKey.length

        // Find end of string, handling escaped quotes
        var i = valueStart
        while (i < json.length) {
            when {
                json[i] == '\\' && i + 1 < json.length -> i += 2  // Skip escaped chars
                json[i] == '"' -> break
                else -> i++
            }
        }

        if (i >= json.length) return null

        val content = json.substring(valueStart, i)

        // Unescape JSON string
        return content
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    /**
     * Cleans the LLM response to extract the command.
     * Removes quotes, prefixes, punctuation, normalizes whitespace.
     */
    private fun cleanRewrite(response: String): String? {
        val trimmed = response.trim()

        // Check for NO_VALID_REWRITE marker
        if (trimmed.contains("<NO_VALID_REWRITE>", ignoreCase = true)) {
            return null
        }

        // Remove common prefixes
        var cleaned = trimmed
            .removePrefix(">")
            .removePrefix("Command:")
            .removePrefix("Rewrite:")
            .removePrefix("The command is:")
            .trim()

        // Extract from quotes if present
        if (cleaned.startsWith("\"") || cleaned.startsWith("'")) {
            val quote = if (cleaned.startsWith("\"")) "\"" else "'"
            val start = cleaned.indexOf(quote)
            val end = cleaned.lastIndexOf(quote)
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start + 1, end).trim()
            }
        }

        // Extract after colon if present
        if (cleaned.contains(":")) {
            val parts = cleaned.split(":", limit = 2)
            if (parts.size == 2 && parts[1].trim().isNotBlank()) {
                cleaned = parts[1].trim()
            }
        }

        // Normalize whitespace
        cleaned = cleaned.replace(Regex("""\s+"""), " ")

        // Remove trailing punctuation
        cleaned = cleaned
            .removeSuffix(".")
            .removeSuffix("!")
            .removeSuffix("?")
            .removeSuffix(",")

        return cleaned.lowercase().trim().takeIf { it.isNotBlank() }
    }

    /**
     * Validates rewrite against game vocabulary.
     * - Hard reject: verbs not in vocabulary
     * - Soft allow: nouns not in vocabulary (dictories may be incomplete)
     * - Reject: > 6 words (too complex)
     */
    private fun validateRewrite(rewrite: String, failureType: FailureType, vocabulary: Vocabulary): String? {
        val words = rewrite.split(Regex("""\s+"""))

        // Length check
        if (words.size > 6) return null
        if (words.isEmpty()) return null

        // Extract verb (first word)
        val verb = words[0].lowercase()

        // Hard reject unknown verbs (except for direction commands)
        val isDirection = verb in setOf("n", "s", "e", "w", "ne", "nw", "se", "sw", "u", "d", "up", "down")
        if (!isDirection && !vocabulary.containsVerb(verb)) {
            return null
        }

        // For verb errors, ensure we're using a known verb
        if (failureType == FailureType.UNKNOWN_VERB && !vocabulary.containsVerb(verb)) {
            return null
        }

        return rewrite
    }
}
