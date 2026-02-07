# LLM-Assisted Parser Repair for Z-Machine Games
## Project Plan (Revised 2026-02-07)

---

## Project Goal

Build on the existing LLM command rewriting in IFAutoFab's terminal module to reduce parser friction by using a tightly constrained LLM to rewrite failed player commands into valid canonical game commands. The system must not solve puzzles, invent objects, or alter game logic, and must support offline/local execution including Android.

**Scope**: Z-machine games only (.z3–.z8). The architecture should allow future extension to Glulx/TADS/Hugo, but these are explicitly out of scope for v1.

**Starting point**: Existing LLM rewriting in `TerminalMain.kt` (branch `main`). This plan extends that foundation with vocabulary-aware validation, parser failure detection, and local model support.

---

## Design Constraints

### Performance Targets
- **Local inference latency**: <1 second (prioritize accuracy over speed)
- **Cloud fallback latency**: <3 seconds
- **Memory budget (Android)**: <500MB additional RAM for model

### LLM Context Strategy
- **Minimal context**: Provide only the last game output (room description or response) to the LLM
- Rationale: Reduces token cost, simplifies implementation, sufficient for most rewrites
- Future consideration: Escalate to richer context if accuracy proves insufficient

---

## Why We Parse Output Text (Not the Interpreter)

The Z-machine has a clean architectural split that makes interpreter-level failure
detection impossible:

1. **The interpreter only tokenizes.** The `read` opcode captures keystrokes,
   splits into words, looks each up in the game's dictionary, and writes results
   to a parse buffer. Then it returns control to game code. That's it.

2. **The parser is in the story file.** All command understanding — grammar
   matching, error detection, error message generation — is game code compiled
   from ZIL (Infocom) or Inform 6/7. To the interpreter, printing "You can't
   see any such thing" is indistinguishable from printing "West of House."

3. **No signal to intercept.** There is no "parser error" opcode. A failed
   command and a successful command are both just `print` + `read` from the
   interpreter's perspective.

4. **Every game has different error messages.** Infocom, Inform 6, and Inform 7
   all have different parser code. Even two Inform 7 games can have fully
   customized error messages.

**Could we decompile the story file to extract error strings?** Partially.
`infodump` (Ztools) can dump grammar tables and dictionary, and `txd` can
disassemble all text strings. But story files contain thousands of strings with
no tag distinguishing errors from narrative. Tracing which strings are referenced
from parser error routines requires partial Z-code static analysis — steep
effort for ~10% gain over the regex catalog + heuristic approach.

**Our approach (the practical sweet spot):**
- Use `infodump` to fingerprint the compiler family (reliable, fast)
- Apply known regex catalogs for that family's standard error messages (~90%)
- Catch-all heuristic for the remainder (short output, no room/object markers)

See: `docs/z-machine-parsing-references.md` for full documentation links.

---

## Parser Failure Categories

Different failure types require different rewrite strategies:

| Category | Example Error | Rewrite Strategy |
|----------|---------------|------------------|
| Unknown verb | "I don't know the word 'grab'" | Synonym mapping (grab → take) |
| Unknown noun | "You can't see any such thing" | Typo correction, context from last output |
| Syntax error | "I don't understand that sentence" | Restructure to VERB NOUN [PREP NOUN] |
| Ambiguity | "Which do you mean, the red book or the blue book?" | Pass through (not a failure) |
| Impossible action | "You can't do that" | Return `<NO_VALID_REWRITE>` (not a parser issue) |

---

## What's Already Built

The terminal module already has a working end-to-end pipeline:

| Component | File | Lines | Status |
|-----------|------|-------|--------|
| dfrotz wrapper + retry loop | `TerminalMain.kt` | 401 | Done |
| LLM rewriter (Groq/llama-3.1-8b) | `LLMRewriter.kt` | 320 | Done |
| Parser failure regex catalog | `ParserFailureDetector.kt` | 330 | Done |
| Z-machine vocab extractor | `VocabularyExtractor.kt` | 273 | Done |
| Rewrite event logger (JSONL) | `RewriteLogger.kt` | 170 | Done |
| Unit tests | `*Test.kt` | ~500 | Done |

