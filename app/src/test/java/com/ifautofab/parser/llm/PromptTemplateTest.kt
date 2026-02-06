package com.ifautofab.parser.llm

import com.ifautofab.parser.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PromptTemplate.
 */
class PromptTemplateTest : LLMTestBase() {

    private lateinit var testVocabulary: ZMachineVocabulary
    private lateinit var testContext: GameContext

    @Before
    fun setup() {
        testVocabulary = ZMachineVocabulary(version = 3).apply {
            verbs.addAll(listOf("take", "drop", "open", "close", "go", "look"))
            nouns.addAll(listOf("key", "door", "lamp", "leaflet", "mailbox"))
            adjectives.addAll(listOf("brass", "rusty", "magic", "small"))
            prepositions.addAll(listOf("with", "from", "behind", "under"))
        }

        testContext = GameContext(
            currentRoom = "Kitchen",
            visibleObjects = setOf("table", "chair", "knife"),
            inventory = setOf("lamp", "key"),
            recentCommands = listOf("look", "go north"),
            exits = setOf("north", "south", "east")
        )
    }

    @Test
    fun `buildPrompt includes system prompt`() {
        val error = ErrorInfo(
            ErrorType.UNKNOWN_VERB,
            "I don't understand",
            "I don't know the word 'grab'."
        )

        val prompt = PromptTemplate.buildPrompt(
            "grab key",
            error.fullOutput,
            error.type,
            testVocabulary,
            testContext
        )

        assertNotNull(prompt.system)
        assertTrue(prompt.system.contains("parser assistant"))
        assertTrue(prompt.system.contains("NO_VALID_REWRITE"))
        assertTrue(prompt.system.contains("CRITICAL CONSTRAINTS"))
    }

    @Test
    fun `buildPrompt includes failed command`() {
        val error = ErrorInfo(
            ErrorType.UNKNOWN_VERB,
            "I don't understand",
            "I don't know the word 'xyzzy'."
        )

        val prompt = PromptTemplate.buildPrompt(
            "xyzzy",
            error.fullOutput,
            error.type,
            testVocabulary,
            testContext
        )

        assertTrue(prompt.user.contains("xyzzy"))
        assertTrue(prompt.user.contains("Failed Command"))
    }

    @Test
    fun `buildPrompt includes error type`() {
        val error = ErrorInfo(
            ErrorType.UNKNOWN_NOUN,
            "You can't see any such thing",
            "You can't see any such thing."
        )

        val prompt = PromptTemplate.buildPrompt(
            "take nonexistent",
            error.fullOutput,
            error.type,
            testVocabulary,
            testContext
        )

        assertTrue(prompt.user.contains("UNKNOWN_NOUN"))
        assertTrue(prompt.user.contains("Error Type"))
    }

    @Test
    fun `buildPrompt includes vocabulary for verb error`() {
        val error = ErrorInfo(
            ErrorType.UNKNOWN_VERB,
            "I don't understand",
            "I don't know the word 'grab'."
        )

        val prompt = PromptTemplate.buildPrompt(
            "grab key",
            error.fullOutput,
            error.type,
            testVocabulary,
            testContext
        )

        // For verb errors, should include verbs
        assertTrue(prompt.user.contains("Verbs:"))
        assertTrue(prompt.user.contains("take"))
        assertTrue(prompt.user.contains("drop"))

        // But not nouns (reduces noise)
        assertFalse(prompt.user.contains("Nouns:"))
    }

    @Test
    fun `buildPrompt includes vocabulary for noun error`() {
        val error = ErrorInfo(
            ErrorType.UNKNOWN_NOUN,
            "You can't see any such thing",
            "You can't see any such thing."
        )

        val prompt = PromptTemplate.buildPrompt(
            "take nonexistent",
            error.fullOutput,
            error.type,
            testVocabulary,
            testContext
        )

        // For noun errors, should include nouns and adjectives
        assertTrue(prompt.user.contains("Nouns:"))
        assertTrue(prompt.user.contains("Adjectives:"))

        // But not verbs (reduces noise)
        assertFalse(prompt.user.contains("Verbs:"))
    }

