package com.ifautofab.parser.llm

/**
 * Configuration for cloud LLM services.
 *
 * Supports multiple LLM providers (OpenAI, Anthropic, Google)
 * with configurable model parameters.
 */
data class LLMConfig(
    val provider: LLMProvider,
    val apiKey: String,
    val model: String,
    val maxTokens: Int = 50,
    val temperature: Double = 0.3,  // Low temperature for consistent rewrites
    val timeoutMs: Long = 30000L
) {
    init {
        require(maxTokens > 0) { "maxTokens must be positive" }
        require(temperature in 0.0..2.0) { "temperature must be between 0 and 2" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        require(apiKey.isNotBlank()) { "apiKey cannot be blank" }
    }
}

/**
 * Supported LLM providers.
 */
enum class LLMProvider {
    /** OpenAI GPT models (GPT-4, GPT-3.5-turbo) */
    OPENAI,

    /** Anthropic Claude models (Claude 3 Opus, Sonnet, Haiku) */
    ANTHROPIC,

    /** Google Gemini models */
    GOOGLE,

    /** Groq (hosted Llama 3.1, Mixtral, Gemma - ultra-fast) */
    GROQ,

    /** Together AI (open source models) */
    TOGETHER
}

/**
 * Manages LLM configuration for the application.
 */
object LLMConfigManager {
    private var config: LLMConfig? = null

    /**
     * Initializes the LLM configuration.
     *
     * @param config The LLM configuration to use
     * @throws IllegalStateException if already initialized
     */
    fun initialize(config: LLMConfig) {
        if (this.config != null) {
            throw IllegalStateException("LLM already configured. Call shutdown() first.")
        }
        this.config = config
    }

    /**
     * Gets the current LLM configuration.
     *
     * @return The LLM configuration
     * @throws IllegalStateException if not configured
     */
    fun getConfig(): LLMConfig {
        return config ?: throw IllegalStateException("LLM not configured. Call initialize() first.")
    }

    /**
     * Checks if LLM is configured.
     */
    fun isConfigured(): Boolean = config != null

    /**
     * Shuts down and clears the LLM configuration.
     */
    fun shutdown() {
        config = null
    }

    /**
     * Gets the configured provider.
     */
    fun getProvider(): LLMProvider? = config?.provider

    /**
     * Gets the configured model name.
     */
    fun getModel(): String? = config?.model
}

/**
 * Pre-configured model settings for common LLM providers.
 */
object LLMModelPresets {
    /** OpenAI GPT-4 (recommended for accuracy) */
    val GPT4 = LLMConfig(
        provider = LLMProvider.OPENAI,
        apiKey = "",  // Set at runtime
        model = "gpt-4",
        maxTokens = 50,
        temperature = 0.3
    )

    /** OpenAI GPT-3.5-turbo (faster, less accurate) */
    val GPT35_TURBO = LLMConfig(
        provider = LLMProvider.OPENAI,
        apiKey = "",  // Set at runtime
        model = "gpt-3.5-turbo",
        maxTokens = 50,
        temperature = 0.3
    )

    /** Anthropic Claude 3 Opus (highest accuracy) */
    val CLAUDE_3_OPUS = LLMConfig(
        provider = LLMProvider.ANTHROPIC,
        apiKey = "",  // Set at runtime
        model = "claude-3-opus-20240229",
        maxTokens = 50,
        temperature = 0.3
    )

    /** Anthropic Claude 3 Haiku (fastest) */
    val CLAUDE_3_HAIKU = LLMConfig(
        provider = LLMProvider.ANTHROPIC,
        apiKey = "",  // Set at runtime
        model = "claude-3-haiku-20240307",
        maxTokens = 50,
        temperature = 0.3
    )

    /** Google Gemini 2.0 Flash Experimental (fastest, recommended) */
    val GEMINI_2_0_FLASH_EXP = LLMConfig(
        provider = LLMProvider.GOOGLE,
        apiKey = "",  // Set at runtime
        model = "gemini-2.0-flash-exp",
        maxTokens = 50,
        temperature = 0.3
    )

    /** Google Gemini 1.5 Pro (highest accuracy) */
    val GEMINI_1_5_PRO = LLMConfig(
        provider = LLMProvider.GOOGLE,
        apiKey = "",  // Set at runtime
        model = "gemini-1.5-pro-latest",
        maxTokens = 50,
        temperature = 0.3
    )

    /** Google Gemini 1.5 Flash (balanced) */
    val GEMINI_1_5_FLASH = LLMConfig(
        provider = LLMProvider.GOOGLE,
        apiKey = "",  // Set at runtime
        model = "gemini-1.5-flash-latest",
        maxTokens = 50,
        temperature = 0.3
    )

    /** Google Gemini 1.0 Pro (legacy, stable) */
    val GEMINI_PRO = LLMConfig(
        provider = LLMProvider.GOOGLE,
        apiKey = "",  // Set at runtime
        model = "gemini-pro",
        maxTokens = 50,
        temperature = 0.3
    )

    // ==================== Groq Models (Ultra-fast, Free Tier) ====================

    /** Groq Llama 3.1 70B (recommended for accuracy) */
    val GROQ_LLAMA_3_1_70B = LLMConfig(
        provider = LLMProvider.GROQ,
        apiKey = "",  // Set at runtime - get free key at https://console.groq.com/
        model = "llama-3.1-70b-versatile",
        maxTokens = 50,
        temperature = 0.3
    )

    /** Groq Llama 3.1 8B (faster, good for simple rewrites) */
    val GROQ_LLAMA_3_1_8B = LLMConfig(
        provider = LLMProvider.GROQ,
        apiKey = "",
        model = "llama-3.1-8b-instant",
        maxTokens = 50,
        temperature = 0.3
    )

    /** Groq Mixtral 8x7B (fast multilingual model) */
    val GROQ_MIXTRAL_8X7B = LLMConfig(
        provider = LLMProvider.GROQ,
        apiKey = "",
        model = "mixtral-8x7b-32768",
        maxTokens = 50,
        temperature = 0.3
    )

    /** Groq Gemma 2 9B (Google's open model) */
    val GROQ_GEMMA_2_9B = LLMConfig(
        provider = LLMProvider.GROQ,
        apiKey = "",
        model = "gemma2-9b-it",
        maxTokens = 50,
        temperature = 0.3
    )

    // ==================== Together AI Models ====================

    /** Together AI Llama 3.1 70B */
    val TOGETHER_LLAMA_3_1_70B = LLMConfig(
        provider = LLMProvider.TOGETHER,
        apiKey = "",  // Set at runtime - get free key at https://api.together.xyz/
        model = "meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo",
        maxTokens = 50,
        temperature = 0.3
    )
}
