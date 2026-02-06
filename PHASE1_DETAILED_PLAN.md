# Phase 1: Baseline Wrapper Prototype
## Detailed Implementation Plan

**Duration**: Weeks 1-2
**Status**: Planning

---

## Overview

Phase 1 establishes the foundation for LLM-assisted parser repair by implementing input interception, parser failure detection, and a single retry mechanism with strict fallback. The prototype will work with the existing IFAutoFab architecture without modifying the GLK layer or native C code.

---

## Architecture Integration

### Current Architecture Analysis

Based on the existing codebase:

| Component | File | Lines | Current Role | Phase 1 Extension |
|-----------|------|-------|--------------|-------------------|
| Input Capture | MainActivity.kt | 87-98, 100-109, 134-156 | Phone UI input | Add ParserWrapper call |
| Input Capture | CommandScreen.kt | 19-29 | Car quick commands | Add ParserWrapper call |
| Input Routing | GLKGameEngine.kt | 169-250 | Main input handler | Insert ParserWrapper |
| Output Collection | TextOutputInterceptor.kt | 36-45 | Thread-safe output | Add error pattern detection |
| Char Input Check | GLKGameEngine.kt | 211, 340-347 | Char input detection | Error state awareness |
| Line Input Check | GLKGameEngine.kt | 212 | Line input detection | Error state awareness |

### New Components

```
┌─────────────────────────────────────────────────────────────────┐
│ UI Layer (Existing)                                            │
│ ├─ MainActivity, CommandScreen, GameScreen                     │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ ParserWrapper (NEW - Phase 1)                                   │
│ ├─ interceptInput(command, context)                            │
│ ├─ detectParserFailure(output)                                 │
│ ├─ attemptRewrite(command, context) → placeholder              │
│ └─ shouldRetry(errorType) → Boolean                            │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ GLKGameEngine (Existing - Modified Entry Point)                 │
│ ├─ sendInput() → routes to wrapper first                       │
│ └─ receives post-rewrite commands                              │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ Native Interpreter (Unchanged)                                  │
│ └─ bocfel.so via GLKController/JNI                             │
└─────────────────────────────────────────────────────────────────┘
```

---

## Week 1: Input Interception & Parser Failure Detection

### 1.1 Create ParserWrapper Class

**File**: `app/src/main/java/com/ifautofab/parser/ParserWrapper.kt`

