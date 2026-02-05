# Kokoro TTS Storage Strategy for Android

## Overview

This document describes the storage and download strategy for integrating Kokoro ONNX TTS into IFAutoFab.

## Model Files

### Required Files
| File | Size (INT8) | Size (FP16) | Size (FP32) | Purpose |
|------|-------------|-------------|-------------|---------|
| `kokoro-v1.0.int8.onnx` | 88 MB | - | - | **Recommended** - 8-bit quantized model |
| `kokoro-v1.0.fp16.onnx` | - | 169 MB | - | 16-bit half precision |
| `kokoro-v1.0.onnx` | - | - | 310 MB | 32-bit full precision |
| `voices.bin` | 27 MB | 27 MB | 27 MB | Voice style vectors (26 voices) |

**Total storage: 115 MB (INT8 + voices)**

### Why INT8?
- **3.5x smaller** than FP32 (88 MB vs 310 MB)
- Faster inference (lower memory bandwidth)
- Minimal quality degradation for TTS use case
- Suitable for mobile devices

## Storage Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ Android App                                                     │
├─────────────────────────────────────────────────────────────────┤
│ APK Size: ~15-20 MB (no bundled models)                         │
├─────────────────────────────────────────────────────────────────┤
│ External Storage (no root required):                            │
│ └─ /data/data/com.ifautofab/files/tts/                         │
│    ├─ models/                                                   │
│    │  ├─ kokoro-v1.0.int8.onnx        (88 MB)                   │
│    │  └─ voices.bin                   (27 MB)                   │
│    ├─ .download_lock                 (download state)           │
│    └─ .model_version                 (version tracking)         │
├─────────────────────────────────────────────────────────────────┤
│ Cache (optional - for generated audio):                         │
│ └─ /data/data/com.ifautofab/cache/tts_audio/                    │
└─────────────────────────────────────────────────────────────────┘
```

## Download Strategy

### 1. Lazy Download (Recommended)

Download models on first TTS usage, not app install.

**Pros:**
- Fast app install
- Only download for users who want TTS
- Can show progress in UI

**Cons:**
- First TTS use has delay (~30-60 seconds on 4G, ~5-10 seconds on WiFi)

### 2. Download Flow

```
User enables TTS
       │
       ▼
Check if models exist
       │
       ├─ Yes ──> Load Kokoro ──> Start TTS
       │
       └─ No ──> Show download dialog
                      │
                      ▼
              User confirms download
                      │
                      ▼
            ┌─────────────────┐
            │  Download       │
            │  (88MB + 27MB)  │
            │  Progress: 45%  │
            │       [Cancel]  │
            └─────────────────┘
                      │
                      ├─ Success ──> Verify checksum ──> Load Kokoro
                      │
                      └─ Error ──> Show retry dialog
```

## Implementation Design

### File Locations

```kotlin
// Context.getFilesDir() - no permissions needed
private val TTS_DIR = "tts"
private val MODELS_DIR = "tts/models"
private val MODEL_FILE = "kokoro-v1.0.int8.onnx"
private val VOICES_FILE = "voices.bin"
private val VERSION_FILE = "tts/.model_version"
private val LOCK_FILE = "tts/.download_lock"

fun getModelDir(context: Context): File {
    return File(context.filesDir, MODELS_DIR).apply { mkdirs() }
}

fun getModelFile(context: Context): File {
    return File(getModelDir(context), MODEL_FILE)
}

fun getVoicesFile(context: Context): File {
    return File(getModelDir(context), VOICES_FILE)
}
```

### Download URLs

```kotlin
object KokoroModelUrls {
    private const val BASE_URL = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0"

    const val MODEL_INT8 = "$BASE_URL/kokoro-v1.0.int8.onnx"
    const val MODEL_FP16 = "$BASE_URL/kokoro-v1.0.fp16.onnx"
    const val MODEL_FP32 = "$BASE_URL/kokoro-v1.0.onnx"
    const val VOICES = "$BASE_URL/voices-v1.0.bin"

