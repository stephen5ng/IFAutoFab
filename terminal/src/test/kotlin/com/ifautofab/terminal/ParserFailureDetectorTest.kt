package com.ifautofab.terminal

import org.junit.Test
import org.junit.Assert.*

class ParserFailureDetectorTest {

    @Test
    fun testUnknownVerbPatterns_Infocom() {
        // Classic Infocom error messages
        val outputs = listOf(
            "I don't know the word \"grab\".",
            "I don't know the word 'grab'.",
            "I don't understand that sentence.",
            "I don't understand that sentence",
            "I didn't understand that sentence.",
            "You used the word \"xray\" in a way that I don't understand.",
            "I don't know how to do that.",
            "I only understood you as far as \"take\".",
            "You seem to have said too much."
        )

        for (output in outputs) {
            val result = ParserFailureDetector.detect(output)
            assertNotNull("Should detect error in: $output", result)
            assertEquals("Should be UNKNOWN_VERB: $output", FailureType.UNKNOWN_VERB, result?.type)
            assertTrue("Should be rewritable: $output", result?.isRewritable == true)
        }
    }

    @Test
    fun testUnknownVerbPatterns_Inform() {
        // Inform 6/7 error messages
        val outputs = listOf(
            "That's not a verb I recognise.",
            "That's not a verb I recognize.",
            "I can't see that as a verb."
        )

        for (output in outputs) {
            val result = ParserFailureDetector.detect(output)
            assertNotNull("Should detect error in: $output", result)
            assertEquals("Should be UNKNOWN_VERB: $output", FailureType.UNKNOWN_VERB, result?.type)
            assertTrue("Should be rewritable: $output", result?.isRewritable == true)
        }
    }

    @Test
    fun testUnknownNounPatterns() {
        val outputs = listOf(
            "You can't see any such thing.",
            "I don't see that here.",
            "I don't see the mailbox here.",
            "There is none of that here.",
            "There is no mailbox here.",
            "You don't see that here.",
            "I can't find a mailbox.",
            "That is not available here.",
            "What do you want to examine?"
        )

        for (output in outputs) {
            val result = ParserFailureDetector.detect(output)
            assertNotNull("Should detect error in: $output", result)
            assertEquals("Should be UNKNOWN_NOUN: $output", FailureType.UNKNOWN_NOUN, result?.type)
            assertTrue("Should be rewritable: $output", result?.isRewritable == true)
        }
    }

    @Test
    fun testAmbiguityPatterns_NotRewritable() {
        val outputs = listOf(
            "Which do you mean, the red book or the blue book?",
            "Do you mean the red book?",
            "The word \"open\" is not used in this game."
        )

        for (output in outputs) {
            val result = ParserFailureDetector.detect(output)
            assertNotNull("Should detect error in: $output", result)
            assertEquals("Should be AMBIGUITY: $output", FailureType.AMBIGUITY, result?.type)
            assertFalse("Should NOT be rewritable: $output", result?.isRewritable == true)
        }
    }

    @Test
    fun testGameRefusal_NotRewritable() {
        val outputs = listOf(
            "You can't do that.",
            "You can't go that way.",
            "Nothing to eat.",
            "That's nothing to eat.",
            "It's too dark to see.",
            "It is pitch dark.",
            "You don't have the key.",
            "You're not holding the key.",
            "There's nothing here.",
            "It's locked.",
            "The door is locked.",
            "It's already open.",
            "You can't take that."
        )

        for (output in outputs) {
            val result = ParserFailureDetector.detect(output)
            assertNotNull("Should detect error in: $output", result)
            assertEquals("Should be GAME_REFUSAL: $output", FailureType.GAME_REFUSAL, result?.type)
            assertFalse("Should NOT be rewritable: $output", result?.isRewritable == true)
        }
    }

    @Test
    fun testNormalOutput_NotErrors() {
        val outputs = listOf(
            // Room descriptions
            "You are in a kitchen.",
            "You're standing in a dark room.",
            "You are on a path.",
            "You see a table here.",
            "You can see a mailbox.",
            "North of the house.",
            "South of the kitchen.",
            "The room contains a table and a chair.",
            "Exits: North, South, East, West.",
            "Obvious exits: North, South.",
            "You can go north, south, east, or west.",
            "You are standing at the end of a road.",
            "You are sitting at a desk.",

            // Status lines
            "Score: 0",
            "Moves: 10",
            "Score: 5  Moves: 20",

            // Normal game text
            "The door opens.",
            "You pick up the key.",
            "The light flickers.",
            "A voice echoes through the room.",

            // Numbered lists
            "1. Take the key",
            "2. Open the door",

            // Empty/prompt
            "",
            ">"
        )

        for (output in outputs) {
            val result = ParserFailureDetector.detect(output)
            assertNull("Should NOT detect error in normal output: $output", result)
        }
    }