```kotlin
/**
 * Wrapper for Z-machine parser that enables command rewriting on failure.
 *
 * Constraints:
 * - Maximum ONE rewrite attempt per command
 * - Always falls back to original parser error on retry failure
 * - No state inference or puzzle solving
 * - Transparent operation (logs all actions)
 */
object ParserWrapper {

    // Parser state tracking
    private var lastCommand: String = ""
    private var lastAttemptedRewrite: String? = null
    private var retryAvailable: Boolean = true

    // Configuration
    const val MAX_RETRIES = 1  // Single retry only

    /**
     * Intercepts input before sending to native interpreter.
     * Returns the command to send (either original or rewritten).
     */
    fun interceptInput(command: String, context: GameContext): String {
        // Reset state for new command
        if (command != lastCommand) {
            lastCommand = command
            lastAttemptedRewrite = null
            retryAvailable = true
        }

        // If this is a retry attempt (already rewritten once), fail fast
        if (!retryAvailable) {
            Log.w("ParserWrapper", "Retry exhausted, returning original: $command")
            return command
        }

        return command
    }

    /**
     * Analyzes output to detect parser failures.
     * Returns null if no error, or ErrorInfo if detected.
     */
    fun detectParserFailure(output: String): ErrorInfo? {
        return ERROR_PATTERTERS.firstNotNullOfOrNull { (pattern, type) ->
            val matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(output)
            if (matcher.find()) {
                ErrorInfo(type, matcher.group(), output)
            } else null
        }
    }

    /**
     * Determines if a rewrite should be attempted for this error type.
     */
    fun shouldAttemptRewrite(error: ErrorInfo): Boolean {
        return when (error.type) {
            // Rewritable errors
            ErrorType.UNKNOWN_VERB,
            ErrorType.UNKNOWN_NOUN,
            ErrorType.AMBIGUOUS,
            ErrorType.SYNTAX,
                -> true

            // Do NOT rewrite - these are genuine game responses
            ErrorType.CANT_DO_THAT,
            ErrorType.NO_SUCH_THING,
            ErrorType.DARKNESS,
            ErrorType.NOT_HERE,
                -> false
        }
    }

    /**
     * Marks a rewrite attempt (consumes the single retry).
     */
    fun markRewriteAttempted(rewrite: String) {
        lastAttemptedRewrite = rewrite
        retryAvailable = false
        Log.i("ParserWrapper", "Rewrite attempted: '$lastCommand' → '$rewrite'")
    }

    /**
     * Resets state (e.g., on new turn or game restart).
     */
    fun reset() {
        lastCommand = ""
        lastAttemptedRewrite = null
        retryAvailable = true
    }
}

// Error patterns compiled from Infocom/Z-machine conventions
private val ERROR_PATTERTERS = mapOf<String, ErrorType>(
    // Verb errors
    """\bI don't understand ("[^"]*"|that sentence)\.""" to ErrorType.UNKNOWN_VERB,
    """\bI don't know the word ["']([^"']+)["']\.?""" to ErrorType.UNKNOWN_VERB,
    """\bYou used the word ["']([^"']+)["'] in a way that I don't understand\.?""" to ErrorType.UNKNOWN_VERB,

    // Noun/Object errors
    """\bYou can't see any such thing\.?""" to ErrorType.UNKNOWN_NOUN,
    """\bI don't see (that|the|any) ["']?([^"']+)["']?\.?""" to ErrorType.UNKNOWN_NOUN,
    """\bThere is (no|none of that) (here|here now)\.?""" to ErrorType.UNKNOWN_NOUN,

    // Ambiguity errors
    """\bWhich do you mean, """ to ErrorType.AMBIGUOUS,
    """\bDo you mean the """ to ErrorType.AMBIGUOUS,
    """\bThe word ["']([^"']+)["'] (should be|is) (not|unused)\.?""" to ErrorType.AMBIGUOUS,

    // Syntax errors
    """\bI only understood you as far as ["']([^"']+)["']\.?""" to ErrorType.SYNTAX,
    """\bYou seem to have said too much\!""" to ErrorType.SYNTAX,
    """\bI didn't understand that sentence\.?""" to ErrorType.SYNTAX,

    // Game-state responses (NOT parser errors)
    """\bYou can't do that\.?""" to ErrorType.CANT_DO_THAT,
    """\b(It's|It is) (too dark|pitch dark|dark) to see\.?""" to ErrorType.DARKNESS,
    """\b(There is no|That's not available|You don't have) ["']?([^"']+)["']?\.?""" to ErrorType.NOT_HERE,
)

enum class ErrorType {
    UNKNOWN_VERB,      // "I don't understand that sentence"
    UNKNOWN_NOUN,      // "You can't see any such thing"
    AMBIGUOUS,         // "Which do you mean..."
    SYNTAX,            // "I only understood you as far as..."
    CANT_DO_THAT,      // Game logic refusal
    NO_SUCH_THING,     // World state (not parser error)
    DARKNESS,          // Environmental
    NOT_HERE,          // Not present in current room
}

data class ErrorInfo(
    val type: ErrorType,
    val matchedText: String,
    val fullOutput: String
)

data class GameContext(
    val currentRoom: String? = null,
    val visibleObjects: Set<String> = emptySet(),
    val inventory: Set<String> = emptySet(),
    val recentCommands: List<String> = emptyList()
)
```

**Acceptance Criteria**:
- [ ] `ParserWrapper.kt` compiles and integrates with existing build
- [ ] Unit tests for all error patterns pass (see Section 1.3)
- [ ] No modifications to GLK layer or native code required

---

### 1.2 Modify GLKGameEngine for Wrapper Integration

**File**: `app/src/main/java/com/ifautofab/GLKGameEngine.kt`

**Changes at line 169** - Modify `sendInput()` method:

```kotlin
// BEFORE (current code):
fun sendInput(input: String) {
    // ... existing implementation ...
}

// AFTER (with wrapper integration):
fun sendInput(input: String) {
    // Pass through parser wrapper first
    val processedInput = if (parserEnabled) {
        ParserWrapper.interceptInput(input, currentGameContext())
    } else {
        input
    }

    // Log the input decision
    if (processedInput != input) {
        Log.i("GLKGameEngine", "Input rewritten: '$input' → '$processedInput'")
    }

    // Continue with existing implementation
    val m = GLKModel.instance() ?: run {
        Log.w(TAG, "sendInput: no model")
        return
    }
    // ... rest of existing implementation unchanged ...
}
```

