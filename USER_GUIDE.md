# Linguini - User Guide

Welcome to Linguini! This application allows you to download videos from YouTube, transcribe their audio,
translate subtitles to your preferred language, and export the result with burned-in subtitles or as separate subtitle
files.

---

## Table of Contents

1. [System Requirements](#system-requirements)
2. [Installation](#installation)
3. [First Launch - Setup Wizard](#first-launch---setup-wizard)
4. [Main Interface](#main-interface)
5. [Translating a Video](#translating-a-video)
6. [Settings](#settings)
7. [Troubleshooting](#troubleshooting)
8. [FAQ](#faq)

---

## System Requirements

### Minimum Requirements

| Component            | Requirement                                                     |
|----------------------|-----------------------------------------------------------------|
| **Operating System** | Windows 10/11, macOS 11+, or Linux (Ubuntu 20.04+)              |
| **RAM**              | 8 GB (16 GB recommended for larger models)                      |
| **Disk Space**       | 5 GB for application and dependencies                           |
| **Internet**         | Required for downloading videos and online translation services |

### Recommended for Best Performance

- **GPU**: NVIDIA GPU with CUDA support (significantly speeds up transcription)
- **RAM**: 16 GB or more
- **SSD**: For faster processing of video files

---

## Installation

### Windows

1. Download the `.msi` installer from the releases page
2. Double-click the installer and follow the on-screen instructions
3. Launch Linguini from the Start Menu

### macOS

1. Download the `.dmg` file from the releases page
2. Open the DMG and drag Linguini to your Applications folder
3. On first launch, right-click the app and select "Open" to bypass Gatekeeper

### Linux

1. Download the `.deb` package (Debian/Ubuntu) or `.rpm` package (Fedora/RHEL)
2. Install using your package manager:
   ```bash
   # Debian/Ubuntu
   sudo dpkg -i video-translator_*.deb

   # Fedora/RHEL
   sudo rpm -i video-translator_*.rpm
   ```
3. Launch from your application menu or run `video-translator` in terminal

---

## First Launch - Setup Wizard

When you first launch Linguini, a setup wizard will guide you through the initial configuration.

### Step 1: Welcome Screen

![Screenshot: Welcome screen showing the Linguini logo, a brief description of the application, and a "Get Started" button at the bottom]

Click **"Get Started"** to begin the setup process.

### Step 2: Select Translation Service

![Screenshot: Translation service selection screen showing four options as cards - LibreTranslate (with "Free, runs locally" subtitle), DeepL (with "High quality, API key required" subtitle), OpenAI (with "GPT-powered, API key required" subtitle), and Google Translate (with "Fast, API key required" subtitle). LibreTranslate is highlighted as selected]

Choose your preferred translation service:

| Service              | Description                                                  | Requirements                                         |
|----------------------|--------------------------------------------------------------|------------------------------------------------------|
| **LibreTranslate**   | Free, open-source, runs locally on your computer             | No API key needed, downloads ~2GB of language models |
| **DeepL**            | High-quality translations, especially for European languages | Requires DeepL API key (free tier available)         |
| **OpenAI**           | GPT-powered translations with context awareness              | Requires OpenAI API key                              |
| **Google Translate** | Fast and reliable                                            | Requires Google Cloud API key                        |

If you select a service requiring an API key, you'll be prompted to enter it.

### Step 3: Select Whisper Model

![Screenshot: Whisper model selection screen showing a dropdown menu with options: Tiny, Base, Small, Medium, Large. Each option shows the model size in parentheses (e.g., "Base (142 MB)"). A description below explains that larger models are more accurate but slower]

Select the Whisper model for audio transcription:

| Model      | Size   | Speed   | Accuracy  | Recommended For          |
|------------|--------|---------|-----------|--------------------------|
| **Tiny**   | 75 MB  | Fastest | Basic     | Quick tests, short clips |
| **Base**   | 142 MB | Fast    | Good      | General use, most videos |
| **Small**  | 466 MB | Medium  | Better    | When accuracy matters    |
| **Medium** | 1.5 GB | Slow    | Very Good | Professional use         |
| **Large**  | 3 GB   | Slowest | Best      | Maximum accuracy needed  |

**Tip:** Start with "Base" for a good balance of speed and accuracy. You can change this later in Settings.

### Step 4: Downloading Dependencies

![Screenshot: Download progress screen showing a list of components being downloaded with checkmarks for completed items and a progress bar for the current download. Components listed: yt-dlp (checked), FFmpeg (checked), FFprobe (checked), Whisper (downloading, 45%), Python (pending), LibreTranslate (pending)]

The application will download and install only the components that are actually missing. Each component shows a status of *Pending*, *Downloading*, *Installing*, or *Complete*:

- **yt-dlp**: For downloading videos from YouTube
- **FFmpeg & FFprobe**: For video/audio processing
- **Whisper**: For audio transcription (speech-to-text)
- **Whisper model** (the size you picked in Step 3): The neural network used to transcribe audio
- **Visual C++ Runtime** (Windows only, if needed): The wizard detects the system's Visual C++ Runtime and skips installation when version 14.x or newer is already present. Only when nothing compatible is found does it install the bundled v14.29.
- **Python** (only when LibreTranslate is selected): Required to run LibreTranslate. Skipped if a compatible Python is already on your machine.
- **LibreTranslate** (if selected): Local translation service. Installed in its own isolated Python environment so it never conflicts with system packages.
- **Language models** (if LibreTranslate was selected): Translation packages for the most common language pairs (English/French, English/German, English/Spanish).

**Resumable downloads**: If the wizard fails mid-way (e.g., the network drops), the partially downloaded files are kept in a cache for one week. Click **Retry** and the wizard will pick up where it left off instead of re-downloading from scratch.

**Already installed?** If you re-run the wizard after a previous install, components that are already present are detected automatically and marked *Complete* without being re-downloaded. To start completely from scratch, use *Factory Reset* (see [Settings → General](#general-tab)).

This may take several minutes depending on your internet connection.

### Step 5: Setup Complete

![Screenshot: Setup complete screen with a green checkmark icon, text saying "You're all set!", a summary of configured settings, and a "Start Using Linguini" button]

Click **"Start Using Linguini"** to begin!

---

## Main Interface

![Screenshot: Main application window showing: 1) A URL input field at the top with placeholder text "Paste YouTube URL here...", 2) A "Fetch Video Info" button next to it, 3) A large empty area in the center with text "Paste a YouTube URL to get started", 4) Settings gear icon in the top-right corner]

### Interface Elements

1. **URL Input Field**: Paste your YouTube video URL here
2. **Fetch Button**: Click to retrieve video information
3. **Video Preview Area**: Shows video thumbnail and details after fetching
4. **Settings Button**: Access application settings (gear icon)

---

## Translating a Video

### Step 1: Enter the Video URL

![Screenshot: Main screen with a YouTube URL pasted in the input field (e.g., "https://www.youtube.com/watch?v=example"), the Fetch button is highlighted]

1. Copy a YouTube video URL from your browser
2. Paste it into the URL input field
3. Click **"Fetch Video Info"**

### Step 2: Review Video Information

![Screenshot: Main screen showing fetched video information - thumbnail image on the left, video title "Example Video Title" in bold, channel name below it, duration "12:34", detected language "English", and available captions listed. Below are dropdowns for "Source Language" (set to "Auto-detect") and "Target Language" (set to "German")]

After fetching, you'll see:

- Video thumbnail
- Title and channel name
- Duration
- Detected language (if captions are available)

Configure your translation:

- **Source Language**: Usually auto-detected, or select manually
- **Target Language**: Choose your desired subtitle language

### Step 3: Configure Output Options

![Screenshot: Output options panel showing: 1) Output mode dropdown with options "Burned-in Subtitles", "External SRT File", "Both", 2) Output folder selector showing current path with a "Browse" button, 3) Checkbox "Use hardware acceleration" (checked), 4) Large "Start Translation" button at the bottom]

- **Output Mode**:
    - *Burned-in*: Subtitles embedded in the video (permanent)
    - *External SRT*: Separate subtitle file
    - *Both*: Creates both versions
- **Output Folder**: Where to save the translated video
- **Hardware Acceleration**: Enable for faster processing (if supported)

### Step 4: Start Translation

Click **"Start Translation"** to begin the process.

### Step 5: Monitor Progress

![Screenshot: Progress screen showing the translation pipeline stages as a vertical timeline: 1) "Downloading Video" with green checkmark, 2) "Extracting Audio" with green checkmark, 3) "Transcribing Audio" with spinning indicator and "45% - Processing segment 12/27", 4) "Translating Subtitles" grayed out, 5) "Rendering Video" grayed out. A cancel button is visible at the bottom]

