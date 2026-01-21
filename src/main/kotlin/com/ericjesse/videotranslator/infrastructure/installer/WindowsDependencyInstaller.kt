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
 * Windows-specific dependency installer.
 * Downloads and installs pre-built binaries for Windows (x64).
 */
class WindowsDependencyInstaller(
    platformPaths: PlatformPaths,
    httpClient: HttpClient,
    processExecutor: ProcessExecutor,
    archiveExtractor: ArchiveExtractor,
) : AbstractDependencyInstaller(platformPaths, httpClient, processExecutor, archiveExtractor) {

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
        return when (componentId) {
            ComponentId.YT_DLP -> "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"

            ComponentId.FFMPEG -> "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"

            ComponentId.WHISPER_CPP -> {
                // whisper.cpp releases for Windows
                "https://github.com/ggerganov/whisper.cpp/releases/latest/download/whisper-bin-x64.zip"
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
            ComponentId.YT_DLP -> "yt-dlp.exe"
            ComponentId.FFMPEG -> "ffmpeg.exe"
            ComponentId.WHISPER_CPP -> "whisper.exe"
            ComponentId.WHISPER_MODEL_BASE -> "ggml-base.bin"
            ComponentId.WHISPER_MODEL_SMALL -> "ggml-small.bin"
            ComponentId.WHISPER_MODEL_MEDIUM -> "ggml-medium.bin"
            ComponentId.WHISPER_MODEL_LARGE -> "ggml-large-v3.bin"
            ComponentId.LIBRE_TRANSLATE -> "libretranslate"
        }
    }

    override suspend fun postInstallSetup(componentId: ComponentId, installPath: String) {
        when (componentId) {
            ComponentId.FFMPEG -> {
                // FFmpeg zip from gyan.dev extracts to a folder like "ffmpeg-7.0-essentials_build"
                // We need to find and move the binaries to the expected location
                moveFFmpegBinaries(installPath)
            }

            ComponentId.WHISPER_CPP -> {
                // whisper.cpp zip may extract to a subdirectory
                moveWhisperBinaries(installPath)
            }

            else -> {
                // Single executables and models don't need special setup on Windows
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

        // Check for Visual C++ Redistributable
        results.add(checkVCRedist())

        // Check Windows version
        results.add(checkWindowsVersion())

        return results
    }

    private fun moveFFmpegBinaries(installDir: String) {
        val binDir = File(installDir)

        // Find the ffmpeg folder (e.g., "ffmpeg-7.0-essentials_build")
        val ffmpegFolder = binDir.listFiles()?.find {
            it.isDirectory && it.name.startsWith("ffmpeg-")
        }

        if (ffmpegFolder != null) {
            val ffmpegBinDir = File(ffmpegFolder, "bin")
            if (ffmpegBinDir.exists()) {
                // Move ffmpeg.exe, ffprobe.exe, ffplay.exe to bin directory
                ffmpegBinDir.listFiles()?.filter { it.extension == "exe" }?.forEach { exe ->
                    val target = File(binDir, exe.name)
                    if (!target.exists()) {
                        exe.copyTo(target)
                        logger.debug { "Copied ${exe.name} to ${target.absolutePath}" }
                    }
                }
            }
            // Clean up the extracted folder
            ffmpegFolder.deleteRecursively()
            logger.debug { "Cleaned up extracted folder: ${ffmpegFolder.absolutePath}" }
        }
    }

    private fun moveWhisperBinaries(installDir: String) {
        val binDir = File(installDir)

        // whisper.cpp might extract to a subdirectory or directly
        // Look for whisper.exe or main.exe in subdirectories
        val whisperExe = binDir.walkTopDown()
            .filter { it.isFile && (it.name == "main.exe" || it.name == "whisper.exe") }
            .firstOrNull()

        if (whisperExe != null && whisperExe.parentFile != binDir) {
            val target = File(binDir, "whisper.exe")
            if (!target.exists()) {
                whisperExe.copyTo(target)
                logger.debug { "Copied ${whisperExe.name} to ${target.absolutePath}" }
            }

            // Also copy any DLLs that might be needed
            whisperExe.parentFile.listFiles()?.filter { it.extension == "dll" }?.forEach { dll ->
                val dllTarget = File(binDir, dll.name)
                if (!dllTarget.exists()) {
                    dll.copyTo(dllTarget)
                    logger.debug { "Copied ${dll.name} to ${dllTarget.absolutePath}" }
                }
            }

            // Clean up any extracted subdirectories
            binDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                dir.deleteRecursively()
                logger.debug { "Cleaned up extracted folder: ${dir.absolutePath}" }
            }
        }
    }

    private fun checkVCRedist(): PreInstallCheckResult {
        return try {
            // Check for Visual C++ Redistributable by looking for common DLLs
            // This is a simplified check - vcredist is typically in System32
            val system32 = System.getenv("SystemRoot")?.let { "$it\\System32" }
                ?: "C:\\Windows\\System32"

            val vcRuntimeFiles = listOf(
                "vcruntime140.dll",
                "msvcp140.dll"
            )

            val allPresent = vcRuntimeFiles.all { File(system32, it).exists() }

            if (allPresent) {
                PreInstallCheckResult.Passed(
                    checkName = "Visual C++ Runtime",
                    details = "Visual C++ Redistributable is installed"
                )
            } else {
                PreInstallCheckResult.Warning(
                    checkName = "Visual C++ Runtime",
                    message = "Visual C++ Redistributable may not be installed",
                    suggestion = "Download and install from: https://aka.ms/vs/17/release/vc_redist.x64.exe"
                )
            }
        } catch (e: Exception) {
            PreInstallCheckResult.Warning(
                checkName = "Visual C++ Runtime",
                message = "Could not check Visual C++ Runtime status: ${e.message}",
                suggestion = "Ensure Visual C++ Redistributable is installed"
            )
        }
    }

    private fun checkWindowsVersion(): PreInstallCheckResult {
        return try {
            val osVersion = System.getProperty("os.version") ?: "unknown"
            val majorVersion = osVersion.split(".").firstOrNull()?.toIntOrNull() ?: 0

            // Windows 10 is version 10.x, Windows 11 is also 10.x internally
            if (majorVersion >= 10) {
                PreInstallCheckResult.Passed(
                    checkName = "Windows Version",
                    details = "Windows version $osVersion is supported"
                )
            } else {
                PreInstallCheckResult.Warning(
                    checkName = "Windows Version",
                    message = "Windows version $osVersion may have compatibility issues",
                    suggestion = "Windows 10 or later is recommended"
                )
            }
        } catch (e: Exception) {
            PreInstallCheckResult.Warning(
                checkName = "Windows Version",
                message = "Could not determine Windows version",
                suggestion = null
            )
        }
    }
}
