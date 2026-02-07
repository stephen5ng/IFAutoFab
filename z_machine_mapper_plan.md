# Z-Machine Auto-Mapper: Project Plan

This plan outlines the implementation of an automatic mapping system for a Z-Machine interpreter.

**MVP Scope:** Terminal-only ASCII mapper that auto-renders on every room transition, displaying 4-letter room codes in a clean grid layout.

**Future:** After terminal mapper is proven, port to Android with graphical rendering.

---

## 1. Data Extraction (The "Hook" Phase)

Instead of parsing messy text strings, we will hook into the Z-Machine's internal state.

### Implementation Strategy (IFAutoFab-Specific)

**Option A: Interpreter Modification (Recommended)**
- Modify bocfel C code to expose room transition callbacks via JNI
- Add `JNIEXPORT void notifyRoomChange(int oldRoom, int newRoom)` in glkstart.c
- Hook into the `set_parent` opcode handler in bocfel's opcode dispatch loop
- Minimal invasiveness: ~50 lines of C code

**Option B: Memory Polling**
- Poll Z-Machine memory from Java/Kotlin side via JNI
- Read player object's parent property every N milliseconds
- Higher overhead, but no interpreter modifications needed
- Use existing GLKModel memory access methods

**Option C: Text Output Parsing (Fallback)**
- Parse "You are now in..." style messages via TextOutputInterceptor
- Less reliable, but works for all interpreters without modification
- Use regex patterns similar to ParserFailureDetector approach

### Z-Machine Object Access

* **The Player Object:**
  - Defined in game header at address `$10` (V1-V3) or `$18` (V4+)
  - Object ID is typically 1-4 in most games
  - Monitor changes to this object's parent property

* **The Move Trigger:**
  - Intercept `set_parent` Z-Instruction (opcode `0x0E` in most versions)
  - Alternative: Hook `@get_parent` and detect when result changes between reads

* **Object Table Inspection:**
  - **Room ID:** Use the Object ID as a unique, immutable key
  - **Room Name:** Retrieve property 1 (short name) of the current room object
  - **Exits:** Property mapping varies by compiler:

| Compiler | Direction Properties | Notes |
|----------|---------------------|-------|
| **Infocom (ZIL)** | Custom per-game | Use heuristic: properties pointing to room objects |
| **Inform 6** | Props 12-19 typically | n_to, s_to, e_to, w_to, ne_to, se_to, nw_to, sw_to, u_to, d_to, in_to, out_to |
| **Inform 7** | Compiled to relations | May need to walk relation tables instead |

**Detection Strategy:**
1. Check property values—if they point to valid room objects, they're exits
2. Build direction mapping dynamically by observing actual moves
3. Use `infodump` (from Ztools) to fingerprint compiler and use known mappings

---

## 2. Graph Construction

We will use a **Directed Multigraph** to store the world. This data structure is platform-agnostic and can be reused when porting to Android.

### Data Structure (Pure Kotlin)

```kotlin
data class MapNode(
    val roomId: Int,
    var name: String = "Unknown",
    var visited: Boolean = false,
    var isDark: Boolean = false,
    var coordinates: Pair<Int, Int>? = null,
    var level: Int = 0  // For vertical movement tracking
)

data class MapEdge(
    val sourceId: Int,
    val destId: Int,
    val direction: Direction,
    val isOneWay: Boolean = false,  // Set to true if reverse not confirmed
    val isSpecial: Boolean = false   // For named exits like "enter building"
)

enum class Direction {
    NORTH, SOUTH, EAST, WEST,
    NORTHEAST, NORTHWEST, SOUTHEAST, SOUTHWEST,
    UP, DOWN, IN, OUT,
    SPECIAL  // For non-standard exits
}

class WorldGraph {
    private val nodes: MutableMap<Int, MapNode> = mutableMapOf()
    private val edges: MutableList<MapEdge> = mutableListOf()

    fun addNode(roomId: Int, name: String)
    fun addEdge(from: Int, to: Int, direction: Direction)
    fun confirmReverseEdge(from: Int, to: Int, direction: Direction)
    fun getNeighbors(roomId: Int): List<MapEdge>
    fun assignCoordinates(startRoom: Int)
}
```

