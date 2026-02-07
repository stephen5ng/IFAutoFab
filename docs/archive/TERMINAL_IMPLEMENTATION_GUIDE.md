# Terminal Implementation Guide

**Goal:** Run Z-machine games (Zork, etc.) in a macOS/Linux terminal with ~30 lines of Kotlin.

**Time estimate:** 3 hours

**Skills needed:** Basic command line, Kotlin basics

**Background reading:** `TERMINAL_SIMPLE_PLAN.md` (the "why"), this doc is the "how"

---

## Quick Reference Checklist

- [ ] Step 1: Build bocfel standalone binary (30 min)
- [ ] Step 2: Create terminal module in Gradle (1 hour)
- [ ] Step 3: Test with included games (30 min)
- [ ] Step 4: Document and commit (30 min)

**Total:** ~3 hours to working implementation

---

## Prerequisites

```bash
# Verify you have these installed:
java -version          # Should be Java 11+
kotlinc -version       # Kotlin compiler
cc --version          # C compiler (clang on macOS, gcc on Linux)
```

If missing, install via:
- macOS: `brew install kotlin`
- Linux: `apt install kotlin openjdk-11-jdk`

---

## Step 1: Build Bocfel Standalone (30 minutes)

### 1.1 Navigate to bocfel source

From project root:
```bash
cd app/src/main/jni/garglk/terps/bocfel
ls -la
# Should see: *.c and *.h files (blorb.c, branch.c, etc.)
```

If directory is empty:
```bash
cd /path/to/IFAutoFab
git submodule update --init --recursive
```

### 1.2 Try simple compile

```bash
# From app/src/main/jni/garglk/terps/bocfel/
cc -DUNIX -o bocfel *.c -I../..
```

**If this succeeds:** Skip to 1.3

**If you get errors:**

**Error: "glk.h not found"**
```bash
# The -I../.. should find it, but try explicit path:
cc -DUNIX -o bocfel *.c -I../../glk
```

**Error: "undefined reference to `glk_*`"**
This means bocfel needs GLK library, can't run standalone. **STOP HERE** and notify Stephen - need to use Plan B (the archived JNI approach or use frotz instead).

**Error: Other missing headers**
```bash
# Build with minimal features:
cc -DUNIX -DZTERP_NO_BLORB -o bocfel *.c -I../..
```

### 1.3 Test the binary

```bash
# Still in bocfel directory
./bocfel ../../../../../assets/games/zork1.z3
```

**Expected output:**
```
ZORK I: The Great Underground Empire
Copyright (c) 1981, 1982, 1983 Infocom, Inc. All rights reserved.
ZORK is a registered trademark of Infocom, Inc.
Revision 88 / Serial number 840726

West of House
You are standing in an open field west of a white house...

>
```

**Try commands:**
```
> look
> open mailbox
> read leaflet
> quit
```

**If this works:** ✅ Proceed to Step 2

**If you get garbled text or crashes:** bocfel standalone might not be stable. Try building `frotz` instead:
```bash
cd ../../frotz
make
./dfrotz ../../../../../assets/games/zork1.z3
```

---

## Step 2: Create Terminal Module (1 hour)

### 2.1 Create directory structure

From project root:
```bash
mkdir -p terminal/src/main/kotlin/com/ifautofab/terminal
mkdir -p terminal/bin
```

### 2.2 Copy bocfel binary

```bash
cp app/src/main/jni/garglk/terps/bocfel/bocfel terminal/bin/
chmod +x terminal/bin/bocfel

# Verify it still works from new location:
cd terminal
./bin/bocfel ../app/src/main/assets/games/zork1.z3
# Should play the game
# Ctrl+C to exit
cd ..
```

### 2.3 Create build.gradle.kts

```bash
cat > terminal/build.gradle.kts << 'EOF'
plugins {
    kotlin("jvm") version "1.9.20"
    application
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.ifautofab.terminal.TerminalMainKt")
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(kotlin("stdlib"))
}
EOF
```

### 2.4 Create TerminalMain.kt

```bash
cat > terminal/src/main/kotlin/com/ifautofab/terminal/TerminalMain.kt << 'EOF'
package com.ifautofab.terminal

import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: terminal <game-file.z3|z5|z8>")
        println("\nExample:")
        println("  ./terminal app/src/main/assets/games/zork1.z3")
        return
    }

    val gameFile = File(args[0])
    if (!gameFile.exists()) {
        println("Error: File not found: ${gameFile.absolutePath}")
        return
    }

    // Validate Z-machine file
    val ext = gameFile.extension.lowercase()
    if (ext !in listOf("z3", "z4", "z5", "z8")) {
        println("Error: Only Z-machine games (.z3, .z4, .z5, .z8) supported")
        println("Got: $ext")
        return
    }

    // Get path to bocfel binary (relative to terminal/ directory)
    val terminalDir = File(System.getProperty("user.dir"))
    val bocfelPath = File(terminalDir, "bin/bocfel").absolutePath

    if (!File(bocfelPath).exists()) {
        println("Error: bocfel binary not found at: $bocfelPath")
        println("Run from terminal/ directory or update path")
        return
    }

    println("Starting ${gameFile.name}...")
    println("(Type 'quit' to exit game)\n")

    val process = ProcessBuilder(bocfelPath, gameFile.absolutePath)
        .inheritIO()  // Connect game's stdin/stdout to terminal
        .start()

    val exitCode = process.waitFor()

    if (exitCode != 0) {
        println("\nGame exited with code: $exitCode")
    }
}
EOF
```

### 2.5 Add terminal module to project

Edit `settings.gradle.kts` at project root:
```kotlin
// Add this line at the end:
include(":terminal")
```

### 2.6 Test the build

