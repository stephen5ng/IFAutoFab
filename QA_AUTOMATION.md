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
registerReceiver(debugReceiver, IntentFilter("com.ifautofab.DEBUG_INPUT"), Context.RECEIVER_EXPORTED)
```

`RECEIVER_EXPORTED` is required on API 34+. Without it the app crashes on launch with a `SecurityException`.

Send commands from ADB with no keyboard involved:
```bash
# Single-word commands: quotes don't matter
adb shell am broadcast -a com.ifautofab.DEBUG_INPUT -e command "look"

# Multi-word commands: wrap the entire am invocation in single quotes,
# otherwise adb shell splits the value and interprets the extra words
# as flags (e.g. "open mailbox" becomes pkg=mailbox in the intent).
adb shell 'am broadcast -a com.ifautofab.DEBUG_INPUT -e command "open mailbox"'
```

This completely bypasses the on-screen keyboard and its autocomplete.

---

## Logcat Output Structure

Each call to `TextOutputInterceptor.appendText()` produces one logcat line. The interpreter doesn't flush a whole turn at once — it outputs line by line as it renders. A typical turn in logcat looks like:

```
D GameOutput: >
D GameOutput: open mailbox
D GameOutput: Opening the small mailbox reveals a leaflet.
D GameOutput:
D GameOutput: >
```

Two things worth noting for scripting:

- **The `>` prompt is a reliable "ready for input" signal.** It arrives as its own logcat line after every response. An automation loop can wait for it before sending the next command instead of using a fixed `sleep`.
- **All lines within a turn share the same timestamp.** The interpreter flushes its output buffer in one go after processing a command, so logcat timestamps can be used to group lines into turns if needed.

---

## Automation Loop Pattern

With both changes in place, a full turn looks like this:

```bash
#!/bin/bash
# qa_turn.sh — send a command and capture the response

CMD="$1"
if [ -z "$CMD" ]; then echo "Usage: $0 <command>"; exit 1; fi

# Send command. Single quotes around the am invocation are required
# for multi-word commands — see "Accept commands via broadcast intent" above.
adb shell 'am broadcast -a com.ifautofab.DEBUG_INPUT -e command "'"$CMD"'"'

# Wait for interpreter to respond
sleep 2

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
adb shell 'am broadcast -a com.ifautofab.DEBUG_INPUT -e command "open mailbox"'
sleep 2; grep -A5 "open mailbox" session.log

adb shell 'am broadcast -a com.ifautofab.DEBUG_INPUT -e command "northeast"'
sleep 2; grep -A5 "northeast" session.log

# ... etc

kill $LOGCAT_PID
```

---

## When to Use UIAutomator XML

UIAutomator dump is still useful for verifying UI state rather than reading game output:
- Checking that specific buttons are present/enabled
- Confirming layout after a state change (e.g., did the game selection screen appear?)
- Asserting the input field is cleared after SEND
- Grabbing the **full** output text when you need the entire transcript (e.g. to verify autosave restore output that has scrolled off screen)

```bash
adb shell uiautomator dump /sdcard/ui.xml

# Button presence check
grep 'resource-id="com.ifautofab:id/sendButton"' /sdcard/ui.xml

# Full game transcript — the text attribute is on the outputText node
# which is a child of the scrollView node. It uses &#10; for newlines.
grep -o 'scrollView[^<]*<[^<]*' /sdcard/ui.xml   # scrollView + first child
# The text="..." attribute contains the full transcript, &#10;-delimited.
```

Don't use it as the primary output channel — logcat is faster, streaming, and doesn't require parsing a bloated XML tree.

---

## Bugs Found (Zork 1 session, 2026-02-01)

### 1. Autosave restore prints "restore" twice — FIXED
When a game loads with an existing autosave, output showed:
```
[Restoring autosave...]
restore
restore
Ok.
```
`GLKGameEngine.sendInput("restore")` echoed the command via `window.putString(input + "\n")`, but bocfel also echoes line input as part of standard GLK processing. Double echo.

**Fix:** Removed the manual echo (`window.putString`) from `sendInput()`. Bocfel echoes input itself after processing the line event. Verified via both logcat and UIAutomator full-text dump — autosave restore now shows a single `restore`.

### 2. First command after autorestore echoes twice — FIXED
Same root cause as #1. The first real command after restore showed:
```
>open mailbox
open mailbox
Opening the small mailbox reveals a leaflet.
```

**Fix:** Same as #1 — removing the manual echo resolved both. Verified with a 6-turn session; every command echoes exactly once.

### 3. On-screen keyboard takes ~50% of display — OPEN
The Google keyboard consumes roughly half the automotive display while typing. Only 2–3 lines of game output remain visible. For a text adventure this is severe. The debug broadcast intent approach (section above) sidesteps this entirely for automated testing. For interactive use, the keyboard should dismiss after SEND, or the TYPE input should use a smaller overlay.