The progress screen shows each stage:

1. **Downloading Video**: Fetching the video from YouTube
2. **Extracting Audio**: Preparing audio for transcription
3. **Transcribing Audio**: Converting speech to text (Whisper)
4. **Translating Subtitles**: Translating to target language
5. **Rendering Video**: Creating final video with subtitles

You can click **"Cancel"** at any time to stop the process.

### Step 6: Complete

![Screenshot: Completion screen showing a green checkmark, "Translation Complete!" message, the output file path, and two buttons: "Open File" and "Open Folder". Statistics shown: Duration 12:34, Processing time 8:45, Segments translated: 127]

When finished:

- Click **"Open File"** to play the translated video
- Click **"Open Folder"** to view all output files
- Click **"Translate Another"** to start a new translation

---

## Settings

Access settings by clicking the gear icon in the top-right corner.

### General Tab

![Screenshot: Settings window with "General" tab selected. Shows: 1) Output folder path with Browse button, 2) "Delete temporary files after completion" checkbox (checked), 3) Theme selector dropdown (Light/Dark/System), 4) Language dropdown for UI language]

- **Default Output Folder**: Where translated videos are saved
- **Delete Temporary Files**: Automatically clean up after processing
- **Theme**: Light, Dark, or follow system setting
- **UI Language**: Application interface language