### Graph Rules

* **Nodes ($N$):** Each node represents a unique Room Object ID
  - *Metadata:* `roomId`, `name`, `visited`, `isDark`, `coordinates`, `level`

* **Edges ($E$):** Each edge represents a discovered transition
  - *Properties:* `sourceId`, `destId`, `direction`, `isOneWay`, `isSpecial`

* **The Consistency Check:** Do not assume symmetry. Mark edges as two-way only after confirming both directions:
  - North from A→B creates a tentative one-way edge
  - South from B→A confirms the bidirectional link and updates both edges

* **Special Exit Handling:**
  - Named exits (e.g., "enter house", "climb tree") stored as `Direction.SPECIAL`
  - Edge metadata includes original command text
  - Not used for coordinate calculation

---

## 3. Coordinate Geometry & Multi-Level Mapping

Since we are building visual representations, we need to map graph nodes to coordinates.

### The 3D Coordinate System

Each room has: $(x, y, level)$

- $x, y$ = Horizontal position (2D plane)
- $level$ = Vertical layer (for Up/Down/In/Out)

### The Relative Coordinate Algorithm

1. **Origin:** Assign the first room (starting location) to $(0, 0, 0)$

2. **Propagation:** When moving in a direction $D$ from $(x, y, level)$:
   - **North:** $(x, y+1, level)$
   - **South:** $(x, y-1, level)$
   - **East:** $(x+1, y, level)$
   - **West:** $(x-1, y, level)$
   - **Northeast:** $(x+1, y+1, level)$
   - **Northwest:** $(x-1, y+1, level)$
   - **Southeast:** $(x+1, y-1, level)$
   - **Southwest:** $(x-1, y-1, level)$
   - **Up / In:** $(x, y, level+1)$
   - **Down / Out:** $(x, y, level-1)$

3. **Collision Handling:**
   - If moving to a Room ID that already has coordinates → use existing coordinates (warp/teleport detected)
   - If moving to a *new* Room ID but target $(x, y, level)$ is occupied by *different* Room ID:
     - **Strategy 1 (Simple):** Increment $x$ until finding empty space, mark both rooms as "non-Euclidean"
     - **Strategy 2 (Smart):** Try surrounding cells in spiral pattern: $(x+1,y), (x,y+1), (x-1,y), (x,y-1), (x+1,y+1)$...
     - **Strategy 3 (Overflow Layer):** Place on separate "impossible geometry" layer with visual indicator

4. **Non-Standard Exits:**
   - `Direction.SPECIAL` exits do not affect coordinates
   - Treated as aliases/flavor text for standard directions

---

## 4. Terminal Rendering (ASCII Auto-Map)

The renderer outputs a grid-based string buffer that **automatically updates after every room transition**.

### Visual Representation Components
* `[    ]` : An unvisited/empty room (6 chars wide)
* `[KITC]` : A visited room (first 4 letters of name)
* `[@@@@]` : Current player location
* `[????]` : A dark/unknown room
* `|`, `-` : N/S and E/W connectors
* `/`, `\` : Diagonal connectors (NE/NW/SE/SW)
* `^`, `v` : Up/Down indicators (rendered inside room box)
* `[~~~~]` : Non-Euclidean room (coordinates conflict)
* `>`, `<`, `^`, `v` : One-way passage indicators (arrows show direction)

### Example Output (Single Level):
```text
> north
You are in the kitchen.

═══════════════════════════════════
           AUTO-MAP
═══════════════════════════════════
Layer 0 (Main Floor)

      [KITC]
         |
[CLER]--[@@@@]--[BEHI]
         |
      [PATH]

