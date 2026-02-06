package com.ifautofab.parser.llm

import com.ifautofab.parser.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for VocabularyValidator.
 */
class VocabularyValidatorTest : LLMTestBase() {

    private lateinit var validator: VocabularyValidator
    private lateinit var testVocabulary: ZMachineVocabulary
    private lateinit var testContext: GameContext

    @Before
    fun setup() {
        validator = VocabularyValidator(strictMode = true)

        testVocabulary = ZMachineVocabulary(version = 3).apply {
            verbs.addAll(listOf("take", "drop", "open", "close", "go", "look", "unlock", "pick", "up"))
            nouns.addAll(listOf("key", "door", "lamp", "leaflet", "mailbox", "table", "chair", "knife", "sword"))
            adjectives.addAll(listOf("brass", "rusty", "magic", "small", "wooden", "rusty iron"))
            prepositions.addAll(listOf("with", "from", "behind", "under", "on", "near"))
        }

        testContext = GameContext(
            currentRoom = "Kitchen",
            visibleObjects = setOf("table", "chair", "knife"),
            inventory = setOf("lamp", "key"),
            recentCommands = listOf("look", "go north"),
            exits = setOf("north", "south", "east")
        )
    }

    // ==================== Valid Commands ====================

    @Test
    fun `accepts simple verb command`() {
        assertTrue(validator.isValid("look", testVocabulary, testContext))
    }

    @Test
    fun `accepts verb noun command`() {
        assertTrue(validator.isValid("take key", testVocabulary, testContext))
    }

    @Test
    fun `accepts verb adjective noun command`() {
        assertTrue(validator.isValid("take brass key", testVocabulary, testContext))
    }

    @Test
    fun `accepts verb noun preposition noun command`() {
        assertTrue(validator.isValid("unlock door with key", testVocabulary, testContext))
    }

    @Test
    fun `accepts verb adjective noun preposition noun command`() {
        assertTrue(validator.isValid("take brass key from table", testVocabulary, testContext))
    }

    @Test
    fun `accepts noun from visible objects`() {
        assertTrue(validator.isValid("take knife", testVocabulary, testContext))
    }

    @Test
    fun `accepts noun from inventory`() {
        assertTrue(validator.isValid("drop lamp", testVocabulary, testContext))
    }

    @Test
    fun `accepts direction command`() {
        assertTrue(validator.isValid("go north", testVocabulary, testContext))
    }

    // ==================== Invalid Verbs ====================

    @Test
    fun `rejects unknown verb`() {
        assertFalse(validator.isValid("grab key", testVocabulary, testContext))
    }

    @Test
    fun `rejects unknown verb synonym`() {
        assertFalse(validator.isValid("pick up key", testVocabulary, testContext))
    }

    @Test
    fun `rejects numeric verb`() {
        assertFalse(validator.isValid("1 key", testVocabulary, testContext))
    }

    // ==================== Invalid Nouns ====================

    @Test
    fun `rejects unknown noun`() {
        assertFalse(validator.isValid("take nonexistent", testVocabulary, testContext))
    }

    @Test
    fun `rejects noun not in vocabulary or context`() {
        assertFalse(validator.isValid("take sword", testVocabulary, testContext))
    }

    // ==================== Invalid Structure ====================

    @Test
    fun `rejects empty command`() {
        assertFalse(validator.isValid("", testVocabulary, testContext))
    }

    @Test
    fun `rejects whitespace only command`() {
        assertFalse(validator.isValid("   ", testVocabulary, testContext))
    }

    @Test
    fun `rejects overly long command`() {
        val tooLong = "take ${testVocabulary.nouns.joinToString(" ")} key"
        assertFalse(validator.isValid(tooLong, testVocabulary, testContext))
    }

    // ==================== Preposition Validation ====================

    @Test
    fun `accepts valid preposition`() {
        assertTrue(validator.isValid("look on table", testVocabulary, testContext))
    }

    @Test
    fun `rejects unknown preposition`() {
        assertFalse(validator.isValid("look near table", testVocabulary, testContext))
    }

    @Test
    fun `rejects preposition without object`() {
        assertFalse(validator.isValid("take with", testVocabulary, testContext))
    }

    // ==================== Error Reporting ====================

