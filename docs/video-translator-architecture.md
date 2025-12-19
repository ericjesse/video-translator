# Video Translator — Architecture Overview

**Repository:** github.com/ericjesse/video-translator  
**License:** Apache 2.0  
**Version:** Draft 1.0

---

## 1. Project Summary

A cross-platform desktop application that downloads YouTube videos, extracts or generates transcripts, translates them, and produces output videos with embedded subtitles. Built with Kotlin and Compose Multiplatform, natively compiled for macOS, Linux, and Windows.

---

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              VIDEO TRANSLATOR                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        PRESENTATION LAYER                            │   │
│  │                   (Compose Multiplatform UI)                         │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │   │
│  │  │  Setup   │ │   Main   │ │ Progress │ │ Settings │ │  About   │  │   │
│  │  │  Wizard  │ │  Screen  │ │  Screen  │ │  Screen  │ │  Screen  │  │   │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                        │
│                                    ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        APPLICATION LAYER                             │   │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐   │   │
│  │  │  Pipeline   │ │   Update    │ │   Config    │ │    i18n     │   │   │
│  │  │ Orchestrator│ │   Manager   │ │   Manager   │ │   Manager   │   │   │
│  │  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                        │
│                                    ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                          DOMAIN LAYER                                │   │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐   │   │
│  │  │   Video     │ │ Transcriber │ │ Translator  │ │  Subtitle   │   │   │
│  │  │  Downloader │ │   Service   │ │   Service   │ │  Renderer   │   │   │
│  │  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                        │
│                                    ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                      INFRASTRUCTURE LAYER                            │   │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐   │   │
│  │  │   yt-dlp    │ │   FFmpeg    │ │  Whisper    │ │    HTTP     │   │   │
│  │  │   Wrapper   │ │   Wrapper   │ │   Wrapper   │ │   Client    │   │   │
│  │  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                        │
└────────────────────────────────────┼────────────────────────────────────────┘
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         EXTERNAL DEPENDENCIES                                │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────────────┐   │
│  │   yt-dlp    │ │   FFmpeg    │ │   Whisper   │ │ Translation APIs    │   │
│  │  (binary)   │ │  (binary)   │ │  (models)   │ │ (LibreTranslate,    │   │
│  │             │ │             │ │             │ │  DeepL, OpenAI...)  │   │
│  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Processing Pipeline

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                            TRANSLATION PIPELINE                               │
└──────────────────────────────────────────────────────────────────────────────┘

     ┌─────────┐
     │ YouTube │
     │   URL   │
     └────┬────┘
          │
          ▼
┌─────────────────┐
│  1. DOWNLOAD    │──────────────────────────────────────┐
│    (yt-dlp)     │                                      │
└────────┬────────┘                                      │
         │                                               │
         ▼                                               ▼
┌─────────────────┐                            ┌─────────────────┐
│  Video + Audio  │                            │ YouTube Captions│
│      File       │                            │   (if exist)    │
└────────┬────────┘                            └────────┬────────┘
         │                                              │
         │                                              ▼
         │                                     ┌─────────────────┐
         │                                     │ Captions Found? │
         │                                     └────────┬────────┘
         │                                              │
         │                              ┌───────────────┴───────────────┐
         │                              │ YES                       NO  │
         │                              ▼                               ▼
         │                     ┌─────────────────┐            ┌─────────────────┐
         │                     │  Parse Caption  │            │  2. TRANSCRIBE  │
         │                     │     Format      │            │    (Whisper)    │
         │                     └────────┬────────┘            └────────┬────────┘
         │                              │                              │
         │                              └──────────────┬───────────────┘
         │                                             │
         │                                             ▼
         │                                    ┌─────────────────┐
         │                                    │   Source Text   │
         │                                    │  + Timestamps   │
         │                                    └────────┬────────┘
         │                                             │
         │                                             ▼
         │                                    ┌─────────────────┐
         │                                    │  3. TRANSLATE   │
         │                                    │ (LibreTranslate │
         │                                    │   or others)    │
         │                                    └────────┬────────┘
         │                                             │
         │                                             ▼
         │                                    ┌─────────────────┐
         │                                    │ Translated Text │
         │                                    │  + Timestamps   │
         │                                    └────────┬────────┘
         │                                             │
         └──────────────────────┬──────────────────────┘
                                │
                                ▼
                       ┌─────────────────┐
                       │  4. RENDER      │
                       │   (FFmpeg)      │
                       └────────┬────────┘
                                │
               ┌────────────────┼────────────────┐
               ▼                ▼                ▼
      ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
      │  Soft Subs  │  │ Burned-in   │  │  SRT/VTT    │
      │   (.mkv)    │  │   Subs      │  │   Export    │
      └─────────────┘  └─────────────┘  └─────────────┘
