package com.ifautofab.terminal

import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: terminal <game-file.z3|z4|z5|z8>")
        println("\nExample:")
        println("  terminal app/src/main/assets/games/zork1.z3")
        return
    }

    val gameFile = File(args[0])
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

    println("Starting ${gameFile.name}...")
    println("(Type 'quit' to exit game)\n")

    val process = ProcessBuilder(dfrotzPath, gameFile.absolutePath)
        .inheritIO()
        .start()

    val exitCode = process.waitFor()

    if (exitCode != 0) {
        println("\nGame exited with code: $exitCode")
    }
}

private fun findDfrotz(): String? {
    // Check common locations
    val candidates = listOf(
        "/opt/homebrew/bin/dfrotz",
        "/usr/local/bin/dfrotz",
        "/usr/bin/dfrotz"
    )
    for (path in candidates) {
        if (File(path).exists()) return path
    }
    // Try PATH via which
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