**Add configuration flag** (lines ~50-60 region):

```kotlin
class GLKGameEngine : Lifecycle.EventObserver {
    // ... existing properties ...

    companion object {
        // ... existing constants ...

        /** Enable/disable LLM parser assistance */
        var parserEnabled: Boolean = true

        /** Enable debug logging for parser operations */
        var parserDebug: Boolean = false
    }
}
```

**Add context extraction method**:

```kotlin
/**
 * Extracts current game context for parser use.
 * Does NOT infer state - only returns what's observable.
 */
private fun currentGameContext(): GameContext {
    val m = GLKModel.instance() ?: return GameContext()

    // Note: This is intentionally minimal for Phase 1
    // Future phases may add more sophisticated context extraction
    return GameContext(
        currentRoom = null,  // Room detection not implemented yet
        visibleObjects = emptySet(),  // Object parsing not implemented yet
        inventory = emptySet(),  // Inventory parsing not implemented yet
        recentCommands = emptyList()  // Command history not tracked yet
    )
}
```

**Acceptance Criteria**:
- [ ] Existing `sendInput()` behavior unchanged when `parserEnabled = false`
- [ ] No regression in normal command execution
- [ ] All existing tests pass
- [ ] Integration tests verify wrapper receives all commands

---

### 1.3 Unit Tests for Error Detection

**File**: `app/src/test/java/com/ifautofab/parser/ParserErrorDetectionTest.kt`

```kotlin
class ParserErrorDetectionTest {

    @Test
    fun `detects unknown verb error`() {
        val output = "I don't understand that sentence."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.UNKNOWN_VERB, error.type)
        assertTrue(error.matchedText.contains("don't understand"))
    }

    @Test
    fun `detects unknown noun error`() {
        val output = "You can't see any such thing."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.UNKNOWN_NOUN, error.type)
    }

    @Test
    fun `detects ambiguous noun error`() {
        val output = "Which do you mean, the brass key or the rusty key?"
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.AMBIGUOUS, error.type)
    }

    @Test
    fun `detects syntax error`() {
        val output = "I only understood you as far as \"take the\"."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.SYNTAX, error.type)
    }

    @Test
    fun `does not rewrite game logic responses`() {
        val gameResponses = listOf(
            "You can't do that.",
            "It's too dark to see!",
            "You don't have the key.",
            "The door is locked."
        )

        gameResponses.forEach { output ->
            val error = ParserWrapper.detectParserFailure(output)
            if (error != null) {
                assertFalse(
                    ParserWrapper.shouldAttemptRewrite(error),
                    "Should not rewrite game response: $output"
                )
            }
        }
    }

    @Test
    fun `handles multi-paragraph output correctly`() {
        val output = """
            The door opens to reveal a dark room beyond.

            I don't understand the word "xyzzy".
        """.trimIndent()

        val error = ParserWrapper.detectParserFailure(output)
        assertNotNull(error)
        assertEquals(ErrorType.UNKNOWN_VERB, error.type)
    }

    @Test
    fun `returns null for valid command responses`() {
        val validResponses = listOf(
            "You take the brass key.",
            "Opened.",
            "You go north.",
            "The door is open.",
            "Dropping the lantern. Done."
        )

        validResponses.forEach { output ->
            val error = ParserWrapper.detectParserFailure(output)
            assertNull(error, "Should not detect error in: $output")
        }
    }
}
```

**Acceptance Criteria**:
- [ ] All tests pass
- [ ] Coverage for all error types
- [ ] Tests cover edge cases (multi-paragraph output, punctuation variations)
- [ ] Test execution time < 100ms total

---

### 1.4 Output Listener Integration

**File**: `app/src/main/java/com/ifautofab/parser/ParserOutputListener.kt` (NEW)