    @Test
    fun `buildPrompt includes visible objects from context`() {
        val error = ErrorInfo(
            ErrorType.UNKNOWN_NOUN,
            "You can't see any such thing",
            "You can't see any such thing."
        )

        val prompt = PromptTemplate.buildPrompt(
            "take nonexistent",
            error.fullOutput,
            error.type,
            testVocabulary,
            testContext
        )

        assertTrue(prompt.user.contains("Visible Objects"))
        assertTrue(prompt.user.contains("table"))
        assertTrue(prompt.user.contains("chair"))
        assertTrue(prompt.user.contains("knife"))
    }

    @Test
    fun `buildPrompt includes exits from context`() {
        val error = ErrorInfo(
            ErrorType.SYNTAX,
            "I only understood you as far as",
            "I only understood you as far as 'go'."
        )

        val prompt = PromptTemplate.buildPrompt(
            "go",
            error.fullOutput,
            error.type,
            testVocabulary,
            testContext
        )

        assertTrue(prompt.user.contains("Available Exits"))
        assertTrue(prompt.user.contains("north"))
        assertTrue(prompt.user.contains("south"))
        assertTrue(prompt.user.contains("east"))
    }

    @Test
    fun `buildPrompt includes task instruction`() {
        val error = ErrorInfo(
            ErrorType.UNKNOWN_VERB,
            "I don't understand",
            "I don't understand that sentence."
        )

        val prompt = PromptTemplate.buildPrompt(
            "asdf",
            error.fullOutput,
            error.type,
            testVocabulary,
            testContext
        )

        assertTrue(prompt.user.contains("Task"))
        assertTrue(prompt.user.contains("Rewrite this command"))
    }

    @Test
    fun `estimatedTokenCount is reasonable`() {
        val error = ErrorInfo(
            ErrorType.UNKNOWN_VERB,
            "I don't understand",
            "I don't understand that sentence."
        )

        val prompt = PromptTemplate.buildPrompt(
            "xyzzy",
            error.fullOutput,
            error.type,
            testVocabulary,
            testContext
        )

        val tokenCount = prompt.estimatedTokenCount()

        // Should be between 100 and 1000 tokens for a typical prompt
        assertTrue("Token count too low: $tokenCount", tokenCount > 100)
        assertTrue("Token count too high: $tokenCount", tokenCount < 1000)
    }

    @Test
    fun `buildPrompt truncates long output`() {
        val longOutput = "A".repeat(1000) + "I don't understand that sentence."

        val error = ErrorInfo(
            ErrorType.UNKNOWN_VERB,
            "I don't understand",
            longOutput
        )

        val prompt = PromptTemplate.buildPrompt(
            "xyzzy",
            error.fullOutput,
            error.type,
            testVocabulary,
            testContext
        )

        // Should truncate to 500 characters
        val outputSection = prompt.user.substringAfter("Game Output")
            .substringBefore("##")
            .trim()

        assertTrue("Output not truncated: ${outputSection.length} chars", outputSection.length <= 600)
    }

    @Test
    fun `buildPrompt with empty vocabulary`() {
        val emptyVocab = ZMachineVocabulary(version = 3)
        val error = ErrorInfo(
            ErrorType.UNKNOWN_VERB,
            "I don't understand",
            "I don't understand that sentence."
        )

        val prompt = PromptTemplate.buildPrompt(
            "xyzzy",
            error.fullOutput,
            error.type,
            emptyVocab,
            testContext
        )

        // Should still include system prompt even with no vocabulary
        assertNotNull(prompt.system)
        assertTrue(prompt.system.isNotBlank())
    }

    @Test
    fun `buildPrompt with empty context`() {
        val emptyContext = GameContext.EMPTY
        val error = ErrorInfo(
            ErrorType.UNKNOWN_VERB,
            "I don't understand",
            "I don't understand that sentence."
        )

        val prompt = PromptTemplate.buildPrompt(
            "xyzzy",
            error.fullOutput,
            error.type,
            testVocabulary,
            emptyContext
        )

        // Should not include visible objects or exits sections
        assertFalse(prompt.user.contains("Visible Objects"))
        assertFalse(prompt.user.contains("Available Exits"))
    }
}
