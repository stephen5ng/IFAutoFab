package com.ifautofab.terminal

import java.io.File
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

// Shared state for rewriting
@Volatile private var lastCommand: String = ""

// ANSI Colors for terminal output
private const val ANSI_RESET = "\u001B[0m"
private const val ANSI_YELLOW = "\u001B[33m"
private const val ANSI_CYAN = "\u001B[36m"
private const val ANSI_GREEN = "\u001B[32m"
private const val ANSI_RED = "\u001B[31m"

/**
 * Rewrite state machine for tracking retry attempts.
 */
private enum class RewriteState {
    /** No retry in progress */
    IDLE,

    /** Waiting for game response to retry attempt */
    AWAITING_RETRY_RESULT
}

fun main(args: Array<String>) {
    val flagsWithValues = setOf("--save-dir")
    val positional = mutableListOf<String>()
    val flags = mutableSetOf<String>()
    var i = 0
    while (i < args.size) {
        when {
            args[i] in flagsWithValues -> { flags.add(args[i]); i += 2 }
            args[i].startsWith("--") -> { flags.add(args[i]); i++ }
            else -> { positional.add(args[i]); i++ }
        }
    }

    if (positional.isEmpty() || "--help" in flags) {
        println("Usage: terminal [options] <game-file.z3|z4|z5|z8>")
        println()
        println("Options:")
        println("  --llm           Enable LLM command rewriting (requires API key)")
        println("  --tts           Speak game output using macOS 'say' command")
        println("  --transcript    Save game transcript to ~/.ifautofab/transcripts/")
        println("  --save-dir DIR  Set working directory for save files")
        println("                  (default: ~/.ifautofab/saves/)")
        println("  --dump-vocab    Extract and print vocabulary from game file")
        println()
        println("Example:")
        println("  terminal --llm app/src/main/assets/games/zork1.z3")
        return
    }

    val gameFile = File(positional[0])
    if (!gameFile.exists()) {
        println("${ANSI_YELLOW}Error: File not found: ${gameFile.absolutePath}$ANSI_RESET")
        return
    }

    val ext = gameFile.extension.lowercase()
    if (ext !in listOf("z3", "z4", "z5", "z8")) {
        println("${ANSI_YELLOW}Error: Only Z-machine games (.z3, .z4, .z5, .z8) supported$ANSI_RESET")
        return
    }

    // Handle --dump-vocab flag
    if ("--dump-vocab" in flags) {
        val vocabulary = VocabularyExtractor.extract(gameFile)
        if (vocabulary != null) {
            println("${ANSI_CYAN}${vocabulary.getSummary()}$ANSI_RESET")
            println()
            println("${ANSI_GREEN}Verbs (${vocabulary.verbs.size}):$ANSI_RESET")
            vocabulary.verbs.sorted().forEach { println("  $it") }
            println()
            println("${ANSI_GREEN}Nouns (${vocabulary.nouns.size}):$ANSI_RESET")
            vocabulary.nouns.sorted().forEach { println("  $it") }
            println()
            println("${ANSI_GREEN}Adjectives (${vocabulary.adjectives.size}):$ANSI_RESET")
            vocabulary.adjectives.sorted().forEach { println("  $it") }
            println()
            println("${ANSI_GREEN}Prepositions (${vocabulary.prepositions.size}):$ANSI_RESET")
            vocabulary.prepositions.sorted().forEach { println("  $it") }
            if (vocabulary.misc.isNotEmpty()) {
                println()
                println("${ANSI_GREEN}Other (${vocabulary.misc.size}):$ANSI_RESET")
                vocabulary.misc.sorted().forEach { println("  $it") }
            }
        }
        return
    }

    // Setup LLM if requested
    val llmEnabled = "--llm" in flags
    var llmRewriter: LLMRewriter? = null
    var vocabulary: Vocabulary? = null
    var rewriteLogger: RewriteLogger? = null

    if (llmEnabled) {
        val apiKey = loadApiKey()
        if (apiKey == null) {
            println("${ANSI_YELLOW}Error: LLM enabled but no API key found.$ANSI_RESET")
            println("Checks: local.properties (groq.api.key) OR GROQ_API_KEY env var.")
            return
        }
        println("${ANSI_CYAN}LLM rewriting enabled (Groq llama-3.1-8b-instant)$ANSI_RESET")

        vocabulary = VocabularyExtractor.extract(gameFile)
        if (vocabulary != null) {
            println("${ANSI_CYAN}${vocabulary.getSummary()}$ANSI_RESET")
        }
        llmRewriter = LLMRewriter(apiKey)

        // Setup logger
        rewriteLogger = RewriteLogger(gameFile.nameWithoutExtension)
    }

    val dfrotzPath = findDfrotz()
    if (dfrotzPath == null) {
        println("${ANSI_YELLOW}Error: dfrotz not found. Install with: brew install frotz$ANSI_RESET")
        return
    }

    val ttsEnabled = "--tts" in flags
    val transcriptEnabled = "--transcript" in flags
    val saveDirArg = extractFlagValue(args, "--save-dir")
    val saveDir = File(saveDirArg ?: "${System.getProperty("user.home")}/.ifautofab/saves")
    saveDir.mkdirs()

    // Setup TTS
    val ttsQueue = if (ttsEnabled) TTSQueue() else null

    println("Starting ${gameFile.name}...")
    println("(Type 'quit' to exit game)\n")

    // If LLM is enabled, we FORCE interception mode
    val needsInterception = ttsEnabled || transcriptEnabled || llmEnabled

    val pb = ProcessBuilder(dfrotzPath, gameFile.absolutePath)
        .directory(saveDir)

    if (!needsInterception) {
        pb.inheritIO()
        val process = pb.start()
        process.waitFor()
        return
    }

    // Manual I/O Mode
    pb.redirectErrorStream(true)
    val process = pb.start()
    val transcript = if (transcriptEnabled) StringBuilder() else null
    val processWriter = OutputStreamWriter(process.outputStream)

    // STDIN Thread
    val stdinThread = Thread {
        try {
            val reader = System.`in`.bufferedReader()
            var line = reader.readLine()
            while (line != null) {
                lastCommand = line // Track for LLM

                processWriter.write(line)
                processWriter.write("\n")
                processWriter.flush()

                transcript?.appendLine("> $line")
                line = reader.readLine()
            }
        } catch (_: Exception) { }
    }.apply { isDaemon = true; start() }

    // STDOUT / Main Loop
    try {
        val buffer = StringBuilder()
        var charCode = process.inputStream.read()

        // Accumulate text since last prompt to detect errors
        val outputAccumulator = StringBuilder()

        // Rewrite state machine
        var rewriteState = RewriteState.IDLE
        var lastRewrite: String? = null
        var lastOriginalCommand: String? = null

        while (charCode != -1) {
            val c = charCode.toChar()
            print(c)

            outputAccumulator.append(c)
            buffer.append(c)

            // Prompt detection (ends with >)
            if (c == '>') {
                val output = outputAccumulator.toString()

                if (llmEnabled && llmRewriter != null && vocabulary != null && rewriteLogger != null) {
                    when (rewriteState) {
                        RewriteState.IDLE -> {
                            // Check for parser error
                            val failureInfo = ParserFailureDetector.detect(output)

                            if (failureInfo != null && failureInfo.isRewritable) {
                                // Attempt rewrite
                                val originalCmd = lastCommand
                                val rewrite = llmRewriter.rewrite(
                                    originalCmd,
                                    output,
                                    failureInfo.type,
                                    vocabulary
                                )

                                rewriteLogger.logRewriteAttempt(
                                    originalCmd,
                                    rewrite,
                                    failureInfo.type,
                                    output
                                )

                                if (rewrite != null) {
                                    // Print colorful rewrite notification
                                    println("\n\n  $ANSI_YELLOW[LLM Rewrite: \"$originalCmd\" -> \"$rewrite\"]$ANSI_RESET")
                                    ttsQueue?.add("Rewriting command to $rewrite")

                                    // Inject command output simulation
                                    println("> $rewrite")

                                    processWriter.write(rewrite)
                                    processWriter.write("\n")
                                    processWriter.flush()

                                    lastCommand = rewrite
                                    lastRewrite = rewrite
                                    lastOriginalCommand = originalCmd
                                    rewriteState = RewriteState.AWAITING_RETRY_RESULT
                                    transcript?.appendLine("[LLM Rewrite] > $rewrite")
                                }
                            }
                        }

                        RewriteState.AWAITING_RETRY_RESULT -> {
                            // Check if retry succeeded or failed
                            val failureInfo = ParserFailureDetector.detect(output)

                            if (failureInfo != null && failureInfo.isRewritable) {
                                // Retry also failed - show dual error display
                                if (lastOriginalCommand != null && lastRewrite != null) {
                                    println("\n  $ANSI_RED[Tried: \"$lastRewrite\" -> ${output.trim().take(100)}]$ANSI_RESET")

                                    rewriteLogger.logRetryResult(
                                        lastOriginalCommand,
                                        lastRewrite,
                                        success = false,
                                        output
                                    )
                                }
                            } else {
                                // Retry succeeded!
                                if (lastOriginalCommand != null && lastRewrite != null) {
                                    rewriteLogger.logRetryResult(
                                        lastOriginalCommand,
                                        lastRewrite,
                                        success = true,
                                        output
                                    )
                                }
                            }

                            // Reset state
                            rewriteState = RewriteState.IDLE
                            lastRewrite = null
                            lastOriginalCommand = null
                        }
                    }
                }

                // Reset accumulator after handling prompt
                outputAccumulator.clear()
            }

            // TTS and Transcript handling on lines
            if (c == '\n') {
                val line = buffer.toString().trim()
                if (line.isNotEmpty() && line != ">") {
                    transcript?.appendLine(line)
                    ttsQueue?.add(line)
                }
                buffer.clear()
            }

            charCode = process.inputStream.read()
        }
    } catch (_: Exception) { }

    val exitCode = process.waitFor()
    ttsQueue?.shutdown()

    // Flush rewrite log before exit
    rewriteLogger?.flush()

    if (transcript != null) {
        val transcriptDir = File("${System.getProperty("user.home")}/.ifautofab/transcripts")
        transcriptDir.mkdirs()
        try {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
            val name = gameFile.nameWithoutExtension
            val transcriptFile = File(transcriptDir, "${name}_$timestamp.txt")
            transcriptFile.writeText(transcript.toString())
            println("\n${ANSI_GREEN}Transcript saved: ${transcriptFile.absolutePath}$ANSI_RESET")
        } catch (_: Exception) {}
    }

    if (exitCode != 0) println("\nGame exited with code: $exitCode")
}

