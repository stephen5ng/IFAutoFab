package com.ifautofab.parser.llm

import com.ifautofab.parser.Logger
import java.io.File
import java.util.Properties

/**
 * Test-safe logger that doesn't use Android Log.
 */
class TestLogger : Logger {
    override fun d(tag: String, msg: String): Int {
        println("[D] $tag: $msg")
        return 0
    }
    override fun i(tag: String, msg: String): Int {
        println("[I] $tag: $msg")
        return 0
    }
    override fun w(tag: String, msg: String): Int {
        println("[W] $tag: $msg")
        return 0
    }
    override fun e(tag: String, msg: String, e: Throwable?): Int {
        println("[E] $tag: $msg${e?.let { ": ${it.message}" } ?: ""}")
        return 0
    }
}

/**
 * Helper to get API keys from local.properties.
 */
object TestApiKeyHelper {
    fun getGeminiApiKey(): String? {
        return getApiKey("gemini.api.key")
    }

    fun getGroqApiKey(): String? {
        return getApiKey("groq.api.key")
    }

    fun getTogetherApiKey(): String? {
        return getApiKey("together.api.key")
    }

    private fun getApiKey(key: String): String? {
        try {
            // Try multiple possible locations for local.properties
            val possiblePaths = listOf(
                "local.properties",  // Current directory
                "../local.properties",  // Parent directory (app/)
                "../../local.properties"  // Grandparent (project root)
            )

            for (path in possiblePaths) {
                val localProperties = File(path)
                if (localProperties.exists()) {
                    val properties = Properties()
                    localProperties.inputStream().use { properties.load(it) }
                    val apiKey = properties.getProperty(key)
                    if (apiKey != null && !apiKey.startsWith("your-")) {
                        return apiKey
                    }
                }
            }
        } catch (e: Exception) {
            println("Warning: Could not read local.properties: ${e.message}")
        }
        return null
    }
}
