# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

IFAutoFab is an Android interactive fiction player for phones and Android Automotive OS. It runs 12+ text adventure interpreters (Z-Machine, Glulx, TADS, Hugo, Alan, etc.) via native JNI code, with output displayed through Car App Library templates or a phone UI.

## Build Commands

```bash
# Set Java home (required on macOS)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Build debug APK
./gradlew assembleDebug

# Clean build (after native code changes)
./gradlew clean assembleDebug

# Verify native libraries built
find app/build/intermediates/ndkLibs -name "*.so" | sort
```

## Deployment & Testing

```bash
# Install to connected device/emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch phone UI
adb shell am start -n com.ifautofab/.MainActivity

# Stream game output (real-time)
adb logcat -s GameOutput:D

# Send command without keyboard (bypasses autocomplete issues)
adb shell 'am broadcast -a com.ifautofab.DEBUG_INPUT -e command "open mailbox"'
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ UI Layer (com.ifautofab.*)                                      │
│ ├─ MainActivity (phone UI)                                      │
│ ├─ GameScreen, CommandScreen, VoiceInputScreen (Car App UI)    │
│ └─ TTSManager (text-to-speech)                                  │
├─────────────────────────────────────────────────────────────────┤
│ Engine Layer                                                     │
│ ├─ GLKGameEngine (lifecycle, input/output bridge)               │
│ └─ TextOutputInterceptor (thread-safe output collector)         │
├─────────────────────────────────────────────────────────────────┤
│ GLK Java Layer (com.luxlunae.glk.*)  [LOCKED - cannot rename]   │
│ ├─ GLKModel (interpreter state, streams, windows)              │
│ └─ GLKController (JNI bridge, terp initialization)             │
├─────────────────────────────────────────────────────────────────┤
│ Native C Layer (JNI via CMake)                                  │
│ ├─ GLK dispatcher (glk.c, glkstart.c)                          │
│ └─ 12+ interpreter .so files (bocfel, git, hugo, tads, etc.)   │
└─────────────────────────────────────────────────────────────────┘
```

**Thread model:**
- Main thread: UI (MainActivity/GameScreen)
- Worker thread: Native interpreter via JNI, blocks on `glk_select()` waiting for input
- TextOutputInterceptor bridges output from worker thread to UI listeners

## Critical Constraints

### Locked Package: `com.luxlunae.glk.*`

The `glkstart.c` file contains 100+ hardcoded JNI class references like:
```c
FindClass(env, "com/luxlunae/glk/model/GLKModel");
```

**This package structure cannot be renamed without modifying C code.** All new app code must use `com.ifautofab.*` (already done).

### Native Code Submodule

The C interpreter sources are in a git submodule at `app/src/main/jni/garglk/`. If missing:
```bash
git submodule update --init --recursive
```

## Key Files

| File | Purpose |
|------|---------|
| `app/src/main/java/com/ifautofab/GLKGameEngine.kt` | Interpreter lifecycle, input/output |
| `app/src/main/java/com/ifautofab/TextOutputInterceptor.kt` | Thread-safe output collection |
| `app/src/main/java/com/ifautofab/MainActivity.kt` | Phone UI |
| `app/src/main/java/com/ifautofab/GameScreen.kt` | Car App Library UI |
| `app/src/main/jni/CMakeLists.txt` | Native build config (14 .so targets) |
| `app/src/main/jni/glk/glkstart.c` | JNI lifecycle with hardcoded class names |

## Game Format to Interpreter Mapping

| Extensions | Interpreter | Format |
|------------|-------------|--------|
| .z3, .z4, .z5, .z6, .z8 | bocfel | zcode |
| .ulx, .gblorb | git | glulx |
| .t3 | tads | tads3 |
| .t2, .gam | tads | tads2 |
| .h30, .hex | hugo | hugo |
| .a3c | alan | alan |

## Terminal/Desktop Support

**Current status:** Implemented (Z-machine only), with LLM parser repair

**Build:** `./gradlew :terminal:installDist`
**Run:** `./terminal/build/install/terminal/bin/terminal game.z3`
**With LLM:** `./terminal/build/install/terminal/bin/terminal --llm game.z3`
**Prerequisite:** `brew install frotz` (provides dfrotz interpreter)

Key components:
- `TerminalMain.kt` — dfrotz wrapper, retry state machine, CLI options
- `LLMRewriter.kt` — Groq API integration, vocab-aware prompt, validation
- `ParserFailureDetector.kt` — Regex catalog (Infocom, Inform 6/7) + catch-all heuristic
- `VocabularyExtractor.kt` — Pure Kotlin Z-machine dictionary parser
- `RewriteLogger.kt` — JSONL logging of rewrite attempts

See: `terminal/README.md` for full details
See: `LLM_Parser_Repair_Project_Plan.md` for roadmap

### Auto-Mapper (Planned)

**Status:** Planning complete, implementation pending
**Scope:** Terminal-only MVP, Z-machine games

Automatic mapping system that detects room transitions and renders real-time ASCII maps:
- Memory polling to detect player location changes (no C modifications)
- Graph-based world model with 3D coordinates (x, y, level)
- Auto-render after every move with 4-letter room codes (e.g., `[KITC]`)
- JSON persistence for map state across sessions
- Future: Android graphical port

**Planned components:**
- `mapper/WorldGraph.kt` — Graph data structure
- `mapper/ZMachineMemoryReader.kt` — Object table access
- `mapper/AsciiMapRenderer.kt` — Terminal rendering
- `mapper/MapPersistence.kt` — JSON save/load

See: `z_machine_mapper_plan.md` for complete implementation plan

**Archived approach:** Branch `archive/terminal-jni-shared-architecture`

## Troubleshooting

**NDK build fails:** Clear cache and rebuild
```bash
rm -rf app/.cxx build/
./gradlew assembleDebug
```

**Submodule empty:** Initialize it
```bash
git submodule update --init --recursive
```

**Multi-word commands via adb shell input text don't work:** Use broadcast intent instead (keyboard autocomplete mangles spaces)
```bash
adb shell 'am broadcast -a com.ifautofab.DEBUG_INPUT -e command "go north"'
```