private fun loadApiKey(): String? {
    // 1. Check local.properties in current dir or project root
    val filesToCheck = listOf(File("local.properties"), File("../local.properties"), File("../../local.properties"))
    for (f in filesToCheck) {
        if (f.exists()) {
            val props = Properties()
            try {
                f.inputStream().use { props.load(it) }
                val key = props.getProperty("groq.api.key")
                if (!key.isNullOrBlank()) return key.replace("\"", "") // Remove quotes if present
            } catch (_: Exception) {}
        }
    }

    // 2. Env var
    return System.getenv("GROQ_API_KEY")
}

private fun findDfrotz(): String? {
    val candidates = listOf(
        "/opt/homebrew/bin/dfrotz",
        "/usr/local/bin/dfrotz",
        "/usr/bin/dfrotz"
    )
    for (path in candidates) {
        if (File(path).exists()) return path
    }
    return try {
        val proc = ProcessBuilder("which", "dfrotz")
            .redirectErrorStream(true)
            .start()
        val result = proc.inputStream.bufferedReader().readLine()?.trim()
        if (proc.waitFor() == 0 && result != null && File(result).exists()) result else null
    } catch (_: Exception) {
        null
    }
}

private fun extractFlagValue(args: Array<String>, flag: String): String? {
    val index = args.indexOf(flag)
    return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
}

class TTSQueue {
    private val queue = java.util.concurrent.LinkedBlockingQueue<String>()
    private val thread = Thread {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val text = queue.take()
                if (text == "POISON_PILL") break
                speak(text)
            }
        } catch (e: InterruptedException) {
            // Exit
        }
    }

    init {
        thread.isDaemon = true
        thread.start()
    }

    fun add(text: String) {
        // Simple heuristics to avoid speaking non-text UI elements
        if (text.isBlank()) return
        if (text.startsWith("Score:") && text.contains("Moves:")) return // Skip Status bar

        queue.offer(text)
    }

    fun shutdown() {
        queue.offer("POISON_PILL")
    }

    private fun speak(text: String) {
        try {
            // Use 'say' command on macOS, wait for it to finish so we don't talk over ourselves
            ProcessBuilder("say", text).start().waitFor()
        } catch (_: Exception) { }
    }
}