```kotlin
/**
 * Listens to game output to detect parser failures and trigger rewrites.
 */
class ParserOutputListener : TextOutputInterceptor.OutputListener {

    private var pendingCommand: String? = null
    private val outputBuffer = StringBuilder()

    override fun onOutput(text: String) {
        outputBuffer.append(text)

        // Check if we have a complete response (ends with newline or prompt)
        val completeOutput = outputBuffer.toString()
        if (isCompleteResponse(completeOutput)) {
            processCompleteResponse(completeOutput)
            outputBuffer.clear()
        }
    }

    private fun isCompleteResponse(output: String): Boolean {
        // Heuristic: response is complete if it ends with a blank line
        // or contains a parser error pattern
        return output.contains("\n\n") ||
               ParserWrapper.detectParserFailure(output) != null
    }

    private fun processCompleteResponse(output: String) {
        val error = ParserWrapper.detectParserFailure(output)

        if (error != null && ParserWrapper.shouldAttemptRewrite(error)) {
            pendingCommand?.let { original ->
                handleParserError(original, error)
            }
        }

        pendingCommand = null
    }

    private fun handleParserError(originalCommand: String, error: ErrorInfo) {
        Log.w("ParserOutputListener", "Parser error detected: ${error.type}")
        Log.d("ParserOutputListener", "Original: '$originalCommand'")
        Log.d("ParserOutputListener", "Output: ${error.fullOutput}")

        // Phase 1: Log only (no actual rewrite yet)
        // Phase 2 will call the LLM here
        Log.i("ParserOutputListener", "[PHASE 1] Would attempt rewrite for: $originalCommand")
    }

    /**
     * Called before a command is sent, to associate it with the response.
     */
    fun onCommandSent(command: String) {
        pendingCommand = command
    }
}
```

**Integration in TextOutputInterceptor**:

Modify `TextOutputInterceptor.kt` to allow parser listener registration (no changes to existing listener mechanism):

```kotlin
// In TextOutputInterceptor companion or as new property
private val parserListeners = mutableListOf<ParserOutputListener>()

fun registerParserListener(listener: ParserOutputListener) {
    synchronized(parserListeners) {
        parserListeners.add(listener)
    }
}

// In onOutput method, after notifying regular listeners:
synchronized(parserListeners) {
    parserListeners.forEach { it.onOutput(text) }
}
```

**Acceptance Criteria**:
- [ ] Output listener detects all parser error types within 100ms of output
- [ ] No interference with existing UI listeners
- [ ] Listener properly handles multi-turn output

---

## Week 2: Single Retry Mechanism & Fallback

### 2.1 Implement Retry State Machine

**File**: `app/src/main/java/com/ifautofab/parser/RetryStateMachine.kt` (NEW)

```kotlin
/**
 * State machine for tracking command retries.
 * Enforces single-retry constraint.
 */
enum class RetryState {
    IDLE,           // No command pending
    COMMAND_SENT,   // Command sent, awaiting response
    ERROR_DETECTED, // Parser error detected, retry available
    RETRY_SENT,     // Retry attempted, no more retries allowed
    FAILED          // Retry also failed, showing original error
}

class RetryStateMachine {
    private var state: RetryState = RetryState.IDLE
    private var originalCommand: String = ""
    private var rewrittenCommand: String = ""
    private var lastError: ErrorInfo? = null

    fun onCommandSent(command: String) {
        when (state) {
            RetryState.IDLE, RetryState.FAILED -> {
                state = RetryState.COMMAND_SENT
                originalCommand = command
                rewrittenCommand = ""
                lastError = null
            }
            RetryState.RETRY_SENT -> {
                // This is the retry result
                state = if (lastError != null) RetryState.FAILED else RetryState.IDLE
            }
            else -> {
                Log.w("RetryStateMachine", "Unexpected command in state: $state")
            }
        }
    }

    fun onParserError(error: ErrorInfo): Boolean {
        return when (state) {
            RetryState.COMMAND_SENT -> {
                state = RetryState.ERROR_DETECTED
                lastError = error
                true  // Retry is available
            }
            RetryState.RETRY_SENT -> {
                state = RetryState.FAILED
                true  // Signal that retry failed
            }
            else -> false
        }
    }

    fun canRetry(): Boolean {
        return state == RetryState.ERROR_DETECTED
    }

    fun onRetrySent(rewrittenCommand: String) {
        state = RetryState.RETRY_SENT
        this.rewrittenCommand = rewrittenCommand
    }

    fun getOriginalCommand(): String = originalCommand
    fun getRewrittenCommand(): String = rewrittenCommand
    fun getLastError(): ErrorInfo? = lastError
    fun getState(): RetryState = state

    fun reset() {
        state = RetryState.IDLE
        originalCommand = ""
        rewrittenCommand = ""
        lastError = null
    }
}
```

