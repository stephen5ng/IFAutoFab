# Phase 2: Cloud LLM Prompting & Validation
## Detailed Implementation Plan

**Duration**: Weeks 3-4
**Status**: Starting
**Dependencies**: Phase 1 complete ✅

---

## Overview

Phase 2 replaces the Phase 1 placeholder rewriter with actual cloud LLM integration while maintaining strict validation against extracted game vocabulary. The system will rewrite failed parser commands using GPT-4/Claude API while ensuring outputs only use valid game vocabulary.

---

## Architecture Integration

### Phase 1 → Phase 2 Transition

```
┌─────────────────────────────────────────────────────────────────┐
│ Phase 1 (Complete)                                              │
│ ├─ PlaceholderRewriter (rule-based)                             │
│ └─ No actual LLM calls                                          │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ Phase 2 (Current)                                               │
│ ├─ CloudLLMCommandRewriter (OpenAI/Anthropic API)               │
│ ├─ VocabularyValidator (game dictionary validation)            │
│ ├─ PromptTemplate (strict rewrite-only system prompt)           │
│ └─ OutputParser (extract rewrite from LLM response)            │
└─────────────────────────────────────────────────────────────────┘
```

### New Components

| Component | File | Purpose | Lines (est) |
|-----------|------|---------|-------------|
| CloudLLMCommandRewriter | `CloudLLMCommandRewriter.kt` | LLM API integration | ~250 |
| PromptTemplate | `PromptTemplate.kt` | System prompt construction | ~150 |
| VocabularyValidator | `VocabularyValidator.kt` | Output validation | ~200 |
| OutputParser | `OutputParser.kt` | Parse LLM responses | ~100 |
| LLMConfig | `LLMConfig.kt` | API keys/settings | ~50 |
| CloudLLMTest | `CloudLLMTest.kt` | Integration tests | ~200 |

---

## Week 1: Cloud LLM Integration

### 1.1 Create LLM Configuration

**File**: `app/src/main/java/com/ifautofab/parser/llm/LLMConfig.kt`

```kotlin
/**
 * Configuration for cloud LLM services.
 */
data class LLMConfig(
    val provider: LLMProvider,
    val apiKey: String,
    val model: String,
    val maxTokens: Int = 50,
    val temperature: Double = 0.3,  // Low temperature for consistent rewrites
    val timeoutMs: Long = 30000L
)

enum class LLMProvider {
    OPENAI,
    ANTHROPIC,
    GOOGLE
}

object LLMConfigManager {
    private var config: LLMConfig? = null

    fun initialize(config: LLMConfig) {
        this.config = config
    }

    fun getConfig(): LLMConfig {
        return config ?: throw IllegalStateException("LLM not configured")
    }

    fun isConfigured(): Boolean = config != null
}
```

**Acceptance Criteria**:
- [ ] Configuration stores API key securely
- [ ] Supports multiple providers (OpenAI, Anthropic, Google)
- [ ] Validates configuration before use

---

### 1.2 Implement Prompt Template

**File**: `app/src/main/java/com/ifautofab/parser/llm/PromptTemplate.kt`

