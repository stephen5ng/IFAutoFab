package com.ifautofab.parser.llm

import android.util.Log
import com.ifautofab.parser.ErrorInfo
import com.ifautofab.parser.GameContext
import com.ifautofab.parser.Logger
import com.ifautofab.parser.ParserLogger
import com.ifautofab.parser.ZMachineVocabulary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Helper to build JSON request bodies.
 * Uses string building to avoid Android JSONObject issues in unit tests.
 */
private object JsonBuilder {
    fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    fun buildOpenAIRequest(model: String, maxTokens: Int, temperature: Double, system: String, user: String): String {
        return """{
  "model": "$model",
  "max_tokens": $maxTokens,
  "temperature": $temperature,
  "messages": [
    {"role": "system", "content": "${escapeJson(system)}"},
    {"role": "user", "content": "${escapeJson(user)}"}
  ]
}"""
    }

    fun buildGeminiRequest(combinedPrompt: String, maxTokens: Int, temperature: Double): String {
        return """{
  "contents": [
    {"parts": [{"text": "${escapeJson(combinedPrompt)}"}]}
  ],
  "generationConfig": {
    "maxOutputTokens": $maxTokens,
    "temperature": $temperature,
    "topP": 0.95,
    "topK": 40
  },
  "safetySettings": [
    {"category": "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold": "BLOCK_ONLY_HIGH"}
  ]
}"""
    }
}

/**
 * Android logger implementation for CloudLLMCommandRewriter.
 */
private class CloudLLMLogger : Logger {
    override fun d(tag: String, msg: String): Int = Log.d(tag, msg)
    override fun i(tag: String, msg: String): Int = Log.i(tag, msg)
    override fun w(tag: String, msg: String): Int = Log.w(tag, msg)
    override fun e(tag: String, msg: String, e: Throwable?): Int = Log.e(tag, msg, e)
}

/**
 * Cloud LLM-based command rewriter.
 *
 * Replaces Phase 1 PlaceholderRewriter with actual LLM API calls.
 * Supports OpenAI (GPT-4, GPT-3.5-turbo) with Anthropic/Google planned.
 *
 * Features:
 * - Async API calls with coroutine support
 * - Network availability checking
 * - Timeout handling
 * - Vocabulary validation integration
 * - Graceful fallback on errors
 */