All source in `terminal/src/main/kotlin/com/ifautofab/terminal/`.

---

## Test Game Corpus

| Game | Author/Year | Format | Parser Family |
|------|-------------|--------|---------------|
| Hitchhiker's Guide | Adams/Infocom, 1984 | .z3 | Infocom |
| Zork I | Infocom, 1980 | .z3 | Infocom |
| Planetfall | Infocom, 1983 | .z3 | Infocom |
| Lost Pig | Admiral Jota, 2007 | .z8 | Inform 7 |
| Spider and Web | Andrew Plotkin, 1998 | .z5 | Inform 6 |

---

## Phase 1: Compiler Fingerprinting & Detection Hardening (Week 1)

**Objectives**
- Add `infodump`-based compiler family detection (Infocom vs Inform 6 vs Inform 7)
- Wire detection into `ParserFailureDetector` so regex catalog is auto-selected per game
- Review and fill gaps in existing regex catalog by playtesting

**Deliverables**
- Compiler fingerprint module (reads story file header + grammar table format)
- `ParserFailureDetector` selects regex set based on detected compiler family
- Catch-all heuristic remains as fallback for unknown compilers

---

## Phase 1.5: User Playtesting — Failure Detection (Week 2)

**Objective**: Validate that parser failure detection works in real play across all
five test games. The human player plays normally, deliberately triggers errors,
and notes any misclassified output.

**Process**
- Build the terminal module: `./gradlew :terminal:installDist`
- Play each game for 15–20 minutes with `--llm` and `--transcript` enabled
- Deliberately provoke each failure category:
  - Unknown verb: use synonyms not in dictionary ("grab", "snatch", "yank")
  - Unknown noun: refer to objects with wrong names or typos
  - Syntax errors: malformed sentences ("put the in box", "take")
  - Ambiguity: use "it" or underspecified nouns when multiple objects present
  - Game refusals: try impossible actions ("eat the house", "go up" when no stairs)
- After each session, review transcript + rewrite log for:
  - **False negatives**: game output was an error but detector didn't catch it
  - **False positives**: normal game output was misclassified as an error
  - **Wrong category**: error was caught but classified as wrong type
- Log findings in `docs/playtest-notes-phase1.md`

**Deliverables**
- Playtest notes with specific misclassified examples per game
- Updated regex catalog to fix any gaps found
- Confidence assessment: detection accuracy per compiler family

---

## Phase 2: Cloud LLM Prompting & Validation (Weeks 3–4)

**Objectives**
- Iterate on the existing rewrite prompt based on playtest findings
- Tune vocabulary-aware validation using extracted game vocabulary:
  - Verb validation against game's actual verb list (reject if verb not found)
  - Noun validation against game's dictionary (warn but allow — dictionaries may be incomplete or truncated)
  - Syntax guardrails (VERB [NOUN] [PREP NOUN] patterns)
- Ensure `<NO_VALID_REWRITE>` fires correctly for game refusals and impossible actions
- Review rewrite logs from Phase 1.5 to identify common LLM failure patterns

**Prompt Design Principles**
- System prompt establishes strict rewrite-only behavior
- Include game's vocabulary subset relevant to current room/context
- Explicit instruction to return `<NO_VALID_REWRITE>` when uncertain
- No access to game state, inventory, or puzzle hints

**Deliverables**
- Revised prompt specification
- Updated validation rules based on real playtest data
- Metrics from rewrite logs: success rate, common failure modes

---

## Phase 2.5: User Playtesting — Rewrite Quality (Week 5)

**Objective**: Validate that LLM rewrites are helpful, accurate, and never leak
puzzle information. The human player plays naturally and evaluates each rewrite.

**Process**
- Play each game for 20–30 minutes with `--llm` enabled
- For each rewrite that fires, evaluate:
  - **Helpful**: rewrite succeeded and matched player intent
  - **Unhelpful but harmless**: rewrite failed or was irrelevant
  - **Harmful**: rewrite solved a puzzle, invented an object, or changed intent
  - **Annoying**: rewrite fired when it shouldn't have (false positive detection)
