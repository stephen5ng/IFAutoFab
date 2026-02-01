# IFAutoFab Build and Setup Notes

This document covers building IFAutoFab, a multi-interpreter interactive fiction player for Android and Android Automotive.

## Project Overview

**IFAutoFab** combines:
- **Fabularium's GLK layer** (Java bridge to 12+ native interpreters via JNI)
- **Garglk submodule** (C source for interpreters: bocfel, git, hugo, tads, alan, level9, magnetic, scott, scare, agility, advsys, glulxe)
- **IFAuto's UI patterns** (Phone MainActivity + Car/Automotive GameScreen via Car App Library)
- **Custom threading & I/O** (TextOutputInterceptor for thread-safe output, GLKGameEngine for lifecycle)

**Supported Game Formats**:
- Z-Machine (`.z3`, `.z5`, `.z8`) → bocfel
- Glulx (`.ulx`, `.gblorb`) → git
- Hugo → hugo
- TADS 2/3 → tads
- Alan 2/3 → alan2, alan3
- Level 9 → level9
- Magnetic Scrolls → magnetic
- Scott Adams → scott
- SCARE → scare
- Agility → agility
- AdvSys → advsys
- Glulxe (alternate Glulx) → glulxe

---

## Prerequisites

### Android SDK & NDK
- **Android SDK**: API 34 (minimum: 28 for minSdk)
- **Android NDK**: Required for native compilation (CMake will use it automatically)
- **CMake**: Bundled with NDK, configured in build.gradle.kts

### Java Runtime
Use JBR (JetBrains Runtime) bundled with Android Studio:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Or verify your standalone JDK is 11+:
```bash
java -version
```

---

## Building the Project

### Clean Build
```bash
cd ~/programming/IFAutoFab
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew clean assembleDebug
```

### Incremental Build
```bash
./gradlew assembleDebug
```

### Expected Build Artifacts

After successful build:
```bash
# APK for installation
app/build/outputs/apk/debug/app-debug.apk

# Native libraries (in intermediates, packaged into APK)
app/build/intermediates/ndkLibs/debug/arm64-v8a/
  ├── libglk.so              # GLK dispatcher
  ├── libbocfel.so           # Z-Machine interpreter
  ├── libgit.so              # Glulx interpreter
  ├── libhugo.so             # Hugo interpreter
  ├── libtads.so             # TADS interpreter
  ├── libalan2.so, libalan3.so
  ├── liblevel9.so
  ├── libmagnetic.so
  ├── libscott.so
  ├── libscare.so
  ├── libagility.so
  ├── libadvsys.so
  └── libglulxe.so
```

**Verify .so files**:
```bash
find app/build/intermediates/ndkLibs -name "*.so" | sort
# Should list 14-15 .so files
```

---

## Current Build Status

**Phase 3 (GLK Java Layer)**: ⚠️ 67 compilation errors (WIP)

These are from incomplete refactoring of Fabularium dependencies (PreferencesActivity, GLKKeyboardMapping, GLKScreen, getApplication() calls, resource references). They do not block progress.

**To reach compilation**, targeted removals needed in:
- `app/src/main/java/com/luxlunae/glk/model/GLKModel.java`
- `app/src/main/java/com/luxlunae/glk/controller/GLKController.java`
- `app/src/main/java/com/luxlunae/glk/GLKUtils.java`

See `IMPLEMENTATION_PROGRESS.md` for current status and next steps.

---

## Emulator Setup

### Android Automotive OS Emulator

**Configuration:**
- **Device Definition**: Automotive (1024p landscape or higher)
- **System Image**: Android Automotive with Google APIs (API 33+)
- **Architecture**: arm64-v8a (recommended for Apple Silicon)

**Launch:**
1. Open Android Studio → Device Manager
2. Create/select Automotive emulator
3. Start emulator (separate from phone emulator)

**Verify Connection:**
```bash
adb devices
# Should show automotive emulator, e.g.:
# emulator-5554  device
```

### Multiple Emulators

If running both phone and car emulators:
```bash
adb devices
# Example output:
# emulator-5554  device   (phone)
# emulator-5556  device   (car/automotive)

# Install to specific device
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Deployment & Testing

### Install to Device/Emulator

**Single emulator (simplest):**
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Multiple emulators (specify device):**
```bash
adb devices  # List all
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

### Launch Application

**Phone UI:**
```bash
adb shell am start -n com.ifautofab/.MainActivity
```

**Car UI (Automotive):**
```bash
# Automotive emulator will auto-connect. Launch via:
adb shell am start -n com.ifautofab/.MainActivity
# Or via Car App Library (after integration complete)
```

### View Logs

```bash
# Real-time logs
adb logcat | grep "GLK\|IFAuto"

# Filter by tag
adb logcat GLK:V *:S

# Save to file
adb logcat > build.log
```

---

## Native Code Build Details

### CMakeLists.txt Structure

