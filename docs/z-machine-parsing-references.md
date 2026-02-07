# Z-Machine Parsing Architecture — Reference Documentation

## Key Architectural Insight

The Z-machine interpreter does **not** contain a parser. The parser lives entirely
inside the **story file** (game code), not the interpreter. The interpreter only
provides lexical analysis primitives (tokenization + dictionary lookup). All
command understanding, error detection, and error message generation happen in
game code that varies by compiler family.

---

## Z-Machine Specification (Official)

### Primary Spec
- **Full spec (v1.1)**: https://inform-fiction.org/zmachine/standards/z1point1/preface.html
- **Full spec (v1.0, PDF)**: https://wolldingwacht.de/if/z-spec10.pdf
- **Alternate HTML (jaredreisinger)**: https://zspec.jaredreisinger.com/001-preface
  - Section 10 — Input streams: https://zspec.jaredreisinger.com/10-input
  - Section 13 — Dictionary & lexical analysis: https://zspec.jaredreisinger.com/13-dictionary
  - Section 14 — Opcode table: https://zspec.jaredreisinger.com/14-opcode-table
  - Section 15 — Dictionary of opcodes: https://zspec.jaredreisinger.com/15-opcodes

### What the Interpreter Does (from spec)
1. `read`/`sread`/`aread` opcode captures raw keyboard input → text buffer (lowercased)
2. Interpreter performs **lexical analysis**: splits text into words using separators from dictionary header
3. Each word is Z-encoded and looked up in the game's dictionary
4. Results go into **parse buffer**: for each word, its dictionary address (or **0** if not found), length, and position
5. **Control returns to game code** — the interpreter's job is done

### What the Game Code Does
- The game's parser (compiled into the story file) reads the parse buffer
- It matches tokenized words against grammar tables (also in the story file)
- It determines actions, objects, prepositions
- It generates ALL error messages ("I don't know the word X", "You can't see any such thing", etc.)

---

## Inform 6 Parser (Library Code)

### Source Code
- **parser.h (inform6lib)**: https://github.com/DavidGriffith/inform6lib/blob/master/parser.h

### Parser Error Constants (from Inform 6/7 CommandParserKit)
- Reference: https://ganelson.github.io/inform/CommandParserKit/S-prs.html
- I6T Parser template: https://zedlopez.github.io/standard_rules/I6T/Parser.i6t.html

| Error Constant | Code | Default Message |
|----------------|------|-----------------|
| STUCK_PE       | 1    | "I didn't understand that sentence." |
| UPTO_PE        | 2    | "I only understood you as far as..." |
| NUMBER_PE      | 3    | "I didn't understand that number." |
| ANIMA_PE       | 4    | "You can only do that to something animate." |
| CANTSEE_PE     | 5    | "You can't see any such thing." |
| TOOLIT_PE      | 6    | "You seem to have said too little." |
| NOTHELD_PE     | 7    | "You aren't holding that." |
| MULTI_PE       | 8    | "You can't use multiple objects with that verb." |
| VAGUE_PE       | 10   | "I'm not sure what 'it' refers to." |
| VERB_PE        | 12   | "That's not a verb I recognise." |

### Parsing Flow (Letters A-K)
- **A-B**: Input acquisition, direction checking
- **C**: Conversation mode (addressing NPCs)
- **D**: Verb identification and grammar lookup
- **E-G**: Grammar line and token parsing (most rejections here)
- **H**: Fallback conversation handling
- **I**: Error message generation and retry prompts
- **K**: "then" connective handling

---

## Inform 7 Parser Error Handling
- **"Printing a parser error" activity**: https://ganelson.github.io/inform-website/book/WI_18_35.html
- 25 distinct error types, customizable via rules
- Game authors can override any error message

---

## Infocom Parser
- Parser is compiled into the story file as ZIL (Zork Implementation Language) code
- Each Infocom game has its own copy of the parser (evolving from Zork I's parser)
- Version 6 used a completely new LALR(1) parser
- Error messages are stored as strings in the story file
- Historical source: https://github.com/erkyrath/infocom-zcode-terps

---

## Bocfel Interpreter (used by IFAutoFab)
- **Homepage**: https://cspiegel.github.io/bocfel/
- **Manual**: https://cspiegel.github.io/bocfel/bocfel.html
- **IFWiki**: https://www.ifwiki.org/Bocfel
- Bocfel implements the Z-machine spec — it handles `read` opcode, tokenization,
  dictionary lookup, and returns control to game code
- Bocfel has **no knowledge** of whether a command succeeded or failed

---

## Z-Machine Decompilation / Static Analysis Tools

### Ztools (The Infocom Toolkit)
- **Homepage**: https://www.inform-fiction.org/zmachine/ztools.html
- **Source (GitHub)**: https://github.com/ecliptik/ztools
- **IFWiki**: https://www.ifwiki.org/Ztools

**txd** — Z-code disassembler
- Disassembles story files to Z-code assembly + text strings
- Works on all versions (V1-V8), supports Inform symbol names (`-s` flag)
- Usage: `txd [options] story-file`

**infodump** — Story file table parser
- Dumps header, object list, grammar tables, and dictionary
- Grammar dump doesn't work for V6 games
- Usage: `infodump [options] story-file`

### Reform — Z-Machine Decompiler
- By Ben Rudiak-Gould
- Attempts to convert Z-code back to Inform 6 source
- Config files: https://github.com/allengarvin/reform-conf
- Produces low-level code; variable names are lost
- Only works for Inform-compiled games (not Infocom ZIL)

### Decompiling IF (blog post)
- https://www.ecliptik.com/blog/2015/Decompiling-Interactive-Fiction/

### Discussion
- Decompiling into Inform source: https://intfiction.org/t/decompiling-into-inform-source-code/304
- Z-code dissection: https://rec.arts.int-fiction.narkive.com/ri0E6Hnx/z-code-disection

---

## Other References
- **Z-machine Wikipedia**: https://en.wikipedia.org/wiki/Z-machine
- **Z-machine IFWiki**: https://www.ifwiki.org/Z-machine
- **Digital Antiquarian - Z-Machine deep dive**: https://www.filfre.net/2019/10/new-tricks-for-an-old-z-machine-part-1-digging-the-trenches/
- **Zarf on parser disambiguation**: https://blog.zarfhome.com/2024/01/parser-if-disambiguation
- **Inform 6 FAQ**: http://www.firthworks.com/roger/informfaq/pp.html
- **Inform 7 source (GitHub)**: https://github.com/ganelson/inform