- Pay special attention to:
  - Commands near puzzle solutions — does the LLM hint at answers?
  - Ambiguous rewrites — does "get thing" become the right "thing"?
  - Repeated failures — does the system gracefully give up?
- Log findings in `docs/playtest-notes-phase2.md`

**Deliverables**
- Playtest notes with per-rewrite evaluations
- Updated prompt / validation rules based on findings
- Decision: is cloud rewrite quality good enough for v1?

---

## Phase 3: Local Model Evaluation (Week 6)

**Objectives**
- Evaluate whether a prompted (non-fine-tuned) small model works well enough
  - Candidates: Phi-3, Gemma 2B, Qwen 1.5B
- Test with the same prompt and vocabulary validation from Phase 2
- Compare accuracy against cloud model baseline
- If local model feels good enough in practice → proceed to Phase 4 with local
- If not → use cloud as default, revisit fine-tuning later using logs from real play sessions

**Evaluation Approach**
- Use the rewrite logs from Phase 2.5 as ground truth
- Replay logged failure scenarios through local model, compare outputs
- Benchmark inference latency and memory on Pixel 7a / Tensor G2
- Qualitative gut check: play a few rooms with local model and see if rewrites feel right

**Deliverables**
- Comparison notes: local models vs cloud baseline
- Decision on local vs cloud-only for v1

---

## Phase 4: Android Integration (Weeks 7–8)

**Objectives**
- If local model passed Phase 3: integrate via llama.cpp / MediaPipe LLM / GGUF runtime
- If local model insufficient: integrate cloud-only path
- Quantize local model (4–8 bit) if applicable
- Integrate into IFAutoFab app
- Show rewritten command in game output ("Rephrased as: …")
- Toggle between off / local / cloud modes

**Deliverables**
- Working end-to-end integration in IFAutoFab
- Basic mode toggle (off / local / cloud)

---

## Phase 4.5: User Playtesting — Android (Week 9)

**Objective**: Validate the full Android experience end-to-end.

**Process**
- Play 2–3 games on a real device (Pixel 7a or equivalent)
- Evaluate:
  - Latency: does the rewrite feel instant or does it break flow?
  - UX: is "Rephrased as: …" clear or confusing?
  - Reliability: any crashes, hangs, or garbled output?
  - Battery/memory impact during extended play
- Compare experience to terminal version — any regressions?

**Deliverables**
- Playtest notes: `docs/playtest-notes-android.md`
- Bug list and UX polish items

---

## Phase 5: Final Testing & Polish (Week 10)

**Objectives**
- Fix issues from Android playtesting
- Regression testing across test game corpus (minimum 5 games)
- Verify no puzzle bypass or state leakage
- Performance validation on Pixel 7a

**Test Scenarios**
- [ ] Typo correction preserves player intent
- [ ] Synonym expansion uses only valid game verbs
- [ ] `<NO_VALID_REWRITE>` returned for unsolvable commands
- [ ] No information leakage about unseen rooms/objects
- [ ] Latency <1s on Pixel 7a (Tensor G2)
- [ ] Graceful degradation when model unavailable
- [ ] Detection accuracy >90% across all test games (validated by playtesting)

**Deliverables**
- Automated test suite covering above scenarios
- Final playtest sign-off

---

## Future Considerations (Out of Scope for v1)

- **Fine-tuning**: If prompted local models aren't accurate enough, collect rewrite logs from real play sessions and fine-tune a small model on actual failure data
- **Multi-interpreter support**: Extend to Glulx (.ulx, .gblorb), TADS (.t2, .t3), Hugo (.hex)
- **Rich context mode**: Include inventory, recent commands, and room history
- **Adaptive context**: Escalate context depth on repeated failures
- **Fast-path optimization**: Levenshtein-based typo correction before LLM (battery savings)
- **Decompilation-based detection**: Use `txd` + Z-code static analysis to extract game-specific error strings for games with customized parsers

---

## Final Outputs

- Z-machine wrapper with LLM parser repair (cloud, with optional local inference)
- Compiler fingerprinting and auto-detection
- Vocabulary extraction tooling
- Automated test suite
- Playtest notes documenting real-world accuracy across 5 games
