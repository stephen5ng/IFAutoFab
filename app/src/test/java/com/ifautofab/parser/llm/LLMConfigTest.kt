package com.ifautofab.parser.llm

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for LLMConfig and LLMConfigManager.
 */
class LLMConfigTest : LLMTestBase() {

    @Before
    fun setup() {
        LLMConfigManager.shutdown()
    }

    @Test
    fun `creates valid OpenAI config`() {
        val config = LLMConfig(
            provider = LLMProvider.OPENAI,
            apiKey = "test-key",
            model = "gpt-4"
        )

        assertEquals(LLMProvider.OPENAI, config.provider)
        assertEquals("test-key", config.apiKey)
        assertEquals("gpt-4", config.model)
        assertEquals(50, config.maxTokens)
        assertEquals(0.3, config.temperature, 0.001)
    }

    @Test
    fun `creates valid Anthropic config`() {
        val config = LLMConfig(
            provider = LLMProvider.ANTHROPIC,
            apiKey = "test-key",
            model = "claude-3-opus-20240229"
        )

        assertEquals(LLMProvider.ANTHROPIC, config.provider)
        assertEquals("claude-3-opus-20240229", config.model)
    }

    @Test
    fun `creates valid Google config`() {
        val config = LLMConfig(
            provider = LLMProvider.GOOGLE,
            apiKey = "test-key",
            model = "gemini-pro"
        )

        assertEquals(LLMProvider.GOOGLE, config.provider)
        assertEquals("gemini-pro", config.model)
    }

    @Test
    fun `rejects blank API key`() {
        try {
            LLMConfig(
                provider = LLMProvider.OPENAI,
                apiKey = "   ",
                model = "gpt-4"
            )
            fail("Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("apiKey") ?: false)
        }
    }

    @Test
    fun `rejects invalid temperature`() {
        try {
            LLMConfig(
                provider = LLMProvider.OPENAI,
                apiKey = "test-key",
                model = "gpt-4",
                temperature = 3.0
            )
            fail("Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("temperature") ?: false)
        }
    }

    @Test
    fun `rejects invalid max tokens`() {
        try {
            LLMConfig(
                provider = LLMProvider.OPENAI,
                apiKey = "test-key",
                model = "gpt-4",
                maxTokens = 0
            )
            fail("Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("maxTokens") ?: false)
        }
    }

    @Test
    fun `rejects invalid timeout`() {
        try {
            LLMConfig(
                provider = LLMProvider.OPENAI,
                apiKey = "test-key",
                model = "gpt-4",
                timeoutMs = 0
            )
            fail("Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("timeoutMs") ?: false)
        }
    }

    @Test
    fun `config manager initializes config`() {
        val config = LLMConfig(
            provider = LLMProvider.OPENAI,
            apiKey = "test-key",
            model = "gpt-4"
        )

        LLMConfigManager.initialize(config)

        assertTrue(LLMConfigManager.isConfigured())
        assertEquals(config, LLMConfigManager.getConfig())
    }

    @Test
    fun `config manager throws when not configured`() {
        assertFalse(LLMConfigManager.isConfigured())

        try {
            LLMConfigManager.getConfig()
            fail("Should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("not configured") ?: false)
        }
    }

    @Test
    fun `config manager throws when already initialized`() {
        val config = LLMConfig(
            provider = LLMProvider.OPENAI,
            apiKey = "test-key",
            model = "gpt-4"
        )

        LLMConfigManager.initialize(config)

        try {
            LLMConfigManager.initialize(config)
            fail("Should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("already configured") ?: false)
        }
    }

    @Test
    fun `config manager shutdown clears config`() {
        val config = LLMConfig(
            provider = LLMProvider.OPENAI,
            apiKey = "test-key",
            model = "gpt-4"
        )

        LLMConfigManager.initialize(config)
        assertTrue(LLMConfigManager.isConfigured())

        LLMConfigManager.shutdown()
        assertFalse(LLMConfigManager.isConfigured())
    }

    @Test
    fun `config manager returns provider`() {
        val config = LLMConfig(
            provider = LLMProvider.ANTHROPIC,
            apiKey = "test-key",
            model = "claude-3-opus"
        )

        LLMConfigManager.initialize(config)

        assertEquals(LLMProvider.ANTHROPIC, LLMConfigManager.getProvider())
    }

    @Test
    fun `config manager returns model`() {
        val config = LLMConfig(
            provider = LLMProvider.OPENAI,
            apiKey = "test-key",
            model = "gpt-3.5-turbo"
        )

        LLMConfigManager.initialize(config)

        assertEquals("gpt-3.5-turbo", LLMConfigManager.getModel())
    }

    @Test
    fun `GPT4 preset has correct values`() {
        val preset = LLMModelPresets.GPT4.copy(apiKey = "test-key")

        assertEquals(LLMProvider.OPENAI, preset.provider)
        assertEquals("gpt-4", preset.model)
        assertEquals(50, preset.maxTokens)
        assertEquals(0.3, preset.temperature, 0.001)
    }

    @Test
    fun `CLAUDE_3_OPUS preset has correct values`() {
        val preset = LLMModelPresets.CLAUDE_3_OPUS.copy(apiKey = "test-key")

        assertEquals(LLMProvider.ANTHROPIC, preset.provider)
        assertEquals("claude-3-opus-20240229", preset.model)
        assertEquals(50, preset.maxTokens)
        assertEquals(0.3, preset.temperature, 0.001)
    }
}
