package com.ifautofab.parser

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Android logger implementation for ParserLogger.
 */
private class ParserLoggerAndroid : Logger {
    override fun d(tag: String, msg: String): Int = Log.d(tag, msg)
    override fun i(tag: String, msg: String): Int = Log.i(tag, msg)
    override fun w(tag: String, msg: String): Int = Log.w(tag, msg)
    override fun e(tag: String, msg: String, e: Throwable?): Int = Log.e(tag, msg, e)
}

/**
 * Sealed class for all parser events.
 */
sealed class ParserEvent {
    abstract val timestamp: Long

    data class CommandSent(
        val command: String,
        override val timestamp: Long
    ) : ParserEvent()

    data class ErrorDetected(
        val command: String,
        val errorType: ErrorType,
        val output: String,
        override val timestamp: Long
    ) : ParserEvent()

    data class RewriteAttempted(
        val original: String,
        val rewritten: String,
        val errorType: ErrorType,
        override val timestamp: Long
    ) : ParserEvent()

    data class RetryResult(
        val rewritten: String,
        val success: Boolean,
        val output: String,
        override val timestamp: Long
    ) : ParserEvent()

    data class Fallback(
        val original: String,
        val errorType: ErrorType,
        val reason: String,
        override val timestamp: Long
    ) : ParserEvent()

    data class SessionStarted(
        val gameName: String?,
        override val timestamp: Long
    ) : ParserEvent()
}

/**
 * Comprehensive logging for parser operations.
 * Enables analysis of failure patterns and rewrite effectiveness.
 */
object ParserLogger {

    private const val TAG = "ParserLog"
    private var logger: Logger = ParserLoggerAndroid()

    private var sessionId: String = UUID.randomUUID().toString()
    private val eventLog = mutableListOf<ParserEvent>()
    private val eventLock = Any()

    /**
     * Sets the logger implementation (for testing).
     */
    fun setLogger(l: Logger) {
        logger = l
    }

    /**
     * Logs that a command was sent to the interpreter.
     */
    fun logCommandSent(command: String) {
        val event = ParserEvent.CommandSent(command, System.currentTimeMillis())
        addEvent(event)
        logger.d(TAG, "[SENT] $command")
    }

    /**
     * Logs that a parser error was detected.
     */
    fun logErrorDetected(command: String, error: ErrorInfo) {
        val event = ParserEvent.ErrorDetected(
            command,
            error.type,
            error.fullOutput.take(500),  // Truncate long outputs
            System.currentTimeMillis()
        )
        addEvent(event)
        logger.d(TAG, "[ERROR ${error.type}] '$command'")
    }

    /**
     * Logs that a rewrite was attempted.
     */
    fun logRewriteAttempted(original: String, rewritten: String, error: ErrorInfo) {
        val event = ParserEvent.RewriteAttempted(
            original,
            rewritten,
            error.type,
            System.currentTimeMillis()
        )
        addEvent(event)
        logger.i(TAG, "[REWRITE ${error.type}] '$original' -> '$rewritten'")
    }

    /**
     * Logs the result of a retry attempt.
     */
    fun logRetryResult(rewritten: String, success: Boolean, output: String) {
        val event = ParserEvent.RetryResult(
            rewritten,
            success,
            output.take(500),
            System.currentTimeMillis()
        )
        addEvent(event)
        val resultStr = if (success) "SUCCESS" else "FAILED"
        logger.i(TAG, "[RETRY $resultStr] '$rewritten'")
    }

    /**
     * Logs that we fell back to showing the original error.
     */
    fun logFallback(original: String, error: ErrorInfo, reason: String) {
        val event = ParserEvent.Fallback(
            original,
            error.type,
            reason,
            System.currentTimeMillis()
        )
        addEvent(event)
        logger.w(TAG, "[FALLBACK ${error.type}] $reason - '$original'")
    }

    /**
     * Logs the start of a new game session.
     */
    fun logSessionStarted(gameName: String? = null) {
        sessionId = UUID.randomUUID().toString()
        synchronized(eventLock) {
            eventLog.clear()
        }
        val event = ParserEvent.SessionStarted(gameName, System.currentTimeMillis())
        addEvent(event)
        logger.i(TAG, "New session: $sessionId (game: $gameName)")
    }

    /**
     * Adds an event to the log in a thread-safe manner.
     */
    private fun addEvent(event: ParserEvent) {
        synchronized(eventLock) {
            eventLog.add(event)
        }
    }

