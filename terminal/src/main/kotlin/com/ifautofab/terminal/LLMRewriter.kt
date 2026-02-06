package com.ifautofab.terminal

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class LLMRewriter(private val apiKey: String) {

    private val apiUrl = "https://api.groq.com/openai/v1/chat/completions"
    private val model = "llama-3.1-8b-instant"

    // Error patterns ported from ParserWrapper.kt
    private val errorPatterns = listOf(
        "I don't know the word" to true,
        "I don't understand that sentence" to true,
        "I don't understand the word" to true,
        "You can't see any such thing" to true,
        "I don't see any" to true,
        "I don't see that" to true,
        "I only understood you as far as" to true,
        "You seem to have said too much" to true,
        "Which do you mean" to true
    )

    fun hasError(output: String): Boolean {
        return errorPatterns.any { (pattern, rewritable) ->
            output.contains(pattern, ignoreCase = true) && rewritable
        }
    }

    fun rewrite(command: String, gameOutput: String, vocabulary: Vocabulary): String? {
        val prompt = buildPrompt(command, gameOutput, vocabulary)
        val jsonBody = buildJson(prompt)

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
                // Read error stream for debugging
                val errorMsg = conn.errorStream?.bufferedReader()?.use { it.readText() }
                println("LLM API Error: ${conn.responseCode} - $errorMsg")
                return null
            }

            // Read response
            val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            
            // Simple parsing to extract content
            return extractContent(response)

        } catch (e: Exception) {
            println("LLM Connection Error: ${e.message}")
            return null
        }
    }

    private fun buildPrompt(command: String, error: String, vocabulary: Vocabulary): String {
        return """
            You are a helper for a Z-machine text adventure game.
            The user entered a command that the parser did not understand.
            Your job is to rewrite the command into a valid Format that the game will understand, using the provided vocabulary.
            
            Vocabulary:
            Verbs: ${vocabulary.verbs.take(50).joinToString(", ")}...
            Nouns: ${vocabulary.nouns.take(50).joinToString(", ")}...
            
            Rules:
            1. Return ONLY the rewritten command. Nothing else.
            2. If you cannot rewrite it with high confidence to a valid command, return <NO_VALID_REWRITE>.
            3. Use simple verb-noun structures (e.g., "take sword", "go north").
            4. Do not apologize or explain.
            5. Do NOT includes quotes or prefixes like ">" or "Command:".
            
            User Command: "$command"
            Game Error: "$error"
            
            Rewrite:
        """.trimIndent()
    }

    private fun buildJson(prompt: String): String {
        // Simple JSON escape
        val escapedPrompt = prompt.replace("\n", "\\n").replace("\"", "\\\"")
        
        return """
            {
                "model": "$model",
                "messages": [
                    {
                        "role": "user",
                        "content": "$escapedPrompt"
                    }
                ],
                "temperature": 0.1
            }
        """.trimIndent()
    }

    private fun extractContent(json: String): String? {
        // Naive JSON parsing finding "content": "..."
        // We look for the "content" field in the message object
        val contentKey = "\"content\":\""
        val startIndex = json.indexOf(contentKey)
        if (startIndex == -1) return null
        
        val valueStart = startIndex + contentKey.length
        
        // Find end of string, handling escaped quotes (simplified)
        var i = valueStart
        while (i < json.length) {
            if (json[i] == '"' && json[i-1] != '\\') {
                break
            }
            i++
        }
        
        if (i >= json.length) return null
        
        val content = json.substring(valueStart, i)
        
        // Unescape JSON string
        val unescaped = content.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
        
        val clean = unescaped.trim().removePrefix(">").removePrefix("Command:").trim()

        if (clean == "<NO_VALID_REWRITE>") return null
        
        return clean
    }
}
