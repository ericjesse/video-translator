package com.ericjesse.videotranslator.infrastructure.update

import com.ericjesse.videotranslator.infrastructure.archive.ArchiveExtractor
import com.ericjesse.videotranslator.infrastructure.archive.ExtractionConfig
import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.config.OperatingSystem
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/**
 * Manages updates for the application and its dependencies.
 *
 * Features:
 * - Download with retry logic and exponential backoff
 * - Resume partial downloads via HTTP Range headers
 * - SHA256 checksum verification
 * - Atomic file replacement (download to temp, verify, move)
 * - Platform-specific archive extraction for FFmpeg and Whisper.cpp
 *
 * @property httpClient HTTP client for downloading files and fetching release information.
 * @property platformPaths Platform-specific paths for binaries, cache, and models directories.
 * @property configManager Configuration manager for reading/writing installed versions.
 */
class UpdateManager(
    private val httpClient: HttpClient,
    private val platformPaths: PlatformPaths,
    private val configManager: ConfigManager
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val archiveExtractor = ArchiveExtractor()

    companion object {
        const val APP_REPO = "ericjesse/video-translator"
        const val YTDLP_REPO = "yt-dlp/yt-dlp"
        const val WHISPER_REPO = "ggerganov/whisper.cpp"

        // Download configuration
        const val MAX_RETRIES = 3
        const val INITIAL_RETRY_DELAY_MS = 1000L
        const val BUFFER_SIZE = 8192

        val WHISPER_MODELS = mapOf(
            "tiny" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
            "base" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
            "small" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
            "medium" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin",
            "large" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3.bin"
        )

        val WHISPER_MODEL_SIZES = mapOf(
            "tiny" to 75L * 1024 * 1024,
            "base" to 142L * 1024 * 1024,
            "small" to 466L * 1024 * 1024,
            "medium" to 1536L * 1024 * 1024,
            "large" to 2952L * 1024 * 1024
        )

        // Known checksums for Whisper models (SHA256)
        val WHISPER_MODEL_CHECKSUMS = mapOf(
            "tiny" to "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
            "base" to "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
            "small" to "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1c7ddb4c18",
            "medium" to "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208",
            "large" to "64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2"
        )
    }

    // ========== Application Updates ==========

    /**
     * Checks for application updates from GitHub.
     */
    suspend fun checkForAppUpdate(): AppUpdateInfo? {
        return try {
            val release = getLatestRelease(APP_REPO)
            val currentVersion = getCurrentAppVersion()

            if (isNewerVersion(release.tagName, currentVersion)) {
                AppUpdateInfo(
                    currentVersion = currentVersion,
                    newVersion = release.tagName,
                    releaseNotes = release.body ?: "",
                    downloadUrl = getAppDownloadUrl(release),
                    publishedAt = release.publishedAt
                )
            } else null
        } catch (e: Exception) {
            logger.warn { "Failed to check for updates: ${e.message}" }
            null
        }
    }

    /**
     * Downloads and installs an application update.
     */
    fun downloadAppUpdate(updateInfo: AppUpdateInfo): Flow<DownloadProgress> = channelFlow {
        send(DownloadProgress(0f, "Starting download..."))

        val tempFile = File(platformPaths.cacheDir, "update-${updateInfo.newVersion}.tmp")
        val targetFile = File(platformPaths.cacheDir, "update-${updateInfo.newVersion}")

        downloadFileWithRetry(
            url = updateInfo.downloadUrl,
            tempFile = tempFile,
            targetFile = targetFile,
            expectedChecksum = null // App updates may not have checksums
        ) { progress, message ->
            send(DownloadProgress(progress * 0.9f, message))
        }

        send(DownloadProgress(1f, "Download complete. Ready to install."))

        // The actual installation would be platform-specific
        // and typically requires restarting the app
    }.flowOn(Dispatchers.IO)

    // ========== Dependency Installation ==========

    /**
     * Downloads and installs yt-dlp.
     * - macOS: Uses Homebrew to get native arm64/x86_64 binaries
     * - Linux: Uses package managers or downloads from GitHub
     * - Windows: Downloads from GitHub releases
     */
    fun installYtDlp(): Flow<DownloadProgress> = channelFlow {
        when (platformPaths.operatingSystem) {
            OperatingSystem.MACOS -> {
                // macOS: Use Homebrew to get native architecture binary
                installYtDlpViaBrew { progress, message ->
                    send(DownloadProgress(progress, message))
                }
            }
            OperatingSystem.LINUX -> {
                // Linux: Try package managers first, fall back to download
                installYtDlpOnLinux { progress, message ->
                    send(DownloadProgress(progress, message))
                }
            }
            OperatingSystem.WINDOWS -> {
                // Windows: Download from GitHub
                installYtDlpFromGitHub { progress, message ->
                    send(DownloadProgress(progress, message))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Installs yt-dlp via Homebrew (macOS only).
     * This ensures native arm64 binaries on Apple Silicon Macs.
     */
    private suspend fun installYtDlpViaBrew(
        onProgress: suspend (Float, String) -> Unit
    ) {
        onProgress(0f, "Checking for Homebrew...")

        val brewPath = findBrewPath()
        if (brewPath == null) {
            throw UpdateException(
                "Homebrew is required to install yt-dlp on macOS. " +
                "Please install Homebrew from https://brew.sh and try again."
            )
        }

        logger.info { "Found Homebrew at: $brewPath" }
        onProgress(0.1f, "Installing yt-dlp via Homebrew...")

        val process = ProcessBuilder(brewPath, "install", "yt-dlp")
            .redirectErrorStream(true)
            .start()

        val reader = process.inputStream.bufferedReader()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            logger.debug { "[brew] $line" }
            when {
                line!!.contains("Downloading") -> onProgress(0.3f, "Downloading yt-dlp...")
                line!!.contains("Pouring") -> onProgress(0.6f, "Installing yt-dlp...")
                line!!.contains("Caveats") -> onProgress(0.8f, "Finalizing installation...")
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw UpdateException("Failed to install yt-dlp via Homebrew (exit code: $exitCode)")
        }

        onProgress(0.9f, "Linking binary...")

        // Find the installed yt-dlp binary
        val ytDlpBrewPath = findBrewYtDlpPath(brewPath)
        if (ytDlpBrewPath == null) {
            throw UpdateException("yt-dlp was installed but binary not found. Try running: brew link yt-dlp")
        }

        // Create symlink to Homebrew binary (preserves dynamic library dependencies)
        val targetFile = File(platformPaths.getBinaryPath("yt-dlp"))
        targetFile.parentFile?.mkdirs()

        Files.deleteIfExists(targetFile.toPath())
        Files.createSymbolicLink(targetFile.toPath(), Path.of(ytDlpBrewPath))
        logger.info { "Created symlink: ${targetFile.absolutePath} -> $ytDlpBrewPath" }

        // Get version from the Homebrew binary directly
        val version = getYtDlpVersion(File(ytDlpBrewPath)) ?: "unknown"
        val versions = configManager.getInstalledVersions()
        configManager.saveInstalledVersions(versions.copy(
            ytDlp = version,
            ytDlpPath = ytDlpBrewPath
        ))

        onProgress(1f, "yt-dlp $version installed via Homebrew")
        logger.info { "yt-dlp $version installed successfully via Homebrew" }
    }

    /**
     * Finds the yt-dlp binary installed by Homebrew.
     */
    private fun findBrewYtDlpPath(brewPath: String): String? {
        // Get the Homebrew prefix for yt-dlp
        val prefixProcess = ProcessBuilder(brewPath, "--prefix", "yt-dlp")
            .redirectErrorStream(true)
            .start()
        val prefix = prefixProcess.inputStream.bufferedReader().readLine()
        prefixProcess.waitFor()

        // Also check common Homebrew bin directories directly
        val brewBinDirs = listOf(
            "/opt/homebrew/bin",  // Apple Silicon
            "/usr/local/bin"      // Intel Mac
        )

        val searchDirs = if (prefix.isNullOrBlank()) {
            brewBinDirs
        } else {
            listOf("$prefix/bin") + brewBinDirs
        }

        for (dir in searchDirs) {
            val ytDlpFile = File("$dir/yt-dlp")
            if (ytDlpFile.exists() && ytDlpFile.canExecute()) {
                logger.debug { "Found yt-dlp at: ${ytDlpFile.absolutePath}" }
                return ytDlpFile.absolutePath
            }
        }

        logger.warn { "Could not find yt-dlp binary. Prefix: $prefix, searched: $searchDirs" }
        return null
    }

    /**
     * Installs yt-dlp on Linux using package managers or downloading from GitHub.
     */
    private suspend fun installYtDlpOnLinux(
        onProgress: suspend (Float, String) -> Unit
    ) {
        onProgress(0f, "Detecting package manager...")

        // Try Linuxbrew first if available
        val brewPath = findBrewPath()
        if (brewPath != null) {
            logger.info { "Found Linuxbrew, using it to install yt-dlp" }
            installYtDlpViaBrew(onProgress)
            return
        }

        // Try system package managers
        val packageManager = detectLinuxPackageManagerForYtDlp()
        if (packageManager != null) {
            onProgress(0.1f, "Installing yt-dlp via ${packageManager.name}...")
            logger.info { "Using ${packageManager.name} to install yt-dlp" }

            val process = ProcessBuilder(*packageManager.installCommand.toTypedArray())
                .redirectErrorStream(true)
                .start()

            val reader = process.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                logger.debug { "[${packageManager.name}] $line" }
            }

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                onProgress(0.9f, "Locating yt-dlp binary...")

                val ytDlpPath = findSystemBinaryPath("yt-dlp")
                if (ytDlpPath != null) {
                    val targetFile = File(platformPaths.getBinaryPath("yt-dlp"))
                    targetFile.parentFile?.mkdirs()

                    // Create symlink to system binary (preserves dynamic library dependencies)
                    Files.deleteIfExists(targetFile.toPath())
                    Files.createSymbolicLink(targetFile.toPath(), Path.of(ytDlpPath))
                    logger.info { "Created symlink: ${targetFile.absolutePath} -> $ytDlpPath" }

                    val version = getYtDlpVersion(File(ytDlpPath)) ?: "unknown"
                    val versions = configManager.getInstalledVersions()
                    configManager.saveInstalledVersions(versions.copy(
                        ytDlp = version,
                        ytDlpPath = ytDlpPath
                    ))

                    onProgress(1f, "yt-dlp $version installed via ${packageManager.name}")
                    logger.info { "yt-dlp installed successfully via ${packageManager.name}" }
                    return
                }
            }
            // Fall through to GitHub download if package manager fails
            logger.warn { "Package manager installation failed, falling back to GitHub download" }
        }

        // Fall back to GitHub download
        installYtDlpFromGitHub(onProgress)
    }

    /**
     * Detects available Linux package manager for yt-dlp installation.
     */
    private fun detectLinuxPackageManagerForYtDlp(): LinuxPackageManager? {
        val packageManagers = listOf(
            LinuxPackageManager("apt", listOf("apt", "install", "-y", "yt-dlp")),
            LinuxPackageManager("dnf", listOf("dnf", "install", "-y", "yt-dlp")),
            LinuxPackageManager("pacman", listOf("pacman", "-S", "--noconfirm", "yt-dlp")),
            LinuxPackageManager("zypper", listOf("zypper", "install", "-y", "yt-dlp")),
            LinuxPackageManager("apk", listOf("apk", "add", "yt-dlp"))
        )

        for (pm in packageManagers) {
            if (isCommandAvailable(pm.name)) {
                logger.debug { "Found package manager for yt-dlp: ${pm.name}" }
                return pm
            }
        }

        return null
    }

    /**
     * Downloads and installs yt-dlp from GitHub releases.
     */
    private suspend fun installYtDlpFromGitHub(
        onProgress: suspend (Float, String) -> Unit
    ) {
        onProgress(0f, "Fetching latest yt-dlp release...")

        val release = getLatestRelease(YTDLP_REPO)
        val downloadUrl = getYtDlpDownloadUrl(release)
        val tempFile = File(platformPaths.cacheDir, "yt-dlp.tmp")
        val targetFile = File(platformPaths.getBinaryPath("yt-dlp"))

        onProgress(0.05f, "Downloading yt-dlp ${release.tagName}...")

        downloadFileWithRetry(
            url = downloadUrl,
            tempFile = tempFile,
            targetFile = targetFile,
            expectedChecksum = null
        ) { progress, message ->
            onProgress(0.05f + progress * 0.9f, message)
        }

        // Make executable on Unix
        if (platformPaths.operatingSystem != OperatingSystem.WINDOWS) {
            targetFile.setExecutable(true)
            logger.debug { "Set executable permission on ${targetFile.name}" }
        }

        // Update version info
        val versions = configManager.getInstalledVersions()
        configManager.saveInstalledVersions(versions.copy(ytDlp = release.tagName))

        onProgress(1f, "yt-dlp ${release.tagName} installed")
        logger.info { "yt-dlp ${release.tagName} installed successfully" }
    }

    /**
     * Gets the installed yt-dlp version.
     */
    private fun getYtDlpVersion(file: File): String? {
        return try {
            val process = ProcessBuilder(file.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            val version = process.inputStream.bufferedReader().readLine()
            process.waitFor()
            if (process.exitValue() == 0) version?.trim() else null
        } catch (e: Exception) {
            logger.debug { "Could not get yt-dlp version: ${e.message}" }
            null
        }
    }

    /**
     * Downloads and installs FFmpeg.
     * - macOS: Uses Homebrew to get native arm64/x86_64 binaries
     * - Linux: Uses package managers (apt, dnf, pacman) or Homebrew
     * - Windows: Downloads pre-built binaries from the web
     */
    fun installFfmpeg(): Flow<DownloadProgress> = channelFlow {
        when (platformPaths.operatingSystem) {
            OperatingSystem.MACOS -> {
                // macOS: Use Homebrew to get native architecture binaries
                installFfmpegViaBrew { progress, message ->
                    send(DownloadProgress(progress, message))
                }
            }
            OperatingSystem.LINUX -> {
                // Linux: Use package managers or Homebrew
                installFfmpegOnLinux { progress, message ->
                    send(DownloadProgress(progress, message))
                }
            }
            OperatingSystem.WINDOWS -> {
                // Windows: Download pre-built binaries
                installFfmpegFromWeb { progress, message ->
                    send(DownloadProgress(progress, message))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Installs FFmpeg via Homebrew (macOS only).
     * This ensures native arm64 binaries on Apple Silicon Macs.
     */
    private suspend fun installFfmpegViaBrew(
        onProgress: suspend (Float, String) -> Unit
    ) {
        onProgress(0f, "Checking for Homebrew...")

        // Check if brew is available
        val brewPath = findBrewPath()
        if (brewPath == null) {
            throw UpdateException(
                "Homebrew is required to install FFmpeg on macOS. " +
                "Please install Homebrew from https://brew.sh and try again."
            )
        }

        logger.info { "Found Homebrew at: $brewPath" }
        onProgress(0.1f, "Installing FFmpeg via Homebrew...")

        // Install ffmpeg via brew
        val process = ProcessBuilder(brewPath, "install", "ffmpeg")
            .redirectErrorStream(true)
            .start()

        // Read output for progress updates
        val reader = process.inputStream.bufferedReader()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            logger.debug { "[brew] $line" }
            // Update progress based on output
            when {
                line!!.contains("Downloading") -> onProgress(0.3f, "Downloading FFmpeg...")
                line!!.contains("Pouring") -> onProgress(0.6f, "Installing FFmpeg...")
                line!!.contains("Caveats") -> onProgress(0.8f, "Finalizing installation...")
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw UpdateException("Failed to install FFmpeg via Homebrew (exit code: $exitCode)")
        }

        onProgress(0.9f, "Linking binaries...")

        // Find the installed FFmpeg binaries
        val (ffmpegBrewPath, ffprobeBrewPath) = findBrewFfmpegPaths(brewPath)
        if (ffmpegBrewPath == null) {
            throw UpdateException("FFmpeg was installed but binary not found. Try running: brew link ffmpeg")
        }

        // Create symlinks to Homebrew binaries (preserves dynamic library dependencies)
        val ffmpegTarget = File(platformPaths.getBinaryPath("ffmpeg"))
        val ffprobeTarget = File(platformPaths.getBinaryPath("ffprobe"))
        ffmpegTarget.parentFile?.mkdirs()

        // Delete existing files/symlinks before creating new symlinks
        Files.deleteIfExists(ffmpegTarget.toPath())
        Files.createSymbolicLink(ffmpegTarget.toPath(), Path.of(ffmpegBrewPath))
        logger.info { "Created symlink: ${ffmpegTarget.absolutePath} -> $ffmpegBrewPath" }

        if (ffprobeBrewPath != null) {
            Files.deleteIfExists(ffprobeTarget.toPath())
            Files.createSymbolicLink(ffprobeTarget.toPath(), Path.of(ffprobeBrewPath))
            logger.info { "Created symlink: ${ffprobeTarget.absolutePath} -> $ffprobeBrewPath" }
        }

        // Get version from the Homebrew binary directly
        val version = getFfmpegVersion(File(ffmpegBrewPath)) ?: "unknown"
        val versions = configManager.getInstalledVersions()
        configManager.saveInstalledVersions(versions.copy(
            ffmpeg = version,
            ffmpegPath = ffmpegBrewPath,
            ffprobePath = ffprobeBrewPath
        ))

        onProgress(1f, "FFmpeg $version installed via Homebrew")
        logger.info { "FFmpeg $version installed successfully via Homebrew" }
    }

    /**
     * Finds the FFmpeg and FFprobe binaries installed by Homebrew.
     * Returns a pair of (ffmpegPath, ffprobePath), where ffprobePath may be null.
     */
    private fun findBrewFfmpegPaths(brewPath: String): Pair<String?, String?> {
        // Get the Homebrew prefix for ffmpeg
        val prefixProcess = ProcessBuilder(brewPath, "--prefix", "ffmpeg")
            .redirectErrorStream(true)
            .start()
        val prefix = prefixProcess.inputStream.bufferedReader().readLine()
        prefixProcess.waitFor()

        // Also check common Homebrew bin directories directly
        val brewBinDirs = listOf(
            "/opt/homebrew/bin",  // Apple Silicon
            "/usr/local/bin"      // Intel Mac
        )

        val searchDirs = if (prefix.isNullOrBlank()) {
            brewBinDirs
        } else {
            listOf("$prefix/bin") + brewBinDirs
        }

        var ffmpegPath: String? = null
        var ffprobePath: String? = null

        for (dir in searchDirs) {
            val ffmpegFile = File("$dir/ffmpeg")
            val ffprobeFile = File("$dir/ffprobe")

            if (ffmpegFile.exists() && ffmpegFile.canExecute()) {
                ffmpegPath = ffmpegFile.absolutePath
                logger.debug { "Found ffmpeg at: $ffmpegPath" }
            }
            if (ffprobeFile.exists() && ffprobeFile.canExecute()) {
                ffprobePath = ffprobeFile.absolutePath
                logger.debug { "Found ffprobe at: $ffprobePath" }
            }

            if (ffmpegPath != null && ffprobePath != null) break
        }

        // Log what we found for debugging
        if (ffmpegPath == null) {
            logger.warn { "Could not find ffmpeg binary. Prefix: $prefix, searched: $searchDirs" }
        }

        return Pair(ffmpegPath, ffprobePath)
    }

    /**
     * Installs FFmpeg on Linux using package managers or Homebrew.
     */
    private suspend fun installFfmpegOnLinux(
        onProgress: suspend (Float, String) -> Unit
    ) {
        onProgress(0f, "Detecting package manager...")

        // Try Linuxbrew first if available
        val brewPath = findBrewPath()
        if (brewPath != null) {
            logger.info { "Found Linuxbrew, using it to install FFmpeg" }
            installFfmpegViaBrew(onProgress)
            return
        }

        // Detect Linux package manager
        val packageManager = detectLinuxPackageManagerForFfmpeg()

        if (packageManager != null) {
            onProgress(0.1f, "Installing FFmpeg via ${packageManager.name}...")
            logger.info { "Using ${packageManager.name} to install FFmpeg" }

            val process = ProcessBuilder(*packageManager.installCommand.toTypedArray())
                .redirectErrorStream(true)
                .start()

            val reader = process.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                logger.debug { "[${packageManager.name}] $line" }
                when {
                    line!!.contains("Downloading", ignoreCase = true) ||
                    line!!.contains("Get:", ignoreCase = true) ->
                        onProgress(0.3f, "Downloading FFmpeg...")
                    line!!.contains("Unpacking", ignoreCase = true) ||
                    line!!.contains("Installing", ignoreCase = true) ->
                        onProgress(0.6f, "Installing FFmpeg...")
                    line!!.contains("Setting up", ignoreCase = true) ||
                    line!!.contains("running", ignoreCase = true) ->
                        onProgress(0.8f, "Finalizing installation...")
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw UpdateException(
                    "Failed to install FFmpeg via ${packageManager.name} (exit code: $exitCode). " +
                    "You may need to run: sudo ${packageManager.installCommand.joinToString(" ")}"
                )
            }

            onProgress(0.9f, "Locating FFmpeg binaries...")

            // Find the installed binaries
            val ffmpegPath = findSystemBinaryPath("ffmpeg")
            val ffprobePath = findSystemBinaryPath("ffprobe")

            if (ffmpegPath == null) {
                throw UpdateException(
                    "FFmpeg was installed but binary not found. " +
                    "Try running: which ffmpeg"
                )
            }

            // Create symlinks to system binaries (preserves dynamic library dependencies)
            val ffmpegTarget = File(platformPaths.getBinaryPath("ffmpeg"))
            val ffprobeTarget = File(platformPaths.getBinaryPath("ffprobe"))
            ffmpegTarget.parentFile?.mkdirs()

            Files.deleteIfExists(ffmpegTarget.toPath())
            Files.createSymbolicLink(ffmpegTarget.toPath(), Path.of(ffmpegPath))
            logger.info { "Created symlink: ${ffmpegTarget.absolutePath} -> $ffmpegPath" }

            if (ffprobePath != null) {
                Files.deleteIfExists(ffprobeTarget.toPath())
                Files.createSymbolicLink(ffprobeTarget.toPath(), Path.of(ffprobePath))
                logger.info { "Created symlink: ${ffprobeTarget.absolutePath} -> $ffprobePath" }
            }

            val version = getFfmpegVersion(File(ffmpegPath)) ?: "unknown"
            val versions = configManager.getInstalledVersions()
            configManager.saveInstalledVersions(versions.copy(
                ffmpeg = version,
                ffmpegPath = ffmpegPath,
                ffprobePath = ffprobePath
            ))

            onProgress(1f, "FFmpeg $version installed via ${packageManager.name}")
            logger.info { "FFmpeg installed successfully via ${packageManager.name}" }
            return
        }

        // No package manager found - provide instructions
        throw UpdateException(
            "Could not automatically install FFmpeg on Linux. Please install manually:\n\n" +
            "Ubuntu/Debian: sudo apt install ffmpeg\n" +
            "Fedora/RHEL: sudo dnf install ffmpeg\n" +
            "Arch Linux: sudo pacman -S ffmpeg\n" +
            "Or install Homebrew and run: brew install ffmpeg"
        )
    }

    /**
     * Detects available Linux package manager for FFmpeg installation.
     */
    private fun detectLinuxPackageManagerForFfmpeg(): LinuxPackageManager? {
        // Package managers in order of preference
        val packageManagers = listOf(
            LinuxPackageManager("apt", listOf("apt", "install", "-y", "ffmpeg")),
            LinuxPackageManager("dnf", listOf("dnf", "install", "-y", "ffmpeg")),
            LinuxPackageManager("yum", listOf("yum", "install", "-y", "ffmpeg")),
            LinuxPackageManager("pacman", listOf("pacman", "-S", "--noconfirm", "ffmpeg")),
            LinuxPackageManager("zypper", listOf("zypper", "install", "-y", "ffmpeg")),
            LinuxPackageManager("apk", listOf("apk", "add", "ffmpeg"))
        )

        for (pm in packageManagers) {
            if (isCommandAvailable(pm.name)) {
                logger.debug { "Found package manager: ${pm.name}" }
                return pm
            }
        }

        return null
    }

    /**
     * Finds a binary in the system PATH.
     */
    private fun findSystemBinaryPath(binaryName: String): String? {
        return try {
            val process = ProcessBuilder("which", binaryName)
                .redirectErrorStream(true)
                .start()
            val result = process.inputStream.bufferedReader().readLine()
            process.waitFor()
            if (process.exitValue() == 0 && result?.isNotBlank() == true) {
                logger.debug { "Found $binaryName in PATH: $result" }
                result.trim()
            } else null
        } catch (e: Exception) {
            logger.debug { "Could not find $binaryName in PATH: ${e.message}" }
            null
        }
    }

    /**
     * Downloads and installs FFmpeg from the web (Windows only).
     */
    private suspend fun installFfmpegFromWeb(
        onProgress: suspend (Float, String) -> Unit
    ) {
        onProgress(0f, "Fetching FFmpeg...")

        val downloadUrl = getFfmpegDownloadUrl()
        val archiveExtension = if (platformPaths.operatingSystem == OperatingSystem.LINUX) ".tar.xz" else ".zip"
        val tempFile = File(platformPaths.cacheDir, "ffmpeg-download.tmp")
        val archiveFile = File(platformPaths.cacheDir, "ffmpeg$archiveExtension")
        val extractDir = File(platformPaths.cacheDir, "ffmpeg-extract")

        onProgress(0.05f, "Downloading FFmpeg...")

        downloadFileWithRetry(
            url = downloadUrl,
            tempFile = tempFile,
            targetFile = archiveFile,
            expectedChecksum = null
        ) { progress, message ->
            onProgress(0.05f + progress * 0.65f, message)
        }

        onProgress(0.7f, "Extracting FFmpeg...")

        // Clean up existing extraction directory
        if (extractDir.exists()) {
            extractDir.deleteRecursively()
        }
        extractDir.mkdirs()

        // Extract the archive
        val extractionResult = archiveExtractor.extractBlocking(
            archivePath = archiveFile.toPath(),
            destinationDir = extractDir.toPath(),
            config = ExtractionConfig(flattenSingleRoot = true)
        ) { progress ->
            val extractProgress = progress.percentage
            if (extractProgress >= 0) {
                onProgress(0.7f + extractProgress * 0.2f, "Extracting: ${progress.currentFile}")
            }
        }

        onProgress(0.9f, "Installing FFmpeg binaries...")

        // Find and install the binaries
        val (ffmpegPath, ffprobePath) = archiveExtractor.findFfmpegBinaries(extractionResult.extractedPath)

        if (ffmpegPath == null) {
            throw UpdateException("FFmpeg binary not found in archive")
        }

        // Copy binaries to bin directory
        val ffmpegTarget = File(platformPaths.getBinaryPath("ffmpeg"))
        val ffprobeTarget = File(platformPaths.getBinaryPath("ffprobe"))

        Files.copy(ffmpegPath, ffmpegTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
        logger.info { "Installed ffmpeg to ${ffmpegTarget.absolutePath}" }

        if (ffprobePath != null) {
            Files.copy(ffprobePath, ffprobeTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
            logger.info { "Installed ffprobe to ${ffprobeTarget.absolutePath}" }
        }

        // Make executable on Unix
        if (platformPaths.operatingSystem != OperatingSystem.WINDOWS) {
            ffmpegTarget.setExecutable(true)
            ffprobeTarget.setExecutable(true)
        }

        // Cleanup
        archiveFile.delete()
        extractDir.deleteRecursively()

        // Get version from ffmpeg
        val version = getFfmpegVersion(ffmpegTarget) ?: "unknown"

        // Update version info
        val versions = configManager.getInstalledVersions()
        configManager.saveInstalledVersions(versions.copy(ffmpeg = version))

        onProgress(1f, "FFmpeg $version installed")
        logger.info { "FFmpeg $version installed successfully" }
    }

    /**
     * Downloads a Whisper model with checksum verification.
     */
    fun installWhisperModel(modelName: String): Flow<DownloadProgress> = channelFlow {
        val url = WHISPER_MODELS[modelName]
            ?: throw IllegalArgumentException("Unknown model: $modelName")
        val expectedChecksum = WHISPER_MODEL_CHECKSUMS[modelName]

        send(DownloadProgress(0f, "Preparing to download Whisper $modelName model..."))

        val modelDir = File(platformPaths.modelsDir, "whisper").apply { mkdirs() }
        val tempFile = File(platformPaths.cacheDir, "ggml-$modelName.bin.tmp")
        val targetFile = File(modelDir, "ggml-$modelName.bin")

        downloadFileWithRetry(
            url = url,
            tempFile = tempFile,
            targetFile = targetFile,
            expectedChecksum = expectedChecksum
        ) { progress, message ->
            send(DownloadProgress(progress * 0.95f, message))
        }

        // Update version info
        val versions = configManager.getInstalledVersions()
        configManager.saveInstalledVersions(versions.copy(whisperModel = modelName))

        send(DownloadProgress(1f, "Whisper $modelName model installed"))
        logger.info { "Whisper model '$modelName' installed successfully" }
    }.flowOn(Dispatchers.IO)

    /**
     * Downloads and installs whisper.cpp binary.
     * - Windows: Downloads pre-built binary from GitHub releases
     * - macOS: Uses Homebrew (no pre-built binaries available)
     * - Linux: Uses apt/dnf package manager or builds from source
     */
    fun installWhisperCpp(): Flow<DownloadProgress> = channelFlow {
        when (platformPaths.operatingSystem) {
            OperatingSystem.MACOS -> {
                // macOS: Use Homebrew since whisper.cpp doesn't provide pre-built macOS binaries
                installWhisperCppViaBrew { progress, message ->
                    send(DownloadProgress(progress, message))
                }
            }
            OperatingSystem.LINUX -> {
                // Linux: Try package manager, fall back to building from source
                installWhisperCppOnLinux { progress, message ->
                    send(DownloadProgress(progress, message))
                }
            }
            OperatingSystem.WINDOWS -> {
                // Windows: Download from GitHub releases
                installWhisperCppFromGitHub { progress, message ->
                    send(DownloadProgress(progress, message))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Installs whisper.cpp via Homebrew (macOS only).
     */
    private suspend fun installWhisperCppViaBrew(
        onProgress: suspend (Float, String) -> Unit
    ) {
        onProgress(0f, "Checking for Homebrew...")

        // Check if brew is available
        val brewPath = findBrewPath()
        if (brewPath == null) {
            throw UpdateException(
                "Homebrew is required to install whisper.cpp on macOS. " +
                "Please install Homebrew from https://brew.sh and try again."
            )
        }

        logger.info { "Found Homebrew at: $brewPath" }
        onProgress(0.1f, "Installing whisper.cpp via Homebrew...")

        // Install whisper-cpp via brew
        val process = ProcessBuilder(brewPath, "install", "whisper-cpp")
            .redirectErrorStream(true)
            .start()

        // Read output for progress updates
        val reader = process.inputStream.bufferedReader()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            logger.debug { "[brew] $line" }
            // Update progress based on output
            when {
                line!!.contains("Downloading") -> onProgress(0.3f, "Downloading whisper-cpp...")
                line!!.contains("Pouring") -> onProgress(0.6f, "Installing whisper-cpp...")
                line!!.contains("Caveats") -> onProgress(0.8f, "Finalizing installation...")
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw UpdateException("Failed to install whisper-cpp via Homebrew (exit code: $exitCode)")
        }

        onProgress(0.9f, "Creating symlink...")

        // Find the installed whisper binary
        val whisperBrewPath = findBrewWhisperPath(brewPath)
        if (whisperBrewPath == null) {
            throw UpdateException("whisper-cpp was installed but binary not found. Try running: brew link whisper-cpp")
        }

        // Create symlink to Homebrew binary (preserves dynamic library dependencies)
        val whisperTarget = File(platformPaths.getBinaryPath("whisper"))
        whisperTarget.parentFile?.mkdirs()

        Files.deleteIfExists(whisperTarget.toPath())
        Files.createSymbolicLink(whisperTarget.toPath(), Path.of(whisperBrewPath))
        logger.info { "Created symlink: ${whisperTarget.absolutePath} -> $whisperBrewPath" }

        // Get version from the Homebrew binary directly
        val version = getWhisperVersion(File(whisperBrewPath)) ?: "unknown"
        val versions = configManager.getInstalledVersions()
        configManager.saveInstalledVersions(versions.copy(
            whisperCpp = version,
            whisperPath = whisperBrewPath
        ))

        onProgress(1f, "whisper.cpp $version installed via Homebrew")
        logger.info { "whisper.cpp $version installed successfully via Homebrew" }
    }

    /**
     * Finds the Homebrew executable path.
     */
    private fun findBrewPath(): String? {
        // Check common Homebrew locations
        val brewPaths = listOf(
            "/opt/homebrew/bin/brew",      // Apple Silicon
            "/usr/local/bin/brew",          // Intel Mac
            "/home/linuxbrew/.linuxbrew/bin/brew"  // Linux
        )

        for (path in brewPaths) {
            if (File(path).exists()) {
                return path
            }
        }

        // Try to find in PATH
        return try {
            val process = ProcessBuilder("which", "brew")
                .redirectErrorStream(true)
                .start()
            val result = process.inputStream.bufferedReader().readLine()
            process.waitFor()
            if (process.exitValue() == 0 && result?.isNotBlank() == true) result else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Finds the whisper binary installed by Homebrew.
     */
    private fun findBrewWhisperPath(brewPath: String): String? {
        // Get the Homebrew prefix
        val prefixProcess = ProcessBuilder(brewPath, "--prefix", "whisper-cpp")
            .redirectErrorStream(true)
            .start()
        val prefix = prefixProcess.inputStream.bufferedReader().readLine()
        prefixProcess.waitFor()

        if (prefix.isNullOrBlank()) return null

        // Check for the binary in various locations
        // Note: Homebrew's whisper-cpp installs as "whisper-cli" not "whisper"
        val binaryNames = listOf("whisper-cli", "whisper", "whisper-cpp", "main")
        val searchDirs = listOf("$prefix/bin", "$prefix/libexec/bin", prefix)

        for (dir in searchDirs) {
            for (name in binaryNames) {
                val path = "$dir/$name"
                val file = File(path)
                if (file.exists() && file.canExecute()) {
                    logger.debug { "Found whisper binary at: $path" }
                    return path
                }
            }
        }

        // Also check common Homebrew bin directories
        val brewBinDirs = listOf("/opt/homebrew/bin", "/usr/local/bin")
        for (dir in brewBinDirs) {
            for (name in binaryNames) {
                val path = "$dir/$name"
                val file = File(path)
                if (file.exists() && file.canExecute()) {
                    logger.debug { "Found whisper binary in Homebrew bin: $path" }
                    return path
                }
            }
        }

        // Log what we found for debugging
        logger.warn { "Could not find whisper binary. Prefix: $prefix" }
        try {
            val binDir = File("$prefix/bin")
            if (binDir.exists()) {
                logger.debug { "Contents of $prefix/bin: ${binDir.listFiles()?.map { it.name }}" }
            }
        } catch (e: Exception) {
            logger.debug { "Could not list prefix bin directory: ${e.message}" }
        }

        return null
    }

    /**
     * Gets whisper.cpp version by running whisper --version or parsing help output.
     */
    private fun getWhisperVersion(whisperFile: File): String? {
        return try {
            // Try --version first
            var process = ProcessBuilder(whisperFile.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            var output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            if (process.exitValue() == 0 && output.isNotBlank()) {
                return output.trim().take(20)
            }

            // Fall back to -h and look for version in output
            process = ProcessBuilder(whisperFile.absolutePath, "-h")
                .redirectErrorStream(true)
                .start()
            output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val versionRegex = Regex("v?(\\d+\\.\\d+\\.\\d+)")
            versionRegex.find(output)?.groupValues?.get(1)
        } catch (e: Exception) {
            logger.debug { "Could not get whisper version: ${e.message}" }
            null
        }
    }

    /**
     * Installs whisper.cpp on Linux using package manager or Homebrew.
     */
    private suspend fun installWhisperCppOnLinux(
        onProgress: suspend (Float, String) -> Unit
    ) {
        onProgress(0f, "Detecting package manager...")

        // Try Homebrew first (Linuxbrew)
        val brewPath = findBrewPath()
        if (brewPath != null) {
            logger.info { "Found Linuxbrew, using it to install whisper.cpp" }
            installWhisperCppViaBrew(onProgress)
            return
        }

        // Try to find a system package manager
        val packageManager = detectLinuxPackageManagerForWhisper()

        if (packageManager != null) {
            onProgress(0.1f, "Installing whisper.cpp via ${packageManager.name}...")
            logger.info { "Using ${packageManager.name} to install whisper.cpp" }

            try {
                val process = ProcessBuilder(*packageManager.installCommand.toTypedArray())
                    .redirectErrorStream(true)
                    .start()

                val reader = process.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    logger.debug { "[${packageManager.name}] $line" }
                    when {
                        line!!.contains("Downloading", ignoreCase = true) ->
                            onProgress(0.3f, "Downloading whisper.cpp...")
                        line!!.contains("Installing", ignoreCase = true) ->
                            onProgress(0.6f, "Installing whisper.cpp...")
                        line!!.contains("Setting up", ignoreCase = true) ->
                            onProgress(0.8f, "Finalizing installation...")
                    }
                }

                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    throw UpdateException(
                        "Failed to install whisper.cpp via ${packageManager.name} (exit code: $exitCode). " +
                        "You may need to run with sudo privileges."
                    )
                }

                // Find the installed binary
                val whisperPath = findSystemWhisperPath()
                if (whisperPath == null) {
                    throw UpdateException(
                        "whisper.cpp was installed but binary not found. " +
                        "Try running: which whisper"
                    )
                }

                onProgress(0.9f, "Linking binary...")

                // Create symlink to system binary (preserves dynamic library dependencies)
                val whisperTarget = File(platformPaths.getBinaryPath("whisper"))
                whisperTarget.parentFile?.mkdirs()
                Files.deleteIfExists(whisperTarget.toPath())
                Files.createSymbolicLink(whisperTarget.toPath(), Path.of(whisperPath))
                logger.info { "Created symlink: ${whisperTarget.absolutePath} -> $whisperPath" }

                val version = getWhisperVersion(File(whisperPath)) ?: "unknown"
                val versions = configManager.getInstalledVersions()
                configManager.saveInstalledVersions(versions.copy(
                    whisperCpp = version,
                    whisperPath = whisperPath
                ))

                onProgress(1f, "whisper.cpp $version installed")
                logger.info { "whisper.cpp installed successfully via ${packageManager.name}" }
                return

            } catch (e: UpdateException) {
                throw e
            } catch (e: Exception) {
                logger.warn { "Package manager installation failed: ${e.message}" }
                // Fall through to build from source
            }
        }

        // No package manager available - provide instructions
        throw UpdateException(
            "Could not automatically install whisper.cpp on Linux. Please install manually:\n\n" +
            "Option 1: Install Homebrew and run: brew install whisper-cpp\n" +
            "Option 2: Use your package manager (if available):\n" +
            "  Arch Linux: sudo pacman -S whisper.cpp\n" +
            "  Fedora: sudo dnf install whisper-cpp\n" +
            "Option 3: Build from source:\n" +
            "  git clone https://github.com/ggerganov/whisper.cpp.git\n" +
            "  cd whisper.cpp && make\n" +
            "  cp main ~/.local/share/VideoTranslator/bin/whisper"
        )
    }

    /**
     * Represents a Linux package manager.
     */
    private data class LinuxPackageManager(
        val name: String,
        val installCommand: List<String>
    )

    /**
     * Detects available Linux package manager for whisper.cpp installation.
     * Note: whisper.cpp may not be available in all package managers.
     */
    private fun detectLinuxPackageManagerForWhisper(): LinuxPackageManager? {
        // Package managers in order of preference
        // Note: whisper.cpp package names vary by distro
        val packageManagers = listOf(
            // Arch Linux AUR (whisper.cpp is available)
            LinuxPackageManager("pacman", listOf("pacman", "-S", "--noconfirm", "whisper.cpp")),
            // Fedora/RHEL (if available in repos)
            LinuxPackageManager("dnf", listOf("dnf", "install", "-y", "whisper-cpp")),
            // Ubuntu/Debian (if available in repos)
            LinuxPackageManager("apt", listOf("apt", "install", "-y", "whisper.cpp")),
            // openSUSE
            LinuxPackageManager("zypper", listOf("zypper", "install", "-y", "whisper-cpp")),
            // Alpine
            LinuxPackageManager("apk", listOf("apk", "add", "whisper-cpp"))
        )

        for (pm in packageManagers) {
            if (isCommandAvailable(pm.name)) {
                logger.debug { "Found package manager for whisper: ${pm.name}" }
                return pm
            }
        }

        return null
    }

    /**
     * Checks if a command is available in PATH.
     */
    private fun isCommandAvailable(command: String): Boolean {
        return try {
            val process = ProcessBuilder("which", command)
                .redirectErrorStream(true)
                .start()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Finds whisper binary in system PATH.
     */
    private fun findSystemWhisperPath(): String? {
        val binaryNames = listOf("whisper-cli", "whisper", "whisper-cpp", "main")

        for (name in binaryNames) {
            try {
                val process = ProcessBuilder("which", name)
                    .redirectErrorStream(true)
                    .start()
                val result = process.inputStream.bufferedReader().readLine()
                process.waitFor()
                if (process.exitValue() == 0 && result?.isNotBlank() == true) {
                    logger.debug { "Found whisper in PATH: $result" }
                    return result
                }
            } catch (e: Exception) {
                continue
            }
        }

        return null
    }

    /**
     * Downloads and installs whisper.cpp from GitHub releases (Windows only).
     */
    private suspend fun installWhisperCppFromGitHub(
        onProgress: suspend (Float, String) -> Unit
    ) {
        // On Windows, ensure Visual C++ Redistributable is installed (required for whisper.cpp)
        if (platformPaths.operatingSystem == OperatingSystem.WINDOWS) {
            ensureVcRedistInstalled(onProgress)
        }

        onProgress(0.1f, "Fetching whisper.cpp release...")

        val release = getLatestRelease(WHISPER_REPO)
        val downloadUrl = getWhisperCppDownloadUrl(release)
        val tempFile = File(platformPaths.cacheDir, "whisper-download.tmp")
        val archiveFile = File(platformPaths.cacheDir, "whisper.zip")
        val extractDir = File(platformPaths.cacheDir, "whisper-extract")

        onProgress(0.15f, "Downloading whisper.cpp ${release.tagName}...")

        downloadFileWithRetry(
            url = downloadUrl,
            tempFile = tempFile,
            targetFile = archiveFile,
            expectedChecksum = null
        ) { progress, message ->
            onProgress(0.15f + progress * 0.55f, message)
        }

        onProgress(0.7f, "Extracting whisper.cpp...")

        // Clean up existing extraction directory
        if (extractDir.exists()) {
            extractDir.deleteRecursively()
        }
        extractDir.mkdirs()

        // Extract the archive
        val extractionResult = archiveExtractor.extractBlocking(
            archivePath = archiveFile.toPath(),
            destinationDir = extractDir.toPath(),
            config = ExtractionConfig(flattenSingleRoot = true)
        ) { progress ->
            val extractProgress = progress.percentage
            if (extractProgress >= 0) {
                onProgress(0.7f + extractProgress * 0.2f, "Extracting: ${progress.currentFile}")
            }
        }

        onProgress(0.9f, "Installing whisper.cpp...")

        // Find the whisper binary (might be named 'whisper-cli', 'main', 'whisper', etc.)
        // Note: whisper.cpp renamed main.exe to whisper-cli.exe in recent versions
        val whisperBinaryNames = listOf("whisper-cli", "main", "whisper", "whisper-cpp", "whisper.cpp")
        var whisperPath: Path? = null

        for (name in whisperBinaryNames) {
            whisperPath = archiveExtractor.findBinary(extractionResult.extractedPath, name)
            if (whisperPath != null) {
                logger.debug { "Found whisper binary as '$name' at $whisperPath" }
                break
            }
        }

        if (whisperPath == null) {
            // Try to find any executable in bin directory
            val binaries = archiveExtractor.findAllBinaries(extractionResult.extractedPath)
            whisperPath = binaries.values.firstOrNull()
        }

        if (whisperPath == null) {
            throw UpdateException("whisper.cpp binary not found in archive")
        }

        // Copy to bin directory
        val whisperTarget = File(platformPaths.getBinaryPath("whisper"))
        Files.copy(whisperPath, whisperTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
        logger.info { "Installed whisper to ${whisperTarget.absolutePath}" }

        // On Windows, copy all DLLs from the whisper binary directory to the bin directory
        // whisper.cpp releases include required DLLs (ggml.dll, whisper.dll, etc.) that must be
        // in the same directory as the executable
        if (platformPaths.operatingSystem == OperatingSystem.WINDOWS) {
            val whisperBinaryDir = whisperPath.parent?.toFile()
            val binDir = File(platformPaths.binDir)

            if (whisperBinaryDir != null && whisperBinaryDir.exists()) {
                val dllFiles = whisperBinaryDir.listFiles { file ->
                    file.isFile && file.extension.equals("dll", ignoreCase = true)
                } ?: emptyArray()

                for (dllFile in dllFiles) {
                    val targetDll = File(binDir, dllFile.name)
                    Files.copy(dllFile.toPath(), targetDll.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    logger.info { "Copied DLL: ${dllFile.name} to ${targetDll.absolutePath}" }
                }

                if (dllFiles.isNotEmpty()) {
                    logger.info { "Copied ${dllFiles.size} DLL(s) to bin directory" }
                }
            }
        }

        // Make executable on Unix
        if (platformPaths.operatingSystem != OperatingSystem.WINDOWS) {
            whisperTarget.setExecutable(true)

            // Remove macOS quarantine attribute
            if (platformPaths.operatingSystem == OperatingSystem.MACOS) {
                removeQuarantineAttribute(whisperTarget)
            }
        }

        // Cleanup
        archiveFile.delete()
        extractDir.deleteRecursively()

        // Update version info
        val versions = configManager.getInstalledVersions()
        configManager.saveInstalledVersions(versions.copy(whisperCpp = release.tagName))

        onProgress(1f, "whisper.cpp ${release.tagName} installed")
        logger.info { "whisper.cpp ${release.tagName} installed successfully" }
    }

    // ========== Python Installation ==========

    /**
     * Installs Python 3.11 using platform-specific package managers.
     * - macOS: Uses Homebrew
     * - Linux: Uses system package managers (apt, dnf, pacman, etc.)
     * - Windows: Uses winget
     */
    fun installPython(): Flow<DownloadProgress> = channelFlow {
        when (platformPaths.operatingSystem) {
            OperatingSystem.MACOS -> {
                installPythonViaBrew { progress, message ->
                    send(DownloadProgress(progress, message))
                }
            }

            OperatingSystem.LINUX -> {
                installPythonOnLinux { progress, message ->
                    send(DownloadProgress(progress, message))
                }
            }

            OperatingSystem.WINDOWS -> {
                installPythonOnWindows { progress, message ->
                    send(DownloadProgress(progress, message))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Installs Python via Homebrew (macOS).
     */
    private suspend fun installPythonViaBrew(
        onProgress: suspend (Float, String) -> Unit,
    ) {
        onProgress(0f, "Checking for Homebrew...")

        val brewPath = findBrewPath()
        if (brewPath == null) {
            throw UpdateException(
                "Homebrew is required to install Python on macOS. " +
                        "Please install Homebrew from https://brew.sh and try again."
            )
        }

        logger.info { "Found Homebrew at: $brewPath" }
        onProgress(0.1f, "Installing Python via Homebrew...")

        val process = ProcessBuilder(brewPath, "install", "python@3.11")
            .redirectErrorStream(true)
            .start()

        val reader = process.inputStream.bufferedReader()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            logger.debug { "[brew] $line" }
            when {
                line!!.contains("Downloading") -> onProgress(0.3f, "Downloading Python...")
                line!!.contains("Pouring") -> onProgress(0.6f, "Installing Python...")
                line!!.contains("Caveats") -> onProgress(0.8f, "Finalizing installation...")
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw UpdateException("Failed to install Python via Homebrew (exit code: $exitCode)")
        }

        // Verify installation
        val pythonPath = findPythonPath()
        if (pythonPath == null) {
            throw UpdateException("Python was installed but could not be found in PATH. Try restarting your terminal.")
        }

        onProgress(1f, "Python installed via Homebrew")
        logger.info { "Python installed successfully via Homebrew at: $pythonPath" }
    }

    /**
     * Installs Python on Linux using package managers.
     */
    private suspend fun installPythonOnLinux(
        onProgress: suspend (Float, String) -> Unit,
    ) {
        onProgress(0f, "Detecting package manager...")

        // Try Linuxbrew first if available
        val brewPath = findBrewPath()
        if (brewPath != null) {
            logger.info { "Found Linuxbrew, using it to install Python" }
            installPythonViaBrew(onProgress)
            return
        }

        val packageManager = detectLinuxPackageManagerForPython()

        if (packageManager != null) {
            onProgress(0.1f, "Installing Python via ${packageManager.name}...")
            logger.info { "Using ${packageManager.name} to install Python" }

            val process = ProcessBuilder(*packageManager.installCommand.toTypedArray())
                .redirectErrorStream(true)
                .start()

            val reader = process.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                logger.debug { "[${packageManager.name}] $line" }
                when {
                    line!!.contains("Downloading", ignoreCase = true) ||
                            line!!.contains("Get:", ignoreCase = true) ->
                        onProgress(0.3f, "Downloading Python...")

                    line!!.contains("Unpacking", ignoreCase = true) ||
                            line!!.contains("Installing", ignoreCase = true) ->
                        onProgress(0.6f, "Installing Python...")

                    line!!.contains("Setting up", ignoreCase = true) ||
                            line!!.contains("running", ignoreCase = true) ->
                        onProgress(0.8f, "Finalizing installation...")
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw UpdateException(
                    "Failed to install Python via ${packageManager.name} (exit code: $exitCode). " +
                            "You may need to run with sudo privileges or install Python manually."
                )
            }

            // Verify installation
            val pythonPath = findPythonPath()
            if (pythonPath == null) {
                throw UpdateException("Python was installed but could not be found. Try restarting your terminal.")
            }

            onProgress(1f, "Python installed via ${packageManager.name}")
            logger.info { "Python installed successfully via ${packageManager.name} at: $pythonPath" }
        } else {
            throw UpdateException(
                "No supported package manager found. " +
                        "Please install Python 3.8+ manually from https://python.org and try again."
            )
        }
    }

    /**
     * Installs Python on Windows using winget.
     */
    private suspend fun installPythonOnWindows(
        onProgress: suspend (Float, String) -> Unit,
    ) {
        onProgress(0f, "Checking for Python...")

        // First check if Python is already installed
        val existingPythonPath = findPythonPath()
        if (existingPythonPath != null) {
            logger.info { "Python is already installed at: $existingPythonPath" }
            onProgress(1f, "Python is already installed")
            return
        }

        onProgress(0.05f, "Checking for winget...")

        // Check if winget is available
        val wingetAvailable = try {
            val process = ProcessBuilder("winget", "--version")
                .redirectErrorStream(true)
                .start()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }

        if (!wingetAvailable) {
            throw UpdateException(
                "winget is required to install Python on Windows. " +
                        "Please install Python manually from https://python.org and try again."
            )
        }

        onProgress(0.1f, "Installing Python via winget...")
        logger.info { "Using winget to install Python" }

        val process = ProcessBuilder(
            "winget", "install", "-e", "--id", "Python.Python.3.11",
            "--accept-source-agreements", "--accept-package-agreements"
        )
            .redirectErrorStream(true)
            .start()

        val reader = process.inputStream.bufferedReader()
        val outputLines = mutableListOf<String>()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            logger.debug { "[winget] $line" }
            outputLines.add(line!!)
            when {
                line!!.contains("Downloading", ignoreCase = true) ->
                    onProgress(0.3f, "Downloading Python...")

                line!!.contains("Installing", ignoreCase = true) ->
                    onProgress(0.6f, "Installing Python...")

                line!!.contains("Successfully", ignoreCase = true) ->
                    onProgress(0.9f, "Finalizing installation...")
            }
        }

        val exitCode = process.waitFor()
        val fullOutput = outputLines.joinToString("\n")

        // Check for "already installed" or "no upgrade" messages (winget returns non-zero in these cases)
        val alreadyInstalled = fullOutput.contains("already installed", ignoreCase = true) ||
                fullOutput.contains("No available upgrade", ignoreCase = true) ||
                fullOutput.contains("No newer package versions", ignoreCase = true)

        if (exitCode != 0 && !alreadyInstalled) {
            throw UpdateException(
                "Failed to install Python via winget (exit code: $exitCode). " +
                        "Please install Python manually from https://python.org and try again."
            )
        }

        // Verify installation
        val pythonPath = findPythonPath()
        if (pythonPath == null) {
            throw UpdateException(
                "Python was installed but could not be found. " +
                        "Please restart your terminal or computer and try again."
            )
        }

        onProgress(1f, if (alreadyInstalled) "Python is already installed" else "Python installed via winget")
        logger.info { "Python available at: $pythonPath" }
    }

    // ========== Visual C++ Redistributable Installation (Windows) ==========

    /**
     * Ensures Visual C++ Redistributable is installed on Windows.
     * Required for whisper.cpp to run.
     * This is a public function that can be called before transcription to ensure prerequisites are met.
     *
     * @return true if VC++ is installed (or was successfully installed), false if installation failed
     */
    suspend fun ensureWindowsPrerequisites(): Boolean {
        if (platformPaths.operatingSystem != OperatingSystem.WINDOWS) {
            return true // Not needed on other platforms
        }

        if (isVcRedistInstalled()) {
            return true
        }

        logger.info { "Visual C++ Redistributable not found, attempting installation..." }

        return try {
            ensureVcRedistInstalled { _, _ -> } // No progress reporting needed
            isVcRedistInstalled() // Verify it was installed
        } catch (e: Exception) {
            logger.error(e) { "Failed to install Visual C++ Redistributable" }
            false
        }
    }

    /**
     * Ensures Visual C++ Redistributable is installed on Windows.
     * Required for whisper.cpp to run.
     */
    private suspend fun ensureVcRedistInstalled(
        onProgress: suspend (Float, String) -> Unit,
    ) {
        onProgress(0f, "Checking Visual C++ Redistributable...")

        // Check if VC++ Redistributable is already installed
        if (isVcRedistInstalled()) {
            logger.info { "Visual C++ Redistributable is already installed" }
            onProgress(0.1f, "Visual C++ Redistributable found")
            return
        }

        logger.info { "Visual C++ Redistributable not found, installing..." }
        onProgress(0.02f, "Installing Visual C++ Redistributable...")

        // Check if winget is available
        val wingetAvailable = try {
            val process = ProcessBuilder("winget", "--version")
                .redirectErrorStream(true)
                .start()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }

        if (!wingetAvailable) {
            logger.warn { "winget not available, skipping VC++ Redistributable installation" }
            logger.warn { "User may need to manually install from: https://aka.ms/vs/17/release/vc_redist.x64.exe" }
            onProgress(0.1f, "winget not available - VC++ may need manual install")
            return
        }

        onProgress(0.03f, "Installing Visual C++ Redistributable via winget...")

        val process = ProcessBuilder(
            "winget", "install", "-e", "--id", "Microsoft.VCRedist.2015+.x64",
            "--accept-source-agreements", "--accept-package-agreements", "--silent"
        )
            .redirectErrorStream(true)
            .start()

        val reader = process.inputStream.bufferedReader()
        val outputLines = mutableListOf<String>()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            logger.debug { "[winget] $line" }
            outputLines.add(line!!)
            when {
                line!!.contains("Downloading", ignoreCase = true) ->
                    onProgress(0.05f, "Downloading Visual C++ Redistributable...")

                line!!.contains("Installing", ignoreCase = true) ->
                    onProgress(0.07f, "Installing Visual C++ Redistributable...")

                line!!.contains("Successfully", ignoreCase = true) ->
                    onProgress(0.09f, "Visual C++ Redistributable installed")
            }
        }

        val exitCode = process.waitFor()
        val fullOutput = outputLines.joinToString("\n")

        // Check for "already installed" messages (winget returns non-zero in these cases)
        val alreadyInstalled = fullOutput.contains("already installed", ignoreCase = true) ||
                fullOutput.contains("No available upgrade", ignoreCase = true) ||
                fullOutput.contains("No newer package versions", ignoreCase = true)

        if (exitCode != 0 && !alreadyInstalled) {
            logger.warn { "Failed to install Visual C++ Redistributable via winget (exit code: $exitCode)" }
            logger.warn { "winget output: $fullOutput" }
            logger.warn { "User may need to manually install from: https://aka.ms/vs/17/release/vc_redist.x64.exe" }
            // Don't throw - continue and let whisper fail with a helpful message if needed
        } else {
            logger.info { "Visual C++ Redistributable winget command completed" }
        }

        // Verify installation was successful
        if (isVcRedistInstalled()) {
            logger.info { "Visual C++ Redistributable verified as installed" }
            onProgress(0.1f, "Visual C++ Redistributable ready")
        } else {
            logger.warn { "Visual C++ Redistributable installation could not be verified" }
            logger.warn { "User may need to manually install from: https://aka.ms/vs/17/release/vc_redist.x64.exe" }
            onProgress(0.1f, "VC++ may need manual install")
        }
    }

    /**
     * Checks if Visual C++ Redistributable 2015-2022 (x64) is installed.
     * Checks multiple registry locations as the path can vary.
     */
    private fun isVcRedistInstalled(): Boolean {
        // Check multiple possible registry locations for VC++ Redistributable
        val registryPaths = listOf(
            "HKLM\\SOFTWARE\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\x64",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\x64",
            "HKLM\\SOFTWARE\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\X64"
        )

        for (regPath in registryPaths) {
            try {
                val process = ProcessBuilder(
                    "reg", "query", regPath, "/v", "Installed"
                )
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (exitCode == 0 && output.contains("0x1")) {
                    logger.debug { "VC++ Redistributable found at: $regPath" }
                    return true
                }
            } catch (e: Exception) {
                logger.debug { "Failed to check registry path $regPath: ${e.message}" }
            }
        }

        // Also try to check if the actual DLL exists
        val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
        val vcRuntimeDlls = listOf(
            "$systemRoot\\System32\\vcruntime140.dll",
            "$systemRoot\\System32\\msvcp140.dll"
        )

        val dllsExist = vcRuntimeDlls.all { File(it).exists() }
        if (dllsExist) {
            logger.debug { "VC++ Runtime DLLs found in System32" }
            return true
        }

        logger.debug { "VC++ Redistributable not found in registry or System32" }
        return false
    }

    /**
     * Detects available Linux package manager for Python installation.
     */
    private fun detectLinuxPackageManagerForPython(): LinuxPackageManager? {
        val packageManagers = listOf(
            LinuxPackageManager("apt", listOf("apt", "install", "-y", "python3", "python3-pip", "python3-venv")),
            LinuxPackageManager("dnf", listOf("dnf", "install", "-y", "python3", "python3-pip")),
            LinuxPackageManager("yum", listOf("yum", "install", "-y", "python3", "python3-pip")),
            LinuxPackageManager("pacman", listOf("pacman", "-S", "--noconfirm", "python", "python-pip")),
            LinuxPackageManager("zypper", listOf("zypper", "install", "-y", "python3", "python3-pip")),
            LinuxPackageManager("apk", listOf("apk", "add", "python3", "py3-pip"))
        )

        for (pm in packageManagers) {
            if (isCommandAvailable(pm.name)) {
                logger.debug { "Found package manager: ${pm.name}" }
                return pm
            }
        }

        return null
    }

    // ========== LibreTranslate Installation ==========

    /**
     * Installs LibreTranslate in a Python virtual environment.
     * Automatically installs Python if not found on the system.
     */
    fun installLibreTranslate(): Flow<DownloadProgress> = channelFlow {
        send(DownloadProgress(0f, "Checking Python installation..."))

        var pythonPath = findPythonPath()
        if (pythonPath == null) {
            send(DownloadProgress(0.02f, "Python not found, installing..."))
            logger.info { "Python not found, attempting to install..." }

            // Collect Python installation progress and forward it (scaled to 0.02-0.15 range)
            installPython().collect { progress ->
                val scaledProgress = 0.02f + (progress.percentage * 0.13f)
                send(DownloadProgress(scaledProgress, progress.message))
            }

            // Re-check for Python after installation
            pythonPath = findPythonPath()
            if (pythonPath == null) {
                throw UpdateException(
                    "Python installation completed but Python could not be found. " +
                            "Please restart your terminal and try again."
                )
            }
        }

        logger.info { "Found Python at: $pythonPath" }
        send(DownloadProgress(0.05f, "Creating virtual environment..."))

        val venvDir = File(platformPaths.libreTranslateDir, "venv")
        val venvPython = getVenvPythonPath(venvDir)
        val venvPip = getVenvPipPath(venvDir)

        // Create virtual environment if it doesn't exist
        if (!venvDir.exists()) {
            val venvResult = runCommand(listOf(pythonPath, "-m", "venv", venvDir.absolutePath))
            if (!venvResult.success) {
                throw UpdateException("Failed to create virtual environment: ${venvResult.error}")
            }
            logger.info { "Created virtual environment at: ${venvDir.absolutePath}" }
        }

        send(DownloadProgress(0.15f, "Upgrading pip..."))

        // Upgrade pip first
        val pipUpgradeResult = runCommand(listOf(venvPython, "-m", "pip", "install", "--upgrade", "pip"))
        if (!pipUpgradeResult.success) {
            logger.warn { "pip upgrade warning: ${pipUpgradeResult.error}" }
        }

        send(DownloadProgress(0.20f, "Installing LibreTranslate (this may take several minutes)..."))

        // On Windows, install PyTorch CPU-only version first to avoid fbgemm.dll/MKL dependency issues
        // The default PyTorch install includes CUDA support which requires Intel MKL libraries
        if (platformPaths.operatingSystem == OperatingSystem.WINDOWS) {
            send(DownloadProgress(0.25f, "Installing PyTorch (CPU version)..."))
            val torchResult = runCommand(
                listOf(
                    venvPip, "install", "torch",
                    "--index-url", "https://download.pytorch.org/whl/cpu"
                ),
                timeoutMinutes = 10
            )
            if (!torchResult.success) {
                logger.warn { "PyTorch CPU installation warning: ${torchResult.error}" }
                // Continue anyway, LibreTranslate might still work with default torch
            } else {
                logger.info { "Installed PyTorch CPU-only version" }
            }
        }

        send(DownloadProgress(0.40f, "Installing LibreTranslate..."))

        // Install libretranslate and certifi (for SSL certificate verification)
        val installResult = runCommand(
            listOf(venvPip, "install", "libretranslate", "certifi"),
            timeoutMinutes = 15  // Can take a while due to dependencies
        )
        if (!installResult.success) {
            throw UpdateException("Failed to install LibreTranslate: ${installResult.error}")
        }

        send(DownloadProgress(0.85f, "Verifying installation..."))

        // Verify installation and get version
        val versionResult = runCommand(listOf(venvPip, "show", "libretranslate"))
        val version = if (versionResult.success) {
            val versionLine = versionResult.output.lines().find { it.startsWith("Version:") }
            versionLine?.substringAfter("Version:")?.trim() ?: "unknown"
        } else {
            "unknown"
        }

        logger.info { "LibreTranslate $version installed successfully" }

        // Save installed version
        val versions = configManager.getInstalledVersions()
        configManager.saveInstalledVersions(versions.copy(libreTranslate = version))

        send(DownloadProgress(1f, "LibreTranslate $version installed"))
    }.flowOn(Dispatchers.IO)

    /**
     * Checks if LibreTranslate is installed.
     */
    fun isLibreTranslateInstalled(): Boolean {
        val venvDir = File(platformPaths.libreTranslateDir, "venv")
        val venvPython = getVenvPythonPath(File(platformPaths.libreTranslateDir, "venv"))

        if (!File(venvPython).exists()) {
            return false
        }

        return try {
            val result = runCommand(listOf(venvPython, "-c", "import libretranslate; print('ok')"))
            result.success && result.output.contains("ok")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Gets the installed LibreTranslate version.
     */
    fun getLibreTranslateVersion(): String? {
        val venvPip = getVenvPipPath(File(platformPaths.libreTranslateDir, "venv"))

        if (!File(venvPip).exists()) {
            return null
        }

        return try {
            val result = runCommand(listOf(venvPip, "show", "libretranslate"))
            if (result.success) {
                result.output.lines()
                    .find { it.startsWith("Version:") }
                    ?.substringAfter("Version:")
                    ?.trim()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Checks for LibreTranslate updates via pip.
     */
    suspend fun checkLibreTranslateUpdate(): String? {
        val venvPip = getVenvPipPath(File(platformPaths.libreTranslateDir, "venv"))

        if (!File(venvPip).exists()) {
            return null
        }

        return try {
            val result = runCommand(listOf(venvPip, "list", "--outdated", "--format=json"))
            if (result.success) {
                val outdated = json.decodeFromString<List<PipOutdatedPackage>>(result.output)
                outdated.find { it.name == "libretranslate" }?.latestVersion
            } else null
        } catch (e: Exception) {
            logger.debug { "Failed to check LibreTranslate updates: ${e.message}" }
            null
        }
    }

    /**
     * Finds Python 3.8+ installation.
     */
    private fun findPythonPath(): String? {
        val pythonCommands = when (platformPaths.operatingSystem) {
            OperatingSystem.WINDOWS -> listOf("python", "python3", "py")
            else -> listOf("python3", "python")
        }

        for (cmd in pythonCommands) {
            try {
                val process = ProcessBuilder(cmd, "--version")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()

                if (process.exitValue() == 0) {
                    // Parse version (e.g., "Python 3.11.2")
                    val versionRegex = Regex("Python (\\d+)\\.(\\d+)")
                    val match = versionRegex.find(output)
                    if (match != null) {
                        val major = match.groupValues[1].toIntOrNull() ?: 0
                        val minor = match.groupValues[2].toIntOrNull() ?: 0
                        if (major >= 3 && minor >= 8) {
                            // Get full path
                            val whichProcess = ProcessBuilder(
                                if (platformPaths.operatingSystem == OperatingSystem.WINDOWS) "where" else "which",
                                cmd
                            ).redirectErrorStream(true).start()
                            val path = whichProcess.inputStream.bufferedReader().readLine()
                            whichProcess.waitFor()
                            if (path?.isNotBlank() == true) {
                                return path.trim()
                            }
                            return cmd
                        }
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    /**
     * Gets Python path within a virtual environment.
     */
    private fun getVenvPythonPath(venvDir: File): String {
        return when (platformPaths.operatingSystem) {
            OperatingSystem.WINDOWS -> "${venvDir.absolutePath}\\Scripts\\python.exe"
            else -> "${venvDir.absolutePath}/bin/python"
        }
    }

    /**
     * Gets pip path within a virtual environment.
     */
    private fun getVenvPipPath(venvDir: File): String {
        return when (platformPaths.operatingSystem) {
            OperatingSystem.WINDOWS -> "${venvDir.absolutePath}\\Scripts\\pip.exe"
            else -> "${venvDir.absolutePath}/bin/pip"
        }
    }

    /**
     * Runs a command and returns the result.
     */
    private fun runCommand(
        command: List<String>,
        timeoutMinutes: Int = 5
    ): CommandResult {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val completed = process.waitFor(timeoutMinutes.toLong(), java.util.concurrent.TimeUnit.MINUTES)

            if (!completed) {
                process.destroyForcibly()
                CommandResult(false, "", "Command timed out after $timeoutMinutes minutes")
            } else {
                CommandResult(
                    success = process.exitValue() == 0,
                    output = output,
                    error = if (process.exitValue() != 0) output else ""
                )
            }
        } catch (e: Exception) {
            CommandResult(false, "", e.message ?: "Unknown error")
        }
    }

    private data class CommandResult(
        val success: Boolean,
        val output: String,
        val error: String
    )

    @Serializable
    private data class PipOutdatedPackage(
        val name: String,
        val version: String,
        @SerialName("latest_version")
        val latestVersion: String
    )

    // ========== Dependency Updates ==========

    /**
     * Checks for updates to all dependencies.
     */
    suspend fun checkDependencyUpdates(): DependencyUpdates {
        val installed = configManager.getInstalledVersions()

        val ytDlpUpdate = try {
            val latest = getLatestRelease(YTDLP_REPO)
            if (installed.ytDlp != null && isNewerVersion(latest.tagName, installed.ytDlp)) {
                latest.tagName
            } else null
        } catch (e: Exception) {
            logger.debug { "Failed to check yt-dlp updates: ${e.message}" }
            null
        }

        val whisperCppUpdate = try {
            val latest = getLatestRelease(WHISPER_REPO)
            if (installed.whisperCpp != null && isNewerVersion(latest.tagName, installed.whisperCpp)) {
                latest.tagName
            } else null
        } catch (e: Exception) {
            logger.debug { "Failed to check whisper.cpp updates: ${e.message}" }
            null
        }

        val libreTranslateUpdate = try {
            checkLibreTranslateUpdate()
        } catch (e: Exception) {
            logger.debug { "Failed to check LibreTranslate updates: ${e.message}" }
            null
        }

        return DependencyUpdates(
            ytDlpAvailable = ytDlpUpdate,
            ffmpegAvailable = null, // FFmpeg doesn't have a consistent version API
            whisperCppAvailable = whisperCppUpdate,
            libreTranslateAvailable = libreTranslateUpdate
        )
    }

    // ========== Download Infrastructure ==========

    /**
     * Downloads a file with retry logic, resume support, and optional checksum verification.
     * Implements atomic file replacement (download to temp, verify, move).
     */
    private suspend fun downloadFileWithRetry(
        url: String,
        tempFile: File,
        targetFile: File,
        expectedChecksum: String?,
        onProgress: suspend (Float, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        var attempt = 0

        while (attempt < MAX_RETRIES) {
            try {
                attempt++
                logger.info { "Download attempt $attempt/$MAX_RETRIES: $url" }

                // Check for existing partial download
                val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

                downloadFileWithResume(url, tempFile, existingBytes) { progress ->
                    onProgress(progress, "Downloading... (${(progress * 100).toInt()}%)")
                }

                // Verify checksum if provided
                if (expectedChecksum != null) {
                    onProgress(0.98f, "Verifying checksum...")
                    val actualChecksum = calculateSha256(tempFile)

                    if (!actualChecksum.equals(expectedChecksum, ignoreCase = true)) {
                        tempFile.delete()
                        throw ChecksumMismatchException(
                            expected = expectedChecksum,
                            actual = actualChecksum
                        )
                    }
                    logger.info { "Checksum verified: $actualChecksum" }
                }

                // Atomic move to target location
                onProgress(0.99f, "Finalizing...")
                targetFile.parentFile?.mkdirs()
                Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

                logger.info { "Download complete: ${targetFile.absolutePath}" }
                return@withContext

            } catch (e: ChecksumMismatchException) {
                // Don't retry checksum failures - the file is corrupt
                logger.error { "Checksum verification failed: expected ${e.expected}, got ${e.actual}" }
                throw e
            } catch (e: Exception) {
                lastException = e
                logger.warn { "Download attempt $attempt failed: ${e.message}" }

                if (attempt < MAX_RETRIES) {
                    val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl (attempt - 1)) // Exponential backoff
                    logger.info { "Retrying in ${delayMs}ms..." }
                    delay(delayMs)
                }
            }
        }

        // Clean up temp file on final failure
        tempFile.delete()
        throw UpdateException("Download failed after $MAX_RETRIES attempts", lastException)
    }

    /**
     * Downloads a file with resume support using HTTP Range headers.
     * Uses streaming to avoid loading the entire file into memory.
     */
    private suspend fun downloadFileWithResume(
        url: String,
        targetFile: File,
        existingBytes: Long,
        onProgress: suspend (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        targetFile.parentFile?.mkdirs()

        // First, make a HEAD request to get content length and check Range support
        val headResponse = httpClient.head(url)
        val contentLength = headResponse.contentLength() ?: 0L
        val acceptsRange = headResponse.headers["Accept-Ranges"]?.contains("bytes") == true

        val startByte = if (acceptsRange && existingBytes > 0 && existingBytes < contentLength) {
            logger.info { "Resuming download from byte $existingBytes of $contentLength" }
            existingBytes
        } else {
            if (existingBytes > 0) {
                logger.info { "Server doesn't support resume, starting fresh download" }
                targetFile.delete()
            }
            0L
        }

        // Use prepareGet + execute pattern to enable true streaming without buffering
        // This is critical for large files (Whisper models can be up to 3GB)
        httpClient.prepareGet(url) {
            if (startByte > 0) {
                header(HttpHeaders.Range, "bytes=$startByte-")
            }
        }.execute { response ->
            // Verify we got the expected response
            val expectedStatus = if (startByte > 0) HttpStatusCode.PartialContent else HttpStatusCode.OK
            if (response.status != expectedStatus && response.status != HttpStatusCode.OK) {
                throw UpdateException("Unexpected HTTP status: ${response.status}")
            }

            val totalSize = if (startByte > 0) {
                // For resumed downloads, total is existing + remaining
                startByte + (response.contentLength() ?: (contentLength - startByte))
            } else {
                response.contentLength() ?: contentLength
            }

            // Open file for writing (append if resuming)
            RandomAccessFile(targetFile, "rw").use { raf ->
                raf.seek(startByte)

                val channel = response.bodyAsChannel()
                val buffer = ByteArray(BUFFER_SIZE)
                var totalRead = startByte

                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read <= 0) break

                    raf.write(buffer, 0, read)
                    totalRead += read

                    if (totalSize > 0) {
                        onProgress(totalRead.toFloat() / totalSize)
                    }
                }
            }
        }
    }

    /**
     * Calculates SHA256 checksum of a file.
     */
    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)

        FileInputStream(file).use { fis ->
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ========== Helper Methods ==========

    private suspend fun getLatestRelease(repo: String): GitHubRelease {
        val response = httpClient.get("https://api.github.com/repos/$repo/releases/latest") {
            header("Accept", "application/vnd.github.v3+json")
        }

        if (!response.status.isSuccess()) {
            throw UpdateException("Failed to fetch release info: ${response.status}")
        }

        return json.decodeFromString(response.bodyAsText())
    }

    private fun getYtDlpDownloadUrl(release: GitHubRelease): String {
        val assetName = when (platformPaths.operatingSystem) {
            OperatingSystem.WINDOWS -> "yt-dlp.exe"
            OperatingSystem.MACOS -> "yt-dlp_macos"
            OperatingSystem.LINUX -> "yt-dlp_linux"
        }

        return release.assets.find { it.name == assetName }?.browserDownloadUrl
            ?: throw UpdateException("yt-dlp asset '$assetName' not found for ${platformPaths.operatingSystem}")
    }

    /**
     * Gets FFmpeg download URL for Windows only.
     * macOS and Linux use package managers and don't call this function.
     */
    private fun getFfmpegDownloadUrl(): String {
        return when (platformPaths.operatingSystem) {
            OperatingSystem.WINDOWS ->
                "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip"
            OperatingSystem.LINUX ->
                throw IllegalStateException("Linux should use package managers to install FFmpeg")
            OperatingSystem.MACOS ->
                throw IllegalStateException("macOS should use Homebrew to install FFmpeg")
        }
    }

    private fun getWhisperCppDownloadUrl(release: GitHubRelease): String {
        // whisper.cpp release naming convention:
        // Windows: whisper-bin-x64.zip, whisper-bin-Win32.zip, whisper-blas-bin-x64.zip
        // No pre-built binaries for macOS or Linux (handled separately)

        when (platformPaths.operatingSystem) {
            OperatingSystem.WINDOWS -> {
                // Prefer x64 basic binary, then x64 with BLAS, then Win32
                val preferredPatterns = listOf(
                    Regex("whisper-bin-x64\\.zip", RegexOption.IGNORE_CASE),
                    Regex("whisper-blas-bin-x64\\.zip", RegexOption.IGNORE_CASE),
                    Regex("whisper-bin-Win32\\.zip", RegexOption.IGNORE_CASE)
                )

                for (pattern in preferredPatterns) {
                    val asset = release.assets.find { pattern.matches(it.name) }
                    if (asset != null) {
                        logger.info { "Found Windows whisper binary: ${asset.name}" }
                        return asset.browserDownloadUrl
                    }
                }

                // Fallback: any zip with "bin" and "x64" or "win"
                val fallback = release.assets.find { asset ->
                    val name = asset.name.lowercase()
                    name.endsWith(".zip") && "bin" in name && ("x64" in name || "win" in name)
                }
                if (fallback != null) {
                    logger.info { "Found Windows whisper binary (fallback): ${fallback.name}" }
                    return fallback.browserDownloadUrl
                }

                throw UpdateException(
                    "No Windows whisper.cpp binary found in release ${release.tagName}. " +
                    "Available assets: ${release.assets.map { it.name }}"
                )
            }
            OperatingSystem.LINUX -> {
                // whisper.cpp doesn't provide pre-built Linux binaries
                // This should be handled by installWhisperCpp() using package manager
                throw UpdateException(
                    "No pre-built Linux binaries available for whisper.cpp. " +
                    "Please install via your package manager or build from source."
                )
            }
            OperatingSystem.MACOS -> {
                // macOS is handled separately via Homebrew
                throw UpdateException(
                    "No pre-built macOS binaries available for whisper.cpp. " +
                    "This should be installed via Homebrew."
                )
            }
        }
    }

    private fun getAppDownloadUrl(release: GitHubRelease): String {
        val pattern = when (platformPaths.operatingSystem) {
            OperatingSystem.WINDOWS -> ".msi"
            OperatingSystem.MACOS -> ".dmg"
            OperatingSystem.LINUX -> ".AppImage"
        }

        return release.assets.find { it.name.endsWith(pattern) }?.browserDownloadUrl
            ?: throw UpdateException("App installer not found for ${platformPaths.operatingSystem}")
    }

    /**
     * Gets FFmpeg version by running ffmpeg -version.
     */
    private fun getFfmpegVersion(ffmpegFile: File): String? {
        return try {
            val process = ProcessBuilder(ffmpegFile.absolutePath, "-version")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readLine() ?: return null
            process.waitFor()

            // Parse version from output like "ffmpeg version 7.0 Copyright..."
            val versionRegex = Regex("ffmpeg version ([\\d.]+)")
            versionRegex.find(output)?.groupValues?.get(1)
        } catch (e: Exception) {
            logger.debug { "Could not get FFmpeg version: ${e.message}" }
            null
        }
    }

    private fun getCurrentAppVersion(): String {
        // Read from application resources or build info
        return "1.0.0"
    }

    private fun isNewerVersion(new: String, current: String): Boolean {
        val newParts = new.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(newParts.size, currentParts.size)) {
            val n = newParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (n > c) return true
            if (n < c) return false
        }
        return false
    }

    /**
     * Removes the macOS quarantine attribute from a file.
     * Downloaded files on macOS have this attribute which prevents execution.
     */
    private fun removeQuarantineAttribute(file: File) {
        try {
            val process = ProcessBuilder("xattr", "-d", "com.apple.quarantine", file.absolutePath)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                logger.debug { "Removed quarantine attribute from ${file.name}" }
            } else {
                // Exit code 1 means attribute doesn't exist, which is fine
                logger.trace { "Quarantine attribute not present on ${file.name}" }
            }
        } catch (e: Exception) {
            logger.warn { "Could not remove quarantine attribute from ${file.name}: ${e.message}" }
        }
    }
}

// ========== Data Classes ==========

/**
 * Represents a GitHub release from the API.
 *
 * @property tagName Version tag of the release (e.g., "v1.2.3").
 * @property name Human-readable name of the release.
 * @property body Release notes in Markdown format.
 * @property publishedAt ISO 8601 timestamp when the release was published.
 * @property assets List of downloadable assets attached to this release.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAsset> = emptyList()
)

/**
 * Represents a downloadable asset attached to a GitHub release.
 *
 * @property name Filename of the asset (e.g., "yt-dlp_macos").
 * @property browserDownloadUrl Direct download URL for the asset.
 * @property size File size in bytes.
 */
@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0
)

/**
 * Information about an available application update.
 *
 * @property currentVersion Currently installed application version.
 * @property newVersion Version of the available update.
 * @property releaseNotes Release notes describing what's new in this version.
 * @property downloadUrl URL to download the update installer.
 * @property publishedAt ISO 8601 timestamp when the update was published.
 */
data class AppUpdateInfo(
    val currentVersion: String,
    val newVersion: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val publishedAt: String?
)

/**
 * Available updates for application dependencies.
 *
 * @property ytDlpAvailable New yt-dlp version available, or null if up-to-date.
 * @property ffmpegAvailable New FFmpeg version available, or null if up-to-date.
 * @property whisperCppAvailable New whisper.cpp version available, or null if up-to-date.
 * @property libreTranslateAvailable New LibreTranslate version available, or null if up-to-date.
 */
data class DependencyUpdates(
    val ytDlpAvailable: String?,
    val ffmpegAvailable: String?,
    val whisperCppAvailable: String?,
    val libreTranslateAvailable: String? = null
) {
    /** Whether any dependency updates are available. */
    val hasUpdates: Boolean get() =
        ytDlpAvailable != null || ffmpegAvailable != null || whisperCppAvailable != null || libreTranslateAvailable != null
}

/**
 * Progress update during a download operation.
 *
 * @property percentage Download progress from 0.0 (started) to 1.0 (complete).
 * @property message Human-readable status message describing current activity.
 */
data class DownloadProgress(
    val percentage: Float,
    val message: String
)

// ========== Exceptions ==========

/**
 * Exception thrown when an update operation fails.
 *
 * @property message Description of what went wrong.
 * @property cause Underlying exception that caused the failure, if any.
 */
class UpdateException(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)

/**
 * Exception thrown when checksum verification fails.
 *
 * @property expected Expected SHA256 checksum of the downloaded file.
 * @property actual Actual SHA256 checksum computed from the downloaded file.
 */
class ChecksumMismatchException(
    val expected: String,
    val actual: String
) : Exception("Checksum mismatch: expected $expected, got $actual")