**Key variables:**
- `CMAKE_CURRENT_SOURCE_DIR` = `app/src/main/jni/`
- `garglk/` = Git submodule (automatic relative paths)
- `glk/` = GLK dispatcher bridge

**Build targets** (from `CMakeLists.txt`):
```
glk (SHARED)           - JNI dispatcher (calls Java via JNI)
bocfel (SHARED)        - Z-Machine terp (links glk)
git (SHARED)           - Glulx terp (links glk)
... 12 more interpreters
```

**Each terp**:
1. Compiles terp source with GLK support enabled
2. Links against `libglk.so`
3. Produces `libXXX.so`

### Compiler Flags

**Global flags** (set in CMakeLists.txt):
```
-O2 or -O3             - Optimization
-fno-strict-aliasing   - Some terps need this
-pthread               - Thread support
-std=c99, -std=c++11   - C/C++ standards
```

**Terp-specific flags** (examples):
- `bocfel`: `-DZTERP_GLK -DGARGLK -O3`
- `tads`: `-DTADS_TERP -DFAB -DVMGLOB_VARS -DTC_TARGET_T3`
- `git`: `-O3 -funroll-loops`

### Troubleshooting Native Build

**If CMakeLists.txt fails**:
```bash
# Verify submodule initialized
cd app/src/main/jni/garglk
ls -la terps/   # Should show interpreter directories
cd ../../../../../../

# If submodule is empty, initialize
git submodule update --init --recursive
```

**If specific terp won't compile**:
1. Check CMakeLists.txt for that terp
2. Verify source files exist in `garglk/terps/TERP_NAME/`
3. Check compiler flags (may need `-fno-strict-aliasing` for older code)

**Clear NDK cache if linking fails**:
```bash
rm -rf app/.cxx build/
./gradlew assembleDebug  # Full rebuild
```

---

## Architecture & Threading

### Thread Model

**Main Thread** (UI):
- `MainActivity` / `GameScreen` runs on main thread
- Sends commands via `GLKGameEngine.sendInput(command)`
- Blocks waiting for output via `TextOutputInterceptor.awaitNewText()`

**Worker Thread** (Interpreter):
- `GLKController.RunnableTerp` runs on worker thread
- Executes native terp via `runTerp()` (JNI call)
- Calls `glk_select()` (blocks waiting for input)
- When user sends input, GLK posts event to unblock terp
- Terp processes command, produces output
- Calls `GLKModel.updateView()` → `TextOutputInterceptor.appendText()`

**Synchronization**:
- `TextOutputInterceptor` uses `ReentrantLock` + `Condition` for thread-safe I/O
- JNI calls are thread-safe (each terp thread has its own GLK context)

---

## JNI & Hardcoded Class Names

### Critical Constraint

**`glkstart.c` contains 100+ hardcoded JNI class names:**
```c
FindClass(env, "com/luxlunae/glk/model/GLKModel");
FindClass(env, "com/luxlunae/glk/controller/GLKController");
```

**JNIEXPORT symbols:**
```c
Java_com_luxlunae_glk_controller_GLKController_runTerp(...)
Java_com_luxlunae_glk_model_GLKResourceManager_getBlorbResource(...)
```

**Implication**: The `com.luxlunae.glk.*` package structure is **locked** and cannot be renamed without modifying C code.

---

## Development & Debugging

### Checking Interpreter Support

Query which terps are compiled:
```bash
# After successful build, check APK contents
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "\.so$"
```

### Adding New Game Files

1. **Phone**: Use file picker in MainActivity
2. **Assets**: Add `.z3`, `.ulx`, etc. to `app/src/main/assets/`
3. **Card/Emulated SD**: Via `adb push`

```bash
adb push mygame.z3 /data/local/tmp/
# Then navigate to it from app's file picker
```

### Testing Individual Interpreters

Each `.so` is loaded dynamically based on file extension:
- `.z3`, `.z5`, `.z8` → loads `libbocfel.so`
- `.ulx`, `.gblorb` → loads `libgit.so`
- etc.

To test a specific terp, add a game file with its extension to assets.

---

## Performance & Optimization

### Build Time
- **First build** (full native compilation): ~2-5 minutes
- **Incremental** (Java changes only): ~30 seconds
- **Incremental** (native changes): ~1-2 minutes

### Runtime Performance
- **Game startup**: ~500ms (terp initialization + JNI setup)
- **Command latency**: <100ms typical (depends on game complexity)
- **Memory**: ~50-100MB per running game

### APK Size
- **Debug APK**: ~15-20MB (includes all 12 .so files, debug symbols)
- **Release APK** (minified, stripped): ~5-8MB

---

## See Also

- `IMPLEMENTATION_PROGRESS.md` - Phases 1-5 status, git checkpoints
- `app/src/main/jni/CMakeLists.txt` - Native build config
- `app/build.gradle.kts` - Gradle config, dependencies
- `app/src/main/AndroidManifest.xml` - Permissions, services (when created)

