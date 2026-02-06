package com.ifautofab.parser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the retry state machine.
 * Tests state transitions and single-retry enforcement.
 */
class RetryStateMachineTest {

    private lateinit var stateMachine: RetryStateMachine

    @Before
    fun setup() {
        stateMachine = RetryStateMachine()
        stateMachine.setLogger(NoOpLogger)
    }

    @Test
    fun `initial state is IDLE`() {
        assertEquals(RetryState.IDLE, stateMachine.getState())
    }

    @Test
    fun `onCommandSent transitions from IDLE to COMMAND_SENT`() {
        stateMachine.onCommandSent("look")

        assertEquals(RetryState.COMMAND_SENT, stateMachine.getState())
        assertEquals("look", stateMachine.getOriginalCommand())
    }

    @Test
    fun `onParserError transitions from COMMAND_SENT to ERROR_DETECTED`() {
        stateMachine.onCommandSent("xyzzy")
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "I don't understand", "I don't understand that sentence.")

        val shouldRetry = stateMachine.onParserError(error)

        assertTrue("Should allow retry", shouldRetry)
        assertEquals(RetryState.ERROR_DETECTED, stateMachine.getState())
        assertEquals(error, stateMachine.getLastError())
    }

    @Test
    fun `onParserError returns false when not in COMMAND_SENT`() {
        // Starting from IDLE
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        val shouldRetry = stateMachine.onParserError(error)

        assertFalse("Should not allow retry from IDLE", shouldRetry)
        assertEquals(RetryState.IDLE, stateMachine.getState())
    }

    @Test
    fun `canRetry returns true only in ERROR_DETECTED state`() {
        assertFalse("Should not retry from IDLE", stateMachine.canRetry())

        stateMachine.onCommandSent("xyzzy")
        assertFalse("Should not retry from COMMAND_SENT", stateMachine.canRetry())

        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        stateMachine.onParserError(error)
        assertTrue("Should retry from ERROR_DETECTED", stateMachine.canRetry())

        stateMachine.onRetrySent("look")
        assertFalse("Should not retry from RETRY_SENT", stateMachine.canRetry())
    }

    @Test
    fun `onRetrySent transitions from ERROR_DETECTED to RETRY_SENT`() {
        stateMachine.onCommandSent("xyzzy")
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        stateMachine.onParserError(error)

        stateMachine.onRetrySent("look")

        assertEquals(RetryState.RETRY_SENT, stateMachine.getState())
        assertEquals("look", stateMachine.getRewrittenCommand())
    }

    @Test
    fun `second parser error transitions to FAILED`() {
        stateMachine.onCommandSent("xyzzy")
        val error1 = ErrorInfo(ErrorType.UNKNOWN_VERB, "error1", "error1")
        stateMachine.onParserError(error1)

        stateMachine.onRetrySent("look")

        val error2 = ErrorInfo(ErrorType.UNKNOWN_VERB, "error2", "error2")
        val shouldRetry = stateMachine.onParserError(error2)

        assertTrue("Should signal retry failure", shouldRetry)
        assertEquals(RetryState.FAILED, stateMachine.getState())
    }

    @Test
    fun `onSuccess transitions to IDLE`() {
        stateMachine.onCommandSent("look")
        stateMachine.onSuccess()

        assertEquals(RetryState.IDLE, stateMachine.getState())
        assertEquals("", stateMachine.getOriginalCommand())
    }

    @Test
    fun `reset clears all state`() {
        stateMachine.onCommandSent("xyzzy")
        val error = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        stateMachine.onParserError(error)
        stateMachine.onRetrySent("look")

        stateMachine.reset()

        assertEquals(RetryState.IDLE, stateMachine.getState())
        assertEquals("", stateMachine.getOriginalCommand())
        assertEquals("", stateMachine.getRewrittenCommand())
        assertNull(stateMachine.getLastError())
    }

    @Test
    fun `enforces single retry limit`() {
        // First command
        stateMachine.onCommandSent("xyzzy")
        val error1 = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        stateMachine.onParserError(error1)

        assertTrue("Should allow first retry", stateMachine.canRetry())
        stateMachine.onRetrySent("look")

        // Second error (retry failed)
        val error2 = ErrorInfo(ErrorType.UNKNOWN_VERB, "error", "error")
        stateMachine.onParserError(error2)

        assertEquals(RetryState.FAILED, stateMachine.getState())
        assertFalse("Should not allow second retry", stateMachine.canRetry())
    }

    @Test
    fun `handles new command after failed retry`() {
        // First command fails, retry also fails
        stateMachine.onCommandSent("xyzzy")
        val error1 = ErrorInfo(ErrorType.UNKNOWN_VERB, "error1", "error1")
        stateMachine.onParserError(error1)
        stateMachine.onRetrySent("look")
        val error2 = ErrorType.UNKNOWN_VERB
        stateMachine.onParserError(ErrorInfo(error2, "error2", "error2"))

        assertEquals(RetryState.FAILED, stateMachine.getState())

        // New command should work
        stateMachine.onCommandSent("go north")
        assertEquals(RetryState.COMMAND_SENT, stateMachine.getState())
        assertEquals("go north", stateMachine.getOriginalCommand())
    }

    @Test
    fun `getStateDescription returns state name`() {
        assertEquals("IDLE", stateMachine.getStateDescription())

        stateMachine.onCommandSent("look")
        assertEquals("COMMAND_SENT", stateMachine.getStateDescription())
    }

    @Test
    fun `tracks original command through state transitions`() {
        val command = "take the magic sword"

        stateMachine.onCommandSent(command)
        assertEquals(command, stateMachine.getOriginalCommand())

        val error = ErrorInfo(ErrorType.UNKNOWN_NOUN, "error", "error")
        stateMachine.onParserError(error)
        assertEquals(command, stateMachine.getOriginalCommand())

        stateMachine.onRetrySent("take sword")
        assertEquals(command, stateMachine.getOriginalCommand())
    }
}