```

---

## 4. Component Details

### 4.1 Presentation Layer

| Screen | Purpose |
|--------|---------|
| **Setup Wizard** | First-run experience: language selection, dependency download (yt-dlp, FFmpeg, Whisper model), translation service configuration |
| **Main Screen** | URL input, source/target language selection, output options (soft/burned-in subs, background color/transparency), start button |
| **Progress Screen** | Real-time pipeline progress with stage indicators, logs, cancel option |
| **Settings Screen** | Whisper model selection, translation service config, subtitle styling, update preferences, dependency management |
| **About Screen** | Version info, licenses, links to documentation |

### 4.2 Application Layer

| Component | Responsibility |
|-----------|----------------|
| **Pipeline Orchestrator** | Coordinates the download → transcribe → translate → render flow; manages cancellation and error recovery |
| **Update Manager** | Checks GitHub releases for app updates; manages yt-dlp/FFmpeg updates; handles download and installation |
| **Config Manager** | Reads/writes settings using OS-appropriate paths; validates configuration |
| **i18n Manager** | Loads locale-specific strings; detects system language; provides translation functions |

### 4.3 Domain Layer

| Service | Responsibility |
|---------|----------------|
| **Video Downloader** | Wraps yt-dlp; downloads video+audio; extracts available captions |
| **Transcriber Service** | Wraps Whisper; manages model loading; generates timestamped transcription |
| **Translator Service** | Abstracts translation backends; handles rate limiting and retries; preserves timestamps |
| **Subtitle Renderer** | Generates SRT/VTT files; invokes FFmpeg for burned-in rendering with styling |

### 4.4 Infrastructure Layer

| Wrapper | External Tool | Purpose |
|---------|---------------|---------|
| **yt-dlp Wrapper** | yt-dlp binary | YouTube video/audio download, caption extraction |
| **FFmpeg Wrapper** | FFmpeg binary | Video processing, subtitle embedding/burning |
| **Whisper Wrapper** | whisper.cpp | Speech-to-text transcription |
| **HTTP Client** | Ktor Client | REST calls to translation APIs |

---

## 5. Technology Stack

### 5.1 Core Technologies

| Layer | Technology | Rationale |
|-------|------------|-----------|
| **Language** | Kotlin 2.x | Modern, expressive, multiplatform support |
| **UI Framework** | Compose Multiplatform | Native-feeling UI across all platforms |
| **Native Compilation** | GraalVM Native Image | Fast startup, small memory footprint, no JVM required at runtime |
| **Build System** | Gradle (Kotlin DSL) | Standard for Kotlin projects |

### 5.2 Libraries

| Purpose | Library | Notes |
|---------|---------|-------|
| HTTP Client | Ktor Client | Multiplatform, coroutine-based |
| JSON Parsing | kotlinx.serialization | Native-compatible, type-safe |
| Coroutines | kotlinx.coroutines | Async pipeline execution |
| Process Execution | Custom wrapper | For yt-dlp/FFmpeg invocation |
| Whisper Bindings | whisper.cpp via JNI/Panama | Local STT; see section 6.3 |

### 5.3 External Binaries (Managed)

| Tool | Purpose | Update Source |
|------|---------|---------------|
| **yt-dlp** | YouTube download | github.com/yt-dlp/yt-dlp |
| **FFmpeg** | Video processing | ffmpeg.org or evermeet.cx (macOS) |

---

## 6. External Dependencies Detail

### 6.1 yt-dlp

**Download locations by platform:**
- Windows: `yt-dlp.exe` from GitHub releases
- macOS: `yt-dlp_macos` from GitHub releases  
- Linux: `yt-dlp_linux` from GitHub releases

**Update strategy:** Check GitHub API for latest release, compare with stored version, download if newer.

### 6.2 FFmpeg

**Download locations by platform:**
- Windows: gyan.dev static builds or BtbN GitHub releases
- macOS: evermeet.cx static builds
- Linux: johnvansickle.com static builds

**Update strategy:** Store version metadata, periodically check for newer builds.

### 6.3 Whisper (Speech-to-Text)

**Implementation approach:** Use **whisper.cpp** — a highly optimized C++ port of OpenAI Whisper.

**Integration options (to be decided during implementation):**
1. **JNI bindings** — Compile whisper.cpp as shared library, call via JNI
2. **CLI wrapper** — Bundle whisper.cpp `main` binary, invoke via process
3. **Java Foreign Function API (Panama)** — Modern JNI alternative (requires JDK 22+)

**Recommended:** Option 2 (CLI wrapper) for simplicity and easier updates.

**Model sizes:**

| Model | Parameters | Disk Size | Required VRAM | Relative Speed |
|-------|------------|-----------|---------------|----------------|
| tiny | 39M | ~75 MB | ~1 GB | ~32x |
| base | 74M | ~142 MB | ~1 GB | ~16x |
| small | 244M | ~466 MB | ~2 GB | ~6x |
| medium | 769M | ~1.5 GB | ~5 GB | ~2x |
| large | 1550M | ~2.9 GB | ~10 GB | 1x |

**Default:** `base` (142 MB) — good balance of quality and resource usage.

**Download source:** huggingface.co/ggerganov/whisper.cpp

### 6.4 Translation Services

| Service | API Key Required | Free Tier | Documentation |
|---------|------------------|-----------|---------------|
| **LibreTranslate** (default) | No (public instances) | Unlimited (rate-limited) | libretranslate.com/docs |
| **DeepL** | Yes | 500K chars/month | developers.deepl.com/docs |
| **OpenAI** | Yes | Pay-per-use | platform.openai.com/docs |
| **Google Translate** | Yes | $20 free credit | cloud.google.com/translate/docs |

---

## 7. Configuration Management

### 7.1 Settings Storage Paths

Following OS conventions:

| OS | Config Directory | Example |
|----|------------------|---------|
| **Windows** | `%APPDATA%\VideoTranslator\` | `C:\Users\<user>\AppData\Roaming\VideoTranslator\` |
| **macOS** | `~/Library/Application Support/VideoTranslator/` | `/Users/<user>/Library/Application Support/VideoTranslator/` |
| **Linux** | `$XDG_CONFIG_HOME/video-translator/` or `~/.config/video-translator/` | `/home/<user>/.config/video-translator/` |

### 7.2 Data Storage Paths (Binaries, Models, Cache)

| OS | Data Directory |
|----|----------------|
| **Windows** | `%LOCALAPPDATA%\VideoTranslator\` |
| **macOS** | `~/Library/Application Support/VideoTranslator/` |
| **Linux** | `$XDG_DATA_HOME/video-translator/` or `~/.local/share/video-translator/` |

### 7.3 Configuration File Structure

```
config/
├── settings.json          # User preferences
├── services.json          # Translation service configs (API keys encrypted)
└── state.json             # Window position, last used settings

