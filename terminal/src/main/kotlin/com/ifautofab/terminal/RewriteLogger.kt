package com.ifautofab.terminal

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Logs LLM rewrite attempts and results to JSONL files.
 *
 * Log format: One JSON object per line (JSONL)
 * Location: ~/.ifautofab/rewrite_logs/{game_name}_{date}.jsonl
 *
 * Two event types:
 * - rewrite_attempt: original command, rewrite, failure type, game output
 * - retry_result: original, rewrite, success/failure, retry output
 */
class RewriteLogger(private val gameName: String) {

    private val logDir = File("${System.getProperty("user.home")}/.ifautofab/rewrite_logs")
    private val logFile: File
    private val timestamp = LocalDateTime.now()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

    // In-memory buffer for log events (written to disk periodically)
    private val eventBuffer = ConcurrentLinkedQueue<Map<String, Any>>()

    init {
        // Create log directory
        logDir.mkdirs()

        // Create log file with game name and date
        val dateStr = timestamp.format(dateFormatter)
        logFile = File(logDir, "${gameName}_$dateStr.jsonl")

        // Write header if file is new
        if (!logFile.exists()) {
            logFile.writeText("")
        }
    }

    /**
     * Logs a rewrite attempt event.
     *
     * @param originalCommand The original command that failed
     * @param rewrite The LLM-provided rewrite (null if no rewrite)
     * @param failureType Type of parser failure
     * @param gameOutput The game's error message
     */
    fun logRewriteAttempt(
        originalCommand: String,
        rewrite: String?,
        failureType: FailureType,
        gameOutput: String
    ) {
        val event = mapOf(
            "event_type" to "rewrite_attempt",
            "timestamp" to timestamp.format(timeFormatter),
            "original_command" to originalCommand,
            "rewrite" to (rewrite ?: ""),
            "rewrite_exists" to (rewrite != null),
            "failure_type" to failureType.name,
            "is_rewritable" to (failureType == FailureType.UNKNOWN_VERB ||
                              failureType == FailureType.UNKNOWN_NOUN ||
                              failureType == FailureType.SYNTAX ||
                              failureType == FailureType.CATCH_ALL),
            "game_output" to gameOutput.take(500)
        )

        eventBuffer.offer(event)
        flushIfNeeded()
    }

    /**
     * Logs a retry result event.
     *
     * @param originalCommand The original command
     * @param rewrite The rewritten command that was tried
     * @param success Whether the retry succeeded
     * @param retryOutput The game's response to the retry
     */
    fun logRetryResult(
        originalCommand: String,
        rewrite: String,
        success: Boolean,
        retryOutput: String
    ) {
        val event = mapOf(
            "event_type" to "retry_result",
            "timestamp" to timestamp.format(timeFormatter),
            "original_command" to originalCommand,
            "rewrite" to rewrite,
            "success" to success,
            "retry_output" to retryOutput.take(500)
        )

        eventBuffer.offer(event)
        flushIfNeeded()
    }

    /**
     * Flushes buffered events to disk.
     * Called automatically when buffer reaches threshold size.
     */
    fun flush() {
        if (eventBuffer.isEmpty()) return

        try {
            val jsonLines = eventBuffer.map { toJson(it) }
            logFile.appendText(jsonLines.joinToString("\n") + "\n")
            eventBuffer.clear()
        } catch (e: Exception) {
            println("Warning: Failed to write rewrite log: ${e.message}")
        }
    }

    /**
     * Flushes if buffer has reached threshold.
     */
    private fun flushIfNeeded() {
        if (eventBuffer.size >= FLUSH_THRESHOLD) {
            flush()
        }
    }

    /**
     * Converts a map to JSON string without external library.
     */
    private fun toJson(data: Map<String, Any>): String {
        val sb = StringBuilder()
        sb.append("{")

        var first = true
        for ((key, value) in data) {
            if (!first) sb.append(",")
            first = false

            sb.append("\"$key\":")

            when (value) {
                is String -> sb.append("\"${escapeJson(value)}\"")
                is Boolean -> sb.append(value)
                is Number -> sb.append(value)
                else -> sb.append("\"${escapeJson(value.toString())}\"")
            }
        }

        sb.append("}")
        return sb.toString()
    }

    /**
     * Escapes string for JSON format.
     */
    private fun escapeJson(s: String): String {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    companion object {
        /** Flush buffer after this many events */
        private const val FLUSH_THRESHOLD = 10
    }
}