```kotlin
/**
 * Constructs prompts for LLM command rewriting.
 *
 * Design Principles:
 * - Strict rewrite-only behavior (no hints, no puzzle solving)
 * - Include relevant game vocabulary
 * - Explicit <NO_VALID_REWRITE> instruction
 * - Minimal context (last game output only)
 */
object PromptTemplate {

    private const val SYSTEM_PROMPT = """
You are a text adventure game parser assistant. Your only job is to rewrite failed player commands into valid commands that the game's parser will understand.

CRITICAL CONSTRAINTS:
1. Rewrite ONLY to fix parser errors (typos, synonyms, word order)
2. NEVER provide hints, solutions, or puzzle help
3. NEVER invent objects or actions not in the game
4. If unsure, return exactly: <NO_VALID_REWRITE>
5. Output ONLY the rewritten command (no explanations)

Rewrite Strategy:
- Unknown verbs → Use game's valid verbs
- Unknown nouns → Use visible objects from context
- Syntax errors → Restructure to: VERB [NOUN] [PREPOSITION NOUN]
- Typos → Fix spelling while preserving meaning

Remember: You are helping with PARSE errors, not GAME logic.
""".trimIndent()

    /**
     * Constructs the full prompt for LLM rewriting.
     *
     * @param failedCommand The command that failed
     * @param lastOutput The game's response (parser error)
     * @param errorType Type of parser error detected
     * @param vocabulary Current game vocabulary
     * @param context Observable game context
     */
    fun buildPrompt(
        failedCommand: String,
        lastOutput: String,
        errorType: ErrorType,
        vocabulary: ZMachineVocabulary,
        context: GameContext
    ): LLMRequest {
        val userPrompt = buildUserPrompt(failedCommand, lastOutput, errorType, vocabulary, context)

        return LLMRequest(
            system = SYSTEM_PROMPT,
            user = userPrompt,
            maxTokens = 50,
            temperature = 0.3
        )
    }

    private fun buildUserPrompt(
        failedCommand: String,
        lastOutput: String,
        errorType: ErrorType,
        vocabulary: ZMachineVocabulary,
        context: GameContext
    ): String {
        val prompt = StringBuilder()

        // Game output (error message)
        prompt.appendLine("Game Output:")
        prompt.appendLine(lastOutput.trim().take(500))
        prompt.appendLine()

        // Failed command
        prompt.appendLine("Failed Command:")
        prompt.appendLine(failedCommand)
        prompt.appendLine()

        // Error type
        prompt.appendLine("Error Type: ${errorType.name}")
        prompt.appendLine()

        // Relevant vocabulary (subset based on context)
        val vocabContext = selectRelevantVocabulary(vocabulary, context, errorType)
        if (vocabContext.isNotEmpty()) {
            prompt.appendLine("Valid Game Vocabulary:")
            prompt.appendLine("Verbs: ${vocabContext.verbs.sorted().take(20).joinToString(", ")}")
            prompt.appendLine("Nouns: ${vocabContext.nouns.sorted().take(20).joinToString(", ")}")
            prompt.appendLine("Prepositions: ${vocabContext.prepositions.sorted().joinToString(", ")}")
            prompt.appendLine()
        }

        // Visible objects (if known)
        if (context.visibleObjects.isNotEmpty()) {
            prompt.appendLine("Visible Objects: ${context.visibleObjects.sorted().joinToString(", ")}")
            prompt.appendLine()
        }

        prompt.appendLine("Rewrite this command (or return <NO_VALID_REWRITE>):")

        return prompt.toString()
    }

    /**
     * Selects vocabulary subset relevant to current context.
     * Phase 2: Simple heuristics
     * Phase 3+: ML-based relevance scoring
     */
    private fun selectRelevantVocabulary(
        vocabulary: ZMachineVocabulary,
        context: GameContext,
        errorType: ErrorType
    ): ZMachineVocabulary {
        // Phase 2: Return all vocabulary (simple approach)
        // Phase 3: Filter by room, visible objects, recent commands

        return when (errorType) {
            ErrorType.UNKNOWN_VERB -> vocabulary.copy(
                nouns = emptySet(),
                adjectives = emptySet(),
                prepositions = emptySet()
            )
            ErrorType.UNKNOWN_NOUN -> vocabulary.copy(
                verbs = emptySet(),
                adjectives = emptySet(),
                prepositions = emptySet()
            )
            else -> vocabulary
        }
    }
}

data class LLMRequest(
    val system: String,
    val user: String,
    val maxTokens: Int,
    val temperature: Double
)
```

**Acceptance Criteria**:
- [ ] System prompt establishes strict rewrite-only behavior
- [ ] Includes relevant game vocabulary
- [ ] Explicit <NO_VALID_REWRITE> instruction
- [ ] Minimal context (last output only)
- [ ] No game state inference

---

### 1.3 Implement Cloud LLM Service

**File**: `app/src/main/java/com/ifautofab/parser/llm/CloudLLMCommandRewriter.kt`

