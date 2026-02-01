# IFAutoFab Implementation Progress

## Current State

**Project Location**: `~/programming/IFAutoFab`
**Status**: Phase 3 (GLK Layer) - 67 compilation errors, WIP
**Last Checkpoint**: 7 git commits capturing deltas from Fabularium sources

## Git History Checkpoints

### Baseline Commits (from Fabularium sources)

| Commit | Message | Content |
|--------|---------|---------|
| `658beae` | import: Original Fabularium GLK bridge | 6273 insertions - C files (glk.c, glk.h, glkstart.c, glkstart.h, gi_*.c/h) |
| `216bcc9` | import: Original Fabularium CMakeLists | 635 lines - Full CMake config with all targets |
| `533e41b` | import: Original Fabularium GLK Java | 20230 insertions - 42 Java files (com.luxlunae.glk.*) |

### Modification Commits

| Commit | Message | Changes |
|--------|---------|---------|
| `eb669bc` | modify: CMakeLists - remove tools | Removed: t3make, utils, agt2agx, babel, inform (635→491 lines) |
| `bbc1793` | feat: Add project scaffold | New: settings/build.gradle, gradle wrapper, AndroidX config |
| `0f804ad` | feat: Add garglk submodule | Declared submodule reference |
| `e787a25` | feat: Initialize garglk submodule | 2228 files, 1.3MB - all C source for 12 interpreters |

### Tracking Deltas

**To see what changed in a file from original Fabularium**:
```bash
# Show original (commit 533e41b)
git show 533e41b:app/src/main/java/com/luxlunae/glk/GLKLogger.java

# Show current
git show HEAD:app/src/main/java/com/luxlunae/glk/GLKLogger.java

# See full diff of GLK layer
git diff 533e41b HEAD -- app/src/main/java/com/luxlunae/glk/
```

**Note**: Commit 533e41b may not be the pure original (some sed migrations applied before git init). To see true original, compare to `/tmp/fabularium/app/src/main/java/com/luxlunae/`

## Completed Work

### Phase 1: Project Scaffold ✅
- [x] Settings.gradle.kts - Gradle project config
- [x] Build.gradle.kts - Root plugins
- [x] App/build.gradle.kts - Module config + dependencies
  - AndroidX (core, appcompat, lifecycle)
  - Car App Library (1.4.0)
  - Material Design
- [x] Gradle wrapper (v9.1.0)
- [x] .gitignore

**Rationale**: Modern Gradle toolchain, AndroidX, Car App Library support (Fabularium uses legacy Support Library)

### Phase 2: Native Code ✅
- [x] GLK bridge (C files) - Copied verbatim
  - glk.c/h - Core GLK dispatch (200+ JNI callbacks)
  - glkstart.c/h - JNI lifecycle, terp loader, 100+ hardcoded class names
  - gi_blorb.c/h - Blorb resource handling
  - gi_dispa.c/h - GLK dispatcher
- [x] CMakeLists.txt - Removed build tools, kept 12 interpreters
- [x] Garglk submodule - Added and initialized (2228 files of C source)

**Why no modifications to C code?** JNI class names hardcoded in glkstart.c lock us into `com.luxlunae.glk.*` package structure.

### Phase 3: GLK Java Layer 🟡 WIP
- [x] Copied all 42 Java files from Fabularium
- [x] AndroidX migration (all files)
  - `android.support.*` → `androidx.*`
  - `android.arch.lifecycle.*` → `androidx.lifecycle.*`
- [x] Removed Fabularium imports
  - `import com.luxlunae.fabularium.*` ✗
  - `import com.luxlunae.bebek.*` ✗
- [x] Removed view-layer code
  - `glk/view/*` (100+ files) ✗
  - `GLKActivity.java` ✗
  - `bebek/*` (30 files) ✗
- [ ] Finished GLKModel refactoring (WIP - removing AndroidViewModel)
- [ ] Finished GLKController cleanup (WIP - removing GLKActivity)
- [ ] Reached compilation ❌ 67 errors remaining

**Compilation Errors** (67 total):
- PreferencesActivity references (~20 errors)
- GLKKeyboardMapping references (~15 errors)
- GLKScreen references (~10 errors)
- getApplication() calls (~10 errors)
- R.string/R.layout references (~12 errors)

All errors are from **incomplete refactoring** (removing Fabularium dependencies). Fix approach: targeted sed commands + strategic deletions.

## TODO: Remaining Work

### Phase 3 (continued): Get GLK Layer Compiling
1. Remove all `PreferencesActivity` references from GLKModel
2. Remove all `GLKKeyboardMapping` references
3. Remove all `GLKScreen` references (replace with Object or stub)
4. Replace `getApplication()` calls with provided Context
5. Remove all `R.string` and `R.layout` references
6. Target: `./gradlew assembleDebug` succeeds, produces native .so files

