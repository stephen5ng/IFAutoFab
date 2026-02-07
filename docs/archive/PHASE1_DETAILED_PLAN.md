# Phase 1: Baseline Wrapper & Vocabulary Extraction
## Detailed Implementation Plan

**Duration**: Weeks 1-2
**Status**: Planning
**Starting point**: Existing LLM rewriting in `terminal/src/main/kotlin/com/ifautofab/terminal/TerminalMain.kt`

---

## Overview

Phase 1 extends the existing terminal wrapper with parser failure detection, a single-retry mechanism, and Z-machine vocabulary extraction. All work targets the terminal module first (wrapping dfrotz), with Android integration deferred to Phase 4.

---

## Architecture

### Current Flow (TerminalMain.kt)

```
Player input → LLM rewrite (always) → dfrotz → output
```

### Phase 1 Target Flow

```
Player input → dfrotz → output
                          ↓
                   Parser failure detected?
                    ├─ No  → show output
                    └─ Yes → LLM rewrite → dfrotz retry
                                             ↓
                                      Retry also failed?
                                       ├─ No  → show rewritten output
                                       └─ Yes → show both errors
```

Key change: LLM is only invoked **after** a parser failure, not on every command.

### New Components

| Component | File | Purpose |
|-----------|------|---------|
| ParserFailureDetector | `terminal/src/main/kotlin/.../parser/ParserFailureDetector.kt` | Regex + heuristic error detection |
| ZMachineVocabularyExtractor | `terminal/src/main/kotlin/.../parser/ZMachineVocabularyExtractor.kt` | Extract dictionary from .z3-.z8 files |
| RetryLoop | Integrated in TerminalMain.kt | Single-retry with dual error display |

---

## Week 1: Parser Failure Detection

### 1.1 Parser Failure Detector

**File**: `terminal/src/main/kotlin/com/ifautofab/terminal/parser/ParserFailureDetector.kt`

Two-tier detection:

**Tier 1 — Regex catalog** for Infocom and Inform 6/7 games:

```kotlin
enum class FailureType {
    UNKNOWN_VERB,    // "I don't know the word 'grab'"
    UNKNOWN_NOUN,    // "You can't see any such thing"
    SYNTAX,          // "I only understood you as far as..."
    AMBIGUITY,       // "Which do you mean..." (pass-through, don't rewrite)
    GAME_REFUSAL,    // "You can't do that" (not a parser error)
}

// Infocom patterns
val INFOCOM_PATTERNS = mapOf(
    Regex("""I don't know the word ["']([^"']+)["']""", IGNORE_CASE) to UNKNOWN_VERB,
    Regex("""I don't understand ("[^"]*"|that sentence)""", IGNORE_CASE) to UNKNOWN_VERB,
    Regex("""You used the word ["']([^"']+)["'] in a way""", IGNORE_CASE) to UNKNOWN_VERB,
    Regex("""You can't see any such thing""", IGNORE_CASE) to UNKNOWN_NOUN,
    Regex("""I only understood you as far as""", IGNORE_CASE) to SYNTAX,
    Regex("""Which do you mean""", IGNORE_CASE) to AMBIGUITY,
    Regex("""You can't do that""", IGNORE_CASE) to GAME_REFUSAL,
)

// Inform 7 patterns
val INFORM7_PATTERNS = mapOf(
    Regex("""That's not a verb I recogni""", IGNORE_CASE) to UNKNOWN_VERB,
    Regex("""You can't see any such thing""", IGNORE_CASE) to UNKNOWN_NOUN,
    Regex("""I didn't understand that sentence""", IGNORE_CASE) to SYNTAX,
    Regex("""Did you mean""", IGNORE_CASE) to AMBIGUITY,
)
```

**Tier 2 — Catch-all heuristic** for unknown compilers:
- Response is short (<~80 characters)
- Doesn't contain room description markers (capitalized location names, "You can see" lists)
- Doesn't describe state changes ("Taken.", "Dropped.", "You go north.")

### 1.2 Integrate Detection into TerminalMain.kt

Modify the existing game loop to:
1. Send command to dfrotz
2. Capture output
3. Run through ParserFailureDetector
4. If rewritable failure detected → invoke existing LLM rewrite → send rewrite to dfrotz
5. If rewrite also fails → print both errors:
   ```
   > grab leaflet
   I don't know the word "grab".
   [Tried: "take leaflet" -> You can't see any such thing.]
   ```

### 1.3 Tests

**File**: `terminal/src/test/kotlin/com/ifautofab/terminal/parser/ParserFailureDetectorTest.kt`

Test cases:
- Each Infocom error pattern detected correctly
- Each Inform 7 error pattern detected correctly
- Catch-all heuristic fires on short non-descriptive responses
- Valid responses (room descriptions, "Taken.", directional movement) are NOT flagged
- Ambiguity and game refusals are detected but marked non-rewritable

---

## Week 2: Z-Machine Vocabulary Extraction

### 2.1 Vocabulary Extractor

**File**: `terminal/src/main/kotlin/com/ifautofab/terminal/parser/ZMachineVocabularyExtractor.kt`

Read the Z-machine dictionary table from the game file:

```kotlin
data class ZMachineVocabulary(
    val version: Int,            // Z-machine version (3-8)
    val words: Set<String>,      // All dictionary words
    val verbs: Set<String>,      // Subset identified as verbs
    val nouns: Set<String>,      // Subset identified as nouns
    val prepositions: Set<String> // Subset identified as prepositions
)
```

Z-machine dictionary format:
- **Header byte 0x08**: Dictionary table address
- **Word separators**: Listed at start of dictionary table
- **Entry count**: 2-byte word at offset after separators
- **Entry length**: 1 byte
- **Entries**: Encoded Z-characters, 4 bytes (v1-3) or 6 bytes (v4+)

Word classification heuristic:
- Words that appear as first word in sample commands → likely verbs
- Common IF prepositions (in, on, with, to, from, under, behind) → prepositions
- Everything else → nouns (conservative default)

### 2.2 Vocabulary Dump CLI

Add a `--dump-vocab` flag to the terminal CLI:

```bash
./terminal/build/install/terminal/bin/terminal --dump-vocab zork1.z3
```

Output: JSON file with extracted vocabulary, useful for debugging and prompt construction.

### 2.3 Tests

**File**: `terminal/src/test/kotlin/com/ifautofab/terminal/parser/ZMachineVocabularyExtractorTest.kt`

- Extract vocabulary from a known .z3 file (Zork I)
- Verify known words are present ("take", "drop", "mailbox", "leaflet")
- Verify word count is reasonable (Zork I has ~700 dictionary words)
- Handle corrupt/invalid files gracefully

---

## Deliverables

| Deliverable | Description |
|-------------|-------------|
| ParserFailureDetector | Two-tier detection (regex + heuristic) |
| ZMachineVocabularyExtractor | Dictionary extraction from .z3-.z8 |
| TerminalMain.kt updates | Single-retry loop with dual error display |
| `--dump-vocab` CLI flag | Vocabulary dump for debugging |
| Unit tests | Detection patterns + vocabulary extraction |
| Vocabulary dumps | JSON files for test game corpus |

---

## What This Enables for Phase 2

- Vocabulary data feeds into LLM prompt construction
- Vocabulary data feeds into soft output validation
- Parser failure detection triggers LLM rewrite (instead of rewriting every command)
- Retry mechanism provides the single-attempt constraint
