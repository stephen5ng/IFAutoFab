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

- Uses `dfrotz` (dumb frotz) Z-machine interpreter
- Kotlin wrapper handles argument parsing and process management
- No JNI, no Android dependencies, pure JVM

## Prerequisites

Install frotz (provides dfrotz):

```bash
brew install frotz    # macOS
apt install frotz     # Linux
```

## Architecture

```
+-------------------------------------+
| TerminalMain.kt (Kotlin/JVM)        |
| - Validates input                    |
| - Finds dfrotz on PATH              |
| - Launches dfrotz subprocess         |
+----------------+--------------------+
                 | ProcessBuilder
                 v
+-------------------------------------+
| dfrotz (native C binary)            |
| - Z-machine interpreter             |
| - Reads stdin, writes stdout         |
+-------------------------------------+
```

Total code: ~50 lines of Kotlin

## Future Enhancements

- Add Glulx support (use `git` interpreter)
- Capture output for TTS integration
- Command history (if terminal doesn't provide it)
- Transcript/save location management

See: TERMINAL_SIMPLE_PLAN.md
