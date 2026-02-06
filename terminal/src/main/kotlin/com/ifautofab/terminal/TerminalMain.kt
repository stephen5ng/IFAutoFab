package com.ifautofab.terminal

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
        println("  --tts           Speak game output using macOS 'say' command")
        println("  --transcript    Save game transcript to ~/.ifautofab/transcripts/")
        println("  --save-dir DIR  Set working directory for save files")
        println("                  (default: ~/.ifautofab/saves/)")
        println()
        println("Example:")
        println("  terminal app/src/main/assets/games/zork1.z3")
        println("  terminal --tts --transcript app/src/main/assets/games/zork1.z3")
        return
    }

    val gameFile = File(positional[0])
    if (!gameFile.exists()) {
        println("Error: File not found: ${gameFile.absolutePath}")
        return
    }

    val ext = gameFile.extension.lowercase()
    if (ext !in listOf("z3", "z4", "z5", "z8")) {
        println("Error: Only Z-machine games (.z3, .z4, .z5, .z8) supported")
        println("Got: .$ext")
        return
    }

    val dfrotzPath = findDfrotz()
    if (dfrotzPath == null) {
        println("Error: dfrotz not found. Install with: brew install frotz")
        return
    }

    val ttsEnabled = "--tts" in flags
    val transcriptEnabled = "--transcript" in flags
    val saveDirArg = extractFlagValue(args, "--save-dir")

    val saveDir = File(saveDirArg ?: "${System.getProperty("user.home")}/.ifautofab/saves")
    saveDir.mkdirs()

    println("Starting ${gameFile.name}...")
    if (ttsEnabled) println("TTS enabled (macOS 'say')")
    if (transcriptEnabled) println("Transcript enabled")
    println("Save directory: ${saveDir.absolutePath}")
    println("(Type 'quit' to exit game)\n")

    val needsInterception = ttsEnabled || transcriptEnabled

    val pb = ProcessBuilder(dfrotzPath, gameFile.absolutePath)
        .directory(saveDir)

    if (!needsInterception) {
        pb.inheritIO()
        val process = pb.start()
        val exitCode = process.waitFor()
        if (exitCode != 0) println("\nGame exited with code: $exitCode")
        return
    }

    // Manual I/O mode for transcript/TTS
    pb.redirectErrorStream(true)
    val process = pb.start()
    val transcript = if (transcriptEnabled) StringBuilder() else null

    // Forward stdin to process line by line, flushing after each
    val stdinThread = Thread {
        try {
            val reader = System.`in`.bufferedReader()
            val writer = process.outputStream.bufferedWriter()
            var line = reader.readLine()
            while (line != null) {
                writer.write(line)
                writer.newLine()
                writer.flush()
                line = reader.readLine()
            }
            writer.close()
        } catch (_: Exception) {
            // Process ended
        }
    }.apply { isDaemon = true; start() }

    // Read output, print it, optionally capture/speak
    process.inputStream.bufferedReader().forEachLine { line ->
        println(line)
        transcript?.appendLine(line)
        if (ttsEnabled && line.isNotBlank()) speak(line)
    }

    val exitCode = process.waitFor()

    if (transcript != null) {
        val transcriptDir = File("${System.getProperty("user.home")}/.ifautofab/transcripts")
        transcriptDir.mkdirs()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val name = gameFile.nameWithoutExtension
        val transcriptFile = File(transcriptDir, "${name}_$timestamp.txt")
        transcriptFile.writeText(transcript.toString())
        println("\nTranscript saved: ${transcriptFile.absolutePath}")
    }

    if (exitCode != 0) println("\nGame exited with code: $exitCode")
}

private fun speak(text: String) {
    try {
        ProcessBuilder("say", text).start()
    } catch (_: Exception) {
        // say command not available, silently skip
    }
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