data/
├── bin/
│   ├── yt-dlp[.exe]       # Downloaded binary
│   ├── ffmpeg[.exe]       # Downloaded binary
│   └── whisper[.exe]      # Downloaded binary (if CLI approach)
├── models/
│   └── whisper/
│       └── ggml-base.bin  # Downloaded Whisper model
├── cache/
│   └── downloads/         # Temporary video downloads
└── versions.json          # Tracks installed dependency versions
```

### 7.4 Settings Schema (settings.json)

```json
{
  "version": 1,
  "language": "en",
  "transcription": {
    "whisperModel": "base",
    "preferYouTubeCaptions": true
  },
  "translation": {
    "defaultService": "libretranslate",
    "defaultSourceLanguage": "auto",
    "defaultTargetLanguage": "en"
  },
  "subtitle": {
    "defaultOutputMode": "soft",
    "burnedIn": {
      "fontFamily": "Arial",
      "fontSize": 24,
      "fontColor": "#FFFFFF",
      "backgroundColor": "transparent",
      "backgroundOpacity": 0.0,
      "position": "bottom"
    }
  },
  "updates": {
    "checkAutomatically": true,
    "checkIntervalDays": 7,
    "autoUpdateDependencies": false
  },
  "resources": {
    "maxMemoryMB": 4096,
    "maxMemoryPercent": 60
  }
}
```

---

## 8. Memory Management

**Constraints:**
- Maximum 4 GB
- Maximum 60% of available system RAM

**Implementation:**
```kotlin
val maxMemory = minOf(
    4096L * 1024 * 1024,  // 4 GB hard limit
    (Runtime.getRuntime().maxMemory() * 0.6).toLong()  // 60% of available
)
```

**Strategy:**
- Stream video downloads to disk (don't buffer in memory)
- Process Whisper transcription in chunks
- Release resources between pipeline stages
- Monitor memory usage, warn user if approaching limits

---

## 9. Update System

### 9.1 Application Updates

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Check GitHub   │────▶│  Compare with   │────▶│  Notify User    │
│  Releases API   │     │  Current Ver    │     │  (if newer)     │
└─────────────────┘     └─────────────────┘     └────────┬────────┘
                                                         │
                                               ┌─────────▼─────────┐
                                               │  User Accepts?    │
                                               └─────────┬─────────┘
                                                         │
                                    ┌────────────────────┼────────────────────┐
                                    │ YES                                  NO │
                                    ▼                                         ▼
                           ┌─────────────────┐                       ┌─────────────────┐
                           │ Download New    │                       │ Remind Later    │
                           │ Version         │                       │ (or Never)      │
                           └────────┬────────┘                       └─────────────────┘
                                    │
                                    ▼
                           ┌─────────────────┐
                           │ Verify Checksum │
                           │ & Install       │
                           └────────┬────────┘
                                    │
                                    ▼
                           ┌─────────────────┐
                           │ Restart App     │
                           └─────────────────┘
```

