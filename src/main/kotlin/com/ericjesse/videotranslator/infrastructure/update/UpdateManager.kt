package com.ericjesse.videotranslator.infrastructure.update

import com.ericjesse.videotranslator.infrastructure.archive.ArchiveExtractor
import com.ericjesse.videotranslator.infrastructure.archive.ExtractionConfig
import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.config.OperatingSystem
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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
    fun downloadAppUpdate(updateInfo: AppUpdateInfo): Flow<DownloadProgress> = flow {
        emit(DownloadProgress(0f, "Starting download..."))

        val tempFile = File(platformPaths.cacheDir, "update-${updateInfo.newVersion}.tmp")
        val targetFile = File(platformPaths.cacheDir, "update-${updateInfo.newVersion}")

        downloadFileWithRetry(
            url = updateInfo.downloadUrl,
            tempFile = tempFile,
            targetFile = targetFile,
            expectedChecksum = null // App updates may not have checksums
        ) { progress, message ->
            emit(DownloadProgress(progress * 0.9f, message))
        }

        emit(DownloadProgress(1f, "Download complete. Ready to install."))

        // The actual installation would be platform-specific
        // and typically requires restarting the app
    }.flowOn(Dispatchers.IO)

    // ========== Dependency Installation ==========

    /**
     * Downloads and installs yt-dlp.
     */
    fun installYtDlp(): Flow<DownloadProgress> = flow {
        emit(DownloadProgress(0f, "Fetching latest yt-dlp release..."))

        val release = getLatestRelease(YTDLP_REPO)
        val downloadUrl = getYtDlpDownloadUrl(release)
        val tempFile = File(platformPaths.cacheDir, "yt-dlp.tmp")
        val targetFile = File(platformPaths.getBinaryPath("yt-dlp"))

        emit(DownloadProgress(0.05f, "Downloading yt-dlp ${release.tagName}..."))

        downloadFileWithRetry(
            url = downloadUrl,
            tempFile = tempFile,
            targetFile = targetFile,
            expectedChecksum = null // yt-dlp provides checksums in separate files
        ) { progress, message ->
            emit(DownloadProgress(0.05f + progress * 0.9f, message))
        }

        // Make executable on Unix
        if (platformPaths.operatingSystem != OperatingSystem.WINDOWS) {
            targetFile.setExecutable(true)
            logger.debug { "Set executable permission on ${targetFile.name}" }
        }

        // Update version info
        val versions = configManager.getInstalledVersions()
        configManager.saveInstalledVersions(versions.copy(ytDlp = release.tagName))

        emit(DownloadProgress(1f, "yt-dlp ${release.tagName} installed"))
        logger.info { "yt-dlp ${release.tagName} installed successfully" }
    }.flowOn(Dispatchers.IO)

    /**
     * Downloads and installs FFmpeg.
     */
    fun installFfmpeg(): Flow<DownloadProgress> = flow {
        emit(DownloadProgress(0f, "Fetching FFmpeg..."))

        val downloadUrl = getFfmpegDownloadUrl()
        val archiveExtension = if (platformPaths.operatingSystem == OperatingSystem.LINUX) ".tar.xz" else ".zip"
        val tempFile = File(platformPaths.cacheDir, "ffmpeg-download.tmp")
        val archiveFile = File(platformPaths.cacheDir, "ffmpeg$archiveExtension")
        val extractDir = File(platformPaths.cacheDir, "ffmpeg-extract")

        emit(DownloadProgress(0.05f, "Downloading FFmpeg..."))

        downloadFileWithRetry(
            url = downloadUrl,
            tempFile = tempFile,
            targetFile = archiveFile,
            expectedChecksum = null
        ) { progress, message ->
            emit(DownloadProgress(0.05f + progress * 0.65f, message))
        }

        emit(DownloadProgress(0.7f, "Extracting FFmpeg..."))

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
                emit(DownloadProgress(0.7f + extractProgress * 0.2f, "Extracting: ${progress.currentFile}"))
            }
        }

        emit(DownloadProgress(0.9f, "Installing FFmpeg binaries..."))

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

        emit(DownloadProgress(1f, "FFmpeg $version installed"))
        logger.info { "FFmpeg $version installed successfully" }
    }.flowOn(Dispatchers.IO)

    /**
     * Downloads a Whisper model with checksum verification.
     */
    fun installWhisperModel(modelName: String): Flow<DownloadProgress> = flow {
        val url = WHISPER_MODELS[modelName]
            ?: throw IllegalArgumentException("Unknown model: $modelName")
        val expectedChecksum = WHISPER_MODEL_CHECKSUMS[modelName]

        emit(DownloadProgress(0f, "Preparing to download Whisper $modelName model..."))

        val modelDir = File(platformPaths.modelsDir, "whisper").apply { mkdirs() }
        val tempFile = File(platformPaths.cacheDir, "ggml-$modelName.bin.tmp")
        val targetFile = File(modelDir, "ggml-$modelName.bin")

        downloadFileWithRetry(
            url = url,
            tempFile = tempFile,
            targetFile = targetFile,
            expectedChecksum = expectedChecksum
        ) { progress, message ->
            emit(DownloadProgress(progress * 0.95f, message))
        }

        // Update version info
        val versions = configManager.getInstalledVersions()
        configManager.saveInstalledVersions(versions.copy(whisperModel = modelName))

        emit(DownloadProgress(1f, "Whisper $modelName model installed"))
        logger.info { "Whisper model '$modelName' installed successfully" }
    }.flowOn(Dispatchers.IO)

    /**
     * Downloads and installs whisper.cpp binary.
     */
    fun installWhisperCpp(): Flow<DownloadProgress> = flow {
        emit(DownloadProgress(0f, "Fetching whisper.cpp release..."))

        val release = getLatestRelease(WHISPER_REPO)
        val downloadUrl = getWhisperCppDownloadUrl(release)
        val tempFile = File(platformPaths.cacheDir, "whisper-download.tmp")
        val archiveFile = File(platformPaths.cacheDir, "whisper.zip")
        val extractDir = File(platformPaths.cacheDir, "whisper-extract")

        emit(DownloadProgress(0.05f, "Downloading whisper.cpp ${release.tagName}..."))

        downloadFileWithRetry(
            url = downloadUrl,
            tempFile = tempFile,
            targetFile = archiveFile,
            expectedChecksum = null
        ) { progress, message ->
            emit(DownloadProgress(0.05f + progress * 0.65f, message))
        }

        emit(DownloadProgress(0.7f, "Extracting whisper.cpp..."))

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
                emit(DownloadProgress(0.7f + extractProgress * 0.2f, "Extracting: ${progress.currentFile}"))
            }
        }

        emit(DownloadProgress(0.9f, "Installing whisper.cpp..."))

        // Find the whisper binary (might be named 'main', 'whisper', 'whisper-cpp', etc.)
        val whisperBinaryNames = listOf("main", "whisper", "whisper-cpp", "whisper.cpp")
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

        // Make executable on Unix
        if (platformPaths.operatingSystem != OperatingSystem.WINDOWS) {
            whisperTarget.setExecutable(true)
        }

        // Cleanup
        archiveFile.delete()
        extractDir.deleteRecursively()

        // Update version info
        val versions = configManager.getInstalledVersions()
        configManager.saveInstalledVersions(versions.copy(whisperCpp = release.tagName))

        emit(DownloadProgress(1f, "whisper.cpp ${release.tagName} installed"))
        logger.info { "whisper.cpp ${release.tagName} installed successfully" }
    }.flowOn(Dispatchers.IO)

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

        return DependencyUpdates(
            ytDlpAvailable = ytDlpUpdate,
            ffmpegAvailable = null, // FFmpeg doesn't have a consistent version API
            whisperCppAvailable = whisperCppUpdate
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

        // Make the actual download request
        val response = httpClient.get(url) {
            if (startByte > 0) {
                header(HttpHeaders.Range, "bytes=$startByte-")
            }
        }

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

    private fun getFfmpegDownloadUrl(): String {
        return when (platformPaths.operatingSystem) {
            OperatingSystem.WINDOWS ->
                "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip"
            OperatingSystem.MACOS ->
                "https://evermeet.cx/ffmpeg/getrelease/zip"
            OperatingSystem.LINUX ->
                "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz"
        }
    }

    private fun getWhisperCppDownloadUrl(release: GitHubRelease): String {
        val pattern = when (platformPaths.operatingSystem) {
            OperatingSystem.WINDOWS -> Regex(".*win.*x64.*\\.zip", RegexOption.IGNORE_CASE)
            OperatingSystem.MACOS -> Regex(".*macos.*\\.zip", RegexOption.IGNORE_CASE)
            OperatingSystem.LINUX -> Regex(".*linux.*x64.*\\.zip", RegexOption.IGNORE_CASE)
        }

        return release.assets.find { pattern.matches(it.name) }?.browserDownloadUrl
            ?: release.assets.find {
                val name = it.name.lowercase()
                when (platformPaths.operatingSystem) {
                    OperatingSystem.WINDOWS -> "win" in name && "bin" in name
                    OperatingSystem.MACOS -> "macos" in name || "darwin" in name
                    OperatingSystem.LINUX -> "linux" in name && "bin" in name
                }
            }?.browserDownloadUrl
            ?: throw UpdateException("whisper.cpp binary not found for ${platformPaths.operatingSystem}")
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
    val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val publishedAt: String? = null,
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
    val browserDownloadUrl: String,
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
 */
data class DependencyUpdates(
    val ytDlpAvailable: String?,
    val ffmpegAvailable: String?,
    val whisperCppAvailable: String?
) {
    /** Whether any dependency updates are available. */
    val hasUpdates: Boolean get() =
        ytDlpAvailable != null || ffmpegAvailable != null || whisperCppAvailable != null
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