    /**
     * Exports the log as JSON for analysis.
     */
    fun exportLog(): String {
        synchronized(eventLock) {
            val logData = JSONObject()

            val startTime = eventLog.firstOrNull()?.timestamp ?: System.currentTimeMillis()
            logData.put("sessionId", sessionId)
            logData.put("startTime", startTime)
            logData.put("endTime", System.currentTimeMillis())
            logData.put("eventCount", eventLog.size)

            val eventsArray = JSONArray()
            for (event in eventLog) {
                val eventObj = when (event) {
                    is ParserEvent.CommandSent -> JSONObject().apply {
                        put("type", "command_sent")
                        put("command", event.command)
                        put("timestamp", event.timestamp)
                    }
                    is ParserEvent.ErrorDetected -> JSONObject().apply {
                        put("type", "error_detected")
                        put("command", event.command)
                        put("errorType", event.errorType.name)
                        put("output", event.output)
                        put("timestamp", event.timestamp)
                    }
                    is ParserEvent.RewriteAttempted -> JSONObject().apply {
                        put("type", "rewrite_attempted")
                        put("original", event.original)
                        put("rewritten", event.rewritten)
                        put("errorType", event.errorType.name)
                        put("timestamp", event.timestamp)
                    }
                    is ParserEvent.RetryResult -> JSONObject().apply {
                        put("type", "retry_result")
                        put("rewritten", event.rewritten)
                        put("success", event.success)
                        put("output", event.output)
                        put("timestamp", event.timestamp)
                    }
                    is ParserEvent.Fallback -> JSONObject().apply {
                        put("type", "fallback")
                        put("original", event.original)
                        put("errorType", event.errorType.name)
                        put("reason", event.reason)
                        put("timestamp", event.timestamp)
                    }
                    is ParserEvent.SessionStarted -> JSONObject().apply {
                        put("type", "session_started")
                        put("gameName", event.gameName)
                        put("timestamp", event.timestamp)
                    }
                }
                eventsArray.put(eventObj)
            }
            logData.put("events", eventsArray)

            return logData.toString(2)
        }
    }

    /**
     * Gets the current session ID.
     */
    fun getSessionId(): String {
        return sessionId
    }

    /**
     * Calculates statistics from the log.
     */
    fun getStats(): ParserStats {
        synchronized(eventLock) {
            val commands = eventLog.count { it is ParserEvent.CommandSent }
            val errors = eventLog.count { it is ParserEvent.ErrorDetected }
            val rewrites = eventLog.count { it is ParserEvent.RewriteAttempted }
            val successes = eventLog.count {
                it is ParserEvent.RetryResult && it.success
            }

            // Count errors by type
            val errorsByType = eventLog
                .filterIsInstance<ParserEvent.ErrorDetected>()
                .groupingBy { it.errorType }
                .eachCount()

            return ParserStats(
                totalCommands = commands,
                parserErrors = errors,
                rewriteAttempts = rewrites,
                successfulRetries = successes,
                errorRate = if (commands > 0) errors.toDouble() / commands else 0.0,
                successRate = if (rewrites > 0) successes.toDouble() / rewrites else 0.0,
                errorsByType = errorsByType
            )
        }
    }

    /**
     * Gets the number of events currently logged.
     */
    fun getEventCount(): Int {
        synchronized(eventLock) {
            return eventLog.size
        }
    }

    /**
     * Clears all logged events.
     */
    fun clear() {
        synchronized(eventLock) {
            eventLog.clear()
        }
        logger.d(TAG, "Event log cleared")
    }

    /**
     * Gets the raw event log (for testing/debugging).
     */
    fun getRawEvents(): List<ParserEvent> {
        synchronized(eventLock) {
            return eventLog.toList()
        }
    }
}

/**
 * Statistics summary from parser logging.
 */
data class ParserStats(
    val totalCommands: Int,
    val parserErrors: Int,
    val rewriteAttempts: Int,
    val successfulRetries: Int,
    val errorRate: Double,
    val successRate: Double,
    val errorsByType: Map<ErrorType, Int> = emptyMap()
) {
    /**
     * Returns a human-readable summary.
     */
    fun toSummaryString(): String {
        return """
            |Parser Statistics
            |=================
            |Total Commands: $totalCommands
            |Parser Errors: $parserErrors (${String.format("%.1f%%", errorRate * 100)})
            |Rewrite Attempts: $rewriteAttempts
            |Successful Retries: $successfulRetries (${String.format("%.1f%%", successRate * 100)})
            |
            |Errors by Type:
            |${errorsByType.entries.joinToString("\n") { (type, count) -> "  ${type.name}: $count" }}
        """.trimMargin()
    }

    /**
     * Converts stats to a JSON object.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("totalCommands", totalCommands)
            put("parserErrors", parserErrors)
            put("rewriteAttempts", rewriteAttempts)
            put("successfulRetries", successfulRetries)
            put("errorRate", errorRate)
            put("successRate", successRate)

            val errorsByTypeJson = JSONObject()
            for ((type, count) in errorsByType) {
                errorsByTypeJson.put(type.name, count)
            }
            put("errorsByType", errorsByTypeJson)
        }
    }
}