```bash
# From project root
./gradlew :terminal:installDist
```

**Expected output:**
```
BUILD SUCCESSFUL in 10s
```

**If you get "Project 'terminal' not found":**
- Check that `settings.gradle.kts` includes `include(":terminal")`
- Run `./gradlew projects` to see all modules

**If you get Kotlin version conflicts:**
- Update version in terminal/build.gradle.kts to match app module

---

## Step 3: Test It Works (30 minutes)

### 3.1 Run the installed app

```bash
# From project root
./terminal/build/install/terminal/bin/terminal app/src/main/assets/games/zork1.z3
```

**Expected:** Game starts, you can play

### 3.2 Test error handling

```bash
# No arguments
./terminal/build/install/terminal/bin/terminal
# Should show usage message

# File doesn't exist
./terminal/build/install/terminal/bin/terminal fake.z3
# Should show "File not found"

# Wrong file type
touch test.txt
./terminal/build/install/terminal/bin/terminal test.txt
# Should show "Only Z-machine games supported"
rm test.txt
```

### 3.3 Test with different Z-machine versions

The repo includes several test games:
```bash
# Z-machine v3 games:
./terminal/build/install/terminal/bin/terminal app/src/main/assets/games/zork1.z3
./terminal/build/install/terminal/bin/terminal app/src/main/assets/games/hhgg.z3
./terminal/build/install/terminal/bin/terminal app/src/main/assets/games/planetfall.z3

# Z-machine v5:
./terminal/build/install/terminal/bin/terminal app/src/main/assets/games/Tangle.z5

# Z-machine v8:
./terminal/build/install/terminal/bin/terminal app/src/main/assets/games/LostPig.z8
```

Test at least one from each version to verify compatibility.

---

## Step 4: Documentation & Handoff (30 minutes)

### 4.1 Create README

```bash
cat > terminal/README.md << 'EOF'
# Terminal Z-Machine Player

Simple command-line player for Z-machine interactive fiction games (Zork, etc.).

## Build

```bash
./gradlew :terminal:installDist
```

## Run

```bash
./terminal/build/install/terminal/bin/terminal path/to/game.z3
```

## Supported Formats

- .z3 (Z-machine version 3)
- .z4 (Z-machine version 4)
- .z5 (Z-machine version 5)
- .z8 (Z-machine version 8)

## How It Works

- Uses `bocfel` Z-machine interpreter in standalone mode
- Kotlin wrapper handles argument parsing and process management
- No JNI, no Android dependencies, pure JVM

## Architecture

```
┌─────────────────────────────────────┐
│ TerminalMain.kt (Kotlin/JVM)       │
│ - Validates input                   │
│ - Launches bocfel subprocess        │
└────────────┬────────────────────────┘
             │ ProcessBuilder
             ↓
┌─────────────────────────────────────┐
│ bocfel (native C binary)            │
│ - Z-machine interpreter             │
│ - Reads stdin, writes stdout        │
└─────────────────────────────────────┘
```

Total code: ~50 lines of Kotlin

## Future Enhancements

- Add Glulx support (use `git` interpreter)
- Capture output for TTS integration
- Command history (if terminal doesn't provide it)
- Transcript/save location management

See: TERMINAL_SIMPLE_PLAN.md
EOF
```

### 4.2 Update CLAUDE.md

Edit `CLAUDE.md`, find the "Terminal/Desktop Support" section and update status:

```markdown
## Terminal/Desktop Support

**Current status:** ✅ Implemented (Z-machine only)

**Build:** `./gradlew :terminal:installDist`
**Run:** `./terminal/build/install/terminal/bin/terminal game.z3`

See: `terminal/README.md`
```

### 4.3 Commit your work

```bash
git add terminal/ settings.gradle.kts CLAUDE.md
git status  # Review what's being committed

git commit -m "feat(terminal): add Z-machine command-line player

Implements TERMINAL_SIMPLE_PLAN.md Stage 1 & 2.

- Build bocfel standalone interpreter
- Create terminal module with 50-line Kotlin wrapper
- Uses ProcessBuilder (no JNI needed)
- Tested with Zork 1

Usage: ./terminal/build/install/terminal/bin/terminal game.z3

Co-Authored-By: [Your Name] <your-email>"
```

---

## Success Criteria ✅

You're done when:
- [ ] `./gradlew :terminal:installDist` builds successfully
- [ ] Can play Zork 1 from command line
- [ ] Error messages work (wrong file type, file not found, etc.)
- [ ] README.md documents how to build and run
- [ ] Code is committed to git
- [ ] CLAUDE.md updated to show terminal is implemented

---

## Troubleshooting

### "bocfel: command not found" when running

**Problem:** Binary path is wrong

**Solution:** The app looks for `bin/bocfel` relative to current directory. Either:
- Run from `terminal/` directory: `cd terminal && ./build/install/terminal/bin/terminal ../app/...`
- Or use absolute path in TerminalMain.kt

### "cannot execute binary file"

**Problem:** Built on different architecture

**Solution:** Rebuild bocfel on the target machine (macOS/Linux/etc.)

### Gradle "Kotlin version mismatch"

**Problem:** Terminal module uses different Kotlin version than app

**Solution:** Check app's Kotlin version:
```bash
grep kotlin app/build.gradle.kts
```
Update terminal/build.gradle.kts to match

### Android Studio doesn't see terminal module

**Problem:** IDE hasn't synced

**Solution:**
- File → Sync Project with Gradle Files
- Or restart Android Studio

---

## Contact

Questions? Issues? Ping Stephen or check:
- `TERMINAL_SIMPLE_PLAN.md` - Strategy & rationale
- `archive/terminal-jni-shared-architecture` branch - Complex alternative if this doesn't work