    // Current app version
    const val MODEL_VERSION = "v1.0"
}
```

### Download Manager

Use Android's `DownloadManager` for reliable downloads:

```kotlin
class KokoroModelDownloader(private val context: Context) {

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun startDownload(onProgress: (Int) -> Unit, onComplete: (Boolean) -> Unit): Long {
        // Download voices first (smaller, faster)
        val voicesRequest = DownloadManager.Request(Uri.parse(KokoroModelUrls.VOICES)).apply {
            setDestinationInFilesDir(getModelDir(context), VOICES_FILE)
            setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE)
            setTitle("Kokoro TTS Voices")
            setDescription("Downloading voice data (27 MB)")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        }

        // Then download model
        val modelRequest = DownloadManager.Request(Uri.parse(KokoroModelUrls.MODEL_INT8)).apply {
            setDestinationInFilesDir(getModelDir(context), MODEL_FILE)
            setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE)
            setTitle("Kokoro TTS Model")
            setDescription("Downloading speech model (88 MB)")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        }

        // Enqueue both downloads
        // Track progress via query()
        return downloadManager.enqueue(voicesRequest)
    }

    fun isDownloadComplete(): Boolean {
        return getModelFile(context).exists() && getVoicesFile(context).exists()
    }

    fun getModelSize(): Long {
        return getModelFile(context).length()
    }
}
```

### Model Version Check

```kotlin
fun isModelUpdated(context: Context): Boolean {
    val versionFile = File(context.filesDir, VERSION_FILE)
    if (!versionFile.exists()) return false

    val currentVersion = versionFile.readText()
    return currentVersion == KokoroModelUrls.MODEL_VERSION
}

fun updateModelVersion(context: Context) {
    val versionFile = File(context.filesDir, VERSION_FILE)
    versionFile.writeText(KokoroModelUrls.MODEL_VERSION)
}
```

## Manifest Permissions

No additional permissions needed! Using `context.filesDir` requires no runtime permissions.

```xml
<!-- Already in manifest - keep for voice input -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<!-- For downloads - uses DownloadManager which handles its own permissions -->
<!-- No storage permissions needed for filesDir -->
```

## Network Configuration

Add `network_security_config.xml` for GitHub downloads:

```xml
<!-- res/xml/network_security_config.xml -->
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security_config>
```

## User Experience

### Initial Download Dialog

```
┌─────────────────────────────────────────┐
│  Enable Text-to-Speech                  │
├─────────────────────────────────────────┤
│  TTS requires downloading 115 MB of     │
│  model data.                            │
│                                         │
│  • Download time: ~1 min on WiFi        │
│  • Can be used offline after download   │
│  • High quality voices                  │
│                                         │
│  [ Cancel ]    [ Download ]             │
└─────────────────────────────────────────┘
```

### Progress Notification

```
┌─────────────────────────────────────────┐
│  Downloading Kokoro TTS... 45%          │
│  Model: 40 MB / 88 MB                   │
│                                         │
│  Downloading...                         │
│  ████████████░░░░░░░░░░░░░              │
└─────────────────────────────────────────┘
```

### Settings Screen

```
┌─────────────────────────────────────────┐
│  Text-to-Speech Settings                │
├─────────────────────────────────────────┤
│  ✓ TTS Enabled                          │
│                                         │
│  Voice: af_heart (American Female)      │
│  Speed: 1.0x                            │
│                                         │
│  Model: v1.0 INT8                       │
│  Size: 115 MB                           │
│  Status: Ready                          │
│                                         │
│  [ Check for Updates ]                  │
│  [ Delete Models ] (115 MB)             │
└─────────────────────────────────────────┘
```

## Error Handling

| Scenario | Handling |
|----------|----------|
| **Network error** | Retry button in dialog, exponential backoff |
| **Insufficient storage** | Check free space before download, show clear storage dialog |
| **Download cancelled** | Clean up partial files, remove lock |
| **Corrupted download** | Verify SHA256 checksum, re-download |
| **App upgrade** | Check version file, re-download only if needed |
| **External storage unavailable** | Gracefully degrade to system TTS |

## Storage Management

### Check Free Space

```kotlin
fun hasEnoughSpace(context: Context, requiredBytes: Long): Boolean {
    val filesDir = context.filesDir
    return filesDir.usableSpace >= requiredBytes
}

