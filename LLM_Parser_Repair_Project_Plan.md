# LLM-Assisted Parser Repair for Z-Machine Games  
## Project Plan

---

## Project Goal

Build a Z-machine wrapper that preserves classic parser behavior while reducing parser friction by using a tightly constrained LLM to rewrite failed player commands into valid canonical game commands. The system must not solve puzzles, invent objects, or alter game logic, and must support offline/local execution including Android.

---

## Phase 0: Alignment & Constraints (Week 0)

**Objectives**
- Define success criteria (e.g., parser failure reduction %, latency targets).
- Agree on explicit non-goals (no hinting, no puzzle solving, no state inference).
- Select initial target games (e.g., Zork I, Planetfall).

**Deliverables**
- Written design constraints
- Acceptance criteria document

---

## Phase 1: Baseline Wrapper Prototype (Weeks 1–2)

**Objectives**
- Implement Z-machine input interception.
- Reliably detect parser failure.
- Allow a single rewritten retry only.
- Implement strict fallback to native parser error.

**Deliverables**
- CLI prototype wrapping an existing Z-machine interpreter
- Technical notes on parser-failure detection

---

## Phase 2: Cloud LLM Prompting & Validation (Weeks 3–4)

**Objectives**
- Design and iterate on a strict rewrite-only prompt.
- Implement output validation:
  - Verb whitelist
  - Object matching
  - Regex / syntax guardrails
- Log all failed commands and rewrite attempts.

**Deliverables**
- Stable cloud-backed rewrite service
- Logging and metrics pipeline
- Prompt specification

---

## Phase 3: Dataset Construction (Weeks 5–6)

**Objectives**
- Collect real parser-failure inputs.
- Use high-end LLMs (ChatGPT / Gemini / Opus) to generate gold-standard rewrites.
- Generate negative examples returning `<NO_VALID_REWRITE>`.

**Deliverables**
- Curated training dataset (JSONL)
- Dataset documentation and schema

---

## Phase 4: Model Distillation & Fine-Tuning (Weeks 7–8)

**Objectives**
- Select open-source base model (1B–3B parameter range).
- Fine-tune using cloud GPU infrastructure.
- Evaluate accuracy vs cloud reference model.

**Deliverables**
- Fine-tuned model weights
- Evaluation report (accuracy, failure modes)
- Training configuration files

---

## Phase 5: Local Inference & Android Support (Weeks 9–10)

**Objectives**
- Quantize model (4–8 bit).
- Integrate via llama.cpp / MediaPipe / GGUF runtime.
- Benchmark performance on representative Android hardware.

**Deliverables**
- Android-compatible inference module
- Performance benchmarks (latency, memory, battery impact)

---

## Phase 6: UX & Transparency Layer (Week 11)

**Objectives**
- Optional display of rewritten command (“Rephrased as: …”).
- Settings for:
  - Local vs cloud fallback
  - Strict / purist mode (LLM disabled)
- Latency and battery optimizations.

**Deliverables**
- Player-facing UX configuration
- Accessibility and transparency guidelines

---

## Phase 7: Testing, Ethics & Release Prep (Week 12)

**Objectives**
- Regression testing across multiple games.
- Ensure no puzzle bypass or state leakage.
- License and compliance review.

**Deliverables**
- Release candidate
- Test suite
- Documentation and team handoff package

---

## Final Outputs

- Open-source Z-machine wrapper
- Local, Android-capable intent-rewrite model
- Full documentation and maintenance guide