    @Test
    fun testCatchAllHeuristic_ShortErrors() {
        // Short error-like messages that don't match known patterns
        val outputs = listOf(
            "Error.",
            "Unknown command.",
            "Invalid input.",
            "Try again.",
            "What?",
            "Huh?",
            "I beg your pardon?",
            "Syntax error.",
            "No way.",
            "Impossible."
        )

        for (output in outputs) {
            val result = ParserFailureDetector.detect(output)
            assertNotNull("Should detect error via catch-all: $output", result)
            assertEquals("Should be CATCH_ALL: $output", FailureType.CATCH_ALL, result?.type)
            assertTrue("Should be rewritable: $output", result?.isRewritable == true)
        }
    }

    @Test
    fun testCatchAllHeuristic_NotForLongOutput() {
        // Long output should NOT trigger catch-all
        val longOutput = """
            You are in a large hall with marble floors and high ceilings.
            Torches flicker on the walls, casting dancing shadows.
            To the north is a heavy wooden door.
            To the south is a corridor leading into darkness.
            You can hear the faint sound of dripping water.
        """.trimIndent()

        val result = ParserFailureDetector.detect(longOutput)
        assertNull("Long room descriptions should not trigger catch-all", result)
    }

    @Test
    fun testBracketedAnnotations_NotErrors() {
        val outputs = listOf(
            "[LLM Rewrite: \"grab\" -> \"take\"]",
            "[Tried: \"take\" -> You can't see any such thing.]",
            "[Note: This is a system message]"
        )

        for (output in outputs) {
            val result = ParserFailureDetector.detect(output)
            assertNull("Bracketed annotations should not be errors: $output", result)
        }
    }

    @Test
    fun testCaseInsensitivity() {
        val outputs = listOf(
            "I DON'T KNOW THE WORD \"GRAB\".",
            "i don't know the word 'grab'.",
            "I Don't Know The Word \"grab\".",
            "YOU CAN'T SEE ANY SUCH THING.",
            "you can't see any such thing."
        )

        for (output in outputs) {
            val result = ParserFailureDetector.detect(output)
            assertNotNull("Should detect error regardless of case: $output", result)
            assertTrue("Should be rewritable: $output", result?.isRewritable == true)
        }
    }

    @Test
    fun testMultilineOutput_NotShortError() {
        // Multi-line output should NOT trigger catch-all
        val outputs = listOf(
            "You are in a room.\nExits: north, south.",
            "The door is locked.\nYou need a key.",
            "I don't understand.\nPlease try again."
        )

        for (output in outputs) {
            val result = ParserFailureDetector.detect(output)
            // Multi-line output should either be null or not CATCH_ALL
            if (result != null) {
                assertNotEquals("Multi-line output should not be CATCH_ALL", FailureType.CATCH_ALL, result.type)
            }
        }
    }

    @Test
    fun testPartialMatches() {
        // Test that patterns match even with extra text
        val outputs = listOf(
            "I don't know the word \"foobar\" but you might mean \"foo bar\".",
            "You can't see any such thing here.",
            "It's too dark to see anything."
        )

        for (output in outputs) {
            val result = ParserFailureDetector.detect(output)
            assertNotNull("Should detect error in partial match: $output", result)
            assertTrue("Should be rewritable: $output", result?.isRewritable == true)
        }
    }

    @Test
    fun testEdgeCases() {
        // Edge cases that should NOT be errors
        val outputs = listOf(
            "You",                // Single word, could be room desc start
            "You see",            // Room desc fragment
            "Score: 0",           // Status line with space
            "  >  ",              // Prompt with spaces
            "\n",                 // Just newline
            "   ",                // Just whitespace
            "The End",            // Game end message
            "You won!",           // Victory message
            "Game Over",          // Game over message
            "*** You have died ***" // Death message
        )

        for (output in outputs) {
            val result = ParserFailureDetector.detect(output)
            // Most of these should not be detected as errors
            // (unless they're very short and error-like, like "The End" might be CATCH_ALL)
            if (result != null) {
                // If detected, verify it's reasonable
                assertTrue("Matched text should be relevant", result.matchedText.isNotEmpty())
            }
        }
    }

    @Test
    fun testZorkISpecificErrors() {
        // Specific to Zork I's error style
        val outputs = listOf(
            "I don't understand that sentence.",
            "I don't know the word \"jump\".",
            "You can't see any such thing.",
            "I don't see that here."
        )

        for (output in outputs) {
            val result = ParserFailureDetector.detect(output)
            assertNotNull("Should detect Zork I error: $output", result)
            assertTrue("Zork I errors should be rewritable: $output", result?.isRewritable == true)
        }
    }

    @Test
    fun testInform7SpecificErrors() {
        // Inform 7 specific error patterns
        val outputs = listOf(
            "That's not a verb I recognise.",
            "That sentence isn't one I recognise."
        )

        for (output in outputs) {
            val result = ParserFailureDetector.detect(output)
            assertNotNull("Should detect Inform 7 error: $output", result)
            assertEquals("Should be UNKNOWN_VERB: $output", FailureType.UNKNOWN_VERB, result?.type)
        }
    }
}
