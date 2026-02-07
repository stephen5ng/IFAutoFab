# Terminal Support - Simple Implementation Plan

**Philosophy:** Ship working features with minimal code. Extract abstractions only when needed for a third use case.

**Archived alternative:** See branch `archive/terminal-jni-shared-architecture` for reference implementation with JNI bridge + shared module.

---

## Stage 1: Verify Standalone Interpreters Work (1-2 hours)

**Goal:** Prove we can run games without JNI/GLK by using standalone interpreter binaries.

### Step 1.1: Build bocfel standalone
```bash
cd app/src/main/jni/garglk/terps/bocfel
make clean
# Build without GLK (standalone mode)
cc -DUNIX -o bocfel *.c -I../..
```

### Step 1.2: Test it
```bash
./bocfel ~/programming/IFAutoFab/app/src/main/assets/games/zork1.z3
# Should see: "ZORK I: The Great Underground Empire"
# Commands: look, open mailbox, north, etc.
```

**Deliverable:** Proof that bocfel standalone works without any Kotlin/JNI code.

**Note:** If you later need Glulx support (.ulx files), build `git` interpreter similarly. Start with just Z-machine.

**If standalone mode doesn't work or has issues:** Re-evaluate whether ProcessBuilder approach is viable. May need minimal GLK layer.

---

## Stage 2: Minimal Kotlin Wrapper (2-3 hours)

**Goal:** Run games from Kotlin using `ProcessBuilder`. Add basic conveniences.

### Directory structure
```
terminal/
├── build.gradle.kts           # Simple Kotlin/JVM project
├── bin/
│   └── bocfel                 # Compiled Z-machine interpreter
└── src/main/kotlin/
    └── TerminalMain.kt        # ~30 lines total
```

### Implementation
```kotlin
// terminal/src/main/kotlin/TerminalMain.kt
package com.ifautofab.terminal

import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: terminal <game-file.z3|z5|z8>")
        return
    }

    val gameFile = File(args[0])
    if (!gameFile.exists()) {
        println("Error: File not found: ${gameFile.absolutePath}")
        return
    }

    // Simple validation
    val ext = gameFile.extension.lowercase()
    if (ext !in listOf("z3", "z4", "z5", "z8")) {
        println("Error: Only Z-machine games (.z3, .z4, .z5, .z8) supported")
        return
    }

    val process = ProcessBuilder("bin/bocfel", gameFile.absolutePath)
        .inheritIO()  // Connect stdin/stdout directly
        .start()

    val exitCode = process.waitFor()
    println("\nGame exited with code: $exitCode")
}
```

### Build setup
```kotlin
// terminal/build.gradle.kts
plugins {
    kotlin("jvm") version "1.9.20"
    application
}

application {
    mainClass.set("com.ifautofab.terminal.TerminalMainKt")
}

dependencies {
    implementation(kotlin("stdlib"))
}
```

### Usage
```bash
./gradlew :terminal:installDist
./terminal/build/install/terminal/bin/terminal path/to/zork1.z3
```

**Deliverable:** Working Z-machine player in ~30 lines of Kotlin. No JNI, no GLK, no shared module.

**Future:** If you need Glulx (.ulx) support later, add `git` binary and a simple format check. Don't add it until you need it.

**Test:**
- Zork 1 plays correctly
- Can send commands
- Quit/restart works

---

## Stage 3: Add Quality-of-Life Features (as needed)

Only add these if users actually request them:

### 3.1: Save/transcript location control
```kotlin
val process = ProcessBuilder(binaryPath, gameFile.absolutePath)
    .directory(File(System.getProperty("user.home"), ".ifautofab/saves"))
    .inheritIO()
    .start()
```

### 3.2: Command history (if readline isn't enough)
Most terminal emulators already have command history via up/down arrows. Only add if specifically needed.

### 3.3: Output capture for logging
```kotlin
val output = StringBuilder()
process.inputStream.bufferedReader().forEachLine { line ->
    println(line)  // Still show to user
    output.appendLine(line)  // Also capture
}
// Save transcript on exit
File("game-log.txt").writeText(output.toString())
```

### 3.4: Basic TTS (macOS only)
```kotlin
fun speak(text: String) {
    Runtime.getRuntime().exec(arrayOf("say", text))
}

process.inputStream.bufferedReader().forEachLine { line ->
    println(line)
    if (shouldSpeak(line)) speak(line)
}
```

Uses macOS built-in TTS via `say` command. Zero dependencies.

---

## Stage 4: When to Extract Shared Code

**Don't extract until:**
- You have 3+ implementations (Android app, terminal, AND web/iOS/Windows)
- You see clear duplication patterns
- The abstraction is obvious from real usage

**What NOT to share prematurely:**
- Game engine interfaces (Android and terminal have different threading models)
- Output listeners (different UI update mechanisms)
- Input handling (stdio vs Android EditText vs Car App SearchTemplate)

**What MIGHT be worth sharing later:**
- Copyright/boilerplate filters (same text patterns)
- Save file path management (both platforms need it)
- Game format detection (if supporting multiple formats)

But wait until you have concrete examples of duplication, not theoretical ones.

---

## Comparison to Archived Approach

| Aspect | Archived (JNI + Shared) | Simple (ProcessBuilder) |
|--------|------------------------|------------------------|
| **Lines of code** | 3,143 | ~30 (100x less) |
| **Build complexity** | CMake + JNI + Gradle | Just Gradle |
| **Native debugging** | lldb + gdb | None needed |
| **Memory safety** | Manual (JNI refs, GC) | Automatic (Kotlin only) |
| **Crashes** | Native segfaults | Process crashes, app survives |
| **Time to working** | Days (building GLK bridge) | Hours (thin wrapper) |
| **Maintenance burden** | High (C + Kotlin + interfaces) | Low (Kotlin only) |
| **Premature abstractions** | Shared module + multi-format support | Single interpreter, Z-machine only |

---

## Decision Points

### When Simple Approach Might Not Work

1. **If bocfel standalone mode doesn't work**
   - Test first (Stage 1) before building anything
   - Solution: Minimal GLK wrapper or use pre-built frotz/fizmo binaries

2. **If you need real-time output interception**
   - ProcessBuilder with `inheritIO()` pipes directly to terminal
   - Can't insert TTS/filtering between interpreter and display
   - Solution: Use `process.inputStream.bufferedReader()` instead

3. **If you want GUI, not terminal**
   - Standalone binaries are text-only
   - Solution: Build Compose Desktop app that reuses Android GLK code with platform stubs

### When to Revisit Archived Approach

If you discover:
- Standalone mode is too limited
- Need deep integration with interpreter internals
- Want to build interpreters into app (no external binaries)
- Plan to port to platforms without subprocess support (web/iOS)

Then the JNI bridge approach becomes necessary. But validate the need first.

---

## Next Steps

1. **Test Stage 1** (verify standalone works) - 1 hour
2. **If successful:** Implement Stage 2 (Kotlin wrapper) - 2 hours
3. **Ship it** and wait for user feedback
4. **Add Stage 3 features** only when users request them
5. **Extract shared code** only when building 3rd platform

Total time to working Z-machine player: **3 hours** instead of days/weeks.

Add other interpreters (Glulx, TADS, Hugo) only if you actually need them.

---

## Notes

- Archived branch preserves all JNI/shared work for reference
- Can always return to complex approach if simple one proves insufficient
- Goal: Learn what's actually needed through usage, not speculation
- Follow YAGNI: "You Aren't Gonna Need It"