```kotlin
package com.ifautofab.parser.llm

import com.ifautofab.parser.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Cloud LLM-based command rewriter.
 * Replaces Phase 1 PlaceholderRewriter with actual LLM API calls.
 */
class CloudLLMCommandRewriter(
    private val config: LLMConfig,
    private val vocabulary: ZMachineVocabulary,
    private val validator: VocabularyValidator = VocabularyValidator()
) : CommandRewriter {

    private val TAG = "CloudLLMRewriter"

    override suspend fun attemptRewrite(
        command: String,
        error: ErrorInfo,
        context: GameContext
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Build prompt
            val prompt = PromptTemplate.buildPrompt(
                failedCommand = command,
                lastOutput = error.fullOutput,
                errorType = error.type,
                vocabulary = vocabulary,
                context = context
            )

            // Call LLM API
            val response = callLLMAPI(prompt)

            // Parse response
            val rewrite = OutputParser.parse(response)

            // Validate against game vocabulary
            if (rewrite != null && !validator.isValid(rewrite, vocabulary, context)) {
                logger.w(TAG, "LLM rewrite failed validation: '$rewrite'")
                return@withContext null
            }

            // Log successful rewrite
            if (rewrite != null) {
                logger.i(TAG, "LLM rewrite: '$command' → '$rewrite'")
                ParserLogger.logRewriteAttempted(command, rewrite, error)
            }

            rewrite
        } catch (e: Exception) {
            logger.e(TAG, "LLM rewrite failed: ${e.message}", e)
            ParserLogger.logFallback(command, error, "LLM API error: ${e.message}")
            null
        }
    }

    /**
     * Calls the LLM API based on configured provider.
     */
    private fun callLLMAPI(request: LLMRequest): String {
        return when (config.provider) {
            LLMProvider.OPENAI -> callOpenAI(request)
            LLMProvider.ANTHROPIC -> callAnthropic(request)
            LLMProvider.GOOGLE -> callGoogle(request)
        }
    }

    private fun callOpenAI(request: LLMRequest): String {
        val url = URL("https://api.openai.com/v1/chat/completions")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.doOutput = true
            connection.connectTimeout = config.timeoutMs.toInt()
            connection.readTimeout = config.timeoutMs.toInt()

            // Build request body
            val requestBody = JSONObject().apply {
                put("model", config.model)
                put("max_tokens", request.maxTokens)
                put("temperature", request.temperature)
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", request.system)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", request.user)
                    })
                })
            }

            // Send request
            connection.outputStream.use { output ->
                output.write(requestBody.toString().toByteArray())
            }

            // Read response
            val response = connection.inputStream.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }

            // Parse response
            val jsonResponse = JSONObject(response)
            return jsonResponse
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

        } finally {
            connection.disconnect()
        }
    }

    private fun callAnthropic(request: LLMRequest): String {
        // Similar implementation for Anthropic Claude API
        // TODO: Implement in Week 1
        throw NotImplementedError("Anthropic API not yet implemented")
    }

    private fun callGoogle(request: LLMRequest): String {
        // Similar implementation for Google Gemini API
        // TODO: Implement in Week 1
        throw NotImplementedError("Google API not yet implemented")
    }

    /**
     * Checks if the rewriter is available (API configured and network reachable).
     */
    override fun isAvailable(): Boolean {
        return LLMConfigManager.isConfigured() && isNetworkAvailable()
    }

    override fun getBackendName(): String {
        return "Cloud LLM (${config.provider.name} ${config.model})"
    }

    private fun isNetworkAvailable(): Boolean {
        // Simple network check
        return try {
            val url = URL("https://www.google.com")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.requestMethod = "HEAD"
            val available = connection.responseCode == 200
            connection.disconnect()
            available
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Interface for command rewriters.
 */
interface CommandRewriter {
    suspend fun attemptRewrite(
        command: String,
        error: ErrorInfo,
        context: GameContext
    ): String?

    fun isAvailable(): Boolean
    fun getBackendName(): String
}
```

**Acceptance Criteria**:
- [ ] Supports OpenAI GPT-4 API
- [ ] Fallback to PlaceholderRewriter on API failure
- [ ] Network availability check
- [ ] Timeout handling (target: <3s)
- [ ] Error logging for debugging

---

## Week 2: Vocabulary Validation

### 2.1 Implement Vocabulary Validator

**File**: `app/src/main/java/com/ifautofab/parser/llm/VocabularyValidator.kt`

