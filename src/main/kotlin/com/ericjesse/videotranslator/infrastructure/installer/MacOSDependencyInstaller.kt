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
 * macOS-specific dependency installer.
 * Downloads and installs pre-built binaries for macOS (both Intel and Apple Silicon).
 */
class MacOSDependencyInstaller(
    platformPaths: PlatformPaths,
    httpClient: HttpClient,
    processExecutor: ProcessExecutor,
    archiveExtractor: ArchiveExtractor,
) : AbstractDependencyInstaller(platformPaths, httpClient, processExecutor, archiveExtractor) {

    private val isAppleSilicon: Boolean by lazy {
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
        val arch = if (isAppleSilicon) "arm64" else "x86_64"

        return when (componentId) {
            ComponentId.YT_DLP -> "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos"

            ComponentId.FFMPEG -> if (isAppleSilicon) {
                "https://evermeet.cx/ffmpeg/getrelease/zip/arm64"
            } else {
                "https://evermeet.cx/ffmpeg/getrelease/zip"
            }

            ComponentId.WHISPER_CPP -> {
                // whisper.cpp releases - we'll use a pre-built binary
                // Note: In production, you might want to build this yourself or use a trusted source
                "https://github.com/ggerganov/whisper.cpp/releases/latest/download/whisper-bin-$arch-apple-darwin.zip"
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
            ComponentId.YT_DLP, ComponentId.FFMPEG, ComponentId.WHISPER_CPP -> {
                // Make binary executable
                makeExecutable(installPath)

                // Remove quarantine attribute if present (macOS security)
                removeQuarantine(installPath)
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

        // Check for Rosetta 2 on Apple Silicon if needed
        if (isAppleSilicon) {
            results.add(checkRosetta())
        }

        // Check for Xcode Command Line Tools (needed for some operations)
        results.add(checkXcodeCommandLineTools())

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

    private suspend fun removeQuarantine(path: String) {
        try {
            processExecutor.executeAndCapture(
                listOf("xattr", "-d", "com.apple.quarantine", path),
                ProcessConfig(timeoutMinutes = 1)
            )
            logger.debug { "Removed quarantine from: $path" }
        } catch (e: Exception) {
            // Quarantine attribute may not exist, which is fine
            logger.debug { "Could not remove quarantine from $path (may not be quarantined)" }
        }
    }

    private fun checkRosetta(): PreInstallCheckResult {
        return try {
            val result = ProcessBuilder("arch", "-x86_64", "uname", "-m")
                .redirectErrorStream(true)
                .start()
                .waitFor()

            if (result == 0) {
                PreInstallCheckResult.Passed(
                    checkName = "Rosetta 2",
                    details = "Rosetta 2 is available for x86_64 compatibility"
                )
            } else {
                PreInstallCheckResult.Warning(
                    checkName = "Rosetta 2",
                    message = "Rosetta 2 may not be installed",
                    suggestion = "Some components may require Rosetta 2. Install it via: softwareupdate --install-rosetta"
                )
            }
        } catch (e: Exception) {
            PreInstallCheckResult.Warning(
                checkName = "Rosetta 2",
                message = "Could not check Rosetta 2 status",
                suggestion = "Some x86_64 binaries may not run without Rosetta 2"
            )
        }
    }

    private fun checkXcodeCommandLineTools(): PreInstallCheckResult {
        return try {
            val result = ProcessBuilder("xcode-select", "-p")
                .redirectErrorStream(true)
                .start()
                .waitFor()

            if (result == 0) {
                PreInstallCheckResult.Passed(
                    checkName = "Xcode Command Line Tools",
                    details = "Xcode Command Line Tools are installed"
                )
            } else {
                PreInstallCheckResult.Warning(
                    checkName = "Xcode Command Line Tools",
                    message = "Xcode Command Line Tools may not be installed",
                    suggestion = "Install via: xcode-select --install"
                )
            }
        } catch (e: Exception) {
            PreInstallCheckResult.Warning(
                checkName = "Xcode Command Line Tools",
                message = "Could not check Xcode Command Line Tools status",
                suggestion = null
            )
        }
    }
}
