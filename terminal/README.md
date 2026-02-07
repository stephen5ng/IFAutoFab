# Terminal Z-Machine Player

Command-line player for Z-machine interactive fiction games (Zork, etc.) with optional LLM-assisted parser repair.

## Build

```bash
./gradlew :terminal:installDist
```

## Run

```bash
./terminal/build/install/terminal/bin/terminal [options] path/to/game.z3
```

## Options

```
--llm           Enable LLM parser repair (rewrites failed commands via Groq API)
--tts           Speak game output using macOS 'say' command
--transcript    Save game transcript to ~/.ifautofab/transcripts/
--save-dir DIR  Set working directory for save files (default: ~/.ifautofab/saves/)
--dump-vocab    Dump extracted vocabulary from game file and exit
```

## Examples

```bash
# Basic play
./terminal/build/install/terminal/bin/terminal app/src/main/assets/games/zork1.z3

# With LLM parser repair
./terminal/build/install/terminal/bin/terminal --llm app/src/main/assets/games/zork1.z3

# With TTS and transcript
./terminal/build/install/terminal/bin/terminal --tts --transcript app/src/main/assets/games/zork1.z3

# Dump game vocabulary
./terminal/build/install/terminal/bin/terminal --dump-vocab app/src/main/assets/games/zork1.z3
```

## LLM Parser Repair (`--llm`)

When enabled, the system detects parser failures in game output and attempts to rewrite the failed command using an LLM. Only one retry is attempted per command.

**How it works:**
1. Player types a command (e.g., "grab leaflet")
2. Game responds with a parser error ("I don't know the word 'grab'")
3. Parser failure detector classifies the error (unknown verb, unknown noun, syntax, etc.)
4. LLM rewrites the command using the game's vocabulary ("take leaflet")
5. Rewritten command is sent to the game automatically
6. If the rewrite also fails, both errors are shown

**Failure detection** uses a two-tier approach:
- Regex catalog for known compiler families (Infocom, Inform 6, Inform 7)
- Catch-all heuristic for unknown compilers (short responses without room/object markers)

**Vocabulary validation** extracts the game's dictionary from the story file and uses it to constrain LLM output (hard reject unknown verbs, soft allow unknown nouns).

**Rewrite logging** saves all attempts to `~/.ifautofab/rewrite_logs/` in JSONL format for analysis.

**API key**: Set `GROQ_API_KEY` environment variable, or add `groq.api.key=...` to `local.properties`.

## Supported Formats

- .z3 (Z-machine version 3)
- .z4 (Z-machine version 4)
- .z5 (Z-machine version 5)
- .z8 (Z-machine version 8)

## Prerequisites

Install frotz (provides dfrotz):

```bash
brew install frotz    # macOS
apt install frotz     # Linux
```

## Architecture

```
+----------------------------------------------+
| TerminalMain.kt (Kotlin/JVM)                 |
| - Validates input, finds dfrotz on PATH      |
| - Launches dfrotz subprocess                 |
| - Retry state machine (IDLE/AWAITING_RETRY)  |
+----------------------------------------------+
        |                     ^
        | ProcessBuilder      | stdout capture
        v                     |
+----------------------------------------------+
| dfrotz (native C binary)                     |
| - Z-machine interpreter                      |
| - Reads stdin, writes stdout                 |
+----------------------------------------------+

When --llm is enabled:

  Game output
      |
      v
+----------------------------------------------+
| ParserFailureDetector.kt                     |
| - Regex catalog (Infocom, Inform 6/7)        |
| - Catch-all heuristic for unknown compilers  |
| - Classifies: UNKNOWN_VERB, UNKNOWN_NOUN,    |
|   SYNTAX, AMBIGUITY, GAME_REFUSAL, CATCH_ALL |
+----------------------------------------------+
      |
      v (if rewritable failure detected)
+----------------------------------------------+
| LLMRewriter.kt                               |
| - Groq API (llama-3.1-8b-instant)            |
| - Vocabulary-aware prompt construction        |
| - Strict rewrite-only system prompt           |
| - Soft validation (verbs hard, nouns soft)    |
+----------------------------------------------+
      |
      v
+----------------------------------------------+
| VocabularyExtractor.kt                        |
| - Pure Kotlin Z-machine dictionary parser     |
| - Extracts verbs, nouns, prepositions         |
| - Supports Z3-Z8 game files                   |
+----------------------------------------------+
      |
      v
+----------------------------------------------+
| RewriteLogger.kt                              |
| - JSONL logging to ~/.ifautofab/rewrite_logs/ |
| - Logs: rewrite_attempt, retry_result         |
+----------------------------------------------+
```

## Source Files

| File | Lines | Purpose |
|------|-------|---------|
| `TerminalMain.kt` | 401 | dfrotz wrapper, retry state machine, CLI options |
| `LLMRewriter.kt` | 320 | Groq API integration, prompt construction, validation |
| `ParserFailureDetector.kt` | 330 | Regex catalog + catch-all heuristic |
| `VocabularyExtractor.kt` | 273 | Z-machine dictionary parser |
| `RewriteLogger.kt` | 170 | JSONL event logging |

## Project Plan

See: `LLM_Parser_Repair_Project_Plan.md` for the full roadmap including compiler fingerprinting, user playtesting, local model evaluation, and Android integration.