```kotlin
package com.ifautofab.parser.llm

import com.ifautofab.parser.*
import kotlin.math.max

/**
 * Validates LLM rewrites against extracted game vocabulary.
 *
 * Validation Rules:
 * 1. All verbs must be in game's verb list
 * 2. All nouns must be in game's dictionary
 * 3. Prepositions must be in game's preposition list
 * 4. Syntax must match: VERB [NOUN] [PREP NOUN]
 * 5. No invented words or actions
 */
class VocabularyValidator(
    private val strictMode: Boolean = true
) {

    private val TAG = "VocabularyValidator"

    // Valid syntax patterns
    private val syntaxPatterns = listOf(
        // VERB
        Regex("""^\s*(\w+)\s*$"""),

        // VERB NOUN
        Regex("""^\s*(\w+)\s+(\w+)\s*$"""),

        // VERB ADJECTIVE NOUN
        Regex("""^\s*(\w+)\s+(\w+)\s+(\w+)\s*$"""),

        // VERB NOUN PREP NOUN
        Regex("""^\s*(\w+)\s+(\w+)\s+(\w+)\s+(\w+)\s*$"""),

        // VERB ADJECTIVE NOUN PREP NOUN
        Regex("""^\s*(\w+)\s+(\w+)\s+(\w+)\s+(\w+)\s+(\w+)\s*$""")
    )

    /**
     * Validates a rewrite against game vocabulary.
     *
     * @return true if valid, false otherwise
     */
    fun isValid(
        rewrite: String,
        vocabulary: ZMachineVocabulary,
        context: GameContext
    ): Boolean {
        if (rewrite.isBlank()) return false

        // Check for NO_VALID_REWRITE marker
        if (rewrite == "<NO_VALID_REWRITE>") return false

        // Parse command structure
        val parts = rewrite.trim().split(Regex("""\s+"""))
        if (parts.isEmpty() || parts.size > 5) {
            logger.d(TAG, "Invalid structure: $rewrite")
            return false
        }

        // Validate verb (first word)
        val verb = parts[0].lowercase()
        if (verb !in vocabulary.verbs) {
            logger.d(TAG, "Unknown verb: $verb (not in ${vocabulary.verbs.size} verbs)")
            if (strictMode) return false
        }

        // Validate remaining words based on position
        return validateRemainingWords(parts.drop(1), vocabulary, context)
    }

    private fun validateRemainingWords(
        words: List<String>,
        vocabulary: ZMachineVocabulary,
        context: GameContext
    ): Boolean {
        if (words.isEmpty()) return true

        // Check if first word is a preposition (indicates different structure)
        if (words[0].lowercase() in vocabulary.prepositions) {
            return validatePrepositionalPhrase(words, vocabulary, context)
        }

        // Otherwise, expect: [ADJECTIVE?] NOUN [PREP NOUN]
        return validateNounPhrase(words, vocabulary, context)
    }

    private fun validateNounPhrase(
        words: List<String>,
        vocabulary: ZMachineVocabulary,
        context: GameContext
    ): Boolean {
        if (words.isEmpty()) return true

        var index = 0

        // Optional adjective
        if (index < words.size && words[index].lowercase() in vocabulary.adjectives) {
            index++
        }

        // Required noun
        if (index < words.size) {
            val noun = words[index].lowercase()
            if (noun !in vocabulary.nouns) {
                // Check if it's a visible object (might not be in global dictionary)
                if (noun !in context.visibleObjects && strictMode) {
                    logger.d(TAG, "Unknown noun: $noun")
                    return false
                }
            }
            index++
        }

        // Optional prepositional phrase
        if (index < words.size) {
            return validatePrepositionalPhrase(words.drop(index), vocabulary, context)
        }

        return true
    }

    private fun validatePrepositionalPhrase(
        words: List<String>,
        vocabulary: ZMachineVocabulary,
        context: GameContext
    ): Boolean {
        if (words.isEmpty()) return true

        // First word must be preposition
        val prep = words[0].lowercase()
        if (prep !in vocabulary.prepositions) {
            logger.d(TAG, "Unknown preposition: $prep")
            if (strictMode) return false
        }

        // Remaining words: [ADJECTIVE?] NOUN
        val remaining = words.drop(1)
        if (remaining.isEmpty()) {
            logger.d(TAG, "Preposition without object")
            return false
        }

        return validateNounPhrase(remaining, vocabulary, context)
    }

    /**
     * Returns detailed validation errors for debugging.
     */
    fun getValidationErrors(
        rewrite: String,
        vocabulary: ZMachineVocabulary,
        context: GameContext
    ): List<String> {
        val errors = mutableListOf<String>()

        if (rewrite.isBlank()) {
            errors.add("Rewrite is blank")
            return errors
        }

        val parts = rewrite.trim().split(Regex("""\s+"""))

        // Check verb
        if (parts.isNotEmpty()) {
            val verb = parts[0].lowercase()
            if (verb !in vocabulary.verbs) {
                errors.add("Unknown verb: $verb")
            }
        }

        // Check remaining words
        parts.drop(1).forEachIndexed { index, word ->
            val lower = word.lowercase()
            when {
                lower in vocabulary.verbs -> errors.add("Verb in noun position: $lower")
                lower in vocabulary.adjectives -> { /* OK */ }
                lower in vocabulary.nouns -> { /* OK */ }
                lower in vocabulary.prepositions -> { /* OK */ }
                lower in context.visibleObjects -> { /* OK */ }
                else -> errors.add("Unknown word: $lower")
            }
        }

        return errors
    }
}
```