**Acceptance Criteria**:
- [ ] State machine prevents more than one retry per command
- [ ] State transitions logged for debugging
- [ ] Unit tests cover all state transitions

---

### 2.2 Placeholder Rewrite Function

**File**: `app/src/main/java/com/ifautofab/parser/PlaceholderRewriter.kt` (NEW)

```kotlin
/**
 * Placeholder rewrite function for Phase 1.
 * In Phase 1, this simulates a rewrite without calling an LLM.
 * Phase 2 will replace this with actual LLM calls.
 */
object PlaceholderRewriter {

    /**
     * Simulates a command rewrite.
     *
     * For Phase 1, this does one of:
     * 1. Returns a simple transformation (lowercase, trim)
     * 2. Returns null if no rewrite possible
     *
     * Phase 2 will call the actual LLM service here.
     */
    fun attemptRewrite(
        command: String,
        error: ErrorInfo,
        context: GameContext
    ): String? {
        Log.i("PlaceholderRewriter", "Analyzing: '$command' with error: ${error.type}")

        // Phase 1: Simple rule-based transformations
        // This validates the pipeline without needing an LLM

        val normalized = command.trim().lowercase()

        // Simple fixes that don't require LLM
        when {
            // Multiple spaces to single space
            command.contains(Regex("""\s{2,}""")) ->
                return command.replace(Regex("""\s+"""), " ").trim()

            // Leading/trailing punctuation cleanup
            command.matches(Regex("""^[.,;:!\?]+.*""")) ->
                return command.trimStart(',', '.', ';', ':', '!', '?')

            // Empty or whitespace-only
            command.isBlank() -> null

            // Phase 1: For all other cases, return null to indicate "no rewrite"
            // This tests the fallback behavior
            else -> {
                Log.d("PlaceholderRewriter", "No rule-based rewrite available")
                null
            }
        }
    }

    /**
     * Returns a simulated LLM response for testing.
     * Only used in test mode.
     */
    fun mockRewriteForTesting(command: String): String {
        // Test mock that simulates what Phase 2 LLM might return
        return when {
            command.contains("pick") -> "take $command"
            command.contains("unlock door") -> "unlock door with key"
            else -> command.lowercase()
        }
    }
}
```

**Acceptance Criteria**:
- [ ] Returns null for commands that cannot be rewritten
- [ ] Simple transformations work correctly
- [ ] Logging captures all rewrite attempts
- [ ] Mock function enables testing of retry flow

---

### 2.3 Complete Retry Flow Integration

**File**: `app/src/main/java/com/ifautofab/parser/ParserFlowCoordinator.kt` (NEW)

```kotlin
/**
 * Coordinates the complete parser flow:
 * 1. Intercept input
 * 2. Detect parser failure
 * 3. Attempt single retry
 * 4. Fallback to original error
 */
object ParserFlowCoordinator {

    private val stateMachine = RetryStateMachine()
    private var outputListener: ParserOutputListener? = null

    /**
     * Initialize the coordinator and register output listener.
     */
    fun initialize() {
        outputListener = ParserOutputListener().apply {
            TextOutputInterceptor.registerParserListener(this)
        }
        Log.i("ParserFlowCoordinator", "Initialized")
    }

    /**
     * Process input before sending to interpreter.
     */
    fun processInput(input: String): String {
        val command = input.trim()

        // Track command in state machine
        stateMachine.onCommandSent(command)

        // If this is a retry, consume the retry availability
        if (stateMachine.getState() == RetryState.RETRY_SENT) {
            Log.i("ParserFlowCoordinator", "Sending retry: '$command'")
            return command
        }

        // Normal path: send original command
        return command
    }

    /**
     * Process output to detect and handle parser errors.
     * Returns the rewritten command if a retry should be attempted, null otherwise.
     */
    fun processOutput(output: String): String? {
        val error = ParserWrapper.detectParserFailure(output)

        if (error == null) {
            // Success - reset state for next command
            stateMachine.reset()
            return null
        }

        // Error detected - check if retry is available
        if (!stateMachine.onParserError(error)) {
            // No retry available or retry already attempted
            return null
        }

        // Check if this error type is rewritable
        if (!ParserWrapper.shouldAttemptRewrite(error)) {
            Log.i("ParserFlowCoordinator", "Error type ${error.type} not rewritable")
            return null
        }

        // Attempt rewrite
        val originalCommand = stateMachine.getOriginalCommand()
        val context = currentGameContext()  // From GLKGameEngine

        val rewritten = PlaceholderRewriter.attemptRewrite(originalCommand, error, context)

        return if (rewritten != null) {
            stateMachine.onRetrySent(rewritten)
            ParserWrapper.markRewriteAttempted(rewritten)
            Log.i("ParserFlowCoordinator", "Rewrite: '$originalCommand' → '$rewritten'")
            rewritten
        } else {
            Log.i("ParserFlowCoordinator", "No rewrite available, falling back")
            stateMachine.reset()
            null
        }
    }

    /**
     * Clean up resources.
     */
    fun shutdown() {
        outputListener?.let {
            TextOutputInterceptor.unregisterParserListener(it)
        }
        stateMachine.reset()
    }
}
```

