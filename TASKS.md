# LLM Parser Repair - Task Tracker

## Phase 0: Setup

- [x] **#1** Select test game corpus (5 Z-machine games)
  - Zork I (.z3, Infocom), Planetfall (.z3, Infocom), Hitchhiker's Guide (.z3, Infocom)
  - Lost Pig (.z8, Inform 7), Spider and Web (.z5, Inform 6)

## Phase 1: Baseline Wrapper & Vocabulary Extraction

- [ ] **#2** Build parser failure regex catalog for Infocom/Inform *(blocked by #1)*
  - Cover: unknown verb, unknown noun, syntax error, ambiguity, impossible action
- [ ] **#3** Build catch-all heuristic for unknown compilers *(blocked by #2)*
  - Short responses (<~80 chars) without room/object changes → likely errors
- [ ] **#4** Implement single-retry rewrite loop with dual error display *(blocked by #2)*
  - One retry only; if rewrite also fails, show both errors
  - Build on existing TerminalMain.kt LLM rewriting
- [ ] **#5** Build Z-machine vocabulary extractor *(blocked by #1)*
  - Extract dictionary from .z3–.z8 files at known header offsets
  - Output verbs, nouns, adjectives, prepositions

## Phase 2: Cloud LLM Prompting & Validation

- [ ] **#6** Design strict rewrite-only LLM prompt *(blocked by #4, #5)*
  - Rewrite-only behavior, vocabulary context, `<NO_VALID_REWRITE>` sentinel
- [ ] **#7** Implement soft vocabulary validation for LLM output *(blocked by #5, #6)*
  - Hard reject unknown verbs, soft allow unknown nouns
  - Enforce VERB [NOUN] [PREP NOUN] syntax
- [ ] **#8** Add rewrite logging (command → rewrite → outcome) *(blocked by #4)*
  - Log for potential future fine-tuning

## Dependency Graph

```
#1 Select games
├── #2 Regex catalog
│   ├── #3 Catch-all heuristic
│   └── #4 Rewrite loop ──→ #8 Logging
│       └── #6 Prompt design
└── #5 Vocab extractor
    ├── #6 Prompt design
    └── #7 Vocab validation (also needs #6)
```