**GitHub API endpoint:** `https://api.github.com/repos/ericjesse/video-translator/releases/latest`

### 9.2 Dependency Updates

Same flow for yt-dlp, FFmpeg, and Whisper models:
- Store current version in `data/versions.json`
- Check upstream for latest version
- Download to temporary location
- Verify integrity (checksum when available)
- Atomic replacement of old binary
- Update version metadata

---

## 10. Internationalization (i18n)

### 10.1 Supported Languages (Initial)

| Code | Language |
|------|----------|
| `en` | English |
| `de` | German (Deutsch) |
| `fr` | French (Français) |

### 10.2 Implementation

**Resource location:** `resources/i18n/`

```
i18n/
├── messages_en.properties
├── messages_de.properties
└── messages_fr.properties
```

**Detection priority:**
1. User setting (if explicitly set)
2. System locale
3. Fallback to English

---

## 11. UI Wireframes (Conceptual)

### 11.1 Main Screen

```
┌─────────────────────────────────────────────────────────────────┐
│  Video Translator                                    [─] [□] [×] │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  YouTube URL:                                                    │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ https://youtube.com/watch?v=...                            │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌─────────────────────────┐  ┌─────────────────────────────┐   │
│  │ Source Language         │  │ Target Language             │   │
│  │ [Auto-detect      ▼]    │  │ [English           ▼]       │   │
│  └─────────────────────────┘  └─────────────────────────────┘   │
│                                                                  │
│  Output Options:                                                 │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ ○ Soft subtitles (embedded, toggleable)                     ││
│  │ ● Burned-in subtitles                                       ││
│  │   └─ Background: [None ▼]  Opacity: [░░░░░░░░░░] 0%         ││
│  │ ☐ Also export SRT file                                      ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                  │
│  Output folder: ~/Videos/Translated                    [Browse]  │
│                                                                  │
│                              ┌─────────────────┐                 │
│                              │   Translate     │                 │
│                              └─────────────────┘                 │
│                                                                  │
├─────────────────────────────────────────────────────────────────┤
│  ⚙ Settings                                          v1.0.0     │
└─────────────────────────────────────────────────────────────────┘
```

