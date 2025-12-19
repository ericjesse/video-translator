# Video Translator — Claude Code Implementation Prompts

This document contains step-by-step prompts for Claude Code to implement the complete Video Translator application. Execute these prompts in order for best results.

---

## Table of Contents

1. [Project Setup & Verification](#1-project-setup--verification)
2. [Infrastructure Layer](#2-infrastructure-layer)
3. [Domain Services](#3-domain-services)
4. [UI Theme & Components](#4-ui-theme--components)
5. [Setup Wizard Screens](#5-setup-wizard-screens)
6. [Main Screen](#6-main-screen)
7. [Progress Screen](#7-progress-screen)
8. [Settings Screen](#8-settings-screen)
9. [Error Handling & Edge Cases](#9-error-handling--edge-cases)
10. [Testing](#10-testing)
11. [Build & Distribution](#11-build--distribution)

---

## 1. Project Setup & Verification

### Prompt 1.1: Verify Project Structure

```
Review the project structure in /home/claude/video-translator and verify:

1. The Gradle build configuration compiles successfully
2. All required dependencies are correctly declared
3. The package structure follows the architecture document

Run `./gradlew build` and fix any compilation errors. Report what issues you found and fixed.
```

### Prompt 1.2: Create Application Icons

```
Create placeholder application icons for the Video Translator app:

1. Create src/main/resources/icons/ directory
2. Generate a simple 512x512 PNG icon with:
   - A video/film frame icon
   - Primary color #6366F1 (indigo)
   - Clean, modern design
3. Create icon.ico for Windows (multi-resolution ICO file)
4. Create icon.icns for macOS

You can use a simple geometric design - a rectangle (video frame) with translation arrows or subtitle lines.
```

### Prompt 1.3: Add Logback Configuration

```
Create src/main/resources/logback.xml with:

1. Console appender for development with colored output
2. File appender that writes to the platform-appropriate log directory:
   - Windows: %LOCALAPPDATA%/VideoTranslator/logs/
   - macOS: ~/Library/Logs/VideoTranslator/
   - Linux: ~/.local/share/video-translator/logs/
3. Log rotation (max 5 files, 10MB each)
4. DEBUG level for com.ericjesse.videotranslator package
5. INFO level for everything else
```

---

## 2. Infrastructure Layer

### Prompt 2.1: Complete ProcessExecutor Implementation

```
Review and enhance ProcessExecutor.kt in infrastructure/process/:

1. Add proper handling for Windows vs Unix command execution
2. Implement process cancellation support via a CancellationToken pattern
3. Add memory limit enforcement using -Xmx for Java subprocesses
4. Handle character encoding issues (UTF-8 on all platforms)
5. Add detailed logging for debugging process issues

Ensure the execute() method properly streams output line-by-line in real-time, not buffered.
```

### Prompt 2.2: Implement Archive Extraction

```
Add archive extraction utilities to the infrastructure layer:

1. Create infrastructure/archive/ArchiveExtractor.kt
2. Implement extraction for:
   - ZIP files (for FFmpeg on Windows/macOS)
   - TAR.XZ files (for FFmpeg on Linux)
   - 7z files if needed
3. Use only JDK built-in classes or kotlinx-io (no additional dependencies)
4. Support progress reporting during extraction
5. Handle nested archives (FFmpeg downloads often have nested directories)

Include helper method to find specific binaries within extracted archives.
```

### Prompt 2.3: Complete UpdateManager Implementation

```
Complete the UpdateManager.kt implementation with full functionality:

1. Implement extractFfmpeg() with proper archive handling per platform:
   - Windows: Extract from zip, find ffmpeg.exe and ffprobe.exe
   - macOS: Extract from zip, find ffmpeg binary
   - Linux: Extract from tar.xz, find ffmpeg binary
   
2. Implement extractWhisperCpp() similarly

3. Add checksum verification for downloaded files using SHA256

4. Implement atomic file replacement (download to temp, verify, move)

5. Add retry logic for failed downloads (3 retries with exponential backoff)

6. Handle partial downloads (resume if server supports Range headers)
```

### Prompt 2.4: Add Secure API Key Storage

```
Enhance the services.json configuration to store API keys securely:

1. Create infrastructure/security/SecureStorage.kt
2. On Windows: Use Windows Credential Manager via JNA
3. On macOS: Use Keychain via Security framework
4. On Linux: Use libsecret via JNA, fallback to encrypted file
5. For fallback: Use AES-256-GCM with key derived from machine-specific data

Update ConfigManager to use SecureStorage for sensitive fields in TranslationServiceConfig.
```

---

## 3. Domain Services

### Prompt 3.1: Enhance VideoDownloader

```
Enhance VideoDownloader.kt with complete functionality:

1. Add YouTube URL validation (support youtube.com, youtu.be, shorts)
2. Implement video info fetching with proper JSON parsing using kotlinx.serialization
3. Add support for downloading only audio when captions exist (saves time/bandwidth)
4. Implement caption extraction for multiple languages
5. Add handling for age-restricted videos (may need cookies)
6. Add download speed limiting option
7. Handle yt-dlp errors with user-friendly messages

Create data classes for yt-dlp JSON output in domain/model/YtDlpModels.kt.
```

### Prompt 3.2: Enhance TranscriberService

```
Enhance TranscriberService.kt with complete Whisper integration:

1. Implement accurate progress tracking based on audio duration
2. Add support for all Whisper model sizes
3. Implement language detection result parsing
4. Add word-level timestamp support (for better subtitle timing)
5. Handle long audio files (segment into chunks if needed)
6. Add option to use GPU acceleration if available
7. Implement proper cleanup of temporary audio files

Parse Whisper's actual progress output format and map to 0-100% progress.
```

### Prompt 3.3: Enhance TranslatorService

```
Enhance TranslatorService.kt with production-ready translation:

1. Implement smart batching that respects API limits:
   - LibreTranslate: batch by character count
   - DeepL: batch by text segments (max 50)
   - OpenAI: batch by token estimate
   
2. Add context preservation between batches (send previous translations for context)

3. Implement translation caching (don't re-translate identical segments)

4. Add glossary support for technical terms

5. Handle special characters and formatting in subtitles (preserve <i>, line breaks)

6. Add fallback to next service if primary fails

7. Implement proper rate limit handling with backoff
```

### Prompt 3.4: Enhance SubtitleRenderer

```
Enhance SubtitleRenderer.kt with full rendering capabilities:

1. Implement proper ASS/SSA subtitle generation for better styling control

2. Add font configuration:
   - Font family selection (system fonts)
   - Font weight (bold/normal)
   - Outline/shadow options
   
3. Implement subtitle position options (top, bottom, custom margin)

4. Add support for multi-line subtitle formatting

5. Implement video quality options for burned-in output:
   - Original quality (re-encode with same bitrate)
   - High (CRF 18)
   - Medium (CRF 23)
   - Low (CRF 28)

6. Add hardware encoding support (NVENC, VideoToolbox, VAAPI)

7. Track actual FFmpeg progress using duration-based calculation
```

### Prompt 3.5: Pipeline Orchestrator Error Recovery

```
Enhance PipelineOrchestrator.kt with robust error handling:

1. Implement stage-specific error recovery:
   - Download failures: retry with different format
   - Transcription failures: try smaller model
   - Translation failures: try fallback service
   - Render failures: try software encoding

2. Add checkpoint/resume capability:
   - Save intermediate results (downloaded video, transcription, translation)
   - Allow resuming from last successful stage
   
3. Implement proper resource cleanup on cancellation

4. Add detailed error context for user-friendly messages

5. Emit structured log events for the log panel

Create a StageResult sealed class to represent success/failure/partial states.
```

---

## 4. UI Theme & Components

### Prompt 4.1: Create Reusable UI Components

```
Create a ui/components/ package with reusable Compose components:

1. AppTextField.kt - Styled text field with:
   - Label
   - Placeholder
   - Error state
   - Leading/trailing icons
   - Password visibility toggle

2. AppButton.kt - Styled buttons:
   - Primary (filled)
   - Secondary (outlined)
   - Text button
   - Icon button
   - Loading state with spinner

3. AppCard.kt - Styled card component:
   - Default padding
   - Hover effect
   - Click handling

4. AppDropdown.kt - Dropdown selector:
   - Single selection
   - Searchable option
   - Custom item rendering

5. AppProgressBar.kt - Progress indicators:
   - Linear determinate
   - Linear indeterminate
   - Circular
   - With label

Follow Material 3 guidelines and use the theme colors from Theme.kt.
```

### Prompt 4.2: Create Status Indicator Components

```
Create specialized components for status display in ui/components/:

1. StageIndicator.kt - For pipeline stages:
   - Pending state (gray circle)
   - In Progress state (animated blue circle)
   - Complete state (green checkmark)
   - Error state (red X)
   - Include label and optional subtitle

2. StageProgressRow.kt - Full stage row with:
   - Stage indicator
   - Stage name
   - Progress bar (when in progress)
   - Status text
   - Expandable details

3. LogPanel.kt - Collapsible log output:
   - Auto-scroll to bottom
   - Timestamp formatting
   - Log level coloring
   - Copy to clipboard button
   - Clear button
   - Search/filter

Use animations for smooth transitions between states.
```

### Prompt 4.3: Create Dialog Components

```
Create dialog components in ui/components/dialogs/:

1. ConfirmDialog.kt - Generic confirmation dialog:
   - Title
   - Message
   - Confirm/Cancel buttons
   - Optional "Don't show again" checkbox

2. ErrorDialog.kt - Error display dialog:
   - Error icon
   - Error title
   - Error message
   - Technical details (collapsible)
   - Action buttons (Retry, Settings, Close)

3. UpdateDialog.kt - Update notification:
   - Current vs new version
   - Release notes (scrollable)
   - Download Now / Remind Later buttons
   - Don't remind for this version checkbox

4. ProgressDialog.kt - Modal progress:
   - Title
   - Progress bar
   - Status message
   - Optional cancel button

All dialogs should be modal and centered on screen.
```

---

## 5. Setup Wizard Screens

### Prompt 5.1: Create Setup Wizard Container

```
Create ui/screens/setup/SetupWizard.kt as the main wizard container:

1. Implement a multi-step wizard with:
   - Step indicator (dots or numbers)
   - Current step tracking
   - Forward/back navigation
   - Step validation before proceeding

2. Steps:
   - Welcome (language selection)
   - Dependencies (component download)
   - Translation Service (service configuration)
   - Complete

3. Use AnimatedContent for smooth step transitions

4. Handle window close during setup (confirm dialog)

5. Save progress so wizard can be resumed if closed

Use the i18n system for all text. Reference docs/ui-mockups.md for layouts.
```

### Prompt 5.2: Implement Welcome Step

```
Create ui/screens/setup/steps/WelcomeStep.kt:

1. Display:
   - App icon (large, centered)
   - App name "Video Translator"
   - Version number
   - Tagline from i18n

2. Language selector:
   - Dropdown with flag icons (optional) and native names
   - Options: English, Deutsch, Français
   - Immediately apply selection (UI updates in real-time)

3. "Get Started" button at bottom

4. Animate elements on entrance

Reference the mockup in docs/ui-mockups.md (Screen 1: Setup Wizard — Welcome)
```

### Prompt 5.3: Implement Dependencies Step

```
Create ui/screens/setup/steps/DependenciesStep.kt:

1. Component list showing:
   - yt-dlp with checkbox (read-only, always required)
   - FFmpeg with checkbox (read-only, always required)
   - Whisper with model selector dropdown

2. Whisper model dropdown:
   - tiny (75 MB) - Fastest, lower accuracy
   - base (142 MB) - Balanced (recommended) [default]
   - small (466 MB) - Better accuracy
   - medium (1.5 GB) - High accuracy
   - large (2.9 GB) - Best accuracy, slowest

3. Show total download size (updates when model changes)

4. "Download & Install" button

5. Disable back navigation during download

Reference docs/ui-mockups.md (Screen 2: Setup Wizard — Dependencies)
```

### Prompt 5.4: Implement Downloading Step

```
Create ui/screens/setup/steps/DownloadingStep.kt:

1. Display component list with status icons:
   - Pending (○)
   - Downloading (◉ animated)
   - Complete (✓)
   - Error (✗)

2. Per-component progress:
   - Progress bar
   - Downloaded / Total size
   - Download speed

3. Overall progress bar at bottom

4. Status message showing current action

5. Cancel button (with confirmation)

6. Wire up to UpdateManager download flows

7. Handle download errors:
   - Show error message
   - Retry button
   - Skip button (if optional)

Reference docs/ui-mockups.md (Screen 3: Setup Wizard — Downloading)
```

### Prompt 5.5: Implement Translation Service Step

```
Create ui/screens/setup/steps/TranslationServiceStep.kt:

1. Radio button list of services:
   - LibreTranslate (Free) [default]
     - Instance dropdown (libretranslate.com, libretranslate.de, Custom)
   - DeepL
     - API key field (masked)
     - "How to get API key" link
   - OpenAI
     - API key field (masked)
     - "How to get API key" link

2. Show selected service's configuration panel

3. "Test Connection" button for each service with result indicator

4. Validate API keys before proceeding

5. Save configuration to services.json

Reference docs/ui-mockups.md (Screen 4: Setup Wizard — Translation Service)
```

### Prompt 5.6: Implement Complete Step

```
Create ui/screens/setup/steps/CompleteStep.kt:

1. Success icon (large green checkmark, animated)

2. "Setup Complete!" heading

3. Summary card showing:
   - yt-dlp version ✓
   - FFmpeg version ✓
   - Whisper model ✓
   - Translation service configured ✓

4. "Start Translating" button

5. Mark setup as complete in settings

6. Animate elements on entrance (staggered)

Reference docs/ui-mockups.md (Screen 5: Setup Wizard — Complete)
```

---

## 6. Main Screen

### Prompt 6.1: Create Main Screen Layout

```
Create ui/screens/main/MainScreen.kt with complete layout:

1. Header:
   - App name (left)
   - Window controls handled by system

2. URL Input Section (Card):
   - Label "YouTube URL"
   - Text field with placeholder
   - Paste button (clipboard icon)
   - URL validation feedback

3. Language Selection Section (Card):
   - Source Language dropdown (Auto-detect + languages)
   - Target Language dropdown
   - Side-by-side layout

4. Output Options Section (Card):
   - Subtitle Type radio buttons
   - Burned-in options (shown when selected)
   - Export SRT checkbox

5. Output Location Section (Card):
   - Text field showing path
   - Browse button (folder icon)

6. Footer:
   - Settings button (left)
   - Version info (left)
   - Translate button (right, prominent)

Reference docs/ui-mockups.md (Screen 6: Main Screen)
```

### Prompt 6.2: Implement Main Screen ViewModel

```
Create ui/screens/main/MainViewModel.kt:

1. State class MainScreenState:
   - youtubeUrl: String
   - urlError: String?
   - sourceLanguage: Language?
   - targetLanguage: Language
   - subtitleType: SubtitleType
   - burnedInStyle: BurnedInSubtitleStyle
   - exportSrt: Boolean
   - outputDirectory: String
   - isValidating: Boolean
   - videoInfo: VideoInfo?

2. Actions:
   - onUrlChanged(url: String) - validate URL format
   - onPasteClicked() - read clipboard, set URL
   - onSourceLanguageChanged(language: Language?)
   - onTargetLanguageChanged(language: Language)
   - onSubtitleTypeChanged(type: SubtitleType)
   - onBurnedInStyleChanged(style: BurnedInSubtitleStyle)
   - onExportSrtChanged(export: Boolean)
   - onBrowseClicked() - open file picker
   - onTranslateClicked() - validate and create TranslationJob

3. Load defaults from settings on init

4. Validate YouTube URL using regex, optionally fetch video info

5. Persist frequently changed settings (last output dir, last languages)
```

### Prompt 6.3: Implement Burned-in Subtitle Options

```
Create ui/screens/main/components/BurnedInOptions.kt:

1. Expandable section (shown when burned-in is selected)

2. Background Color dropdown:
   - None (transparent)
   - Black
   - Dark Gray
   - White

3. Background Opacity slider (0-100%)
   - Only enabled when background color != None

4. Preview panel:
   - Dark gradient background (simulating video)
   - Sample subtitle text with applied styling
   - Updates in real-time as options change

5. Smooth expand/collapse animation

Reference docs/ui-mockups.md (Screen 6a: Main Screen — Burned-in Options)
```

### Prompt 6.4: Implement File Picker Integration

```
Create ui/util/FilePicker.kt with native file picker integration:

1. Use JFileChooser on Linux
2. Use FileDialog on macOS for native look
3. Use JFileChooser on Windows (or native via JNA)

4. Functions:
   - pickDirectory(): String? - folder selection
   - pickFile(filters: List<FileFilter>): String? - file selection
   - pickSaveLocation(defaultName: String, filters: List<FileFilter>): String?

5. Remember last used directory per picker type

6. Create a Composable wrapper:
   - rememberDirectoryPickerLauncher()
   - rememberFilePickerLauncher()
```

---

## 7. Progress Screen

### Prompt 7.1: Create Progress Screen Layout

```
Create ui/screens/progress/ProgressScreen.kt with full layout:

1. Header:
   - "Processing Video" title

2. Video Info Card:
   - Video title (fetched from YouTube)
   - URL (truncated)
   - Thumbnail (optional, if we want to show it)

3. Pipeline Progress Card:
   - List of stage rows:
     - Downloading video
     - Checking for captions
     - Transcribing audio (or "Using YouTube captions")
     - Translating subtitles
     - Rendering video
   - Each row shows: icon, name, status/progress

4. Log Panel (collapsible):
   - Expandable section
   - Log entries with timestamps
   - Auto-scroll

5. Action Buttons:
   - Cancel (during processing)
   - Open in Folder + Translate Another (on complete)
   - Open Settings + Try Again (on error)

Reference docs/ui-mockups.md (Screen 7: Progress Screen)
```

### Prompt 7.2: Implement Progress Screen ViewModel

```
Create ui/screens/progress/ProgressViewModel.kt:

1. State class ProgressScreenState:
   - videoInfo: VideoInfo
   - stages: List<StageState>
   - currentStage: Int
   - overallProgress: Float
   - logEntries: List<LogEntry>
   - status: ProgressStatus (Processing, Complete, Error, Cancelled)
   - result: TranslationResult?
   - error: PipelineError?

2. data class StageState:
   - name: String
   - status: StageStatus
   - progress: Float
   - message: String?
   - details: String?

3. enum class StageStatus:
   - Pending, InProgress, Complete, Skipped, Error

4. Connect to PipelineOrchestrator.execute() flow

5. Map PipelineStage emissions to UI state

6. Collect log messages for log panel

7. Handle cancellation with confirmation
```

### Prompt 7.3: Implement Completion State

```
Create ui/screens/progress/components/CompletionCard.kt:

1. Success state:
   - Large green checkmark (animated)
   - "Translation Complete!" text
   - Output files card:
     - Video file with icon and size
     - SRT file (if exported) with icon and size
   - Location path
   - "Open in Folder" and "Translate Another" buttons

2. Error state:
   - Large red X icon
   - "Translation Failed" text
   - Error details card:
     - Error message
     - Stage where error occurred
     - Possible solutions list
   - "Open Settings" and "Try Again" buttons

3. Smooth transition from progress to completion

Reference docs/ui-mockups.md (Screen 8 & 9: Progress Screen — Complete/Error)
```

### Prompt 7.4: Implement Cancel Confirmation

```
Create ui/screens/progress/components/CancelConfirmation.kt:

1. Modal dialog asking "Cancel Translation?"

2. Warning message about losing progress

3. "Continue Processing" button (primary)

4. "Cancel Translation" button (destructive)

5. Show what will be deleted (partial files)

6. Handle both Cancel button and window close

7. Animate dialog appearance

Reference docs/ui-mockups.md (Screen 12: Cancel Confirmation Dialog)
```

---

## 8. Settings Screen

### Prompt 8.1: Create Settings Screen Layout

```
Create ui/screens/settings/SettingsScreen.kt with tab-based layout:

1. Header:
   - Back button (arrow + "Back")
   - "Settings" title

2. Two-column layout:
   - Left: Tab list (vertical)
     - General
     - Translation
     - Transcription
     - Subtitles
     - Updates
     - About
   - Right: Tab content area

3. Footer:
   - "Save Changes" button

4. Track unsaved changes, warn on back/close if unsaved

5. Save changes to ConfigManager

Reference docs/ui-mockups.md (Screen 10: Settings)
```

### Prompt 8.2: Implement General Settings Tab

```
Create ui/screens/settings/tabs/GeneralTab.kt:

1. Language dropdown:
   - English, Deutsch, Français
   - Apply immediately (UI updates)

2. Default Output Location:
   - Text field with current path
   - Browse button

3. Default Source Language:
   - Dropdown: Auto-detect, English, German, French

4. Default Target Language:
   - Dropdown: English, German, French

5. All changes update state, saved on "Save Changes"

Reference docs/ui-mockups.md (Screen 10: Settings)
```

### Prompt 8.3: Implement Translation Settings Tab

```
Create ui/screens/settings/tabs/TranslationTab.kt:

1. Active Service dropdown:
   - LibreTranslate (Free)
   - DeepL
   - OpenAI

2. LibreTranslate Settings panel:
   - Instance URL text field
   - "Test Connection" button with result

3. Other Services section:
   - DeepL API Key field (masked with toggle)
   - "How to get API key" link
   - OpenAI API Key field (masked with toggle)
   - "How to get API key" link

4. Validate API keys on save

Reference docs/ui-mockups.md (Screen 10a: Settings — Translation Tab)
```

### Prompt 8.4: Implement Transcription Settings Tab

```
Create ui/screens/settings/tabs/TranscriptionTab.kt:

1. Checkbox: "Prefer YouTube captions when available"
   - Description text below

2. Whisper Model dropdown:
   - Show all models with size

3. Model comparison table:
   - Columns: Model, Size, Speed (stars), Accuracy (stars)
   - Highlight current model

4. "Download Selected Model" button
   - Disabled if already installed
   - Shows progress during download

5. Installed Models section:
   - List of installed models
   - Delete button for each (except currently selected)

Reference docs/ui-mockups.md (Screen 10b: Settings — Transcription Tab)
```

### Prompt 8.5: Implement Subtitles Settings Tab

```
Create ui/screens/settings/tabs/SubtitlesTab.kt:

1. Default Output Mode dropdown:
   - Soft subtitles
   - Burned-in subtitles

2. "Always export SRT file" checkbox

3. Burned-in Subtitle Style section:
   - Font Size dropdown (18, 20, 22, 24, 26, 28, 32)
   - Font Color picker
   - Background Color dropdown
   - Background Opacity slider

4. Preview panel showing sample subtitle with current style

Reference docs/ui-mockups.md (Screen 10c: Settings — Subtitles Tab)
```

### Prompt 8.6: Implement Updates Settings Tab

```
Create ui/screens/settings/tabs/UpdatesTab.kt:

1. Application Updates section:
   - "Check for updates automatically" checkbox
   - Check interval dropdown (Daily, Weekly, Monthly)
   - "Check Now" button
   - "Last checked: X ago" text

2. Component Updates section:
   - Table with columns: Component, Installed, Latest, Status
   - Rows: yt-dlp, FFmpeg, Whisper.cpp
   - Status: ✓ (up to date) or ⬆ (update available)
   - "Update All Components" button

3. Show progress during update checks/downloads

Reference docs/ui-mockups.md (Screen 10d: Settings — Updates Tab)
```

### Prompt 8.7: Implement About Tab

```
Create ui/screens/settings/tabs/AboutTab.kt:

1. App icon (centered)

2. "Video Translator" title

3. Version number

4. Tagline

5. Links section:
   - GitHub repository (opens browser)
   - Apache License 2.0 (opens browser)

6. Open Source Licenses section:
   - List of dependencies with licenses:
     - yt-dlp — Unlicense
     - FFmpeg — LGPL 2.1
     - whisper.cpp — MIT
     - Compose Multiplatform — Apache 2.0
     - Ktor — Apache 2.0
   - "View All Licenses..." link (opens dialog with full text)

Reference docs/ui-mockups.md (Screen 10e: Settings — About Tab)
```

---

## 9. Error Handling & Edge Cases

### Prompt 9.1: Implement Global Error Handling

```
Create a global error handling system:

1. Create ui/error/ErrorHandler.kt:
   - Singleton error handler
   - Queue of errors to display
   - Methods: reportError(), clearError()

2. Create ui/error/GlobalErrorDisplay.kt:
   - Composable that observes error queue
   - Shows snackbar for minor errors
   - Shows dialog for major errors

3. Wrap all coroutine scopes with error handling:
   - Catch exceptions
   - Log errors
   - Report to ErrorHandler

4. Categories:
   - NetworkError (show retry option)
   - ConfigError (show settings)
   - ProcessError (show technical details)
   - UserError (show corrective action)

5. Include "Copy Error Details" button for support
```

### Prompt 9.2: Handle Network Connectivity

```
Implement network connectivity handling:

1. Create infrastructure/network/ConnectivityChecker.kt:
   - Check internet connectivity
   - Check specific service availability
   - Emit connectivity state changes

2. UI integration:
   - Show offline banner when disconnected
   - Disable actions that require network
   - Auto-retry when connection restored

3. Handle:
   - No internet connection
   - Slow connection (timeout adjustments)
   - Service-specific outages (LibreTranslate down)

4. Test connectivity before starting translation
```

### Prompt 9.3: Implement Resource Management

```
Implement memory and resource management:

1. Track memory usage during transcription/rendering

2. Enforce limits from settings:
   - Max 4GB or 60% of available RAM
   - Cancel operation if limit exceeded

3. Cleanup temporary files:
   - On successful completion
   - On cancellation
   - On error
   - On application exit

4. Handle disk space:
   - Check available space before download
   - Warn if insufficient
   - Clean up old cache files

5. Implement graceful degradation:
   - Use smaller Whisper model if memory low
   - Reduce video quality if disk space low
```

### Prompt 9.4: Handle Edge Cases

```
Implement handling for edge cases:

1. Video edge cases:
   - Very long videos (>2 hours): warn about time
   - Very short videos (<30 seconds): handle properly
   - Live streams: detect and reject
   - Private/deleted videos: clear error message
   - Age-restricted: explain limitations
   - Geo-restricted: suggest VPN

2. Caption edge cases:
   - Multiple caption languages available
   - Auto-generated vs manual captions
   - Captions in wrong language
   - No speech in video

3. Translation edge cases:
   - Source = Target language: skip translation
   - Unknown language detected
   - Special characters/emojis
   - Very long subtitle lines

4. Output edge cases:
   - File already exists: prompt for action
   - Invalid characters in filename
   - Path too long (Windows)
   - Permission denied
```

---

## 10. Testing

### Prompt 10.1: Create Unit Tests for Domain Services

```
Create unit tests in src/test/kotlin/:

1. VideoDownloaderTest.kt:
   - Test URL validation
   - Test video info parsing
   - Test VTT parsing
   - Mock ProcessExecutor for yt-dlp output

2. TranscriberServiceTest.kt:
   - Test SRT parsing
   - Test timestamp conversion
   - Test progress parsing
   - Mock Whisper output

3. TranslatorServiceTest.kt:
   - Test LibreTranslate requests
   - Test DeepL requests
   - Test OpenAI requests
   - Test rate limiting handling
   - Mock HTTP responses

4. SubtitleRendererTest.kt:
   - Test SRT generation
   - Test FFmpeg filter building
   - Test filename sanitization

Use MockK for mocking, JUnit 5 for assertions.
```

### Prompt 10.2: Create Integration Tests

```
Create integration tests for the pipeline:

1. PipelineOrchestratorIntegrationTest.kt:
   - Test full pipeline with mocked services
   - Test cancellation at each stage
   - Test error recovery
   - Test progress emission

2. ConfigManagerIntegrationTest.kt:
   - Test settings persistence
   - Test default values
   - Test migration (version upgrades)

3. UpdateManagerIntegrationTest.kt:
   - Test version comparison
   - Test GitHub API parsing
   - Mock download responses

Use testcontainers if Docker available for real service testing.
```

### Prompt 10.3: Create UI Tests

```
Create UI tests using Compose testing:

1. SetupWizardTest.kt:
   - Test navigation between steps
   - Test language selection persistence
   - Test component download flow

2. MainScreenTest.kt:
   - Test URL input validation
   - Test language selection
   - Test output options
   - Test form submission

3. ProgressScreenTest.kt:
   - Test stage transitions
   - Test log display
   - Test completion states
   - Test cancellation

4. SettingsScreenTest.kt:
   - Test tab navigation
   - Test setting changes
   - Test save/cancel

Use createComposeRule() for Compose tests.
```

---

## 11. Build & Distribution

### Prompt 11.1: Configure GitHub Actions CI

```
Create .github/workflows/build.yml:

1. Trigger on:
   - Push to main
   - Pull requests
   - Release tags (v*)

2. Matrix build:
   - Windows (windows-latest)
   - macOS (macos-latest)
   - Linux (ubuntu-latest)

3. Steps:
   - Checkout code
   - Setup JDK 21
   - Cache Gradle
   - Run tests
   - Build distribution (on tag only)
   - Upload artifacts

4. For releases:
   - Create GitHub release
   - Attach installers for each platform
   - Generate release notes from commits
```

### Prompt 11.2: Configure Distribution Packaging

```
Enhance build.gradle.kts for proper distribution:

1. Configure jpackage options for each platform:
   - Windows: MSI with proper Program Files installation
   - macOS: DMG with Applications link
   - Linux: AppImage, DEB, and RPM

2. Add version injection from git tags

3. Configure ProGuard/R8 if needed for size reduction

4. Add license files to distribution

5. Configure automatic updates URL

6. Add app icons at all required sizes

7. Configure file associations (.srt import?)
```

### Prompt 11.3: Create Release Checklist

```
Create RELEASE.md with release process:

1. Pre-release checklist:
   - All tests passing
   - Version number updated
   - Changelog updated
   - Documentation updated
   - Translations complete

2. Release steps:
   - Create release branch
   - Update version in build.gradle.kts
   - Tag release (v1.0.0)
   - Push tag to trigger build
   - Verify artifacts
   - Publish release

3. Post-release:
   - Announce release
   - Monitor for issues
   - Update roadmap

4. Hotfix process:
   - Branch from release tag
   - Fix issue
   - Release patch version
```

---

## Implementation Order Recommendation

For the most efficient development, implement in this order:

1. **Phase 1: Core Infrastructure** (Prompts 1.x, 2.x)
   - Get the project compiling
   - Implement process execution and downloads
   - This enables testing external tools

2. **Phase 2: Domain Services** (Prompts 3.x)
   - Implement video download
   - Implement transcription
   - Implement translation
   - Implement rendering
   - Test each service independently

3. **Phase 3: Setup Wizard UI** (Prompts 4.1-4.3, 5.x)
   - Create shared components
   - Implement setup wizard
   - This is the first-run experience

4. **Phase 4: Main & Progress UI** (Prompts 6.x, 7.x)
   - Implement main screen
   - Implement progress screen
   - Wire up to pipeline

5. **Phase 5: Settings UI** (Prompts 8.x)
   - Implement all settings tabs
   - This can be done in parallel with testing

6. **Phase 6: Polish** (Prompts 9.x, 10.x, 11.x)
   - Error handling
   - Testing
   - Build configuration
   - Release preparation

---

## Notes for Claude Code

1. **Always use i18n** — All user-facing strings must use the i18n system. Never hardcode text.

2. **Follow Compose best practices** — Use remember, derivedStateOf, and LaunchedEffect appropriately.

3. **Handle errors gracefully** — Every external operation can fail. Always have error handling.

4. **Log appropriately** — Use KotlinLogging for debug/info/error logging.

5. **Test as you go** — Write tests alongside implementation, not after.

6. **Reference the docs** — The architecture document and UI mockups have detailed specifications.

7. **Ask for clarification** — If requirements are unclear, ask rather than assume.
