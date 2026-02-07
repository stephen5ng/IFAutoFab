# LLM-Assisted Parser Repair for Z-Machine Games
## Project Plan

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

## Phase 0: Setup & Target Games (Week 0)

**Objectives**
- Define non-goals (no hinting, no puzzle solving, no state inference)

**Test Game Corpus**

| Game | Author/Year | Format | Parser Family |
|------|-------------|--------|---------------|
| Hitchhiker's Guide | Adams/Infocom, 1984 | .z3 | Infocom |
| Zork I | Infocom, 1980 | .z3 | Infocom |
| Planetfall | Infocom, 1983 | .z3 | Infocom |
| Lost Pig | Admiral Jota, 2007 | .z8 | Inform 7 |
| Spider and Web | Andrew Plotkin, 1998 | .z5 | Inform 6 |

---

## Phase 1: Baseline Wrapper & Vocabulary Extraction (Weeks 1–2)

**Objectives**
- Implement Z-machine input interception
- Detect parser failures using a two-tier approach:
  - **Regex catalog** for known compiler families (Infocom, Inform 6/7) — covers ~90% of target games
  - **Catch-all heuristic** for unknown compilers: short responses (<~80 chars) that don't describe room/object changes are likely errors
- Allow a single rewritten retry only
- If the rewrite also fails, show both: the original error and what was attempted ("Tried rephrasing as 'take leaflet', but: You can't see any such thing.")
- **Extract vocabulary table from Z-machine game files**

**Vocabulary Extraction**
Z-machine files contain embedded dictionary data:
- Standard vocabulary at known header offsets
- Extractable verbs, nouns, adjectives, prepositions
- Use for validation in Phase 2 (replace static whitelists)

**Deliverables**
- CLI prototype wrapping an existing Z-machine interpreter
- Z-machine vocabulary extractor tool
- Technical notes on parser-failure detection and categorization
- Vocabulary dumps for test game corpus

---

## Phase 2: Cloud LLM Prompting & Validation (Weeks 3–4)

**Objectives**
- Design and iterate on a strict rewrite-only prompt
- Implement **soft** output validation using extracted game vocabulary:
  - Verb validation against game's actual verb list (reject if verb not found)
  - Noun validation against game's dictionary (warn but allow — dictionaries may be incomplete or truncated)
  - Syntax guardrails (VERB [NOUN] [PREP NOUN] patterns)
- Log all failed commands and rewrite attempts
- Define minimal context format (last game output only)

**Prompt Design Principles**
- System prompt establishes strict rewrite-only behavior
- Include game's vocabulary subset relevant to current room/context
- Explicit instruction to return `<NO_VALID_REWRITE>` when uncertain
- No access to game state, inventory, or puzzle hints

**Deliverables**
- Stable cloud-backed rewrite service
- Logging and metrics pipeline (command → rewrite → outcome)
- Prompt specification document
- Validation module using extracted vocabulary

---

## Phase 3: Local Model Evaluation (Weeks 5–6)

**Objectives**
- Evaluate whether a prompted (non-fine-tuned) small model works well enough
  - Candidates: Phi-3, Gemma 2B, Qwen 1.5B
- Test with the same prompt and vocabulary validation from Phase 2
- Compare accuracy against cloud model baseline
- If local model feels good enough in practice → proceed to Phase 4 with local
- If not → use cloud as default, revisit fine-tuning later using logs from real play sessions

**Evaluation Approach**
- Build a small test set of synthetic failures (typos, synonyms, rephrasing) without playing through the games
- Benchmark inference latency and memory on Pixel 7a / Tensor G2
- Qualitative gut check: try it on a few rooms and see if the rewrites feel right

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

## Phase 5: Testing (Week 9)

**Objectives**
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

**Deliverables**
- Automated test suite covering above scenarios

---

## Future Considerations (Out of Scope for v1)

- **Fine-tuning**: If prompted local models aren't accurate enough, collect rewrite logs from real play sessions and fine-tune a small model on actual failure data
- **Multi-interpreter support**: Extend to Glulx (.ulx, .gblorb), TADS (.t2, .t3), Hugo (.hex)
- **Rich context mode**: Include inventory, recent commands, and room history
- **Adaptive context**: Escalate context depth on repeated failures
- **Fast-path optimization**: Levenshtein-based typo correction before LLM (battery savings)

---

## Final Outputs

- Z-machine wrapper with LLM parser repair (cloud, with optional local inference)
- Vocabulary extraction tooling
- Automated test suite