fun getRequiredSpace(): Long {
    return 115 * 1024 * 1024L // 115 MB
}
```

### Delete Models

```kotlin
fun deleteModels(context: Context): Boolean {
    return try {
        getModelDir(context).deleteRecursively()
        File(context.filesDir, VERSION_FILE).delete()
        true
    } catch (e: Exception) {
        false
    }
}
```

## Migration Path from System TTS

```
┌─────────────────────────────────────────────────────────────────┐
│ Phase 1: Existing TTSManager (Android system TTS)               │
│ - Uses android.speech.tts.TextToSpeech                          │
│ - No download required                                          │
│ - Variable quality by device                                    │
├─────────────────────────────────────────────────────────────────┤
│ Phase 2: Add KokoroTTSManager (parallel implementation)         │
│ - Downloads Kokoro models on first use                          │
│ - Falls back to system TTS if download cancelled                │
│ - User preference: Settings > TTS Engine                        │
├─────────────────────────────────────────────────────────────────┤
│ Phase 3: Migration                                              │
│ - Prompt existing users to upgrade                              │
│ - Show quality comparison demo                                  │
│ - Make Kokoro default after opt-in                              │
└─────────────────────────────────────────────────────────────────┘
```

## Performance Considerations

### Model Loading
- **Cold start**: ~2-3 seconds to load 88 MB model from storage
- **Warm start**: <100ms (already in memory)
- **Inference speed**: ~50-100ms per sentence (INT8)

### Memory Usage
- **Model memory**: ~100-150 MB RAM when loaded
- **Audio buffer**: ~1-2 MB per sentence
- **Recommendation**: Unload model when TTS not in use for >5 minutes

### Battery Impact
- Minimal when model loaded (no continuous processing)
- Similar to playing audio files
- Much lower than cloud-based TTS (no network after download)

## Testing Checklist

- [ ] Download on WiFi
- [ ] Download on mobile data (with warning)
- [ ] Download interrupted by network loss
- [ ] Download interrupted by app background
- [ ] Resume download after app restart
- [ ] Insufficient storage
- [ ] Model corrupted (checksum validation)
- [ ] Model version upgrade
- [ ] Delete and re-download
- [ ] TTS works offline after download
- [ ] Fallback to system TTS

## Alternative: Asset Bundling

For devices without reliable internet, consider bundling models in APK:

**Pros:**
- Works offline immediately
- No download delay

**Cons:**
- APK size increases by 115 MB
- Google Play warns about large APKs
- Users with data caps can't install easily
- Longer app install time

**Recommendation**: Use lazy download, but offer "offline bundle" as separate APK variant for enterprise/car deployments.

## Future Enhancements

1. **Model variants**: Allow users to download FP16/FP32 if they want higher quality
2. **Voice packs**: Download additional voices on-demand
3. **Model updates**: Automatic checking for new Kokoro versions
4. **Compression**: Use gzip compression for download (model may compress to ~70 MB)
5. **Incremental downloads**: Split model into chunks for better resume support

## References

- [Kokoro ONNX GitHub](https://github.com/thewh1teagle/kokoro-onnx)
- [Kokoro ONNX Releases](https://github.com/thewh1teagle/kokoro-onnx/releases)
- [Android DownloadManager](https://developer.android.com/reference/android/app/DownloadManager)
- [Android Storage Options](https://developer.android.com/training/data-storage)

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2025-02-04 | Initial design for Kokoro v1.0 INT8 integration |