### Transcription Tab

![Screenshot: Settings window with "Transcription" tab selected. Shows: 1) Whisper model dropdown (Tiny/Base/Small/Medium/Large), 2) "Prefer YouTube captions when available" checkbox (checked), 3) "Use GPU acceleration" checkbox with detected GPU shown below]

- **Whisper Model**: Change the transcription model
- **Prefer YouTube Captions**: Use existing captions if available (faster)
- **GPU Acceleration**: Use NVIDIA GPU for faster transcription

### Translation Tab

![Screenshot: Settings window with "Translation" tab selected. Shows: 1) Translation service dropdown (LibreTranslate/DeepL/OpenAI/Google), 2) API key input field (shown for DeepL/OpenAI/Google), 3) Default source language dropdown, 4) Default target language dropdown, 5) "Manage Glossary" button]

- **Translation Service**: Switch between translation providers
- **API Key**: Enter or update API keys for paid services
- **Default Languages**: Set your preferred source/target languages
- **Glossary**: Define custom term translations

### Subtitles Tab

![Screenshot: Settings window with "Subtitles" tab selected. Shows: 1) Default output mode dropdown, 2) "Always export SRT file" checkbox, 3) Font settings section with font family dropdown, font size slider (16-48), 4) Color pickers for text color and outline color, 5) Position dropdown (Bottom/Top), 6) Preview area showing sample subtitle with current settings]

- **Default Output Mode**: Burned-in, External, or Both
- **Always Export SRT**: Create SRT file regardless of output mode
- **Font Settings**: Customize subtitle appearance
    - Font family
    - Font size
    - Text color
    - Outline color
    - Position (top/bottom)

### Updates Tab

![Screenshot: Settings window with "Updates" tab selected. Shows: 1) Current version number, 2) "Check for updates automatically" checkbox, 3) "Check Now" button, 4) Update status message area, 5) "Update Dependencies" button to refresh yt-dlp, FFmpeg, etc.]

- **Auto-Update Check**: Automatically check for new versions
- **Check Now**: Manually check for updates
- **Update Dependencies**: Download latest versions of yt-dlp, FFmpeg, etc.

### General Tab

![Screenshot: Settings window with "General" tab selected. Shows: 1) Application language selector, 2) Default output folder, 3) Default source/target language dropdowns, 4) A "Factory Reset" section at the bottom with a list of items to be deleted, the total reclaimable disk size, and a red "Factory Reset" button]

- **Language**: Application interface language
- **Default Output Location**: Where translated videos are saved by default
- **Default Source / Target Language**: Pre-fill the language dropdowns on the main screen
- **Factory Reset**: Reset the application to its initial state. The wizard will run again on the next launch. Use this when an installation has gotten into a bad state, when you want to free up disk space, or when you want to switch translation services cleanly.

  Factory Reset will:
    - Stop any running LibreTranslate server (including its Python worker subprocesses, so files can be deleted on Windows)
    - Delete all settings and API keys
    - Delete downloaded binaries (FFmpeg, yt-dlp, whisper)
    - Delete Whisper models
    - Delete the LibreTranslate environment and language packages
    - Delete cached files
    - Restart the application

  The estimated total disk space that will be reclaimed is shown above the button. You will be asked to confirm before anything is deleted — the action **cannot** be undone.

---

## Troubleshooting

### Video Download Fails

**Symptoms**: Error message when trying to fetch or download a video

**Solutions**:

1. Check your internet connection
2. Verify the YouTube URL is correct and the video is public
3. Update yt-dlp in Settings → Updates → Update Dependencies
4. Some videos may be geo-restricted or age-restricted

### Transcription is Very Slow

**Symptoms**: The "Transcribing Audio" stage takes a very long time

