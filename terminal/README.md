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

## Options

```
--tts           Speak game output using macOS 'say' command
--transcript    Save game transcript to ~/.ifautofab/transcripts/
--save-dir DIR  Set working directory for save files (default: ~/.ifautofab/saves/)
```

Examples:
```bash
# Basic play
./terminal/build/install/terminal/bin/terminal app/src/main/assets/games/zork1.z3

# With TTS and transcript
./terminal/build/install/terminal/bin/terminal --tts --transcript app/src/main/assets/games/zork1.z3

# Custom save directory
./terminal/build/install/terminal/bin/terminal --save-dir ~/zork-saves app/src/main/assets/games/zork1.z3
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
- When `--tts` or `--transcript` is used, output is intercepted via manual I/O piping; otherwise uses simple `inheritIO()`

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
| - Optional: TTS, transcript capture  |
+----------------+--------------------+
                 | ProcessBuilder
                 v
+-------------------------------------+
| dfrotz (native C binary)            |
| - Z-machine interpreter             |
| - Reads stdin, writes stdout         |
+-------------------------------------+
```

## Future Enhancements

- Add Glulx support (use `git` interpreter)
- Command history (if terminal doesn't provide it)

See: TERMINAL_SIMPLE_PLAN.md