**Acceptance Criteria**:
- [ ] Validates verbs against game vocabulary
- [ ] Validates nouns against game dictionary
- [ ] Validates prepositions
- [ ] Enforces syntax patterns (VERB [NOUN] [PREP NOUN])
- [ ] Returns detailed errors for debugging
- [ ] Supports non-strict mode for testing

---

### 2.2 Implement Output Parser

**File**: `app/src/main/java/com/ifautofab/parser/llm/OutputParser.kt`

```kotlin
package com.ifautofab.parser.llm

/**
 * Parses LLM responses to extract rewritten commands.
 *
 * Handles various response formats:
 * - Plain text command: "take the key"
 * - Quoted command: '"take the key"'
 * - With explanation: "Rewrite: take the key"
 * - NO_VALID_REWRITE marker: "<NO_VALID_REWRITE>"
 */
object OutputParser {

    private const val NO_REWRITE_MARKER = "<NO_VALID_REWRITE>"

    /**
     * Parses LLM response to extract the rewritten command.
     *
     * @return The rewritten command, or null if NO_VALID_REWRITE
     */
    fun parse(response: String): String? {
        if (response.isBlank()) return null

        val trimmed = response.trim()

        // Check for NO_VALID_REWRITE marker
        if (trimmed.contains(NO_REWRITE_MARKER, ignoreCase = true)) {
            return null
        }

        // Extract command from various formats
        val command = when {
            // Format: "take the key"
            trimmed.matches(Regex("""^[a-z\s]+$""", RegexOption.IGNORE_CASE)) -> trimmed

            // Format: "Rewrite: take the key" or "Command: take the key"
            trimmed.contains(":") -> {
                trimmed.substringAfter(":").trim()
            }

            // Format: "take the key" (with explanation before/after)
            else -> extractFirstCommand(trimmed)
        }

        // Clean up the command
        return cleanCommand(command)
    }

    /**
     * Extracts the first command from a multi-line or complex response.
     */
    private fun extractFirstCommand(text: String): String {
        val lines = text.lines()

        for (line in lines) {
            val trimmed = line.trim()

            // Skip obvious non-command lines
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("Here", ignoreCase = true)) continue
            if (trimmed.startsWith("The", ignoreCase = true) &&
                !trimmed.matches(Regex("""^(The|the)\s+\w+\s+\w+"""))) continue

            // Check if this looks like a command
            if (looksLikeCommand(trimmed)) {
                return trimmed
            }
        }

        // Fallback: return first non-empty line
        return lines.firstOrNull { it.isNotBlank() } ?: text
    }

    /**
     * Checks if a string looks like a command.
     */
    private fun looksLikeCommand(text: String): Boolean {
        // Commands start with a verb, typically 2-10 characters
        val words = text.split(Regex("""\s+"""))
        if (words.isEmpty()) return false

        val firstWord = words[0]

        // Verbs are typically 2-10 characters, all letters
        if (firstWord.length !in 2..10) return false
        if (!firstWord.all { it.isLetter() }) return false

        // Total command length should be reasonable
        if (text.length > 100) return false

        return true
    }

    /**
     * Cleans up the command string.
     */
    private fun cleanCommand(command: String): String {
        return command
            .trim()
            .replace(Regex("""\s+"""), " ")  // Normalize whitespace
            .removeSuffix(".")  // Remove trailing period
            .removeSuffix("!")  // Remove trailing exclamation
            .removeSuffix("?")  // Remove trailing question mark
            .lowercase()
    }
}
```

