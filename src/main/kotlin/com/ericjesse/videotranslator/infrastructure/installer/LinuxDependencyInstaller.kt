package com.ericjesse.videotranslator.infrastructure.installer

import com.ericjesse.videotranslator.domain.installer.ComponentDescription
import com.ericjesse.videotranslator.domain.installer.ComponentId
import com.ericjesse.videotranslator.domain.installer.PreInstallCheckResult
import com.ericjesse.videotranslator.infrastructure.archive.ArchiveExtractor
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import com.ericjesse.videotranslator.infrastructure.process.ProcessConfig
import com.ericjesse.videotranslator.infrastructure.process.ProcessExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Linux-specific dependency installer.
 * Downloads and installs pre-built binaries for Linux (x86_64 and aarch64).
 */
class LinuxDependencyInstaller(
    platformPaths: PlatformPaths,
    httpClient: HttpClient,
    processExecutor: ProcessExecutor,
    archiveExtractor: ArchiveExtractor,
) : AbstractDependencyInstaller(platformPaths, httpClient, processExecutor, archiveExtractor) {

    private val isArm64: Boolean by lazy {
        val arch = System.getProperty("os.arch")?.lowercase() ?: ""
        arch.contains("aarch64") || arch.contains("arm64")
    }

    override fun getComponentDescriptions(): List<ComponentDescription> = listOf(
        ComponentDescription(
            id = ComponentId.YT_DLP,
            name = "yt-dlp",
            description = "Video downloader for YouTube and other sites",
            estimatedSizeMb = 20,
            warnings = emptyList()
        ),
        ComponentDescription(
            id = ComponentId.FFMPEG,
            name = "FFmpeg",
            description = "Audio/video processing library",
            estimatedSizeMb = 150,
            warnings = listOf(
                "FFmpeg is a large download (~150MB)"
            )
        ),
        ComponentDescription(
            id = ComponentId.WHISPER_CPP,
            name = "whisper.cpp",
            description = "Fast speech-to-text engine",
            estimatedSizeMb = 5,
            warnings = emptyList(),
            dependencies = listOf(ComponentId.FFMPEG)
        ),
        ComponentDescription(
            id = ComponentId.WHISPER_MODEL_BASE,
            name = "Whisper Base Model",
            description = "Base transcription model (good balance of speed and accuracy)",
            estimatedSizeMb = 150,
            warnings = listOf(
                "Model download may take a few minutes depending on your connection"
            ),
            isOptional = false,
            dependencies = listOf(ComponentId.WHISPER_CPP)
        )
    )

    override fun getDownloadUrl(componentId: ComponentId): String {
        val arch = if (isArm64) "aarch64" else "x86_64"

        return when (componentId) {
            ComponentId.YT_DLP -> {
                if (isArm64) {
                    "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64"
                } else {
                    "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux"
                }
            }

            ComponentId.FFMPEG -> {
                // Using static builds from johnvansickle.com for Linux
                if (isArm64) {
                    "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-arm64-static.tar.xz"
                } else {
                    "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz"
                }
            }

            ComponentId.WHISPER_CPP -> {
                // whisper.cpp releases for Linux
                "https://github.com/ggerganov/whisper.cpp/releases/latest/download/whisper-bin-$arch-linux-gnu.zip"
            }

            ComponentId.WHISPER_MODEL_BASE ->
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"

            ComponentId.WHISPER_MODEL_SMALL ->
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"

            ComponentId.WHISPER_MODEL_MEDIUM ->
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin"

            ComponentId.WHISPER_MODEL_LARGE ->
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3.bin"

            ComponentId.LIBRE_TRANSLATE ->
                throw UnsupportedOperationException("LibreTranslate is installed via pip, not direct download")
        }
    }

    override fun getExpectedFileName(componentId: ComponentId): String {
        return when (componentId) {
            ComponentId.YT_DLP -> "yt-dlp"
            ComponentId.FFMPEG -> "ffmpeg"
            ComponentId.WHISPER_CPP -> "whisper"
            ComponentId.WHISPER_MODEL_BASE -> "ggml-base.bin"
            ComponentId.WHISPER_MODEL_SMALL -> "ggml-small.bin"
            ComponentId.WHISPER_MODEL_MEDIUM -> "ggml-medium.bin"
            ComponentId.WHISPER_MODEL_LARGE -> "ggml-large-v3.bin"
            ComponentId.LIBRE_TRANSLATE -> "libretranslate"
        }
    }

    override suspend fun postInstallSetup(componentId: ComponentId, installPath: String) {
        when (componentId) {
            ComponentId.YT_DLP, ComponentId.WHISPER_CPP -> {
                // Make binary executable
                makeExecutable(installPath)
            }

            ComponentId.FFMPEG -> {
                // FFmpeg tar.xz extracts to a folder, need to move binaries
                moveFFmpegBinaries(File(installPath).parent)
                makeExecutable(installPath)

                // Also make ffprobe executable if present
                val ffprobePath = File(installPath).parent + File.separator + "ffprobe"
                if (File(ffprobePath).exists()) {
                    makeExecutable(ffprobePath)
                }
            }

            else -> {
                // Models don't need special setup
            }
        }
    }

    override suspend fun getInstalledVersion(componentId: ComponentId): String? {
        return try {
            val config = ProcessConfig(timeoutMinutes = 1)
            when (componentId) {
                ComponentId.YT_DLP -> {
                    val result = processExecutor.executeAndCapture(
                        listOf(getInstallPath(componentId), "--version"),
                        config
                    )
                    if (result.exitCode == 0) result.stdout.trim() else null
                }

                ComponentId.FFMPEG -> {
                    val result = processExecutor.executeAndCapture(
                        listOf(getInstallPath(componentId), "-version"),
                        config
                    )
                    if (result.exitCode == 0) {
                        result.stdout.lines().firstOrNull()?.let { line ->
                            Regex("""ffmpeg version (\S+)""").find(line)?.groupValues?.get(1)
                        }
                    } else null
                }

                ComponentId.WHISPER_CPP -> {
                    val result = processExecutor.executeAndCapture(
                        listOf(getInstallPath(componentId), "--help"),
                        config
                    )
                    // whisper.cpp doesn't have a version flag, so just check it runs
                    if (result.exitCode == 0 || result.stderr.contains("whisper")) "installed" else null
                }

                else -> null
            }
        } catch (e: Exception) {
            logger.warn { "Failed to get version for $componentId: ${e.message}" }
            null
        }
    }

    override suspend fun platformSpecificChecks(): List<PreInstallCheckResult> {
        val results = mutableListOf<PreInstallCheckResult>()

        // Check for required libraries
        results.add(checkLibC())

        // Check for CUDA (optional, for GPU acceleration)
        results.add(checkCuda())

        return results
    }

    private fun makeExecutable(path: String) {
        try {
            val file = File(path)
            if (file.exists()) {
                file.setExecutable(true, false)
                logger.debug { "Made executable: $path" }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to make $path executable: ${e.message}" }
        }
    }

    private fun moveFFmpegBinaries(installDir: String) {
        val binDir = File(installDir)

        // Find the ffmpeg folder (e.g., "ffmpeg-6.1-amd64-static")
        val ffmpegFolder = binDir.listFiles()?.find {
            it.isDirectory && it.name.startsWith("ffmpeg-")
        }

        if (ffmpegFolder != null) {
            // Move ffmpeg, ffprobe binaries to bin directory
            listOf("ffmpeg", "ffprobe").forEach { binaryName ->
                val source = File(ffmpegFolder, binaryName)
                val target = File(binDir, binaryName)
                if (source.exists() && !target.exists()) {
                    source.copyTo(target)
                    logger.debug { "Copied $binaryName to ${target.absolutePath}" }
                }
            }
            // Clean up the extracted folder
            ffmpegFolder.deleteRecursively()
            logger.debug { "Cleaned up extracted folder: ${ffmpegFolder.absolutePath}" }
        }
    }

    private fun checkLibC(): PreInstallCheckResult {
        return try {
            // Check glibc version by running ldd --version
            val result = ProcessBuilder("ldd", "--version")
                .redirectErrorStream(true)
                .start()

            val output = result.inputStream.bufferedReader().readText()
            result.waitFor()

            // Extract version from output like "ldd (GNU libc) 2.35"
            val versionMatch = Regex("""(\d+\.\d+)""").find(output)
            val version = versionMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

            // Most binaries require glibc 2.17 or higher
            if (version >= 2.17) {
                PreInstallCheckResult.Passed(
                    checkName = "GNU C Library",
                    details = "glibc version ${versionMatch?.groupValues?.get(1)} is installed"
                )
            } else {
                PreInstallCheckResult.Warning(
                    checkName = "GNU C Library",
                    message = "glibc version may be too old",
                    suggestion = "Update your system or use a newer Linux distribution"
                )
            }
        } catch (e: Exception) {
            PreInstallCheckResult.Warning(
                checkName = "GNU C Library",
                message = "Could not check glibc version: ${e.message}",
                suggestion = null
            )
        }
    }

    private fun checkCuda(): PreInstallCheckResult {
        return try {
            // Check if nvidia-smi is available (indicates CUDA support)
            val result = ProcessBuilder("nvidia-smi", "--query-gpu=driver_version", "--format=csv,noheader")
                .redirectErrorStream(true)
                .start()

            val output = result.inputStream.bufferedReader().readText().trim()
            val exitCode = result.waitFor()

            if (exitCode == 0 && output.isNotEmpty()) {
                PreInstallCheckResult.Passed(
                    checkName = "NVIDIA GPU",
                    details = "NVIDIA driver version $output detected - GPU acceleration available"
                )
            } else {
                PreInstallCheckResult.Warning(
                    checkName = "NVIDIA GPU",
                    message = "No NVIDIA GPU detected",
                    suggestion = "GPU acceleration won't be available. Transcription will use CPU only."
                )
            }
        } catch (e: Exception) {
            PreInstallCheckResult.Warning(
                checkName = "NVIDIA GPU",
                message = "NVIDIA GPU not detected",
                suggestion = "GPU acceleration won't be available. Transcription will use CPU only."
            )
        }
    }
}
