# LLM-Assisted Parser Repair for Z-Machine Games
## Project Plan

---

## Project Goal

Build a Z-machine wrapper that preserves classic parser behavior while reducing parser friction by using a tightly constrained LLM to rewrite failed player commands into valid canonical game commands. The system must not solve puzzles, invent objects, or alter game logic, and must support offline/local execution including Android.

**Scope**: Z-machine games only (.z3–.z8). The architecture should allow future extension to Glulx/TADS/Hugo, but these are explicitly out of scope for v1.

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

### Privacy Considerations
- Cloud mode transmits player commands to external APIs (OpenAI/Anthropic/Google)
- Users must opt-in to cloud mode with clear disclosure
- Local-only mode must be fully functional without network access

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

## Phase 0: Alignment & Constraints (Week 0)

**Objectives**
- Define success criteria (e.g., parser failure reduction %, latency targets)
- Agree on explicit non-goals (no hinting, no puzzle solving, no state inference)
- Select initial target games (e.g., Zork I, Planetfall, Hitchhiker's Guide)

**Deliverables**
- Written design constraints document
- Acceptance criteria with quantitative targets
- Test game corpus (minimum 5 games spanning different Infocom eras)

---

## Phase 1: Baseline Wrapper & Vocabulary Extraction (Weeks 1–2)

**Objectives**
- Implement Z-machine input interception
- Reliably detect parser failure (categorize by failure type—see table above)
- Allow a single rewritten retry only
- Implement strict fallback to native parser error
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
- Implement output validation using **extracted game vocabulary**:
  - Verb validation against game's actual verb list
  - Noun validation against game's dictionary
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

## Phase 3: Dataset Construction (Weeks 5–6)

**Objectives**
- Collect real parser-failure inputs from:
  - Manual playtesting sessions
  - Historical IF transcripts (ClubFloyd, IFDB)
  - Synthetic generation (intentional typos, synonym substitution)
- Use high-end LLMs (GPT-4 / Gemini Ultra / Opus) to generate gold-standard rewrites
- Generate negative examples returning `<NO_VALID_REWRITE>`
- Balance dataset across failure categories

**Dataset Schema**
```json
{
  "game": "zork1",
  "failed_command": "grab the leaflet",
  "last_output": "West of House\nYou are standing in an open field...",
  "failure_type": "unknown_verb",
  "gold_rewrite": "take leaflet",
  "vocabulary_context": ["take", "drop", "open", "leaflet", "mailbox", "house"]
}
```

**Deliverables**
- Curated training dataset (JSONL, target: 10,000+ examples)
- Dataset documentation and schema
- Train/validation/test splits
- Category distribution analysis

---

## Phase 4: Model Distillation & Fine-Tuning (Weeks 7–8)

**Objectives**
- Select open-source base model (1B–3B parameter range)
  - Candidates: Phi-3, Gemma 2B, TinyLlama, Qwen 1.5B
- Fine-tune using cloud GPU infrastructure
- Evaluate accuracy vs cloud reference model
- **Build offline test harness** for model evaluation without cloud dependency

**Evaluation Metrics**
- Exact match rate (rewrite matches gold standard)
- Valid rewrite rate (rewrite parses successfully in game)
- False positive rate (rewrites that change intended meaning)
- `<NO_VALID_REWRITE>` precision/recall

**Deliverables**
- Fine-tuned model weights
- Evaluation report (accuracy, failure modes, per-category breakdown)
- Training configuration files
- Offline evaluation harness

---

## Phase 5: Local Inference & Android Support (Weeks 9–10)

**Objectives**
- Quantize model (4–8 bit) targeting <1s inference
- Integrate via llama.cpp / MediaPipe LLM / GGUF runtime
- Benchmark performance on representative Android hardware:
  - Low-end: Pixel 7a / Tensor G2 (8GB RAM)
  - Mid-range: Snapdragon 778G
  - High-end: Snapdragon 8 Gen 2
- Validate accuracy preservation post-quantization

**Deliverables**
- Android-compatible inference module (.so or AAR)
- Performance benchmarks (latency, memory, battery impact per rewrite)
- Quantization comparison report (4-bit vs 8-bit accuracy/speed tradeoff)
- Integration guide for IFAutoFab codebase

---

## Phase 6: UX & Transparency Layer (Weeks 11–12)

> **Note**: Expanded from 1 week to 2 weeks to adequately cover UX, settings, and optimization work.

**Objectives**
- Optional display of rewritten command ("Rephrased as: …")
- Settings UI:
  - Local-only mode (default) vs cloud fallback
  - Strict / purist mode (LLM disabled entirely)
  - Transparency level (silent, show rewrites, verbose logging)
- Battery optimization (batch inference, idle shutdown)
- Accessibility review (screen reader compatibility for rewrite notifications)

**Privacy & Transparency**
- Clear disclosure when cloud mode is enabled
- No persistent logging of commands in production (opt-in only)
- Rewrite history viewable by player on request

**Deliverables**
- Player-facing settings UI
- Privacy policy updates
- Accessibility audit results
- Battery impact documentation

---

## Phase 7: Testing, Ethics & Release Prep (Week 13)

**Objectives**
- Regression testing across test game corpus (minimum 5 games)
- Adversarial testing: ensure no puzzle bypass or state leakage
- Red-team exercise: attempt to extract hints via prompt injection
- License and compliance review (model weights, training data provenance)
- Performance validation on target devices

**Test Scenarios**
- [ ] Typo correction preserves player intent
- [ ] Synonym expansion uses only valid game verbs
- [ ] `<NO_VALID_REWRITE>` returned for unsolvable commands
- [ ] No information leakage about unseen rooms/objects
- [ ] Latency <1s on Pixel 7a (Tensor G2)
- [ ] Graceful degradation when model unavailable

**Deliverables**
- Release candidate
- Automated test suite
- Security/ethics audit report
- Documentation and maintenance guide

---

## Future Considerations (Out of Scope for v1)

- **Multi-interpreter support**: Extend to Glulx (.ulx, .gblorb), TADS (.t2, .t3), Hugo (.hex)
- **Rich context mode**: Include inventory, recent commands, and room history
- **Adaptive context**: Escalate context depth on repeated failures
- **Fast-path optimization**: Levenshtein-based typo correction before LLM (battery savings)
- **Collaborative dataset**: Allow players to contribute anonymized failure/rewrite pairs

---

## Final Outputs

- Z-machine wrapper with LLM parser repair
- Local, Android-capable intent-rewrite model (<500MB)
- Vocabulary extraction tooling
- Full documentation and maintenance guide
- Privacy-respecting, user-configurable experience