**Acceptance Criteria**:
- [ ] Extracts command from plain text response
- [ ] Handles quoted commands
- [ ] Handles responses with explanations
- [ ] Recognizes <NO_VALID_REWRITE> marker
- [ ] Cleans up punctuation and capitalization

---

## Integration with Phase 1

### Update ParserFlowCoordinator

```kotlin
// In ParserFlowCoordinator.kt

object ParserFlowCoordinator {

    private var commandRewriter: CommandRewriter = PlaceholderRewriter
    private var vocabulary: ZMachineVocabulary? = null

    /**
     * Initializes with cloud LLM rewriter.
     */
    fun initializeWithLLM(
        config: LLMConfig,
        vocabulary: ZMachineVocabulary
    ) {
        this.vocabulary = vocabulary
        this.commandRewriter = CloudLLMCommandRewriter(
            config = config,
            vocabulary = vocabulary
        )

        // Also initialize standard components
        initialize()
    }

    /**
     * Processes input before sending to interpreter.
     */
    fun processInput(input: String, isRetry: Boolean = false): String {
        // ... existing implementation ...
        return processedInput
    }

    /**
     * Processes output to detect and handle parser errors.
     */
    private fun handleParserError(command: String, error: ErrorInfo) {
        // ... existing error detection logic ...

        // Attempt rewrite with LLM
        val rewritten = if (commandRewriter.isAvailable()) {
            // Use coroutine for LLM call
            runBlocking {
                commandRewriter.attemptRewrite(command, error, extractGameContext())
            }
        } else {
            // Fallback to placeholder
            logger.w(TAG, "LLM unavailable, using placeholder rewriter")
            PlaceholderRewriter.attemptRewrite(command, error, extractGameContext())
        }

        // ... rest of existing logic ...
    }
}
```

---

## Testing Strategy

### Unit Tests

```kotlin
// CloudLLMTest.kt

class CloudLLMTest {

    @Test
    fun `prompt template includes system prompt`() {
        val vocab = ZMachineVocabulary(version = 3)
        val prompt = PromptTemplate.buildPrompt(
            "grab key",
            ErrorInfo(ErrorType.UNKNOWN_VERB, "I don't understand", "..."),
            vocab,
            GameContext.EMPTY
        )

        assertTrue(prompt.system.contains("parser assistant"))
        assertTrue(prompt.system.contains("NO_VALID_REWRITE"))
    }

    @Test
    fun `vocabulary validator rejects unknown verbs`() {
        val vocab = ZMachineVocabulary(version = 3).apply {
            verbs.add("take")
        }
        val validator = VocabularyValidator()

        assertFalse(validator.isValid("grab key", vocab, GameContext.EMPTY))
    }

    @Test
    fun `output parser extracts plain command`() {
        val response = "take the brass key"
        assertEquals("take the brass key", OutputParser.parse(response))
    }

    @Test
    fun `output parser handles NO_VALID_REWRITE`() {
        val response = "I'm not sure what you mean. <NO_VALID_REWRITE>"
        assertNull(OutputParser.parse(response))
    }
}
```

### Integration Tests

```kotlin
// CloudLLMIntegrationTest.kt

@RunWith(AndroidJUnit4::class)
class CloudLLMIntegrationTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var rewriter: CloudLLMCommandRewriter
    private lateinit var testConfig: LLMConfig

    @Before
    fun setup() {
        // Load test config from environment variable
        val apiKey = System.getenv("OPENAI_TEST_API_KEY")
            ?: throw AssumptionViolatedException("No API key configured")

        testConfig = LLMConfig(
            provider = LLMProvider.OPENAI,
            apiKey = apiKey,
            model = "gpt-4",
            maxTokens = 50,
            temperature = 0.3
        )

        val vocab = loadTestVocabulary()
        rewriter = CloudLLMCommandRewriter(testConfig, vocab)
    }

    @Test
    fun `rewrites unknown verb synonym`() = runTest {
        val error = ErrorInfo(
            ErrorType.UNKNOWN_VERB,
            "I don't understand",
            "I don't know the word 'grab'."
        )

        val rewrite = rewriter.attemptRewrite(
            "grab the key",
            error,
            GameContext.EMPTY
        )

        assertNotNull(rewrite)
        assertTrue(rewrite!!.startsWith("take"))
    }

    @Test
    fun `returns null for impossible action`() = runTest {
        val error = ErrorInfo(
            ErrorType.CANT_DO_THAT,
            "You can't do that",
            "You can't go through the locked door."
        )

        val rewrite = rewriter.attemptRewrite(
            "unlock door with invisible key",
            error,
            GameContext.EMPTY
        )

        assertNull(rewrite)
    }

    private fun loadTestVocabulary(): ZMachineVocabulary {
        // Load from test resources
        val file = File("src/test/resources/games/zork1.z3")
        return ZMachineVocabularyExtractor.extract(file)!!
    }
}
```