**Estimated approach**: 5-10 targeted sed commands + 1-2 method stubs

### Phase 4: App-Layer Code (NEW PACKAGE: com.ifautofab)
Create IFAutoFab-specific UI and engine:

**New files to create**:
```
app/src/main/java/com/ifautofab/
  ├── TextOutputInterceptor.kt         - Thread-safe output bridge
  ├── GLKGameEngine.kt                 - Interpreter lifecycle manager
  ├── MainActivity.kt                  - Phone UI (from IFAuto)
  ├── MyCarAppService.kt               - Car service entry point
  ├── GameSession.kt                   - Car service session
  └── GameScreen.kt                    - Car UI (ListTemplate)

app/src/main/res/
  ├── layout/activity_main.xml         - Phone layout
  ├── xml/automotive_app_desc.xml      - Car app descriptor
  └── values/{strings,colors,themes}.xml
```

**Architecture**:
```
MainActivity & GameScreen
    ↓ sendInput(command)
TextOutputInterceptor (singleton, thread-safe)
    ↑ appendText(output) [from GLK worker thread]
    ↓ awaitNewText(timeout)
GLKGameEngine (singleton)
    ↓ owns GLKModel instance
GLKModel (com.luxlunae.glk.model)
    ↓ JNI callbacks from native terp
Terp worker thread (glkstart.c → dlopen → interpreter .so)
```

**Key Classes**:

**TextOutputInterceptor.kt**:
```kotlin
object TextOutputInterceptor {
    fun appendText(text: String)           // Called by GLKModel.updateView()
    fun awaitNewText(timeoutMs: Long): String  // Blocking read for UI
}
```

**GLKGameEngine.kt**:
```kotlin
object GLKGameEngine {
    fun startGame(context: Context, gamePath: String, format: String)
    fun sendInput(command: String)
    fun getCurrentOutput(): String
}
```

### Phase 5: Build & Verify
1. Compile to APK: `./gradlew assembleDebug`
2. Verify .so files: `find app/build -name "*.so" | sort`
3. Deploy: `adb install app/build/outputs/apk/debug/app-debug.apk`
4. Test:
   - Phone: Play Zork, type commands
   - Car: Verify ListTemplate appears, responds to input
5. Verify flow: bocfel → glk.c → JNI → GLKController → GLKModel → TextOutputInterceptor → MainActivity

## Package Structure (Locked)

```
com.luxlunae.glk.*                    ← LOCKED: JNI class names in C code
  ├── controller/                     ← ~100 glk_* static methods
  ├── model/                          ← GLKModel, streams, windows
  └── [view/, bebek/ - REMOVED]

com.ifautofab.*                       ← NEW: IFAutoFab app code
  ├── MainActivity, MyCarAppService
  ├── GameSession, GameScreen
  ├── GLKGameEngine, TextOutputInterceptor
  └── [other app logic]
```

## Fabularium Delta Reference

### Files Removed
- `glk/view/*` - Android UI rendering system
- `GLKActivity.java` - Fabularium's Activity wrapper
- `bebek/*` - ADRIFT game authoring IDE

### Files Modified
- All Java files - AndroidX migration
- GLKModel - Removing AndroidViewModel, preferences
- GLKController - Removing Activity references
- GLKUtils - Removing UI utility code

### Files Unchanged
- All C files (glk.c, glkstart.c, etc.)
- All com.luxlunae.glk package interfaces

## Build Commands

```bash
# Clean and build
cd ~/programming/IFAutoFab
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew clean assembleDebug

# Check for .so files
find app/build/intermediates/ndkLibs -name "*.so" | sort

# Deploy
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb -s emulator-5554 shell am start -n com.ifautofab/.MainActivity
```

## Known Issues

1. **67 Java compilation errors** (Phase 3 WIP)
   - All from removing Fabularium dependencies
   - Not blocking progress (can be fixed with focused edits)

2. **CMakeLists.txt relative paths**
   - Assumes garglk submodule at `app/src/main/jni/garglk/`
   - Should work (submodule already initialized)

3. **Gradle caching**
   - If build fails, try: `rm -rf .gradle build`
   - Clean rebuild: `./gradlew clean assembleDebug`

## Success Criteria (by phase)

- **Phase 1**: ✅ Gradle builds configured
- **Phase 2**: ✅ Native build system ready (CMakeLists, garglk)
- **Phase 3**: 🟡 GLK Java layer compiles (WIP)
- **Phase 4**: ⏳ App UI layer runs
- **Phase 5**: ⏳ End-to-end game playable

## Git Strategy

Each phase = separate commits capturing deltas:
1. Original sources imported (baseline)
2. Modifications applied (deltas)
3. New code added (features)

This allows reviewing exactly what changed and why.

To review changes:
```bash
git log --oneline                    # See all checkpoints
git show <commit>                    # Details of specific commit
git diff <commit1> <commit2>         # Diff between commits
```