Current: West of House
Rooms Discovered: 5/∞
═══════════════════════════════════

> east
You are behind the house.

═══════════════════════════════════
           AUTO-MAP
═══════════════════════════════════
Layer 0 (Main Floor)

      [KITC]
         |
[CLER]--[WEST]--[@@@@]
         |
      [PATH]

Current: Behind House
Rooms Discovered: 5/∞
═══════════════════════════════════
```

### Example Output (Multi-Level):
```text
═══════════════════════════════════════════════════════════════════
                            AUTO-MAP
═══════════════════════════════════════════════════════════════════
Layer +1 (Upstairs)      Layer 0 (Ground)       Layer -1 (Basement)

    [BEDR]^                  [HALL]                  [WINE]
       |                       |v                      |^
    [@@@@]                  [LIVI]                  [CELL]
                              |v
                           [KITC]

Current: Bedroom (Level +1)
Rooms Discovered: 12/∞
Use 'u' and 'd' commands to change levels
═══════════════════════════════════════════════════════════════════
```

### Rendering Algorithm
1. **Trigger:** Auto-render after detecting room change via memory polling
2. **Bounds:** Show all rooms if <20 total, otherwise viewport centered on player (±5 rooms)
3. **Grid Construction:** Create 2D character array `grid[y][x]`
4. **Room Placement:** Each room is 6 chars wide `[XXXX]`, 1 char tall
5. **Spacing:** 2 chars between rooms horizontally (for `--`), 1 char vertically (for `|`)
6. **Connectors:** Draw `-`, `|`, `/`, `\` between adjacent room coordinates
7. **Level Display:** If multiple levels exist, show current level ±1 in side-by-side columns
8. **Output:** Print border, map grid, legend, then return to game prompt

### Room Name Truncation Rules
- Take first 4 uppercase letters of room name, ignoring common words
- Examples:
  - "West of House" → `WEST`
  - "Kitchen" → `KITC`
  - "Behind the House" → `BEHI`
  - "Forest Path" → `PATH`
  - "Treasure Room" → `TREA`
- If room has no name (dark/unknown): `????`
- Current room always: `@@@@`

---

## 5. Map Persistence

### Save Format: JSON

```json
{
  "version": 1,
  "gameFile": "zork1.z3",
  "createdAt": "2026-02-07T10:30:00Z",
  "nodes": [
    {
      "roomId": 1,
      "name": "West of House",
      "visited": true,
      "isDark": false,
      "coordinates": [0, 0, 0],
      "level": 0
    }
  ],
  "edges": [
    {
      "source": 1,
      "dest": 2,
      "direction": "NORTH",
      "isOneWay": false,
      "isSpecial": false
    }
  ],
  "playerNotes": {
    "1": "Starting location - white house visible"
  }
}
```

### Storage Location
- **Terminal:** `~/.ifautofab/maps/{game_hash}.json`
- **Android:** `Context.getFilesDir()/maps/{game_hash}.json`
- Hash = MD5 of game file to uniquely identify each story

### Auto-Save Strategy
- Save after each room transition (async, non-blocking)
- Load automatically when game starts (if map file exists)
- Merge new discoveries with existing map data

---

## 6. Development Roadmap (Terminal-Only MVP)

| Phase | Task | Description | Success Criteria |
| :--- | :--- | :--- | :--- |
| **I** | **Memory Hook** | Detect when player object's parent changes via polling | Logs "Moved from Room X to Room Y" on every move |
| **II** | **Graph Logic** | Build WorldGraph class, store room transitions | Graph contains all visited rooms and discovered exits |
| **III** | **Z-Machine Object Reader** | Read room names and properties from object table | Can extract room name and detect exits programmatically |
| **IV** | **Coord Solver** | Implement 3D coordinate assignment algorithm | All rooms have valid (x, y, level) coordinates |
| **V** | **ASCII Renderer** | Output 4-letter room codes, auto-render on change | Map displays after every move, readable layout |
| **VI** | **Multi-Level Support** | Handle Up/Down/In/Out movements | Can display multiple levels side-by-side |
| **VII** | **Persistence** | Save/load map to JSON | Map persists across game sessions |
| **VIII** | **Polish** | One-way indicators, non-Euclidean markers, dark rooms | Professional-quality terminal mapper |

**Post-MVP (Future):**
- **IX** | **Android Port** | Graphical MapView with Canvas rendering | Map displays in phone UI
- **X** | **Car App Integration** | Exit indicators in GameScreen | Glanceable navigation while driving

---

## 7. Testing Strategy

### Test Games (Ordered by Complexity)

1. **Zork I (zork1.z3)** - Classic, well-documented, V3, moderate complexity
2. **Planetfall (planetfall.z3)** - Infocom, more complex map with multiple levels
3. **Anchorhead (anchorhead.z8)** - Inform 6, large world, V8 features
4. **Counterfeit Monkey (monkey.zblorb)** - Inform 7, tests modern compiler compatibility

### Test Scenarios

| Scenario | Test Case | Expected Behavior |
|----------|-----------|-------------------|
| **Basic Movement** | N, S, E, W from starting room | Graph adds 4 nodes, 4 edges |
| **Symmetry** | N then S back to origin | Edge marked as bidirectional |
| **Asymmetry** | Down a well (can't climb back up) | One-way edge rendered with arrow |
| **Vertical** | Climb tree, go up stairs | New level created, can toggle view |
| **Teleport** | Magic word transports to distant room | Warp detected (no connecting path) |
| **Dark Room** | Enter room without light source | Room added to graph, marked as dark |
| **Non-Euclidean** | Maze where E-E-E returns to start | Collision detected, rooms offset |
| **Save/Load** | Save map, restart, load map | All discovered rooms preserved |

### Acceptance Criteria
- ✅ 100% of movements captured (no missed transitions)
- ✅ Room names extracted correctly (Z-string decode working)
- ✅ Coordinates assigned without crashes (collision handling robust)
- ✅ Map renders readable ASCII within 5 seconds on 100+ room graph
- ✅ JSON save/load roundtrip preserves all data

---

## 8. Implementation Tips

### Z-Machine Version Differences

| Version | Object Table Location | Property Size | Notes |
|---------|----------------------|---------------|-------|
| **V1-V3** | Dynamic memory start | 31 attributes, var props | Zork I-III, most Infocom |
| **V4-V5** | Header offset $0A | 48 attributes, var props | Trinity, AMFV |
| **V6-V8** | Header offset $0A | 48 attributes, var props | Graphical games rare |

### Reading the Object Table (V3 Example)

```kotlin
fun readObjectName(objectId: Int): String {
    val objTableAddr = readWord(0x0A) // Object table base
    val objEntrySize = 9 // V3: 9 bytes per object
    val objAddr = objTableAddr + ((objectId - 1) * objEntrySize)

    val propTablePtr = readWord(objAddr + 7) // Offset to property table
    val nameLength = readByte(propTablePtr) // First byte = text length
    val nameAddr = propTablePtr + 1

    return decodeZString(nameAddr, nameLength) // Existing Z-string decoder
}