### 11.2 Progress Screen

```
┌─────────────────────────────────────────────────────────────────┐
│  Video Translator                                    [─] [□] [×] │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Processing: "Video Title Here"                                  │
│                                                                  │
│  ✓ Downloading video...                              Complete    │
│  ✓ Extracting captions...                            Not found   │
│  ◉ Transcribing audio...                             43%         │
│    ┌──────────────────████████████░░░░░░░░░░░░░░────────────┐   │
│    └────────────────────────────────────────────────────────┘   │
│  ○ Translating text...                               Pending     │
│  ○ Rendering subtitles...                            Pending     │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ [Whisper] Processing segment 12/28...                       ││
│  │ [Whisper] Detected language: English (confidence: 0.94)     ││
│  │ ...                                                         ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                  │
│                              ┌─────────────────┐                 │
│                              │     Cancel      │                 │
│                              └─────────────────┘                 │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 12. Build & Distribution

### 12.1 Build Pipeline

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Compile   │────▶│   Native    │────▶│   Package   │
│   Kotlin    │     │   Image     │     │  Installer  │
└─────────────┘     └─────────────┘     └─────────────┘
                          │
              ┌───────────┼───────────┐
              ▼           ▼           ▼
         ┌────────┐  ┌────────┐  ┌────────┐
         │ macOS  │  │ Linux  │  │Windows │
         │ (arm64 │  │(x86_64)│  │(x86_64)│
         │ x86_64)│  │        │  │        │
         └────────┘  └────────┘  └────────┘
```

### 12.2 Artifacts per Platform

| Platform | Artifact Type | Contents |
|----------|---------------|----------|
| **Windows** | `.msi` or `.exe` installer | Native executable, app icon, uninstaller |
| **macOS** | `.dmg` with `.app` bundle | Native executable (unsigned) |
| **Linux** | `.AppImage` + `.deb` + `.rpm` | Native executable, desktop entry |

### 12.3 Unsigned Application Handling

Since the application will not be code-signed, users will encounter OS security warnings. The README and documentation must include clear instructions:

**Windows (SmartScreen):**
```
When you see "Windows protected your PC":
1. Click "More info"
2. Click "Run anyway"
```

**macOS (Gatekeeper):**
```
If you see "app can't be opened because it is from an unidentified developer":
1. Open System Settings → Privacy & Security
2. Scroll to "Security" section
3. Click "Open Anyway" next to the Video Translator message
   
Or via Terminal: xattr -d com.apple.quarantine /Applications/VideoTranslator.app
```

**Linux:** No special handling required for most distributions.

### 12.4 CI/CD (GitHub Actions)

