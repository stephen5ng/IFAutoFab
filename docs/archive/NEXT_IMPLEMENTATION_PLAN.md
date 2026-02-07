# Plan: Phase 1-2 Implementation — Parser Detection, Vocab Fixes, Retry Loop, Prompt, Validation, Logging

## Context

The terminal module has a working LLM rewrite pipeline (`TerminalMain.kt` → `LLMRewriter.kt` → Groq API) but it rewrites **every** command instead of only on parser failures, has no retry tracking (could loop infinitely), uses only 9 Infocom error patterns, has Z-string decoding bugs in `VocabularyExtractor.kt`, no output validation, and no logging. This chunk of work completes Phase 1 tasks #2-5 and Phase 2 tasks #6-8 to make the system actually work correctly.

## Files to Modify/Create

| File | Action | What |
|------|--------|------|
| `terminal/build.gradle.kts` | Modify | Add JUnit test dependency |
| `terminal/.../VocabularyExtractor.kt` | Modify | Fix Z-string decoding, fix byte ordering, add `getAllWords()`/`containsWord()` |
| `terminal/.../ParserFailureDetector.kt` | **New** | FailureType enum, regex catalog (Infocom + Inform 6 + Inform 7), catch-all heuristic |
| `terminal/.../LLMRewriter.kt` | Modify | System/user prompt split, error-type-aware prompt, JSON escaping fix, soft vocab validation, remove `hasError()` |
| `terminal/.../RewriteLogger.kt` | **New** | JSONL logging to `~/.ifautofab/rewrite_logs/` |
| `terminal/.../TerminalMain.kt` | Modify | Retry state machine, dual error display, `--dump-vocab` flag, logger wiring |
| `terminal/src/test/.../ParserFailureDetectorTest.kt` | **New** | Error pattern tests for all 3 parser families |
| `terminal/src/test/.../VocabularyExtractorTest.kt` | **New** | Extraction tests against real game files |

## Implementation Order

### Step 1: `terminal/build.gradle.kts` — Add test dependency
Add `testImplementation("junit:junit:4.13.2")`.

### Step 2: `VocabularyExtractor.kt` — Fix bugs + add helpers
**Bug 1 — Z-string decoding (`zsciiToChar`):** Current code maps ZSCII codes 1-6 to space and 65-90 to lowercase. This is wrong. Z-machine dictionary entries use 5-bit Z-characters where codes 6-31 map to a-z. Fix:
```kotlin
private fun zCharToChar(code: Int): Char = when (code) {
    0 -> '\u0000'           // padding
    in 1..5 -> '\u0000'    // shift/abbreviation (skip in dictionary)
    in 6..31 -> ('a' + (code - 6))  // a=6, b=7, ..., z=31
    else -> '?'
}
```

**Bug 2 — Byte ordering:** Z-machine dictionary entries have Z-encoded text FIRST, then data bytes. Current code reads 4 bytes as "flags" then rest as "word" — this is backwards. Fix: read Z-string bytes first (4 bytes for V3, 6 bytes for V4+), then data bytes.

**Add helpers:** `getAllWords(): Set<String>` and `containsWord(word: String): Boolean` on `Vocabulary`.

**Add `--dump-vocab` flag** to TerminalMain.kt argument parsing — prints vocabulary and exits.

### Step 3: `VocabularyExtractorTest.kt` — Verify against all 5 games
Test against game files at `../../app/src/main/assets/games/`. Verify known words exist in zork1.z3, LostPig.z8, Tangle.z5.

### Step 4: `ParserFailureDetector.kt` — New file
- `FailureType` enum: `UNKNOWN_VERB`, `UNKNOWN_NOUN`, `SYNTAX`, `AMBIGUITY`, `GAME_REFUSAL`, `CATCH_ALL`
- `FailureInfo` data class with `type`, `matchedText`, `isRewritable`
- Regex catalog covering all 3 parser families:
  - **Infocom:** "I don't know the word", "I don't understand that sentence", "You can't see any such thing", etc.
  - **Inform 6:** "That's not a verb I recognise", "I didn't understand that sentence"
  - **Inform 7:** Same as Inform 6 with minor variations
  - **Game refusals (non-rewritable):** "You can't do that", "It's too dark", "You don't have"
- Catch-all heuristic: short output (<80 chars), single line, no room description markers → likely error
- Reference: `app/.../parser/ParserWrapper.kt` error patterns (lines 241-283)

### Step 5: `ParserFailureDetectorTest.kt` — Test all patterns
Tests for each error pattern family, catch-all heuristic, non-rewritable game refusals, and normal output that should NOT trigger.

### Step 6: `LLMRewriter.kt` — Prompt redesign + validation
- **Delete `hasError()`** — detection moves to `ParserFailureDetector`
- **Add system prompt constant** — strict rewrite-only behavior, NO_VALID_REWRITE instruction
- **Change `rewrite()` signature** to accept `FailureType` — prompt becomes error-type-aware, sends full vocabulary (not just first 50), sends relevant vocab subset based on error type
- **Fix `buildJson()`** — proper JSON escaping (backslashes, carriage returns, tabs), system+user message split
- **Add `cleanRewrite()`** — strip quotes, prefixes, punctuation, normalize whitespace (ported from `app/.../llm/OutputParser.kt`)
- **Add `validateRewrite()`** — hard reject unknown verbs, soft allow unknown nouns, reject >6 words
- Reference: `app/.../parser/llm/PromptTemplate.kt` for system prompt, `app/.../parser/llm/OutputParser.kt` for cleaning

### Step 7: `RewriteLogger.kt` — New file
JSONL logging to `~/.ifautofab/rewrite_logs/{game}_{date}.jsonl`. Two log events:
- `rewrite_attempt`: original command, rewrite, failure type, game output
- `retry_result`: original, rewrite, success/failure, retry output

No external JSON library — simple string building with proper escaping.

### Step 8: `TerminalMain.kt` — Retry loop integration
Add state tracking:
```kotlin
enum class RewriteState { IDLE, AWAITING_RETRY_RESULT }
```

Restructure the prompt-detection block (`>` character):
- **IDLE + error detected:** Call `ParserFailureDetector.detect()`, if rewritable → call `LLMRewriter.rewrite()` with failure type → inject into dfrotz → set state to `AWAITING_RETRY_RESULT`
- **AWAITING_RETRY_RESULT + error detected:** Show dual error display and reset
- **AWAITING_RETRY_RESULT + no error:** Retry succeeded, log success, reset

Dual error display format:
```
> grab leaflet
I don't know the word "grab".
  [LLM: "grab leaflet" -> "take leaflet"]
> take leaflet
  [Tried: "take leaflet" -> You can't see any such thing.]
```

Wire up `RewriteLogger` creation when `--llm` is enabled.

## Verification

1. **Build:** `./gradlew :terminal:installDist`
2. **Run tests:** `./gradlew :terminal:test`
3. **Dump vocab:** `./terminal/build/install/terminal/bin/terminal --dump-vocab app/src/main/assets/games/zork1.z3`
4. **Play test:** `./terminal/build/install/terminal/bin/terminal --llm app/src/main/assets/games/zork1.z3` — type a bad command like "grab mailbox", verify it detects the error, rewrites, and shows the dual error format if the rewrite also fails
5. **Check logs:** `cat ~/.ifautofab/rewrite_logs/zork1_*.jsonl`

## Scope
~710 lines across 8 files (5 source, 2 test, 1 build config).