**Acceptance Criteria**:
- [ ] Complete end-to-end flow works: input → error detection → retry → fallback
- [ ] Maximum one retry per command enforced
- [ ] Original parser error shown after failed retry
- [ ] No infinite loops possible

---

### 2.4 Logging and Telemetry

**File**: `app/src/main/java/com/ifautofab/parser/ParserLogger.kt` (NEW)

```kotlin
/**
 * Comprehensive logging for parser operations.
 * Enables analysis of failure patterns and rewrite effectiveness.
 */
object ParserLogger {

    private const val TAG = "ParserLog"

    sealed class ParserEvent {
        data class CommandSent(val command: String, val timestamp: Long) : ParserEvent()
        data class ErrorDetected(
            val command: String,
            val errorType: ErrorType,
            val output: String,
            val timestamp: Long
        ) : ParserEvent()
        data class RewriteAttempted(
            val original: String,
            val rewritten: String,
            val errorType: ErrorType,
            val timestamp: Long
        ) : ParserEvent()
        data class RetryResult(
            val rewritten: String,
            val success: Boolean,
            val output: String,
            val timestamp: Long
        ) : ParserEvent()
        data class Fallback(
            val original: String,
            val errorType: ErrorType,
            val reason: String,
            val timestamp: Long
        ) : ParserEvent()
    }

    private val eventLog = mutableListOf<ParserEvent>()
    private var sessionId: String = UUID.randomUUID().toString()

    fun logCommandSent(command: String) {
        val event = ParserEvent.CommandSent(command, System.currentTimeMillis())
        eventLog.add(event)
        Log.d(TAG, "[SENT] $command")
    }

    fun logErrorDetected(command: String, error: ErrorInfo) {
        val event = ParserEvent.ErrorDetected(
            command,
            error.type,
            error.fullOutput.take(200),  // Truncate long outputs
            System.currentTimeMillis()
        )
        eventLog.add(event)
        Log.d(TAG, "[ERROR ${error.type}] '$command'")
    }

    fun logRewriteAttempted(original: String, rewritten: String, error: ErrorInfo) {
        val event = ParserEvent.RewriteAttempted(
            original,
            rewritten,
            error.type,
            System.currentTimeMillis()
        )
        eventLog.add(event)
        Log.i(TAG, "[REWRITE ${error.type}] '$original' → '$rewritten'")
    }

    fun logRetryResult(rewritten: String, success: Boolean, output: String) {
        val event = ParserEvent.RetryResult(
            rewritten,
            success,
            output.take(200),
            System.currentTimeMillis()
        )
        eventLog.add(event)
        Log.i(TAG, "[RETRY ${if (success) "SUCCESS" else "FAILED}] '$rewritten'")
    }

    fun logFallback(original: String, error: ErrorInfo, reason: String) {
        val event = ParserEvent.Fallback(
            original,
            error.type,
            reason,
            System.currentTimeMillis()
        )
        eventLog.add(event)
        Log.w(TAG, "[FALLBACK ${error.type}] $reason - '$original'")
    }

    /**
     * Export log as JSON for analysis.
     */
    fun exportLog(): String {
        val logData = mapOf(
            "sessionId" to sessionId,
            "startTime" to (eventLog.firstOrNull()?.let { event ->
                when (event) {
                    is ParserEvent.CommandSent -> event.timestamp
                    else -> System.currentTimeMillis()
                }
            } ?: System.currentTimeMillis()),
            "events" to eventLog.map { event ->
                when (event) {
                    is ParserEvent.CommandSent -> mapOf(
                        "type" to "command_sent",
                        "command" to event.command,
                        "timestamp" to event.timestamp
                    )
                    is ParserEvent.ErrorDetected -> mapOf(
                        "type" to "error_detected",
                        "command" to event.command,
                        "errorType" to event.errorType.name,
                        "output" to event.output,
                        "timestamp" to event.timestamp
                    )
                    is ParserEvent.RewriteAttempted -> mapOf(
                        "type" to "rewrite_attempted",
                        "original" to event.original,
                        "rewritten" to event.rewritten,
                        "errorType" to event.errorType.name,
                        "timestamp" to event.timestamp
                    )
                    is ParserEvent.RetryResult -> mapOf(
                        "type" to "retry_result",
                        "rewritten" to event.rewritten,
                        "success" to event.success,
                        "output" to event.output,
                        "timestamp" to event.timestamp
                    )
                    is ParserEvent.Fallback -> mapOf(
                        "type" to "fallback",
                        "original" to event.original,
                        "errorType" to event.errorType.name,
                        "reason" to event.reason,
                        "timestamp" to event.timestamp
                    )
                }
            }
        )
        return JSONObject(logData).toString(2)
    }

    fun startNewSession() {
        eventLog.clear()
        sessionId = UUID.randomUUID().toString()
        Log.i(TAG, "New session: $sessionId")
    }

    fun getStats(): ParserStats {
        val commands = eventLog.count { it is ParserEvent.CommandSent }
        val errors = eventLog.count { it is ParserEvent.ErrorDetected }
        val rewrites = eventLog.count { it is ParserEvent.RewriteAttempted }
        val successes = eventLog.count {
            it is ParserEvent.RetryResult && it.success
        }

        return ParserStats(
            totalCommands = commands,
            parserErrors = errors,
            rewriteAttempts = rewrites,
            successfulRetries = successes,
            errorRate = if (commands > 0) errors.toDouble() / commands else 0.0,
            successRate = if (rewrites > 0) successes.toDouble() / rewrites else 0.0
        )
    }
}

data class ParserStats(
    val totalCommands: Int,
    val parserErrors: Int,
    val rewriteAttempts: Int,
    val successfulRetries: Int,
    val errorRate: Double,
    val successRate: Double
)
```

