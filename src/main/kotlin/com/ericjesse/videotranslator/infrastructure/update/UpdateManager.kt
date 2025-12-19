package com.ericjesse.videotranslator.infrastructure.update

import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.config.InstalledVersions
import com.ericjesse.videotranslator.infrastructure.config.OperatingSystem
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.utils.io.core.readAvailable
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.FileOutputStream

private val logger = KotlinLogging.logger {}

/**
 * Manages updates for the application and its dependencies.
 */
class UpdateManager(
    private val httpClient: HttpClient,
    private val platformPaths: PlatformPaths,
    private val configManager: ConfigManager
) {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    companion object {
        const val APP_REPO = "ericjesse/video-translator"
        const val YTDLP_REPO = "yt-dlp/yt-dlp"
        const val WHISPER_REPO = "ggerganov/whisper.cpp"
        
        val WHISPER_MODELS = mapOf(
            "tiny" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
            "base" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
            "small" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
            "medium" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin",
            "large" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large.bin"
        )
        
        val WHISPER_MODEL_SIZES = mapOf(
            "tiny" to 75L * 1024 * 1024,
            "base" to 142L * 1024 * 1024,
            "small" to 466L * 1024 * 1024,
            "medium" to 1536L * 1024 * 1024,
            "large" to 2952L * 1024 * 1024
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
        
        downloadFile(updateInfo.downloadUrl, tempFile) { progress ->
            emit(DownloadProgress(progress, "Downloading update..."))
        }
        
        emit(DownloadProgress(1f, "Download complete. Ready to install."))
        
        // The actual installation would be platform-specific
        // and typically requires restarting the app
    }
    
    // ========== Dependency Installation ==========
    
    /**
     * Downloads and installs yt-dlp.
     */
    fun installYtDlp(): Flow<DownloadProgress> = flow {
        emit(DownloadProgress(0f, "Fetching latest yt-dlp release..."))
        
        val release = getLatestRelease(YTDLP_REPO)
        val downloadUrl = getYtDlpDownloadUrl(release)
        val targetPath = File(platformPaths.getBinaryPath("yt-dlp"))
        
        emit(DownloadProgress(0.1f, "Downloading yt-dlp..."))
        
        downloadFile(downloadUrl, targetPath) { progress ->
            emit(DownloadProgress(0.1f + progress * 0.8f, "Downloading yt-dlp..."))
        }
        
        // Make executable on Unix
        if (platformPaths.operatingSystem != OperatingSystem.WINDOWS) {
            targetPath.setExecutable(true)
        }
        
        // Update version info
        val versions = configManager.getInstalledVersions()
        configManager.saveInstalledVersions(versions.copy(ytDlp = release.tagName))
        
        emit(DownloadProgress(1f, "yt-dlp installed"))
    }
    
    /**
     * Downloads and installs FFmpeg.
     */
    fun installFfmpeg(): Flow<DownloadProgress> = flow {
        emit(DownloadProgress(0f, "Fetching FFmpeg..."))
        
        val downloadUrl = getFfmpegDownloadUrl()
        val tempFile = File(platformPaths.cacheDir, "ffmpeg-download.tmp")
        
        emit(DownloadProgress(0.1f, "Downloading FFmpeg..."))
        
        downloadFile(downloadUrl, tempFile) { progress ->
            emit(DownloadProgress(0.1f + progress * 0.7f, "Downloading FFmpeg..."))
        }
        
        emit(DownloadProgress(0.8f, "Extracting FFmpeg..."))
        
        extractFfmpeg(tempFile)
        tempFile.delete()
        
        // Update version info
        val versions = configManager.getInstalledVersions()
        configManager.saveInstalledVersions(versions.copy(ffmpeg = "7.0")) // TODO: Get actual version
        
        emit(DownloadProgress(1f, "FFmpeg installed"))
    }
    
    /**
     * Downloads a Whisper model.
     */
    fun installWhisperModel(modelName: String): Flow<DownloadProgress> = flow {
        val url = WHISPER_MODELS[modelName] 
            ?: throw IllegalArgumentException("Unknown model: $modelName")
        
        emit(DownloadProgress(0f, "Downloading Whisper $modelName model..."))
        
        val modelDir = File(platformPaths.modelsDir, "whisper").apply { mkdirs() }
        val targetFile = File(modelDir, "ggml-$modelName.bin")
        
        downloadFile(url, targetFile) { progress ->
            emit(DownloadProgress(progress, "Downloading Whisper $modelName model..."))
        }
        
        // Update version info
        val versions = configManager.getInstalledVersions()
        configManager.saveInstalledVersions(versions.copy(whisperModel = modelName))
        
        emit(DownloadProgress(1f, "Whisper model installed"))
    }
    
    /**
     * Downloads and installs whisper.cpp binary.
     */
    fun installWhisperCpp(): Flow<DownloadProgress> = flow {
        emit(DownloadProgress(0f, "Fetching whisper.cpp..."))
        
        val release = getLatestRelease(WHISPER_REPO)
        val downloadUrl = getWhisperCppDownloadUrl(release)
        val tempFile = File(platformPaths.cacheDir, "whisper-download.tmp")
        
        emit(DownloadProgress(0.1f, "Downloading whisper.cpp..."))
        
        downloadFile(downloadUrl, tempFile) { progress ->
            emit(DownloadProgress(0.1f + progress * 0.7f, "Downloading whisper.cpp..."))
        }
        
        emit(DownloadProgress(0.8f, "Extracting whisper.cpp..."))
        
        extractWhisperCpp(tempFile)
        tempFile.delete()
        
        // Update version info
        val versions = configManager.getInstalledVersions()
        configManager.saveInstalledVersions(versions.copy(whisperCpp = release.tagName))
        
        emit(DownloadProgress(1f, "whisper.cpp installed"))
    }
    
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
        } catch (e: Exception) { null }
        
        return DependencyUpdates(
            ytDlpAvailable = ytDlpUpdate,
            ffmpegAvailable = null, // TODO: Implement FFmpeg update check
            whisperCppAvailable = null // TODO: Implement Whisper update check
        )
    }
    
    // ========== Helper Methods ==========
    
    private suspend fun getLatestRelease(repo: String): GitHubRelease {
        val response = httpClient.get("https://api.github.com/repos/$repo/releases/latest") {
            header("Accept", "application/vnd.github.v3+json")
        }
        return json.decodeFromString(response.bodyAsText())
    }
    
    private suspend fun downloadFile(
        url: String,
        target: File,
        onProgress: suspend (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        
        val response = httpClient.get(url)
        val contentLength = response.contentLength() ?: 0L
        
        FileOutputStream(target).use { output ->
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(8192)
            var totalRead = 0L
            
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer)
                if (read <= 0) break
                
                output.write(buffer, 0, read)
                totalRead += read
                
                if (contentLength > 0) {
                    onProgress(totalRead.toFloat() / contentLength)
                }
            }
        }
    }
    
    private fun getYtDlpDownloadUrl(release: GitHubRelease): String {
        val assetName = when (platformPaths.operatingSystem) {
            OperatingSystem.WINDOWS -> "yt-dlp.exe"
            OperatingSystem.MACOS -> "yt-dlp_macos"
            OperatingSystem.LINUX -> "yt-dlp_linux"
        }
        
        return release.assets.find { it.name == assetName }?.browserDownloadUrl
            ?: throw IllegalStateException("yt-dlp asset not found for ${platformPaths.operatingSystem}")
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
            OperatingSystem.WINDOWS -> "win"
            OperatingSystem.MACOS -> "macos"
            OperatingSystem.LINUX -> "linux"
        }
        
        return release.assets.find { pattern in it.name.lowercase() && "bin" in it.name.lowercase() }
            ?.browserDownloadUrl
            ?: throw IllegalStateException("whisper.cpp binary not found")
    }
    
    private fun getAppDownloadUrl(release: GitHubRelease): String {
        val pattern = when (platformPaths.operatingSystem) {
            OperatingSystem.WINDOWS -> ".msi"
            OperatingSystem.MACOS -> ".dmg"
            OperatingSystem.LINUX -> ".AppImage"
        }
        
        return release.assets.find { it.name.endsWith(pattern) }?.browserDownloadUrl
            ?: throw IllegalStateException("App installer not found")
    }
    
    private suspend fun extractFfmpeg(archive: File) = withContext(Dispatchers.IO) {
        // TODO: Implement archive extraction based on platform
        // Windows: unzip
        // macOS: unzip
        // Linux: tar xf
        
        val ffmpegPath = File(platformPaths.getBinaryPath("ffmpeg"))
        
        // Make executable on Unix
        if (platformPaths.operatingSystem != OperatingSystem.WINDOWS) {
            ffmpegPath.setExecutable(true)
        }
    }
    
    private suspend fun extractWhisperCpp(archive: File) = withContext(Dispatchers.IO) {
        // TODO: Implement archive extraction
        
        val whisperPath = File(platformPaths.getBinaryPath("whisper"))
        
        if (platformPaths.operatingSystem != OperatingSystem.WINDOWS) {
            whisperPath.setExecutable(true)
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

@Serializable
data class GitHubRelease(
    val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val publishedAt: String? = null,
    val assets: List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(
    val name: String,
    val browserDownloadUrl: String,
    val size: Long = 0
)

data class AppUpdateInfo(
    val currentVersion: String,
    val newVersion: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val publishedAt: String?
)

data class DependencyUpdates(
    val ytDlpAvailable: String?,
    val ffmpegAvailable: String?,
    val whisperCppAvailable: String?
) {
    val hasUpdates: Boolean get() = 
        ytDlpAvailable != null || ffmpegAvailable != null || whisperCppAvailable != null
}

data class DownloadProgress(
    val percentage: Float,
    val message: String
)