class CloudLLMCommandRewriter(
    private val config: LLMConfig,
    private val vocabulary: ZMachineVocabulary,
    private val validator: VocabularyValidator = VocabularyValidator()
) : CommandRewriter {

    private val TAG = "CloudLLMRewriter"
    private var logger: Logger = CloudLLMLogger()

    /**
     * Sets the logger implementation (for testing).
     */
    fun setLogger(l: Logger) {
        logger = l
        validator.setLogger(l)  // Also set logger on validator
    }

    companion object {
        private const val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
        private const val ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages"
        private const val GOOGLE_API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

        // OpenAI-compatible providers (free tiers available)
        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val TOGETHER_API_URL = "https://api.together.xyz/v1/chat/completions"
    }

    override suspend fun attemptRewrite(
        command: String,
        error: ErrorInfo,
        context: GameContext
    ): String? = withContext(Dispatchers.IO) {
        try {
            logger.i(TAG, "Attempting LLM rewrite: '$command' (error: ${error.type})")

            // Build prompt
            val prompt = PromptTemplate.buildPrompt(
                failedCommand = command,
                lastOutput = error.fullOutput,
                errorType = error.type,
                vocabulary = vocabulary,
                context = context
            )

            logger.d(TAG, "Prompt token estimate: ${prompt.estimatedTokenCount()}")

            // Call LLM API
            val startTime = System.currentTimeMillis()
            val response = callLLMAPI(prompt)
            val latency = System.currentTimeMillis() - startTime

            logger.d(TAG, "LLM response received in ${latency}ms")

            // Parse response
            val rewrite = OutputParser.parse(response)

            if (rewrite == null) {
                logger.i(TAG, "LLM returned <NO_VALID_REWRITE>")
                return@withContext null
            }

            // Validate against game vocabulary
            if (!validator.isValid(rewrite, vocabulary, context)) {
                val errors = validator.getValidationErrors(rewrite, vocabulary, context)
                logger.w(TAG, "LLM rewrite failed validation: '$rewrite'")
                logger.d(TAG, "Validation errors: ${errors.joinToString("; ")}")

                // Log as fallback
                ParserLogger.logFallback(command, error, "Vocabulary validation failed")
                return@withContext null
            }

            // Log successful rewrite
            logger.i(TAG, "LLM rewrite successful: '$command' → '$rewrite'")
            ParserLogger.logRewriteAttempted(command, rewrite, error)

            rewrite

        } catch (e: Exception) {
            logger.e(TAG, "LLM rewrite failed: ${e.message}", e)
            ParserLogger.logFallback(command, error, "LLM API error: ${e.message}")
            null
        }
    }

    /**
     * Calls the appropriate LLM API based on configured provider.
     */
    private fun callLLMAPI(request: LLMRequest): String {
        return when (config.provider) {
            LLMProvider.OPENAI -> callOpenAI(request)
            LLMProvider.GROQ -> callGroq(request)  // OpenAI-compatible
            LLMProvider.TOGETHER -> callTogether(request)  // OpenAI-compatible
            LLMProvider.ANTHROPIC -> callAnthropic(request)
            LLMProvider.GOOGLE -> callGoogle(request)
        }
    }

    /**
     * Calls OpenAI's Chat Completions API.
     */
    private fun callOpenAI(request: LLMRequest): String {
        val url = URL(OPENAI_API_URL)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.doOutput = true
            connection.connectTimeout = config.timeoutMs.toInt()
            connection.readTimeout = config.timeoutMs.toInt()

            // Build request body
            val requestBody = buildOpenAIRequestBody(request)

            logger.d(TAG, "Sending OpenAI request: ${requestBody.length} bytes")

            // Send request
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray())
            }

            // Check response code
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val error = connection.errorStream?.let { errorStream ->
                    BufferedReader(InputStreamReader(errorStream)).readText()
                } ?: "Unknown error"
                throw RuntimeException("OpenAI API error ($responseCode): $error")
            }

            // Read response
            val response = connection.inputStream.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }

            logger.d(TAG, "Received OpenAI response: ${response.length} bytes")

            // Parse response
            return parseOpenAIResponse(response)

        } finally {
            connection.disconnect()
        }
    }

    /**
     * Builds OpenAI request body.
     */
    private fun buildOpenAIRequestBody(request: LLMRequest): String {
        return JsonBuilder.buildOpenAIRequest(
            model = config.model,
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            system = request.system,
            user = request.user
        )
    }

    /**
     * Calls Groq API (OpenAI-compatible).
     * Ultra-fast hosted Llama 3.1, Mixtral, Gemma with free tier.
     */
    private fun callGroq(request: LLMRequest): String {
        val url = URL(GROQ_API_URL)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.doOutput = true
            connection.connectTimeout = config.timeoutMs.toInt()
            connection.readTimeout = config.timeoutMs.toInt()

            val requestBody = buildOpenAIRequestBody(request)

            logger.d(TAG, "Sending Groq request: ${requestBody.length} bytes")

            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray())
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val error = connection.errorStream?.let { errorStream ->
                    BufferedReader(InputStreamReader(errorStream)).readText()
                } ?: "Unknown error"
                throw RuntimeException("Groq API error ($responseCode): $error")
            }

            val response = connection.inputStream.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }

            logger.d(TAG, "Received Groq response: ${response.length} bytes")

            return parseOpenAIResponse(response)

        } finally {
            connection.disconnect()
        }
    }

    /**
     * Calls Together AI API (OpenAI-compatible).
     */
    private fun callTogether(request: LLMRequest): String {
        val url = URL(TOGETHER_API_URL)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.doOutput = true
            connection.connectTimeout = config.timeoutMs.toInt()
            connection.readTimeout = config.timeoutMs.toInt()

            val requestBody = buildOpenAIRequestBody(request)

            logger.d(TAG, "Sending Together request: ${requestBody.length} bytes")

            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray())
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val error = connection.errorStream?.let { errorStream ->
                    BufferedReader(InputStreamReader(errorStream)).readText()
                } ?: "Unknown error"
                throw RuntimeException("Together API error ($responseCode): $error")
            }

            val response = connection.inputStream.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }

            logger.d(TAG, "Received Together response: ${response.length} bytes")

            return parseOpenAIResponse(response)

        } finally {
            connection.disconnect()
        }
    }

    /**
     * Builds Gemini request body.
     *
     * Gemini expects:
     * - contents: array of content objects
     * - Each content has parts: array of text parts
     * - System and user prompts combined into single text
     * - generationConfig for temperature/maxTokens
     */
    private fun buildGeminiRequestBody(request: LLMRequest): String {
        // Combine system and user prompts into single text
        // Gemini doesn't have separate "system" role, so we prefix it
        val combinedPrompt = buildString {
            append("SYSTEM INSTRUCTIONS:\n")
            append(request.system)
            append("\n\n")
            append("USER REQUEST:\n")
            append(request.user)
        }

        return JsonBuilder.buildGeminiRequest(
            combinedPrompt = combinedPrompt,
            maxTokens = request.maxTokens,
            temperature = request.temperature
        )
    }

    /**
     * Parses OpenAI response to extract content.
     */
    private fun parseOpenAIResponse(response: String): String {
        val json = JSONObject(response)
        return json
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    /**
     * Parses Gemini response to extract content.
     *
     * Gemini response structure:
     * {
     *   "candidates": [
     *     {
     *       "content": {
     *         "parts": [
     *           {"text": "rewritten command"}
     *         ]
     *       },
     *       "finishReason": "STOP"
     *     }
     *   ]
     * }
     */
    private fun parseGeminiResponse(response: String): String {
        val json = JSONObject(response)

        // Check for candidates
        if (!json.has("candidates")) {
            throw RuntimeException("Gemini API response missing 'candidates' field")
        }

        val candidates = json.getJSONArray("candidates")
        if (candidates.length() == 0) {
            throw RuntimeException("Gemini API returned no candidates (likely blocked by safety filters)")
        }

        val firstCandidate = candidates.getJSONObject(0)

        // Check finish reason
        val finishReason = firstCandidate.optString("finishReason", "UNKNOWN")
        if (finishReason == "SAFETY") {
            logger.w(TAG, "Gemini blocked response due to safety filters")
            throw RuntimeException("Response blocked by Gemini safety filters")
        }

        // Extract text from content.parts[0].text
        return firstCandidate
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
            .trim()
    }

    /**
     * Calls Anthropic's Claude Messages API.
     * TODO: Implement in Week 2
     */
    private fun callAnthropic(request: LLMRequest): String {
        throw NotImplementedError("Anthropic API not yet implemented. Use OpenAI provider.")
    }

    /**
     * Calls Google's Gemini API.
     *
     * Gemini API structure:
     * - Model name is in the URL path (not request body)
     * - Auth via x-goog-api-key header (not Bearer token)
     * - Combined system/user prompt in single text field
     * - Response in candidates[0].content.parts[0].text
     */
    private fun callGoogle(request: LLMRequest): String {
        // Build dynamic URL with model name
        val url = URL("$GOOGLE_API_BASE_URL/models/${config.model}:generateContent")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", config.apiKey)  // Gemini-specific auth
            connection.doOutput = true
            connection.connectTimeout = config.timeoutMs.toInt()
            connection.readTimeout = config.timeoutMs.toInt()

            // Build request body
            val requestBody = buildGeminiRequestBody(request)

            logger.d(TAG, "Sending Gemini request: ${requestBody.length} bytes")

            // Send request
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray())
            }

            // Check response code
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val error = connection.errorStream?.let { errorStream ->
                    BufferedReader(InputStreamReader(errorStream)).readText()
                } ?: "Unknown error"
                throw RuntimeException("Gemini API error ($responseCode): $error")
            }

            // Read response
            val response = connection.inputStream.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }

            logger.d(TAG, "Received Gemini response: ${response.length} bytes")

            // Parse response
            return parseGeminiResponse(response)

        } finally {
            connection.disconnect()
        }
    }

    override fun isAvailable(): Boolean {
        // Check if configured
        if (!LLMConfigManager.isConfigured()) {
            logger.d(TAG, "LLM not configured")
            return false
        }

        // Check network availability
        if (!isNetworkAvailable()) {
            logger.d(TAG, "Network not available")
            return false
        }

        return true
    }

    override fun getBackendName(): String {
        return "Cloud LLM (${config.provider.name} ${config.model})"
    }

    /**
     * Simple network availability check.
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val url = URL("https://www.google.com")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.requestMethod = "HEAD"
            val available = connection.responseCode == 200
            connection.disconnect()
            available
        } catch (e: Exception) {
            logger.d(TAG, "Network check failed: ${e.message}")
            false
        }
    }

    /**
     * Gets statistics about API usage (for monitoring).
     */
    fun getStats(): LLMStats {
        return LLMStats(
            provider = config.provider.name,
            model = config.model,
            configured = LLMConfigManager.isConfigured(),
            networkAvailable = isNetworkAvailable(),
            backendName = getBackendName()
        )
    }
}

/**
 * Statistics about the LLM rewriter.
 */
data class LLMStats(
    val provider: String,
    val model: String,
    val configured: Boolean,
    val networkAvailable: Boolean,
    val backendName: String
) {
    fun isReady(): Boolean = configured && networkAvailable

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("provider", provider)
            put("model", model)
            put("configured", configured)
            put("networkAvailable", networkAvailable)
            put("backendName", backendName)
            put("ready", isReady())
        }
    }
}
