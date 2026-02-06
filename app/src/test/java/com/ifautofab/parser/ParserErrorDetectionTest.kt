package com.ifautofab.parser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for parser error detection.
 * Tests the error pattern matching and classification.
 */
class ParserErrorDetectionTest {

    @Before
    fun setup() {
        // Reset parser state before each test
        ParserWrapper.reset()
    }

    // ==================== Unknown Verb Tests ====================

    @Test
    fun `detects unknown verb error - I don't understand that sentence`() {
        val output = "I don't understand that sentence."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull("Should detect error", error)
        assertEquals("Should be UNKNOWN_VERB", ErrorType.UNKNOWN_VERB, error.type)
        assertTrue("Should contain error text", error.matchedText.contains("don't understand"))
    }

    @Test
    fun `detects unknown verb error - I don't know the word`() {
        val output = "I don't know the word 'xyzzy'."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.UNKNOWN_VERB, error.type)
    }

    @Test
    fun `detects unknown verb error - used in a way I don't understand`() {
        val output = "You used the word 'jump' in a way that I don't understand."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.UNKNOWN_VERB, error.type)
    }

    @Test
    fun `detects unknown verb error - I didn't understand`() {
        val output = "I didn't understand that sentence."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.UNKNOWN_VERB, error.type)
    }

    // ==================== Unknown Noun Tests ====================

    @Test
    fun `detects unknown noun error - can't see any such thing`() {
        val output = "You can't see any such thing."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.UNKNOWN_NOUN, error.type)
    }

    @Test
    fun `detects unknown noun error - I don't see that`() {
        val output = "I don't see that here."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.UNKNOWN_NOUN, error.type)
    }

    @Test
    fun `detects unknown noun error - There is no here`() {
        val output = "There is none of that here."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.UNKNOWN_NOUN, error.type)
    }

    @Test
    fun `detects unknown noun error - You don't see that here`() {
        val output = "You don't see that here."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.UNKNOWN_NOUN, error.type)
    }

    // ==================== Ambiguity Tests ====================

    @Test
    fun `detects ambiguous noun error - Which do you mean`() {
        val output = "Which do you mean, the brass key or the rusty key?"
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.AMBIGUOUS, error.type)
    }

    @Test
    fun `detects ambiguous noun error - Do you mean the`() {
        val output = "Do you mean the brass key?"
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.AMBIGUOUS, error.type)
    }

    // ==================== Syntax Error Tests ====================

    @Test
    fun `detects syntax error - I only understood you as far as`() {
        val output = "I only understood you as far as \"take the\"."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.SYNTAX, error.type)
    }

    @Test
    fun `detects syntax error - You seem to have said too much`() {
        val output = "You seem to have said too much!"
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.SYNTAX, error.type)
    }

    // ==================== Darkness Tests ====================

    @Test
    fun `detects darkness error - It's too dark to see`() {
        val output = "It's too dark to see!"
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.DARKNESS, error.type)
    }

    @Test
    fun `detects darkness error - It is pitch dark`() {
        val output = "It is pitch dark to see."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.DARKNESS, error.type)
    }

    // ==================== Game Logic Response Tests ====================

    @Test
    fun `detects game logic - You can't do that`() {
        val output = "You can't do that."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.CANT_DO_THAT, error.type)
    }

    @Test
    fun `detects game logic - You don't have the key`() {
        val output = "You don't have the key."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.NOT_HERE, error.type)
    }

    // ==================== Valid Response Tests ====================

    @Test
    fun `does not detect error in valid command response - take`() {
        val output = "Taken."
        val error = ParserWrapper.detectParserFailure(output)

        assertNull("Should not detect error in valid response", error)
    }

    @Test
    fun `does not detect error in valid command response - You take the`() {
        val output = "You take the brass key."
        val error = ParserWrapper.detectParserFailure(output)

        assertNull(error)
    }

    @Test
    fun `does not detect error in valid command response - Opened`() {
        val output = "Opened."
        val error = ParserWrapper.detectParserFailure(output)

        assertNull(error)
    }

    @Test
    fun `does not detect error in valid command response - go north`() {
        val output = "You go north."
        val error = ParserWrapper.detectParserFailure(output)

        assertNull(error)
    }

    @Test
    fun `does not detect error in valid command response - The door is open`() {
        val output = "The door is open."
        val error = ParserWrapper.detectParserFailure(output)

        assertNull(error)
    }

    @Test
    fun `does not detect error in valid command response - Dropping`() {
        val output = "Dropping the lantern. Done."
        val error = ParserWrapper.detectParserFailure(output)

        assertNull(error)
    }

    // ==================== Multi-Paragraph Tests ====================

    @Test
    fun `handles multi-paragraph output with error`() {
        val output = """
            The door opens to reveal a dark room beyond.

            I don't understand the word "xyzzy".
        """.trimIndent()

        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.UNKNOWN_VERB, error.type)
    }

    @Test
    fun `handles multi-paragraph output with valid response`() {
        val output = """
            You are in a dark room.
            A brass key is here.

            Taken.
        """.trimIndent()

        val error = ParserWrapper.detectParserFailure(output)

        assertNull(error)
    }

    // ==================== Rewrite Decision Tests ====================

    @Test
    fun `should attempt rewrite for unknown verb`() {
        val output = "I don't understand that sentence."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertTrue("Should attempt rewrite for UNKNOWN_VERB",
            ParserWrapper.shouldAttemptRewrite(error))
    }

    @Test
    fun `should attempt rewrite for unknown noun`() {
        val output = "You can't see any such thing."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertTrue("Should attempt rewrite for UNKNOWN_NOUN",
            ParserWrapper.shouldAttemptRewrite(error))
    }

    @Test
    fun `should attempt rewrite for ambiguous`() {
        val output = "Which do you mean, the brass key or the rusty key?"
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertTrue("Should attempt rewrite for AMBIGUOUS",
            ParserWrapper.shouldAttemptRewrite(error))
    }

    @Test
    fun `should attempt rewrite for syntax error`() {
        val output = "I only understood you as far as \"take the\"."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertTrue("Should attempt rewrite for SYNTAX",
            ParserWrapper.shouldAttemptRewrite(error))
    }

    @Test
    fun `should NOT rewrite game logic - You can't do that`() {
        val output = "You can't do that."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertFalse("Should NOT rewrite CANT_DO_THAT",
            ParserWrapper.shouldAttemptRewrite(error))
    }

    @Test
    fun `should NOT rewrite darkness error`() {
        val output = "It's too dark to see!"
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertFalse("Should NOT rewrite DARKNESS",
            ParserWrapper.shouldAttemptRewrite(error))
    }

    @Test
    fun `should NOT rewrite not here error`() {
        val output = "You don't have the key."
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertFalse("Should NOT rewrite NOT_HERE",
            ParserWrapper.shouldAttemptRewrite(error))
    }

    @Test
    fun `should NOT rewrite no such thing`() {
        val output = "There's nothing here."
        val error = ParserWrapper.detectParserFailure(output)

        if (error != null) {
            assertFalse("Should NOT rewrite NO_SUCH_THING",
                ParserWrapper.shouldAttemptRewrite(error))
        }
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles empty output`() {
        val output = ""
        val error = ParserWrapper.detectParserFailure(output)

        assertNull("Should return null for empty output", error)
    }

    @Test
    fun `handles whitespace only output`() {
        val output = "   \n\n  "
        val error = ParserWrapper.detectParserFailure(output)

        assertNull("Should return null for whitespace only", error)
    }

    @Test
    fun `handles output with special characters`() {
        val output = "I don't understand \"that\" sentence!"
        val error = ParserWrapper.detectParserFailure(output)

        assertNotNull(error)
        assertEquals(ErrorType.UNKNOWN_VERB, error.type)
    }

    @Test
    fun `is case insensitive for error detection`() {
        val outputs = listOf(
            "I don't understand that sentence.",
            "I DON'T UNDERSTAND THAT SENTENCE.",
            "i don't understand that sentence.",
            "I Don't Understand That Sentence."
        )

        for (output in outputs) {
            val error = ParserWrapper.detectParserFailure(output)
            assertNotNull("Should detect error: $output", error)
            assertEquals(ErrorType.UNKNOWN_VERB, error.type)
        }
    }

    // ==================== Retry Limit Tests ====================

    @Test
    fun `tracks retry availability`() {
        val context = GameContext.EMPTY

        // First call should allow retry
        val command1 = "xyzzy"
        ParserWrapper.interceptInput(command1, context)
        assertTrue("Should have retry available initially",
            ParserWrapper.isRetryAvailable())

        // Mark rewrite attempted
        ParserWrapper.markRewriteAttempted("look")

        // Retry should no longer be available
        assertFalse("Should not have retry after attempt",
            ParserWrapper.isRetryAvailable())
    }

    @Test
    fun `reset clears retry state`() {
        val context = GameContext.EMPTY

        ParserWrapper.interceptInput("xyzzy", context)
        ParserWrapper.markRewriteAttempted("look")
        assertFalse(ParserWrapper.isRetryAvailable())

        ParserWrapper.reset()
        assertTrue("Should have retry available after reset",
            ParserWrapper.isRetryAvailable())
    }

    @Test
    fun `new command resets retry availability`() {
        val context = GameContext.EMPTY

        // First command
        ParserWrapper.interceptInput("xyzzy", context)
        ParserWrapper.markRewriteAttempted("look")
        assertFalse(ParserWrapper.isRetryAvailable())

        // New command
        ParserWrapper.interceptInput("asdf", context)
        assertTrue("New command should reset retry availability",
            ParserWrapper.isRetryAvailable())
    }

    // ==================== GameContext Tests ====================

    @Test
    fun `empty game context is valid`() {
        val context = GameContext.EMPTY

        assertEquals("Current room should be null", null, context.currentRoom)
        assertEquals("Visible objects should be empty", emptySet<String>(), context.visibleObjects)
        assertEquals("Inventory should be empty", emptySet<String>(), context.inventory)
        assertEquals("Recent commands should be empty", emptyList<String>(), context.recentCommands)
        assertEquals("Exits should be empty", emptySet<String>(), context.exits)
    }

    @Test
    fun `game context with data`() {
        val context = GameContext(
            currentRoom = "Kitchen",
            visibleObjects = setOf("table", "chair", "knife"),
            inventory = setOf("lamp", "key"),
            recentCommands = listOf("look", "go north", "take key"),
            exits = setOf("north", "south", "east")
        )

        assertEquals("Kitchen", context.currentRoom)
        assertEquals(3, context.visibleObjects.size)
        assertEquals(2, context.inventory.size)
        assertEquals(3, context.recentCommands.size)
        assertEquals(3, context.exits.size)
    }
}
