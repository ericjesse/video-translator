# Linguini - Developer Documentation

> Comprehensive technical documentation for maintaining and extending the Linguini application.
> (The codebase still uses the legacy `videotranslator` package path; only user-facing branding was renamed.)

---

## Table of Contents

1. [Application Overview](#1-application-overview)
2. [Architecture & Project Structure](#2-architecture--project-structure)
3. [Installation & Dependency System](#3-installation--dependency-system)
4. [Translation Pipeline](#4-translation-pipeline)
5. [External Tool Integrations](#5-external-tool-integrations)
6. [Configuration & Settings](#6-configuration--settings)
7. [Platform-Specific Details](#7-platform-specific-details)
8. [Build System](#8-build-system)
9. [Pain Points & Critical Highlights](#9-pain-points--critical-highlights)
10. [Troubleshooting Guide](#10-troubleshooting-guide)

---

## 1. Application Overview

### Purpose

Video Translator is a cross-platform desktop application that automatically translates YouTube videos by:

1. **Downloading** video content from YouTube
2. **Transcribing** audio to text using Whisper AI
3. **Translating** subtitles using multiple translation services
4. **Rendering** subtitles back into the video

### Technology Stack

| Component     | Technology            | Version |
|---------------|-----------------------|---------|
| Language      | Kotlin                | 2.0.21  |
| UI Framework  | Compose Multiplatform | 1.7.1   |
| DI Framework  | Koin                  | 4.0.0   |
| HTTP Client   | Ktor                  | 3.0.1   |
| Serialization | Kotlinx Serialization | 1.7.3   |
| Build System  | Gradle (Kotlin DSL)   | -       |
| JVM Target    | Java 21               | -       |

### Target Platforms

- **Windows**: MSI installer (x64)
- **macOS**: DMG bundle (Universal - Intel & Apple Silicon)
- **Linux**: DEB package (x86_64 & ARM64)

---

## 2. Architecture & Project Structure

### Layer Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         UI Layer                            │
│  (Compose Desktop, ViewModels, Navigation, Screens)         │
├─────────────────────────────────────────────────────────────┤
│                       Domain Layer                          │
│  (Services, Pipeline, Models, Business Logic)               │
├─────────────────────────────────────────────────────────────┤
│                   Infrastructure Layer                      │
│  (External Tools, Config, Network, Process Execution)       │
└─────────────────────────────────────────────────────────────┘
```

### Directory Structure

```
src/main/kotlin/com/ericjesse/videotranslator/
├── Main.kt                           # Application entry point
├── di/                               # Dependency Injection
│   ├── AppModule.kt                  # Koin initialization
│   ├── DomainModule.kt               # Business services
│   └── InfrastructureModule.kt       # Technical services
├── domain/                           # Business Logic
│   ├── model/                        # Data models
│   │   ├── Models.kt                 # VideoInfo, Subtitles, Language
│   │   └── TranslationModels.kt      # Translation-specific models
│   ├── service/                      # Service implementations
│   │   ├── api/                      # Service interfaces
│   │   ├── SubtitleRenderer.kt
│   │   ├── TranscriberService.kt
│   │   ├── TranslatorService.kt
│   │   └── VideoDownloader.kt
│   ├── pipeline/                     # Pipeline orchestration
│   │   ├── PipelineOrchestrator.kt
│   │   ├── PipelineModels.kt
│   │   └── CheckpointManager.kt
│   ├── installer/                    # Dependency installer interfaces
│   ├── validation/                   # Input validation
│   └── exception/                    # Custom exceptions
├── infrastructure/                   # Technical Infrastructure
│   ├── installer/                    # Platform-specific installers
│   │   ├── AbstractDependencyInstaller.kt
│   │   ├── WindowsDependencyInstaller.kt
│   │   ├── MacOSDependencyInstaller.kt
│   │   ├── LinuxDependencyInstaller.kt
│   │   └── DependencyInstallerFactory.kt
│   ├── config/                       # Configuration management
│   │   ├── ConfigManager.kt
│   │   └── PlatformPaths.kt
│   ├── service/                      # Infrastructure services
│   │   ├── ffmpeg/                   # Video rendering
│   │   ├── whisper/                  # Transcription
│   │   ├── ytdlp/                    # Video download
│   │   └── translation/              # Translation backends
│   ├── resources/                    # Resource management
│   ├── network/                      # Network utilities
│   ├── process/                      # Process execution
│   ├── security/                     # API key storage
│   ├── archive/                      # Archive extraction
│   └── update/                       # Version checking
└── ui/                               # User Interface
    ├── App.kt                        # Root composable & navigation
    ├── screens/
    │   ├── main/                     # Main translation screen
    │   ├── progress/                 # Progress tracking screen
    │   ├── settings/                 # Settings with tabs
    │   └── setup/                    # Setup wizard
    ├── components/                   # Reusable UI components
    ├── theme/                        # Colors, typography
    ├── i18n/                         # Internationalization
    └── error/                        # Global error handling
```

### Dependency Injection Graph

```
┌─ InfrastructureModule ─────────────────────────┐
│  PlatformPaths → ConfigManager                 │
│  HttpClient, ProcessExecutor                   │
│  TempFileManager, ResourceManager              │
│  DiskSpaceChecker, ConnectivityChecker         │
│  LibreTranslateService, UpdateManager          │
│  I18nManager, ArchiveExtractor                 │
└────────────────────────────────────────────────┘
                    ↓ injected into
┌─ DomainModule ─────────────────────────────────┐
│  VideoDownloader → VideoDownloadService        │
│  TranscriberService → TranscriptionService     │
│  TranslatorService → TranslationServiceApi     │
│  SubtitleRenderer → RenderingService           │
│  CheckpointManager, PipelineOrchestrator       │
└────────────────────────────────────────────────┘
                    ↓ injected into
┌─ UI Layer ─────────────────────────────────────┐
│  MainViewModel, ProgressViewModel              │
│  SetupWizard, SettingsScreen                   │
└────────────────────────────────────────────────┘
```

---

## 3. Installation & Dependency System

### Required External Components

| Component          | Purpose                                | Size    | Required     |
|--------------------|----------------------------------------|---------|--------------|
| **yt-dlp**         | Video downloading & caption extraction | 20 MB   | Yes          |
| **FFmpeg**         | Audio/video processing & rendering     | 150 MB  | Yes          |
| **whisper.cpp**    | Speech-to-text transcription           | 5 MB    | Yes          |
| **Whisper Model**  | AI model for transcription             | 150 MB+ | Yes          |
| **Python**         | Runtime for LibreTranslate             | 100 MB  | Optional     |
| **LibreTranslate** | Local translation service              | 2 GB    | Optional     |
| **VC++ Runtime**   | Windows DLL dependencies               | 25 MB   | Windows only |

### Component Download URLs by Platform

#### Windows (x64)

| Component     | URL                                                                                     |
|---------------|-----------------------------------------------------------------------------------------|
| yt-dlp        | `https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe`                  |
| FFmpeg        | `https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip`                      |
| whisper.cpp   | `https://github.com/ggerganov/whisper.cpp/releases/latest/download/whisper-bin-x64.zip` |
| Python 3.11.9 | `https://www.python.org/ftp/python/3.11.9/python-3.11.9-amd64.exe`                      |
| VC++ Runtime  | Bundled with application (see [VC++ Runtime](#vc-runtime-bundled))                       |

#### macOS (Universal)

| Component     | URL (Intel)                                | URL (Apple Silicon)                      |
|---------------|--------------------------------------------|------------------------------------------|
| yt-dlp        | `github.com/.../yt-dlp_macos`              | Same                                     |
| FFmpeg        | `evermeet.cx/ffmpeg/getrelease/zip`        | `.../zip/arm64`                          |
| whisper.cpp   | `.../whisper-bin-x86_64-apple-darwin.zip`  | `.../whisper-bin-arm64-apple-darwin.zip` |
| Python 3.11.9 | `python.org/.../python-3.11.9-macos11.pkg` | Same (Universal)                         |

#### Linux (x86_64 / ARM64)

| Component           | URL (x86_64)                                                               | URL (ARM64)                              |
|---------------------|----------------------------------------------------------------------------|------------------------------------------|
| yt-dlp              | `github.com/.../yt-dlp_linux`                                              | `.../yt-dlp_linux_aarch64`               |
| FFmpeg              | `johnvansickle.com/.../ffmpeg-release-amd64-static.tar.xz`                 | `.../ffmpeg-release-arm64-static.tar.xz` |
| whisper.cpp         | `.../whisper-bin-x86_64-linux-gnu.zip`                                     | `.../whisper-bin-aarch64-linux-gnu.zip`  |
| Python (standalone) | `github.com/indygreg/python-build-standalone/.../x86_64-unknown-linux-gnu` | `.../aarch64-unknown-linux-gnu`          |

### Whisper Models (All Platforms)

| Model    | URL                                                               | Size    |
|----------|-------------------------------------------------------------------|---------|
| Base     | `huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin` | ~150 MB |
| Small    | `.../ggml-small.bin`                                              | ~500 MB |
| Medium   | `.../ggml-medium.bin`                                             | ~1.5 GB |
| Large v3 | `.../ggml-large-v3.bin`                                           | ~3 GB   |

### Installation Directory Structure

```
{dataDir}/
├── bin/                          # Executables
│   ├── yt-dlp[.exe]
│   ├── ffmpeg[.exe]
│   ├── ffprobe[.exe]
│   └── whisper[.exe]
├── models/
│   └── whisper/
│       └── ggml-base.bin         # Whisper models
├── libretranslate/
│   └── venv/                     # Python virtual environment
│       ├── bin/ or Scripts/
│       │   ├── python
│       │   ├── pip
│       │   ├── libretranslate
│       │   └── argospm
│       └── Lib/ or lib/
├── python/                       # Linux standalone Python
│   └── bin/
│       ├── python3
│       └── pip3
└── cache/                        # Temporary downloads
```

### LibreTranslate Installation

LibreTranslate is installed in a Python virtual environment with specific version pinning:

```
Critical Dependencies:
├── PyTorch 2.0.1 (CPU version)
├── LibreTranslate 1.6.0
├── ctranslate2 4.0.0  ⚠️ CRITICAL - must be exactly 4.0.0
└── 24 additional packages (see LIBRETRANSLATE_DEPENDENCIES)
```

**Installation Steps:**

1. Create virtual environment
2. Upgrade pip
3. Install PyTorch 2.0.1 (CPU, ~175MB)
4. Install LibreTranslate 1.6.0 with `--no-deps`
5. Install all pinned dependencies
6. Install ctranslate2==4.0.0 (CRITICAL)
7. Install default language models via `argospm`
8. Verify installation

**Default Language Models:**

- English ↔ French
- English ↔ German
- English ↔ Spanish

---

## 4. Translation Pipeline

### Pipeline Stages

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  DOWNLOAD   │ →  │ TRANSCRIBE  │ →  │  TRANSLATE  │ →  │   RENDER    │
│   (25%)     │    │   (35%)     │    │   (25%)     │    │   (15%)     │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
       ↓                  ↓                  ↓                  ↓
   video.mp4        Subtitles         Translated         output.mp4
                    (source)          Subtitles          + .srt
```

### Stage 1: Download (VideoDownloader)

**Responsibilities:**

- URL validation (multiple YouTube formats supported)
- Video metadata fetching
- Video/audio download with progress
- Caption extraction (prefers manual over auto-generated)

**Supported URL Formats:**

- `youtube.com/watch?v=VIDEO_ID`
- `youtu.be/VIDEO_ID`
- `youtube.com/shorts/VIDEO_ID`
- `youtube.com/embed/VIDEO_ID`
- `music.youtube.com/watch?v=VIDEO_ID`

**Output:** Video file path (MP4)

### Stage 2: Transcription (TranscriberService)

**Responsibilities:**

- Extract audio from video (FFmpeg → MP3 → WAV 16kHz mono)
- Segment long audio files (>30 min)
- Run Whisper transcription
- Parse timing and text output

**Whisper Models:**
| Model | Parameters | VRAM | Speed | Use Case |
|-------|------------|------|-------|----------|
| Tiny | 39M | 390MB | 10x | Quick previews |
| Base | 74M | 500MB | 7x | Good balance (default) |
| Small | 244M | 1GB | 4x | Better accuracy |
| Medium | 769M | 2.6GB | 2x | High accuracy |
| Large | 1.55B | 4.7GB | 1x | Best accuracy |

**Output:** Subtitles object with timing

### Stage 3: Translation (TranslatorService)

**Supported Translation Services:**
| Service | Type | API Key Required | Rate Limits |
|---------|------|------------------|-------------|
| LibreTranslate | Local/Cloud | Optional | None (local) |
| DeepL | Cloud | Yes | 500k chars/month (free) |
| OpenAI | Cloud | Yes | Token-based |
| Google Translate | Cloud | Yes | Character-based |

**Features:**

- Smart batching (respects per-service limits)
- Translation caching (SHA-256 hash keys)
- Formatting preservation (HTML, ASS tags, music symbols)
- Glossary support for technical terms
- Rate limiting with exponential backoff
- Fallback to other services on failure

**Output:** Translated Subtitles object

### Stage 4: Rendering (SubtitleRenderer)

**Subtitle Output Types:**

| Type          | Format  | Re-encoding   | Best For                      |
|---------------|---------|---------------|-------------------------------|
| **Soft**      | MKV     | No (mux only) | Players with subtitle support |
| **Burned-In** | MP4/MKV | Yes           | Universal playback            |

**Burned-In Styling Options:**

- Font family, size, weight
- Primary, outline, shadow colors
- Position (9 options)
- Border style (outline, opaque box, drop shadow)
- Background opacity

**Hardware Encoders:**
| Encoder | Platform | FFmpeg Name |
|---------|----------|-------------|
| Software (CPU) | All | `libx264` |
| NVIDIA NVENC | Windows, Linux | `h264_nvenc` |
| Apple VideoToolbox | macOS | `h264_videotoolbox` |
| Intel QuickSync | All | `h264_qsv` |
| AMD AMF | Windows | `h264_amf` |
| VA-API | Linux | `h264_vaapi` |

**Output:** Final video file + optional SRT export

### Pipeline Checkpoints

The pipeline supports checkpointing for resume capability:

```kotlin
data class PipelineCheckpoint(
    val jobId: String,
    val stage: PipelineStageName,
    val downloadedVideoPath: String?,
    val subtitles: Subtitles?,
    val translatedSubtitles: Subtitles?,
    val timestamp: Long,
    val job: TranslationJob
)
```

---

## 5. External Tool Integrations

### yt-dlp Integration

**Location:** `infrastructure/service/ytdlp/`

**Key Operations:**

```bash
# Fetch video info
yt-dlp -J --no-playlist "URL"

# Download video
yt-dlp -f "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]" \
       --no-playlist -o "output.%(ext)s" "URL"

# Extract captions
yt-dlp --write-subs --sub-format vtt --skip-download "URL"
```

**Error Handling:**

- Video unavailable, private, age-restricted
- Geographic restrictions
- Copyright claims
- Rate limiting

### FFmpeg Integration

**Location:** `infrastructure/service/ffmpeg/`

**Key Operations:**

```bash
# Extract audio for Whisper
ffmpeg -i video.mp4 -vn -ac 1 -ar 16000 -f wav audio.wav

# Burn-in subtitles (ASS format)
ffmpeg -i video.mp4 -vf "ass=subtitles.ass" -c:v libx264 output.mp4

# Soft subtitles (MKV)
ffmpeg -i video.mp4 -i subtitles.srt -c copy -c:s srt output.mkv

# Get video info
ffprobe -v quiet -print_format json -show_format -show_streams video.mp4
```

### Whisper.cpp Integration

**Location:** `infrastructure/service/whisper/`

**Key Operations:**

```bash
# Run transcription
whisper -m ggml-base.bin -f audio.wav -l auto --output-json
```

**Progress Parsing:**

```
[00:01:23.456 --> 00:01:25.789] Transcribed text here
progress = 45.5%
```

### LibreTranslate Integration

**Location:** `infrastructure/translation/`

**API Endpoint:** `http://localhost:5000/translate`

**Request Format:**

```json
{
  "q": "Text to translate",
  "source": "en",
  "target": "de",
  "format": "text"
}
```

---

## 6. Configuration & Settings

### Configuration Files

| File            | Location       | Purpose                      |
|-----------------|----------------|------------------------------|
| `settings.json` | `{configDir}/` | User preferences             |
| `services.json` | `{configDir}/` | API key references           |
| `versions.json` | `{configDir}/` | Installed component versions |

### Platform Directories

| Platform | Config Dir                                      | Data Dir                          |
|----------|-------------------------------------------------|-----------------------------------|
| Windows  | `%APPDATA%\VideoTranslator`                     | `%LOCALAPPDATA%\VideoTranslator`  |
| macOS    | `~/Library/Application Support/VideoTranslator` | Same                              |
| Linux    | `~/.config/video-translator`                    | `~/.local/share/video-translator` |

### AppSettings Structure

```kotlin
data class AppSettings(
    val language: String = "en",
    val setupProgress: SetupProgress,
    val transcription: TranscriptionSettings,
    val translation: TranslationSettings,
    val subtitle: SubtitleSettings,
    val updates: UpdateSettings,
    val resources: ResourceSettings,
    val ui: UiSettings
)

data class TranscriptionSettings(
    val whisperModel: WhisperModel = WhisperModel.BASE,
    val preferYouTubeCaptions: Boolean = true
)

data class TranslationSettings(
    val defaultService: TranslationService = TranslationService.LIBRE_TRANSLATE,
    val defaultSourceLanguage: Language? = null,
    val defaultTargetLanguage: Language = Language.ENGLISH
)

data class SubtitleSettings(
    val defaultOutputMode: SubtitleType = SubtitleType.BURNED_IN,
    val alwaysExportSrt: Boolean = false,
    val burnedIn: BurnedInSettings = BurnedInSettings()
)
```

### API Key Storage

API keys are stored securely using platform-specific mechanisms:

- **Windows:** Windows Credential Manager
- **macOS:** Keychain
- **Linux:** Secret Service API (libsecret)

Reference format in `services.json`:

```json
{
  "deepl": {
    "keyRef": "videotranslator.deepl.apikey"
  },
  "openai": {
    "keyRef": "videotranslator.openai.apikey"
  }
}
```

---

## 7. Platform-Specific Details

### Windows

**Requirements:**

- Windows 10+ (version 10.x)
- Visual C++ Redistributable v14.29.x (CRITICAL: not 14.30+)

**VC++ Runtime (Bundled):**

The VC++ 14.29 redistributable installer (`vc_redist.x64.exe`) is bundled with the application at `src/main/distribution/windows/vc_redist.x64.exe`. This file is automatically included in the Windows package via the `appResourcesRootDir` mechanism in `build.gradle.kts`.

At runtime, `WindowsDependencyInstaller` locates it via `System.getProperty("compose.application.resources.dir")` and runs it directly — no download is needed. This avoids the rolling URL problem where `https://aka.ms/vs/16/release/vc_redist.x64.exe` now installs 14.40+ instead of 14.29.x.

**Python Installation:**

- Uses official Python.org installer
- Installs to `%LOCALAPPDATA%\Programs\Python\Python311`
- Flags: `/passive InstallAllUsers=0 PrependPath=1 Include_pip=1`

**Known Issues:**

- VC++ Runtime v14.30+ causes `fbgemm.dll` loading failures with PyTorch/LibreTranslate
- The bundled installer pins version 14.29.x to avoid this

### macOS

**Requirements:**

- macOS 11+ (Big Sur or later)
- Xcode Command Line Tools (recommended)
- Rosetta 2 for Apple Silicon (some x86_64 binaries)

**Python Installation:**

- Uses official Python.org PKG installer
- Requires administrator password (via osascript)
- Installs to `/Library/Frameworks/Python.framework/Versions/3.11`

**Post-Install:**

- Remove quarantine attribute: `xattr -d com.apple.quarantine <path>`
- Set executable permissions

### Linux

**Requirements:**

- glibc 2.17+ (most modern distributions)
- NVIDIA drivers (optional, for GPU transcription)

**Python Installation:**

- Uses python-build-standalone (self-contained, no sudo)
- Installs to `~/.local/share/video-translator/python`
- Supports both x86_64 and ARM64

**Platform Checks:**

- glibc version via `ldd --version`
- NVIDIA GPU via `nvidia-smi`

---

## 8. Build System

### Gradle Tasks

```bash
# Development
./gradlew run                    # Run application
./gradlew build                  # Build & test
./gradlew test                   # Run tests only

# Distribution
./gradlew packageMsi             # Windows MSI
./gradlew packageDmg             # macOS DMG
./gradlew packageDeb             # Linux DEB

# Utilities
./gradlew printVersion           # Show version info
./gradlew generateIcons          # Generate multi-resolution icons
```

### Version Management

Version is derived from Git tags:

- Tag `v1.2.3` → Version `1.2.3`
- Tag `v1.2.3-5-gabc123` → Version `1.2.3.5`
- Dirty working tree → `1.2.3-dirty`

### JVM Configuration

```kotlin
jvmArgs("-Xmx2g")                // Max heap size
jvmToolchain(21)                 // Java 21
```

### Test Configuration

```kotlin
jvmArgs("-Xmx1g")                // Reduced for tests
useJUnitPlatform()               // JUnit 5
```

---

## 9. Pain Points & Critical Highlights

### Critical Version Dependencies

| Component          | Required Version | Issue if Wrong               |
|--------------------|------------------|------------------------------|
| **ctranslate2**    | 4.0.0 exactly    | LibreTranslate crashes       |
| **VC++ Runtime**   | 14.29.x          | DLL load failures on Windows |
| **PyTorch**        | 2.0.1            | Compatibility issues         |
| **LibreTranslate** | 1.6.0            | API changes                  |

### Memory Management

**High Memory Consumers:**

1. Whisper transcription (especially Large model)
2. PyTorch in LibreTranslate
3. FFmpeg video encoding

**Mitigations:**

- ResourceManager monitors JVM heap (warn at 75%, critical at 90%)
- Audio segmentation for files >30 minutes
- TempFileManager cleans up intermediate files

### Rate Limiting Considerations

| Service    | Limit                | Strategy                |
|------------|----------------------|-------------------------|
| YouTube    | Aggressive detection | Browser cookies, delays |
| DeepL Free | 500k chars/month     | Caching, batching       |
| OpenAI     | Token-based          | Batch optimization      |
| Google     | Character-based      | Caching                 |

### Error-Prone Areas

1. **Caption Extraction:** YouTube frequently changes formats
2. **yt-dlp Updates:** May break due to YouTube changes
3. **Whisper Memory:** Large models can OOM on low-RAM systems
4. **FFmpeg Filters:** Complex subtitle styling can fail
5. **LibreTranslate Startup:** Slow first start (model loading)

### Security Considerations

1. **API Keys:** Never stored in plain text; use secure storage
2. **URL Validation:** Strict regex to prevent injection
3. **Process Execution:** Sanitize all command arguments
4. **Temporary Files:** Clean up sensitive data after processing

### Performance Bottlenecks

1. **Transcription:** CPU-bound, benefit from GPU (CUDA)
2. **Translation:** Network-bound for cloud services
3. **Rendering:** GPU encoding significantly faster
4. **Disk I/O:** SSD recommended for temp files

---

## 10. Troubleshooting Guide

### Common Issues

#### "whisper.cpp failed to start" (Windows)

**Cause:** Missing VC++ Redistributable or wrong version
**Solution:** The bundled VC++ 14.29 installer runs automatically during setup. If manually installing, use the pinned URL `https://aka.ms/vs/16/release/14.29.30133/VC_Redist.x64.exe` (do NOT use the generic `aka.ms/vs/16/release/vc_redist.x64.exe` which now delivers 14.40+).

#### "LibreTranslate fails to translate"

**Cause:** ctranslate2 version mismatch
**Solution:** Reinstall with `ctranslate2==4.0.0`

#### "Video not available"

**Causes:**

- Video removed or private
- Geographic restriction
- Age restriction (needs cookies)

**Solution:** Use browser cookies: `--cookies-from-browser firefox`

#### "Out of memory during transcription"

**Cause:** Whisper model too large for available RAM
**Solution:** Use smaller model (Base instead of Large)

#### "Subtitles out of sync"

**Cause:** Audio segmentation overlap issues
**Solution:** Decrease overlap duration or use shorter segments

### Debug Logging

Enable detailed logging:

```kotlin
// In logback.xml
<logger name ="com.ericjesse.videotranslator" level = "DEBUG"/>
```

Log file locations:

- Windows: `%LOCALAPPDATA%\VideoTranslator\logs\`
- macOS/Linux: `~/.local/share/video-translator/logs/`

### Useful Commands

```bash
# Check yt-dlp version
yt-dlp --version

# Test FFmpeg
ffmpeg -version

# Test whisper.cpp
whisper --help

# Check Python venv
{venv}/bin/python --version
{venv}/bin/pip list

# Manual LibreTranslate test
curl -X POST "http://localhost:5000/translate" \
  -H "Content-Type: application/json" \
  -d '{"q":"Hello","source":"en","target":"de"}'
```

---

## Appendix: Quick Reference

### Key File Locations

| Purpose             | Relative Path                                            |
|---------------------|----------------------------------------------------------|
| Entry Point         | `Main.kt`                                                |
| DI Setup            | `di/AppModule.kt`                                        |
| Pipeline            | `domain/pipeline/PipelineOrchestrator.kt`                |
| Video Download      | `domain/service/VideoDownloader.kt`                      |
| Transcription       | `domain/service/TranscriberService.kt`                   |
| Translation         | `domain/service/TranslatorService.kt`                    |
| Rendering           | `domain/service/SubtitleRenderer.kt`                     |
| Installer (Windows) | `infrastructure/installer/WindowsDependencyInstaller.kt` |
| Installer (macOS)   | `infrastructure/installer/MacOSDependencyInstaller.kt`   |
| Installer (Linux)   | `infrastructure/installer/LinuxDependencyInstaller.kt`   |
| Config              | `infrastructure/config/ConfigManager.kt`                 |
| Main UI             | `ui/screens/main/MainScreen.kt`                          |
| Progress UI         | `ui/screens/progress/ProgressScreen.kt`                  |

### Component ID Reference

```kotlin
enum class ComponentId {
    YT_DLP,              // Video downloader
    FFMPEG,              // Audio/video processing
    WHISPER_CPP,         // Transcription engine
    WHISPER_MODEL_BASE,  // Base model (~150MB)
    WHISPER_MODEL_SMALL, // Small model (~500MB)
    WHISPER_MODEL_MEDIUM,// Medium model (~1.5GB)
    WHISPER_MODEL_LARGE, // Large model (~3GB)
    LIBRE_TRANSLATE,     // Translation service
    VC_REDIST,           // Windows only
    PYTHON               // Python runtime
}
```

### Translation Service Enum

```kotlin
enum class TranslationService {
    LIBRE_TRANSLATE,  // Local/self-hosted
    DEEPL,            // Cloud API
    OPENAI,           // GPT-based
    GOOGLE            // Google Translate API
}
```

---

*Last updated: February 2026*
