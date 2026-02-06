package com.ifautofab.parser

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the placeholder rewriter.
 * Tests rule-based transformations that Phase 1 provides.
 */
class PlaceholderRewriterTest {

    private val context = GameContext.EMPTY

    // ==================== Whitespace Normalization Tests ====================

    @Test
    fun `normalizes multiple spaces to single space`() {
        val command = "take    the    key"
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull("Should return rewrite", result)
        assertEquals("take the key", result)
    }

    @Test
    fun `normalizes leading spaces`() {
        val command = "   take key"
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("take key", result)
    }

    @Test
    fun `normalizes trailing spaces`() {
        val command = "take key   "
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("take key", result)
    }

    @Test
    fun `normalizes mixed whitespace`() {
        val command = "  take   the   key  "
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("take the key", result)
    }

    // ==================== Punctuation Cleanup Tests ====================

    @Test
    fun `removes leading comma`() {
        val command = ",take key"
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("take key", result)
    }

    @Test
    fun `removes leading period`() {
        val command = ".take key"
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("take key", result)
    }

    @Test
    fun `removes leading quote`() {
        val command = "'take key"
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("take key", result)
    }

    @Test
    fun `removes trailing comma`() {
        val command = "take key,"
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("take key", result)
    }

    @Test
    fun `removes trailing semicolon`() {
        val command = "take key;"
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("take key", result)
    }

    @Test
    fun `does not remove trailing period`() {
        val command = "take key."
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        // Trailing period is preserved (might be intentional sentence)
        assertNotNull(result)
        assertEquals("take key.", result)
    }

    // ==================== Direction Synonym Tests ====================

    @Test
    fun `rewrites forward to north`() {
        val command = "forward"
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("north", result)
    }

    @Test
    fun `rewrites backwards to south`() {
        val command = "backwards"
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("south", result)
    }

    @Test
    fun `rewrites right to east`() {
        val command = "right"
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("east", result)
    }

    @Test
    fun `rewrites left to west`() {
        val command = "left"
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("west", result)
    }

    @Test
    fun `rewrites upwards to up`() {
        val command = "upwards"
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("up", result)
    }

    @Test
    fun `rewrites downwards to down`() {
        val command = "downwards"
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("down", result)
    }

    @Test
    fun `direction synonyms are case insensitive`() {
        val command = "FORWARD"
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("north", result)
    }

    // ==================== Verb Synonym Tests ====================

    @Test
    fun `rewrites grab to take`() {
        val command = "grab key"
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("take key", result)
    }

    @Test
    fun `rewrites pick up to take`() {
        val command = "pick up key"
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("take key", result)
    }

    @Test
    fun `rewrites examine to look at`() {
        val command = "examine key"
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("look at key", result)
    }

    @Test
    fun `rewrites inspect to look at`() {
        val command = "inspect key"
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("look at key", result)
    }

    @Test
    fun `rewrites check to look at`() {
        val command = "check key"
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("look at key", result)
    }

    @Test
    fun `rewrites shut to close`() {
        val command = "shut door"
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("close door", result)
    }

    // ==================== Go Simplification Tests ====================

    @Test
    fun `simplifies go north to north`() {
        val command = "go north"
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("north", result)
    }

    @Test
    fun `simplifies go n to n`() {
        val command = "go n"
        val error = ErrorType.UNKNOWN_VERB.let { ErrorInfo(it, "error", "error") }
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("n", result)
    }

    @Test
    fun `simplifies go up to up`() {
        val command = "go up"
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("up", result)
    }

    @Test
    fun `does not simplify go with invalid direction`() {
        val command = "go nowhere"
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNull("Should not rewrite invalid direction", result)
    }

    // ==================== No Rewrite Available Tests ====================

    @Test
    fun `returns null for nonsense command`() {
        val command = "xyzzy"
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNull("Should return null for unrecognizable command", result)
    }

    @Test
    fun `returns null for already correct command`() {
        val command = "take key"
        val error = ErrorInfo(ErrorType.UNKNOWN_NOUN, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNull("Should return null for correct syntax", result)
    }

    @Test
    fun `returns null for empty command`() {
        val command = ""
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNull("Should return null for empty command", result)
    }

    @Test
    fun `returns null for whitespace only command`() {
        val command = "   "
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNull("Should return null for whitespace only", result)
    }

    // ==================== Mock Rewrite Tests ====================

    @Test
    fun `mockRewriteForTesting handles pick synonym`() {
        val result = PlaceholderRewriter.mockRewriteForTesting("pick up key")
        assertTrue("Should contain 'take'", result.lowercase().contains("take"))
    }

    @Test
    fun `mockRewriteForTesting handles unlock door`() {
        val result = PlaceholderRewriter.mockRewriteForTesting("unlock door")
        assertTrue("Should suggest 'with key'", result.contains("with key"))
    }

    @Test
    fun `mockRewriteForTesting handles directions`() {
        assertEquals("north", PlaceholderRewriter.mockRewriteForTesting("forward"))
        assertEquals("south", PlaceholderRewriter.mockRewriteForTesting("backward"))
        assertEquals("east", PlaceholderRewriter.mockRewriteForTesting("right"))
        assertEquals("west", PlaceholderRewriter.mockRewriteForTesting("left"))
    }

    // ==================== Backend Info Tests ====================

    @Test
    fun `isAvailable always returns true in Phase 1`() {
        assertTrue("Placeholder rewriter should always be available",
            PlaceholderRewriter.isAvailable())
    }

    @Test
    fun `getBackendName returns Phase 1 identifier`() {
        val name = PlaceholderRewriter.getBackendName()
        assertTrue("Should mention Phase 1", name.contains("Phase 1"))
        assertTrue("Should mention placeholder or rule-based",
            name.contains("Placeholder", ignoreCase = true) ||
            name.contains("rule-based", ignoreCase = true))
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles command with only punctuation`() {
        val command = "..."
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        // After stripping punctuation, nothing remains
        assertNull("Should return null for punctuation only", result)
    }

    @Test
    fun `handles very long command with extra spaces`() {
        val command = "take     the     very     long     sword     with     the     golden     handle"
        val error = ErrorInfo(ErrorType.SYNTAX, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("Should normalize all spaces",
            "take the very long sword with the golden handle", result)
    }

    @Test
    fun `preserves valid command content`() {
        val command = "examine the brass key carefully"
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        val result = PlaceholderRewriter.attemptRewrite(command, error, context)

        assertNotNull(result)
        assertEquals("look at the brass key carefully", result)
    }
}