**Acceptance Criteria**:
- [ ] All parser events logged with timestamps
- [ ] JSON export format is valid and parseable
- [ ] Stats calculation is accurate
- [ ] Log export works via adb or file

---

## Phase 1 Deliverables

### Code Deliverables

| File | Lines (est) | Purpose |
|------|-------------|---------|
| `ParserWrapper.kt` | ~200 | Core wrapper, error detection, context |
| `ParserOutputListener.kt` | ~100 | Output analysis for parser errors |
| `RetryStateMachine.kt` | ~120 | Single-retry enforcement |
| `PlaceholderRewriter.kt` | ~80 | Phase 1 mock LLM |
| `ParserFlowCoordinator.kt` | ~100 | End-to-end coordination |
| `ParserLogger.kt` | ~180 | Logging and telemetry |
| `ParserErrorDetectionTest.kt` | ~150 | Unit tests |
| `GLKGameEngine.kt` (mod) | ~30 | Integration points |
| `TextOutputInterceptor.kt` (mod) | ~20 | Listener registration |
| **Total** | **~980** | |

### Documentation Deliverables

1. **PHASE1_TECHNICAL_NOTES.md**
   - Parser error pattern catalog with examples
   - State machine diagram
   - Integration guide for future phases

2. **PHASE1_ACCEPTANCE_TEST.md**
   - Manual test procedures
   - Test game list (Zork I, Planetfall)
   - Expected behavior matrix

3. **PHASE1_API_SPEC.md**
   - Public API documentation
   - Extension points for Phase 2 (LLM integration)

---

## Acceptance Criteria

### Functional Requirements

| # | Criterion | Verification Method |
|---|-----------|---------------------|
| FR1 | All Z-machine commands route through ParserWrapper | Unit test + integration test |
| FR2 | Parser errors detected within 100ms of output | Performance test |
| FR3 | Maximum ONE rewrite attempt per command | State machine test + log review |
| FR4 | Original error shown after failed retry | Manual test |
| FR5 | No infinite loops possible | Formal verification + stress test |
| FR6 | Works on existing games without modification | Manual test on 3+ games |
| FR7 | No changes to GLK layer or native code | Code review |
| FR8 | Can be disabled via `parserEnabled` flag | Configuration test |

### Non-Functional Requirements