    @Test
    fun `reports unknown verb error`() {
        val errors = validator.getValidationErrors("grab key", testVocabulary, testContext)

        assertTrue(errors.any { it.contains("Unknown verb") })
        assertTrue(errors.any { it.contains("grab") })
    }

    @Test
    fun `reports unknown noun error`() {
        val errors = validator.getValidationErrors("take sword", testVocabulary, testContext)

        assertTrue(errors.any { it.contains("Unknown word") })
        assertTrue(errors.any { it.contains("sword") })
    }

    @Test
    fun `reports multiple errors`() {
        val errors = validator.getValidationErrors("grab sword", testVocabulary, testContext)

        assertTrue(errors.size >= 2)
        assertTrue(errors.any { it.contains("verb") })
        assertTrue(errors.any { it.contains("noun") || it.contains("word") })
    }

    @Test
    fun `reports empty command error`() {
        val errors = validator.getValidationErrors("", testVocabulary, testContext)

        assertTrue(errors.any { it.contains("blank") })
    }

    // ==================== Non-Strict Mode ====================

    @Test
    fun `non-strict mode allows unknown verbs`() {
        val lenientValidator = VocabularyValidator(strictMode = false)

        assertTrue(lenientValidator.isValid("grab key", testVocabulary, testContext))
    }

    @Test
    fun `non-strict mode allows unknown nouns`() {
        val lenientValidator = VocabularyValidator(strictMode = false)

        assertTrue(lenientValidator.isValid("take sword", testVocabulary, testContext))
    }

    // ==================== Batch Validation ====================

    @Test
    fun `validateAll handles multiple commands`() {
        val commands = listOf(
            "take key",
            "grab key",
            "look",
            "unlock door with key"
        )

        val results = validator.validateAll(commands, testVocabulary, testContext)

        assertEquals(4, results.size)
        assertTrue(results["take key"]!!)
        assertFalse(results["grab key"]!!)
        assertTrue(results["look"]!!)
        assertTrue(results["unlock door with key"]!!)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles command with trailing whitespace`() {
        assertTrue(validator.isValid("take key   ", testVocabulary, testContext))
    }

    @Test
    fun `handles command with leading whitespace`() {
        assertTrue(validator.isValid("  take key", testVocabulary, testContext))
    }

    @Test
    fun `handles mixed case command`() {
        assertTrue(validator.isValid("Take Key", testVocabulary, testContext))
    }

    @Test
    fun `handles apostrophes in nouns`() {
        // Add noun with apostrophe to vocabulary
        testVocabulary.nouns.add("farmer's")

        assertTrue(validator.isValid("take farmer's", testVocabulary, testContext))
    }

    @Test
    fun `handles hyphenated adjectives`() {
        // Add hyphenated adjective to vocabulary
        testVocabulary.adjectives.add("rusty iron")

        assertTrue(validator.isValid("take rusty iron key", testVocabulary, testContext))
    }

    // ==================== Context-Aware Validation ====================

    @Test
    fun `accepts noun from visible objects not in dictionary`() {
        // "chair" is visible but not in vocabulary
        assertTrue(validator.isValid("take chair", testVocabulary, testContext))
    }

    @Test
    fun `accepts noun from inventory not in dictionary`() {
        // "key" is in inventory but let's test with something not in vocabulary
        testContext = GameContext.EMPTY.copy(
            inventory = setOf("special item")
        )

        assertTrue(validator.isValid("drop special item", testVocabulary, testContext))
    }

    @Test
    fun `rejects noun not in dictionary or context`() {
        val emptyContext = GameContext.EMPTY

        assertFalse(validator.isValid("take sword", testVocabulary, emptyContext))
    }

    // ==================== Complex Commands ====================

    @Test
    fun `accepts complex prepositional phrase`() {
        assertTrue(validator.isValid("take small brass key from table", testVocabulary, testContext))
    }

    @Test
    fun `accepts command with multiple adjectives`() {
        testVocabulary.adjectives.addAll(listOf("small", "rusty"))

        assertTrue(validator.isValid("take small rusty key", testVocabulary, testContext))
    }

    @Test
    fun `rejects verb in noun position`() {
        val errors = validator.getValidationErrors("take take key", testVocabulary, testContext)

        assertTrue(errors.any { it.contains("Verb in noun position") })
    }
}
