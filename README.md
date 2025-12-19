# Video Translator

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Translate YouTube videos into any language with automatic transcription and subtitle generation.

## Features

- **YouTube Video Download** — Automatically downloads videos from YouTube URLs
- **Speech-to-Text** — Transcribes audio using Whisper when captions aren't available
- **Multi-language Translation** — Translate subtitles between English, German, and French
- **Flexible Output** — Generate soft subtitles (MKV) or burned-in subtitles (MP4)
- **Multiple Translation Services** — LibreTranslate (free), DeepL, or OpenAI
- **Cross-Platform** — Native desktop app for Windows, macOS, and Linux

## Screenshot

*Screenshot coming soon*

## Installation

### Pre-built Releases

Download the latest release for your platform from the [Releases](https://github.com/ericjesse/video-translator/releases) page:

- **Windows**: `VideoTranslator-x.x.x.msi`
- **macOS**: `VideoTranslator-x.x.x.dmg`
- **Linux**: `VideoTranslator-x.x.x.AppImage`

### Unsigned Application Warning

This application is not code-signed. You may see security warnings on first launch:

**Windows (SmartScreen):**
1. Click "More info"
2. Click "Run anyway"

**macOS (Gatekeeper):**
1. Open System Settings → Privacy & Security
2. Scroll to "Security" section  
3. Click "Open Anyway" next to the Video Translator message

Or via Terminal:
```bash
xattr -d com.apple.quarantine /Applications/VideoTranslator.app
```

### Building from Source

Requirements:
- JDK 21 or higher
- Gradle 8.x

```bash
# Clone the repository
git clone https://github.com/ericjesse/video-translator.git
cd video-translator

# Build the application
./gradlew build

# Run in development mode
./gradlew run

# Create distribution packages
./gradlew packageDistributionForCurrentOS
```

## Usage

1. **First Launch** — The setup wizard will guide you through:
   - Downloading required components (yt-dlp, FFmpeg, Whisper)
   - Selecting a Whisper model for transcription
   - Configuring your preferred translation service

2. **Translate a Video**:
   - Paste a YouTube URL
   - Select source language (or auto-detect) and target language
   - Choose output options (soft or burned-in subtitles)
   - Click "Translate"

3. **Output** — Find your translated video in the output directory

## Configuration

Settings are stored in platform-specific locations:

- **Windows**: `%APPDATA%\VideoTranslator\`
- **macOS**: `~/Library/Application Support/VideoTranslator/`
- **Linux**: `~/.config/video-translator/`

## Translation Services

| Service | API Key Required | Free Tier |
|---------|------------------|-----------|
| LibreTranslate | No | Unlimited (rate-limited) |
| DeepL | Yes | 500K chars/month |
| OpenAI | Yes | Pay-per-use |

## Technology Stack

- **Kotlin** — Application language
- **Compose Multiplatform** — Cross-platform UI framework
- **yt-dlp** — YouTube video downloading
- **FFmpeg** — Video/audio processing
- **whisper.cpp** — Speech-to-text transcription
- **Ktor** — HTTP client for API calls

## Project Structure

```
video-translator/
├── src/main/kotlin/com/ericjesse/videotranslator/
│   ├── Main.kt                 # Application entry point
│   ├── di/                     # Dependency injection
│   ├── domain/
│   │   ├── model/              # Data models
│   │   ├── pipeline/           # Translation pipeline orchestration
│   │   └── service/            # Core services (download, transcribe, translate, render)
│   ├── infrastructure/
│   │   ├── config/             # Configuration management
│   │   ├── http/               # HTTP client setup
│   │   ├── process/            # External process execution
│   │   └── update/             # Update management
│   └── ui/
│       ├── App.kt              # Root composable
│       ├── i18n/               # Internationalization
│       ├── navigation/         # Navigation state
│       ├── screens/            # UI screens
│       └── theme/              # Material theme
├── src/main/resources/
│   ├── i18n/                   # Translation properties files
│   └── icons/                  # Application icons
└── docs/
    ├── ui-mockups.md           # UI design specifications
    └── architecture.md         # System architecture
```

## Contributing

Contributions are welcome! Please read our contributing guidelines before submitting PRs.

## License

This project is licensed under the Apache License 2.0 — see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [yt-dlp](https://github.com/yt-dlp/yt-dlp) — Video downloading
- [FFmpeg](https://ffmpeg.org/) — Video processing
- [whisper.cpp](https://github.com/ggerganov/whisper.cpp) — Speech recognition
- [LibreTranslate](https://libretranslate.com/) — Free translation API
- [JetBrains](https://www.jetbrains.com/) — Compose Multiplatform