```yaml
# Triggers on:
# - Push to main (build + test)
# - Tag v*.*.* (build + release)

jobs:
  build-windows:
    runs-on: windows-latest
    # Build native image + create installer
    
  build-macos:
    runs-on: macos-latest
    # Build for both arm64 and x86_64
    
  build-linux:
    runs-on: ubuntu-latest
    # Build native image + create packages
    
  release:
    needs: [build-windows, build-macos, build-linux]
    # Upload all artifacts to GitHub Release
```

---

## 13. Project Structure

```
video-translator/
├── .github/
│   └── workflows/
│       └── build.yml
├── gradle/
│   └── wrapper/
├── src/
│   ├── commonMain/
│   │   └── kotlin/
│   │       └── com/ericjesse/videotranslator/
│   │           ├── Application.kt
│   │           ├── di/                    # Dependency injection
│   │           ├── domain/
│   │           │   ├── model/             # Data classes
│   │           │   ├── service/           # Business logic interfaces
│   │           │   └── pipeline/          # Orchestration
│   │           ├── infrastructure/
│   │           │   ├── config/            # Settings management
│   │           │   ├── process/           # External process execution
│   │           │   ├── translation/       # Translation service impls
│   │           │   ├── transcription/     # Whisper wrapper
│   │           │   └── update/            # Update manager
│   │           └── ui/
│   │               ├── components/        # Reusable UI components
│   │               ├── screens/           # Screen composables
│   │               ├── theme/             # Colors, typography
│   │               └── i18n/              # Internationalization
│   ├── jvmMain/                           # JVM-specific code (if needed)
│   ├── desktopMain/                       # Desktop-specific code
│   └── commonTest/
├── resources/
│   └── i18n/
│       ├── messages_en.properties
│       ├── messages_de.properties
│       └── messages_fr.properties
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── LICENSE                                # Apache 2.0
└── README.md
```

---

## 14. Risk Assessment & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| YouTube blocks yt-dlp | High | Monitor yt-dlp releases; quick dependency update mechanism |
| LibreTranslate public instances unreliable | Medium | Allow multiple instance URLs; prompt user to configure alternatives |
| Whisper model download fails | Medium | Retry logic; alternative download mirrors; clear error messages |
| GraalVM native image compatibility issues | Medium | Extensive testing; fallback to JVM distribution if needed |
| FFmpeg licensing concerns | Low | Use LGPL builds; document licensing clearly |
| Memory limits exceeded with large videos | Medium | Streaming processing; warn user before processing long videos |

---

## 15. Design Decisions (Resolved)

| Question | Decision | Implications |
|----------|----------|--------------|
| **Whisper integration** | CLI wrapper | Simpler implementation, easier updates; slight process overhead acceptable for v1.0. Consider JNI optimization in future versions. |
| **Code signing** | No certificates | Users will see OS security warnings on first launch. Documentation will include instructions to bypass (macOS Gatekeeper, Windows SmartScreen). |
| **Telemetry** | None | No usage tracking. Simplifies privacy policy and GDPR compliance. |
| **Batch processing** | Single video (v1.0) | Queue/batch processing deferred to v1.1+. Keeps UI and pipeline simpler. |
| **Subtitle editing** | Out of scope (v1.0) | No manual review/edit step before rendering. Consider for v1.1+ as optional workflow step. |

---

## 16. Implementation Phases (Suggested)

| Phase | Scope | Estimated Effort |
|-------|-------|------------------|
| **Phase 1** | Core pipeline: download → caption extraction → translate → soft subs | 3-4 weeks |
| **Phase 2** | Whisper fallback transcription | 1-2 weeks |
| **Phase 3** | Burned-in subtitles with styling | 1 week |
| **Phase 4** | Setup wizard, settings UI, i18n | 2 weeks |
| **Phase 5** | Update system (app + dependencies) | 1-2 weeks |
| **Phase 6** | Native compilation, packaging, distribution | 2 weeks |
| **Phase 7** | Testing, polish, documentation | 1-2 weeks |

**Total estimate:** 11-15 weeks for a polished v1.0

---

*Document version: 1.0 (Final)*  
*Last updated: December 2024*
