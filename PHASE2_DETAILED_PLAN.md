# Phase 2: Cloud LLM Prompting & Validation
## Detailed Implementation Plan

**Duration**: Weeks 3-4
**Status**: Planning
**Dependencies**: Phase 1 (parser failure detection, vocabulary extraction)

---

## Overview

Phase 2 replaces the "always rewrite" approach in TerminalMain.kt with a targeted cloud LLM rewrite that only fires on detected parser failures, validated against the game's extracted vocabulary. The existing Groq integration serves as the starting point.

---

## Architecture

### Phase 1 → Phase 2 Transition

```
Phase 1: Parser failure detected → (no LLM yet, just logs)
Phase 2: Parser failure detected → Cloud LLM rewrite → Vocabulary validation → Retry
```

### New Components

| Component | File | Purpose |
|-----------|------|---------|
| PromptBuilder | `terminal/.../parser/PromptBuilder.kt` | Construct rewrite prompt with vocabulary context |
| VocabularyValidator | `terminal/.../parser/VocabularyValidator.kt` | Soft validation of LLM output |
| OutputParser | `terminal/.../parser/OutputParser.kt` | Extract command from LLM response |
| RewriteLogger | `terminal/.../parser/RewriteLogger.kt` | Log command → rewrite → outcome |

---

## Week 1: Prompt Design & LLM Integration

### 1.1 Prompt Builder

**File**: `terminal/src/main/kotlin/com/ifautofab/terminal/parser/PromptBuilder.kt`

System prompt (strict rewrite-only):

```
You are a text adventure parser assistant. Your ONLY job is to rewrite
failed player commands into valid commands the game parser will accept.

RULES:
1. Fix ONLY parser errors (typos, synonyms, word order)
2. NEVER provide hints, solutions, or puzzle help
3. NEVER invent objects or actions not visible in the game output
4. If unsure, return exactly: <NO_VALID_REWRITE>
5. Output ONLY the rewritten command, nothing else

Rewrite strategies:
- Unknown verb → use a verb from the valid verbs list
- Unknown noun → check visible objects in game output
- Syntax error → restructure to: VERB [NOUN] [PREPOSITION NOUN]
- Typo → fix spelling while preserving intent
```

User prompt template:

```
Game output:
{last_game_output}

Failed command: {failed_command}
Error type: {error_type}

Valid verbs: {verb_subset}
Valid prepositions: {preposition_list}

Rewrite this command (or return <NO_VALID_REWRITE>):
```

Key decisions:
- Only the last game output is provided (minimal context)
- Vocabulary subset included to constrain the LLM
- `<NO_VALID_REWRITE>` as explicit escape hatch

### 1.2 Update TerminalMain.kt LLM Call

Modify the existing Groq integration to:
1. Use the new PromptBuilder instead of the current generic prompt
2. Only call LLM when ParserFailureDetector flags a rewritable error
3. Pass extracted vocabulary into the prompt
4. Parse response through OutputParser

### 1.3 Output Parser

**File**: `terminal/src/main/kotlin/com/ifautofab/terminal/parser/OutputParser.kt`

Extract the rewritten command from LLM response:
- Plain command → use as-is
- `<NO_VALID_REWRITE>` → return null
- Response with explanation → extract first command-like line
- Quoted command → strip quotes
- Clean up: lowercase, normalize whitespace, strip trailing punctuation

---

## Week 2: Vocabulary Validation & Logging

### 2.1 Soft Vocabulary Validator

**File**: `terminal/src/main/kotlin/com/ifautofab/terminal/parser/VocabularyValidator.kt`

**Soft validation** — the key design decision:

```kotlin
data class ValidationResult(
    val accepted: Boolean,
    val warnings: List<String>
)

fun validate(rewrite: String, vocabulary: ZMachineVocabulary): ValidationResult {
    val words = rewrite.trim().split("\\s+")
    val warnings = mutableListOf<String>()

    // Hard reject: verb must be in game's verb list
    val verb = words.firstOrNull()?.lowercase()
    if (verb != null && verb !in vocabulary.verbs) {
        return ValidationResult(accepted = false, warnings = listOf("Unknown verb: $verb"))
    }

    // Soft allow: nouns may not be in dictionary (truncated/incomplete)
    words.drop(1).forEach { word ->
        val lower = word.lowercase()
        if (lower !in vocabulary.words && lower !in COMMON_ARTICLES) {
            warnings.add("Unknown word (allowed): $lower")
        }
    }

    // Reject if too long (parsers rarely accept >6 words)
    if (words.size > 6) {
        return ValidationResult(accepted = false, warnings = listOf("Too many words: ${words.size}"))
    }

    return ValidationResult(accepted = true, warnings = warnings)
}
```

Why soft on nouns:
- Z-machine dictionaries truncate words to 6-9 characters
- Many games have incomplete dictionaries
- Hard rejection would produce too many false negatives

### 2.2 Rewrite Logger

**File**: `terminal/src/main/kotlin/com/ifautofab/terminal/parser/RewriteLogger.kt`

Log every rewrite attempt as JSONL for potential future fine-tuning:

```json
{
  "timestamp": "2026-02-06T14:30:00Z",
  "game": "zork1.z3",
  "failed_command": "grab leaflet",
  "error_type": "UNKNOWN_VERB",
  "last_output": "West of House\nYou are standing...",
  "llm_rewrite": "take leaflet",
  "validation": {"accepted": true, "warnings": []},
  "outcome": "success"
}
```

Log file location: `~/.ifautofab/rewrite_logs/`

This data becomes the training set if fine-tuning is needed later — collected organically from real play sessions without spoiling the games.

### 2.3 Tests

- PromptBuilder produces correct prompt structure
- OutputParser handles all response formats (plain, quoted, with explanation, NO_VALID_REWRITE)
- VocabularyValidator rejects unknown verbs, allows unknown nouns
- VocabularyValidator rejects commands >6 words
- End-to-end: failure detection → prompt → mock LLM response → validation → retry

---

## Deliverables

| Deliverable | Description |
|-------------|-------------|
| PromptBuilder | Vocabulary-aware prompt construction |
| VocabularyValidator | Soft validation (hard on verbs, soft on nouns) |
| OutputParser | LLM response parsing with NO_VALID_REWRITE support |
| RewriteLogger | JSONL logging for future fine-tuning data |
| Updated TerminalMain.kt | Targeted LLM rewrite on failure only |
| Unit tests | Prompt, parser, validator coverage |

---

## What This Enables for Phase 3

- Complete cloud-backed rewrite pipeline ready to evaluate
- Rewrite logs accumulating for potential future fine-tuning
- Vocabulary validation ready to reuse with local models
- Prompt template ready to test with smaller models