fun readObjectParent(objectId: Int): Int {
    val objTableAddr = readWord(0x0A)
    val objAddr = objTableAddr + ((objectId - 1) * 9)
    return readByte(objAddr + 4) // Parent at offset 4 in V3
}
```

### The Darkness Hack
Even if the game says "It is pitch black," the Z-Machine still knows which Room ID you are in. Your map can reveal the room name early—a nice "cheater" perk for debugging. Mark these rooms visually distinct (e.g., `[?]` instead of `[R]`) but still add them to the graph.

### Performance Optimization
- **Lazy Rendering:** Only render visible portion of map (viewport culling)
- **Cached Layouts:** Recalculate coordinates only when new rooms discovered
- **Async Graph Updates:** Update graph on background thread, post results to UI thread

### Edge Cases to Handle
1. **Teleportation Spells:** Create edge with no coordinate propagation
2. **Vehicles:** Player parent changes to vehicle object, not room - detect and ignore
3. **Mazes:** Many rooms with identical names - use Room ID, not name, as key
4. **Timed Events:** Room changes without player command (trap doors) - detect via polling

---

## 9. IFAutoFab Integration Points (Terminal MVP)

### Terminal Module (`terminal/src/main/kotlin/com/ifautofab/terminal/`)

**New Files to Create:**
- `mapper/WorldGraph.kt` - Graph data structure (MapNode, MapEdge, Direction enum)
- `mapper/CoordinateAssigner.kt` - 3D coordinate assignment algorithm
- `mapper/AsciiMapRenderer.kt` - Terminal rendering with 4-letter room codes
- `mapper/MapPersistence.kt` - JSON save/load
- `mapper/ZMachineMemoryReader.kt` - Object table access (reads room names, parent objects)
- `mapper/RoomChangeDetector.kt` - Memory polling loop to detect transitions

**Modified Files:**
- `TerminalMain.kt` - Add `--map` CLI flag (default: enabled), integrate auto-rendering

### Future: Android Module (Not in MVP)

After terminal mapper is proven and working well:
- Port WorldGraph and coordinate logic to shared Kotlin module
- Build graphical MapView with Canvas rendering
- Integrate with GLKGameEngine room change events

---

## 10. Future Enhancements (Post-MVP)

### Android/Car App Port
- **MapView:** Canvas-based graphical rendering with circles/lines
- **Phone UI:** Separate tab with pinch-to-zoom, pan gestures
- **Car App:** Simplified "exits available" indicators (safety-first)
- **Real-time Sync:** Map updates via GLKGameEngine room change events

### Advanced Features
- **Annotations:** Let users add notes to rooms ("key hidden here")
- **Path Finding:** "Show me the shortest path to Room X"
- **Exit Prediction:** Use property table to show unexplored exits as dotted lines
- **Compass Rose:** Always show N/S/E/W orientation
- **Interactive Terminal:** Click on room to auto-walk path

### Export & Sharing
- **Export Formats:** Save map as PNG image or GraphViz DOT file
- **Multi-Game:** Compare maps across different playthroughs
- **Cloud Sync:** Share maps between devices via Firebase

### Multi-Interpreter Support
- **Glulx:** Extend memory reader for 32-bit addressing, Glulx object model
- **TADS 2/3:** Different property system, may need text parsing fallback
- **Hugo/Alan:** Investigate memory layouts for room detection

---

## 11. Decisions Made ✓

- ✅ **Hook Method:** Memory polling (Option B) - no C modifications needed
- ✅ **Rendering Frequency:** Auto-render on every detected room change
- ✅ **Platform Scope:** Terminal-only MVP - prove concept before Android port
- ✅ **Room Display:** 4-letter codes (e.g., `[KITC]`) instead of single letter
- ✅ **Multi-Interpreter:** Z-machine only, with abstraction for future expansion

## 12. Remaining Open Questions

- [ ] **Polling Frequency:** How often to check for room changes? (50ms? 100ms? 200ms?)
- [ ] **Map Toggle:** Should `--map` be opt-in or opt-out? (Recommend: enabled by default)
- [ ] **Viewport Size:** Show all rooms, or limit to ±N rooms from player? (Start: show all if <30 rooms)
- [ ] **Collision Strategy:** Simple increment-X or smart spiral search? (Start: simple)
- [ ] **Dark Room Behavior:** Show `[????]` or use actual room name as "cheat mode"? (Start: show name)

---

*Created for IFAutoFab - Z-Machine Interpreter Project - 2026*
*Updated: 2026-02-07 - Terminal-only MVP with 4-letter room codes, auto-rendering*