---

## Performance Targets

| Metric | Target | Measurement |
|--------|--------|-------------|
| API latency (p50) | <1s | Median response time |
| API latency (p95) | <3s | 95th percentile |
| Validation time | <50ms | Vocabulary check |
| End-to-end latency | <3.5s | API + validation |
| Error rate | <5% | Failed rewrites |
| Valid rewrite rate | >80% | Of attempted rewrites |

---

## Phase 2 Deliverables

1. **Cloud LLM Service**: `CloudLLMCommandRewriter.kt`
2. **Prompt Specification**: `PromptTemplate.kt` + documentation
3. **Vocabulary Validator**: `VocabularyValidator.kt`
4. **Output Parser**: `OutputParser.kt`
5. **Configuration Manager**: `LLMConfig.kt`
6. **Integration Tests**: `CloudLLMTest.kt`, `CloudLLMIntegrationTest.kt`
7. **Prompt Specification Document**: `PHASE2_PROMPT_SPEC.md`

---

## Acceptance Criteria

### Functional Requirements

| # | Criterion | Verification |
|---|-----------|--------------|
| FR1 | LLM API integration working | Integration test with real API |
| FR2 | Vocabulary validation enforced | Unit tests for all validation rules |
| FR3 | Fallback to placeholder on API failure | Error handling tests |
| FR4 | <NO_VALID_REWRITE> respected | Prompt + parser tests |
| FR5 | Network availability check | Unit test |
| FR6 | API key management | Config tests |
| FR7 | Logging of all rewrites | Log verification |

### Non-Functional Requirements

| # | Criterion | Target | Verification |
|---|-----------|--------|-------------|
| NFR1 | API latency (p95) | <3s | Performance test |
| NFR2 | Validation overhead | <50ms | Benchmark |
| NFR3 | Memory overhead | <50MB additional | Profiler |
| NFR4 | Error rate | <5% | Metrics analysis |

### Quality Gates

| # | Gate | Pass Condition |
|---|------|----------------|
| QG1 | Unit test coverage | >90% for new code |
| QG2 | Integration tests | All pass with mock API |
| QG3 | Manual testing | 20 rewrites, >80% valid |
| QG4 | Prompt review | Approved by project lead |
| QG5 | Security review | API key storage validated |

---

## Risk Mitigation

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| API rate limits | Medium | Medium | Implement caching, fallback to placeholder |
| API cost | Medium | Low | Set usage limits, monitor spending |
| Invalid rewrites | Medium | High | Strict vocabulary validation |
| Network issues | High | Medium | Graceful degradation to placeholder |
| Prompt injection | Low | High | Strict system prompt, output validation |

---

## Open Questions for Phase 3

1. How much context is needed for accurate rewrites?
   - Decision: Start with last output only; expand if accuracy insufficient

2. Should we cache LLM responses?
   - Decision: No for Phase 2 (evaluate in Phase 3)

3. How to handle games with non-standard vocabulary?
   - Decision: Use extracted vocabulary as ground truth

4. Should we include room name in context?
   - Decision: Not in Phase 2 (requires output parsing)

---

## Phase 2 Exit Criteria

Phase 2 is complete when:

- [ ] All acceptance criteria met (FR1-FR7, NFR1-NFR4, QG1-QG5)
- [ ] Cloud LLM integration working with at least one provider
- [ ] Vocabulary validation enforcing all rules
- [ ] Prompt specification documented and reviewed
- [ ] Performance targets met (<3s p95 latency)
- [ ] Integration tests passing
- [ ] Code reviewed and merged
- [ ] Phase 3 dependencies clearly defined