**Solutions**:

1. Use a smaller Whisper model (Tiny or Base)
2. Enable GPU acceleration if you have an NVIDIA GPU
3. Enable "Prefer YouTube Captions" to skip transcription when captions exist
4. Close other resource-intensive applications

### Translation Quality is Poor

**Symptoms**: Translated subtitles have errors or sound unnatural

**Solutions**:

1. Try a different translation service (DeepL often provides better quality)
2. Use a larger Whisper model for better transcription accuracy
3. Create a glossary for domain-specific terms
4. Manually correct the source language if auto-detection is wrong

### LibreTranslate Won't Start

**Symptoms**: Error when using LibreTranslate for translation

**Solutions**:

1. Ensure Python is installed (check Settings → Updates)
2. Reinstall LibreTranslate from Settings → Updates → Update Dependencies
3. Check if another application is using port 5000
4. Try restarting the application
5. If reinstalling fails with a "Permission denied" or "directory is locked" error on Windows, fully close the application and try again — the wizard will stop any leftover Python processes before recreating the LibreTranslate environment. As a last resort, run **Settings → General → Factory Reset** to wipe everything and re-run the wizard from scratch.

### Output Video Has No Audio

**Symptoms**: The rendered video is silent

**Solutions**:

1. Try a different hardware encoder in Settings
2. Disable hardware acceleration and use software encoding
3. Ensure FFmpeg is properly installed

### Application Won't Start

**Symptoms**: The application crashes or doesn't open

**Solutions**:

1. **Windows**: The setup wizard normally installs the bundled Visual C++ Runtime automatically when no compatible version is detected. If launch still fails, install Visual C++ Redistributable 2015-2022 manually from Microsoft.
2. **macOS**: Ensure you've allowed the app in Security & Privacy settings
3. **Linux**: Check that all dependencies are installed
4. From inside the app, use **Settings → General → Factory Reset** to wipe state and re-run the wizard. If the app won't open at all, manually delete the configuration folder and restart:
    - Windows: `%APPDATA%\VideoTranslator`
    - macOS: `~/Library/Application Support/VideoTranslator`
    - Linux: `~/.local/share/VideoTranslator`

---

## FAQ

### Is Linguini free?

Yes, Linguini is free and open-source software. However, some translation services (DeepL, OpenAI, Google)
require paid API keys for high-volume usage.

### Can I translate videos from sources other than YouTube?

Currently, Linguini is optimized for YouTube videos. Support for other platforms may be added in future
versions.

### How accurate is the transcription?

Accuracy depends on the Whisper model used and audio quality. The "Base" model typically achieves 90%+ accuracy for
clear audio. Use larger models for difficult audio or specialized content.

### Can I edit the subtitles before rendering?

Currently, subtitle editing is not built into the application. You can export as SRT, edit with a text editor or
subtitle software, then use the SRT file in a video editor.

### Does it work offline?

- **Video download**: Requires internet
- **Transcription**: Works completely offline
- **Translation with LibreTranslate**: Works offline after initial setup
- **Translation with other services**: Requires internet

### How can I improve translation quality?

1. Use DeepL or OpenAI for better quality translations
2. Create a glossary for technical terms or names
3. Ensure the source transcription is accurate by using a larger Whisper model
4. Manually set the correct source language instead of auto-detect

### What languages are supported?

- **Transcription**: Whisper supports 99+ languages
- **Translation**: Depends on the service:
    - LibreTranslate: ~30 languages
    - DeepL: 30+ languages
    - OpenAI: 100+ languages
    - Google: 130+ languages

### Where are my translated videos saved?

By default, videos are saved to your Videos folder. You can change this in Settings → General → Default Output Folder.

### How do I update the application?

Check Settings → Updates for new versions. You can also update the dependencies (yt-dlp, FFmpeg, etc.) from this screen.

---

## Keyboard Shortcuts

| Shortcut                   | Action                   |
|----------------------------|--------------------------|
| `Ctrl+V` / `Cmd+V`         | Paste URL from clipboard |
| `Ctrl+Enter` / `Cmd+Enter` | Start translation        |
| `Escape`                   | Cancel current operation |
| `Ctrl+,` / `Cmd+,`         | Open Settings            |
| `Ctrl+Q` / `Cmd+Q`         | Quit application         |

---

## Getting Help

If you encounter issues not covered in this guide:

1. Check the [GitHub Issues](https://github.com/your-repo/video-translator/issues) for known problems
2. Create a new issue with details about your problem
3. Include your operating system, application version, and error messages

---

*Last updated: January 2026*
*Version: 1.0.0*