| # | Criterion | Target | Verification Method |
|---|-----------|--------|---------------------|
| NFR1 | Command interception overhead | < 5ms | Benchmark |
| NFR2 | Memory overhead | < 1MB | Profiler |
| NFR3 | No UI lag | < 16ms (60fps) | Manual testing |
| NFR4 | Log export time | < 100ms for 1000 events | Unit test |

### Quality Gates

| # | Gate | Pass Condition |
|---|------|----------------|
| QG1 | Unit test coverage | > 90% for new code |
| QG2 | Static analysis | No warnings |
| QG3 | Integration tests | All pass |
| QG4 | Manual gameplay | 30 min Zork I, 10 errors detected correctly |
| QG5 | Code review | Approved by maintainer |

---

## Testing Strategy

### Unit Tests

```bash
# Run parser tests
./gradlew test --tests ParserErrorDetectionTest
./gradlew test --tests RetryStateMachineTest
./gradlew test --tests PlaceholderRewriterTest

# Coverage report
./gradlew testDebugUnitTestCoverage
```

### Integration Tests

1. **Input Flow Test**
   - Send command through MainActivity
   - Verify ParserWrapper receives it
   - Verify GLKGameEngine receives processed command

2. **Error Detection Test**
   - Load test game Zork I
   - Send known bad commands
   - Verify error detection

3. **Retry Flow Test**
   - Send "gdfdssf" (nonsense)
   - Verify error detected
   - Verify retry attempted with mock rewrite
   - Verify fallback on second failure

### Manual Testing Matrix

| Game | Command | Expected Error | Rewritable? |
|------|---------|----------------|-------------|
| Zork I | "xyzzy" | Unknown verb | Yes |
| Zork I | "take nonexistent" | Unknown noun | Yes |
| Zork I | "go" (no direction) | Syntax | Yes |
| Zork I | "unlock door" (no key) | Game response | No |
| Planetfall | "jump" | Canned response | No |
| Planetfall | "asdf" | Unknown verb | Yes |

---

## Success Metrics for Phase 1

| Metric | Baseline | Target | Measurement |
|--------|----------|--------|-------------|
| Parser errors detected | 0% | 100% (of known patterns) | Unit test pass rate |
| Commands intercepted | 0% | 100% | Log analysis |
| False positive rate | N/A | < 1% | Manual review |
| Retry mechanism | N/A | 100% (single retry max) | State machine verification |
| Integration success | N/A | 100% (games playable) | Manual test |

---

## Dependencies for Phase 2

Phase 1 must provide:

1. **Clean API** for LLM integration
   - `attemptRewrite(command, error, context): String?`
   - Defined error types
   - GameContext structure

2. **Comprehensive logging**
   - All parser events timestamped
   - Exportable for Phase 3 dataset construction

3. **Error pattern catalog**
   - Documented regex patterns
   - Frequency data from real gameplay

4. **Test infrastructure**
   - Mock game output
   - Simulated error states

---

## Risk Mitigation

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Error patterns vary by game | High | Medium | Start with Infocom conventions, add patterns per game |
| False positives waste retries | Medium | Low | Strict pattern matching, whitelist approach |
| State machine bugs cause loops | Low | High | Formal verification, extensive testing |
| Performance issues on low-end devices | Low | Medium | Benchmarking, optimization if needed |
| Detection of ambiguous nouns is complex | Medium | Low | Phase 1: log only; Phase 2: LLM handles |

---

## Open Questions for Phase 2

1. Should context extraction include room name parsing?
   - Decision: Deferred to Phase 2 (requires output parsing)

2. Should we track command history?
   - Decision: Yes, but minimal in Phase 1; expand in Phase 2

3. How to handle games with non-standard error messages?
   - Decision: Log unknown patterns, add to catalog iteratively

4. Should the LLM receive the full error message or just error type?
   - Decision: Full message (more context for Phase 2)

---

## Phase 1 Exit Criteria

Phase 1 is complete when:

- [ ] All acceptance criteria met (FR1-FR8, NFR1-NFR4, QG1-QG5)
- [ ] At least 3 games tested successfully (Zork I, Planetfall, one more)
- [ ] Comprehensive error pattern catalog documented
- [ ] No regressions in existing functionality
- [ ] Code reviewed and merged to `llm` branch
- [ ] Technical notes document completed
- [ ] Phase 2 dependencies clearly defined
