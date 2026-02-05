# Kokoro TTS Integration Research

> **Date:** 2026-02-04
> **Purpose:** Research and planning for integrating Kokoro text-to-speech into IFAutoFab with cross-platform support (Android + macOS)

## Summary

**Recommendation:** Use **Sherpa-ONNX** framework with **Kokoro** model for cross-platform TTS support.

## Quick Comparison: Kokoro vs Piper

| Aspect | Kokoro | Piper |
|--------|--------|-------|
| **Voice Quality** | Higher rated, more natural | Good, but less expressive |
| **Model Size** | 82M parameters (~300MB, ~80MB quantized) | Generally smaller |
| **Speed** | Good for 82M params (super fast on M1/M2) | Faster due to smaller models |
| **Multi-language** | Chinese + English (up to 103 speakers) | Primarily English-focused |
| **Maturity** | Newer (2024-2025) | More established |
| **macOS Support** | ✅ Native, Apple Silicon optimized | ✅ macOS 10.9+, some install issues |
| **Android Support** | ✅ Via Sherpa-ONNX | ✅ Via Sherpa-ONNX |

## Cross-Platform Support

### macOS Support Details

**Kokoro TTS:**
- Native Python installation: `pip install kokoro-onnx`
- Optimized for Apple Silicon (M1/M2)
- Reported "super fast" on Mac by Reddit users
- WebGPU support in Safari (macOS 26)
- Model size: ~300MB (quantized: ~80MB)

**Piper TTS:**
- Python installation: `pip install piper-tts`
- macOS 10.9+ for x86-64 (Intel) and ARM (Apple Silicon)
- Version 1.3.0 (July 2025) improved compatibility
- Known installation issues documented in Issue #769

**Sherpa-ONNX (Recommended Framework):**
- Official support for macOS, Linux, Windows, Android, iOS, HarmonyOS
- Unified API across all platforms
- Works offline without internet connection
- Supports both Kokoro and Piper models
- Compatible with multiple CPU architectures: x86, ARM, RISC-V

## Implementation Architecture

```
┌─────────────────────────────────────────────────────────┐
│              IFAutoFab (Common Code)                    │
│         ┌───────────────────────────────────┐           │
│         │    TTSManager (interface)         │           │
│         └───────────────────────────────────┘           │
└─────────────────────────────────────────────────────────┘
         │                          │
         ▼                          ▼
┌──────────────────┐      ┌──────────────────┐
│   Android (Kotlin)│     │    macOS (Swift)  │
│  sherpa-onnx.aar  │      │  sherpa-onnx     │
└──────────────────┘      └──────────────────┘
         │                          │
         └──────────┬───────────────┘
                    ▼
        ┌───────────────────────┐
        │   Sherpa-ONNX Runtime │
        │  (ONNX Runtime shared)│
        └───────────────────────┘
                    │
                    ▼
        ┌───────────────────────┐
        │  Model Files (.onnx)  │
        │  - Kokoro OR Piper   │
        └───────────────────────┘
```

## Available Kokoro Models

| Model | Languages | Speakers | Description |
|-------|-----------|----------|-------------|
| `kokoro-multi-lang-v1_1` | Chinese + English | 103 speakers | Latest multi-language model |
| `kokoro-multi-lang-v1_0` | Chinese + English | 53 speakers | Earlier multi-language version |
| `kokoro-en-v0_19` | English | 11 speakers | English-only model |

## Why Sherpa-ONNX + Kokoro?

1. **Single codebase** - Same API works on Android and macOS
2. **Better voice quality** - Kokoro superior to Piper
3. **Cross-platform model** - Use same `.onnx` files on both platforms
4. **Apple Silicon optimized** - Kokoro runs fast on M1/M2
5. **Modern framework** - Active development, recent Kokoro 1.0 support
6. **Offline operation** - No network connection required

## Alternative: Piper TTS

**Choose Piper if:**
- Faster response time is critical
- Lower CPU/memory usage matters
- You need more voice variety (larger catalog)
- You prefer a more mature/stable solution

## Implementation Plan

### Phase 1: Android Integration

1. Add sherpa-onnx dependency to `app/build.gradle.kts`
2. Download Kokoro model files (place in `assets/`)
3. Create `SherpaTTSManager.kt` wrapper class
4. Replace existing `TTSManager.kt` (Android TTS) with new implementation
5. Test with game text output

### Phase 2: macOS Integration

1. Implement sherpa-onnx Swift bindings for macOS
2. Use same model files from Android
3. Create Swift equivalent of TTSManager
4. Test on Intel and Apple Silicon Macs

## Key Resources

### Sherpa-ONNX
- **GitHub:** https://github.com/k2-fsa/sherpa-onnx
- **Documentation:** https://k2-fsa.github.io/sherpa/onnx/index.html
- **Android Build Guide:** https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html
- **TTS Documentation:** https://k2-fsa.github.io/sherpa/onnx/tts/index.html

### Kokoro TTS
- **kokoro-onnx:** https://github.com/thewh1teagle/kokoro-onnx
- **Kokoro-82M-Android Demo:** https://github.com/puff-dayo/Kokoro-82M-Android
- **Pre-trained Models:** https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/index.html
- **Quality Analysis:** https://artificialanalysis.ai/text-to-speech/model-families/kokoro

### Piper TTS
- **GitHub:** https://github.com/rhasspy/piper
- **Documentation:** https://k2-fsa.github.io/sherpa/onnx/tts/piper.html
- **macOS Guide:** https://www.thoughtasylum.com/2025/08/25/text-to-speech-on-macos-with-piper/

### Other Implementations
- **SherpaOnnxTtsEngineAndroid:** https://github.com/jing332/SherpaOnnxTtsEngineAndroid

## Existing TTS Implementation

Current file: `app/src/main/java/com/ifautofab/TTSManager.kt`

Uses Android's built-in `TextToSpeech`:
- Simple, reliable, system voices
- Requires network for some voices
- Limited voice quality and customization
- Not cross-platform

## Current TTS Usage

Files using TTSManager:
- `app/src/main/java/com/ifautofab/GLKGameEngine.kt` (lines 24, 71, 118, 305, 317)
- Buffers text output with 200ms delay before speaking
- Filters out boilerplate text (commands, prompts)
- Stops speech on game shutdown

## Notes

- Model files will need to be included in app assets (adds ~80-300MB to APK)
- First-time synthesis may have latency as model loads
- Consider offering both Kokoro (quality) and Android TTS (fallback) options
- For car Android Auto, ensure model loading doesn't block UI thread
