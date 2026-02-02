# QA Automation Notes

Practical notes for automating game testing on the Automotive emulator. Covers what works, what doesn't, and the recommended setup for a scriptable QA loop.

---

## Current State: What Works, What Doesn't

### Sending commands

**Broken:** `adb shell input text "open mailbox"`
The Google keyboard's autocomplete intercepts spaces as word-completion triggers. Multi-word commands get mangled (`"open mailbox"` → `"openl"`). Single words work fine.

**Works (slow workaround):** individual `KEYCODE_*` events per character:
```bash
adb shell input keyevent KEYCODE_O KEYCODE_P KEYCODE_E KEYCODE_N KEYCODE_SPACE \
    KEYCODE_M KEYCODE_A KEYCODE_I KEYCODE_L KEYCODE_B KEYCODE_O KEYCODE_X
sleep 0.3
adb shell input tap 1348 268   # SEND button
```
This bypasses autocomplete but requires a separate `adb shell` round-trip per command. Tedious and slow.

### Reading output

**Broken (expensive):** screencap → parse image. Eats tokens and adds latency.

**Works:** `adb shell uiautomator dump` gives exact text in the `outputText` node:
```bash
adb shell uiautomator dump /sdcard/ui.xml
# Text is in: <node resource-id="com.ifautofab:id/outputText" text="...">
```
But the dump includes the full UI tree (keyboard nodes and all — 7.5KB+ per call) and has ~1s latency. Fine for one-off verification, not for a tight loop.

---

## Recommended Setup: Two Small Code Changes

Both the input and output problems go away with minimal additions to the app.

### 1. Log game output to logcat

Add one line to `TextOutputInterceptor.kt` in `appendText()`:

```kotlin
// TextOutputInterceptor.kt, line 35
fun appendText(text: String) {
    if (text.isEmpty()) return
    Log.d("GameOutput", text)          // <-- add this
    synchronized(fullOutput) {
        fullOutput.append(text)
    }
    ...
}
```

(Requires `import android.util.Log` at the top.)

This gives you a real-time, streaming output channel:
```bash
# Stream game output as it arrives
adb logcat -s GameOutput:D

# Capture to file for a full session
adb logcat -s GameOutput:D > session.log

# Watch only new output (tail-style)
adb logcat -s GameOutput:D -c   # clear first
adb logcat -s GameOutput:D      # then stream
```

### 2. Accept commands via broadcast intent

Add a BroadcastReceiver (or handle in `MainActivity.onCreate`):

```kotlin
// In MainActivity.kt, inside onCreate after GLKGameEngine is running:
val debugReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra("command") ?: return
        GLKGameEngine.sendInput(command)
    }
}
registerReceiver(debugReceiver, IntentFilter("com.ifautofab.DEBUG_INPUT"))
```

Send commands from ADB with no keyboard involved:
```bash
adb shell am broadcast -a com.ifautofab.DEBUG_INPUT -e command "open mailbox"
```

This completely bypasses the on-screen keyboard and its autocomplete. Works with spaces, punctuation, anything.

---

## Automation Loop Pattern

With both changes in place, a full turn looks like this:

```bash
#!/bin/bash
# qa_turn.sh — send a command and capture the response

CMD="$1"
if [ -z "$CMD" ]; then echo "Usage: $0 <command>"; exit 1; fi

# Send command
adb shell am broadcast -a com.ifautofab.DEBUG_INPUT -e command "$CMD"

# Wait for interpreter to respond
sleep 1

# Grab output (last N lines of GameOutput from logcat)
# Assumes logcat is already streaming to session.log in background
tail -20 session.log
```

Full session example:
```bash
# Terminal 1: stream output
adb logcat -s GameOutput:D -c
adb logcat -s GameOutput:D > session.log &
LOGCAT_PID=$!

# Terminal 2 (or same script): run turns
adb shell am broadcast -a com.ifautofab.DEBUG_INPUT -e command "open mailbox"
sleep 1; grep -A5 "open mailbox" session.log

adb shell am broadcast -a com.ifautofab.DEBUG_INPUT -e command "northeast"
sleep 1; grep -A5 "northeast" session.log

# ... etc

kill $LOGCAT_PID
```

---

## When to Use UIAutomator XML

UIAutomator dump is still useful for verifying UI state rather than reading game output:
- Checking that specific buttons are present/enabled
- Confirming layout after a state change (e.g., did the game selection screen appear?)
- Asserting the input field is cleared after SEND

```bash
adb shell uiautomator dump /sdcard/ui.xml
# Then parse with grep or xmllint
grep 'resource-id="com.ifautofab:id/sendButton"' /sdcard/ui.xml
```

Don't use it as the primary output channel — logcat is faster, streaming, and doesn't require parsing a bloated XML tree.

---

## Bugs Found (Zork 1 session, 2026-02-01)

### 1. Autosave restore prints "restore" twice
When a game loads with an existing autosave, output shows:
```
[Restoring autosave...]
restore
restore
Ok.
```
`GLKGameEngine.sendInput("restore")` (line 89) echoes the command via `window.putString(input + "\n")` (line 123 of `sendInput`). But bocfel also echoes line input as part of standard GLK `glk_char_input_event` processing. Double echo.

### 2. First command after autorestore echoes twice
Same root cause as #1. The first real command after restore showed:
```
>open mailbox
open mailbox
Opening the small mailbox reveals a leaflet.
```
Subsequent commands echoed only once. The manual echo in `sendInput` (line 123) conflicts with bocfel's own echo. The autorestore state seems to leave the interpreter's echo armed for one extra turn.

**Fix candidate:** Remove the manual echo in `GLKGameEngine.sendInput()` (line 123: `window.putString(input + "\n")`). Bocfel already echoes input. If other interpreters don't echo, this should be conditional on the interpreter, not unconditional.

### 3. On-screen keyboard takes ~50% of display
The Google keyboard consumes roughly half the automotive display while typing. Only 2–3 lines of game output remain visible. For a text adventure this is severe. The debug broadcast intent approach (section above) sidesteps this entirely for automated testing. For interactive use, the keyboard should dismiss after SEND, or the TYPE input should use a smaller overlay.
