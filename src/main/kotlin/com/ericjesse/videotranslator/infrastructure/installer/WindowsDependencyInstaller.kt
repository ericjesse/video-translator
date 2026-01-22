package com.ericjesse.videotranslator.infrastructure.installer

import com.ericjesse.videotranslator.domain.installer.ComponentDescription
import com.ericjesse.videotranslator.domain.installer.ComponentId
import com.ericjesse.videotranslator.domain.installer.InstallationProgress
import com.ericjesse.videotranslator.domain.installer.InstalledComponent
import com.ericjesse.videotranslator.domain.installer.InstallerState
import com.ericjesse.videotranslator.domain.installer.PreInstallCheckResult
import com.ericjesse.videotranslator.infrastructure.archive.ArchiveExtractor
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import com.ericjesse.videotranslator.infrastructure.process.ProcessConfig
import com.ericjesse.videotranslator.infrastructure.process.ProcessExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

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

    companion object {
        // Python version requirements for LibreTranslate (minimum 3.8, no maximum)
        const val REQUIRED_PYTHON_MAJOR = 3
        const val REQUIRED_PYTHON_MINOR_MIN = 8

        // Python installer URL (latest stable release)
        // Using Python 3.11 as the installed version for best compatibility with LibreTranslate
        const val PYTHON_VERSION = "3.11.9"
        const val PYTHON_DOWNLOAD_URL =
            "https://www.python.org/ftp/python/$PYTHON_VERSION/python-$PYTHON_VERSION-amd64.exe"

        // VC++ Runtime version for LibreTranslate compatibility
        // Version 14.29.x is known to work; 14.30+ may cause DLL issues
        const val VC_RUNTIME_PREFERRED_VERSION = "14.29"

        // LibreTranslate language models to install by default
        val DEFAULT_LANGUAGE_MODELS = listOf(
            "translate-en_fr",
            "translate-fr_en",
            "translate-en_de",
            "translate-de_en",
            "translate-en_es",
            "translate-es_en"
        )

        // LibreTranslate pip packages to install (Option A - conservative, proven to work)
        const val PYTORCH_VERSION = "2.0.1"
        const val PYTORCH_INDEX_URL = "https://download.pytorch.org/whl/cpu"
        const val LIBRETRANSLATE_VERSION = "1.6.0"
        const val CTRANSLATE2_VERSION = "4.0.0"

        // Required dependencies for LibreTranslate 1.6.0
        val LIBRETRANSLATE_DEPENDENCIES = listOf(
            "apscheduler==3.9.1",
            "argos-translate-files==1.1.4",
            "flask-session==0.4.0",
            "lexilang==1.0.1",
            "cachelib",
            "argostranslate",
            "flask==2.2.5",
            "flask-babel==3.1.0",
            "flask-limiter==2.6.3",
            "flask-swagger==0.2.14",
            "flask-swagger-ui==4.11.1",
            "werkzeug==2.3.8",
            "itsdangerous==2.1.2",
            "langdetect==1.0.9",
            "morfessor==2.0.6",
            "polib==1.1.1",
            "prometheus-client==0.15.0",
            "redis==4.4.4",
            "requests==2.31.0",
            "translatehtml==1.5.2",
            "waitress==2.1.2",
            "expiringdict==1.2.2",
            "numpy",
            "packaging==23.1"
        )
    }

    // Cached Python path after detection
    private var detectedPythonPath: String? = null

    /**
     * Override install to handle LibreTranslate specially since it uses pip instead of direct download.
     */
    override fun install(): Flow<InstallationProgress> = flow {
        if (_state == InstallerState.INSTALLING) {
            emit(
                InstallationProgress.Failed(
                    error = "Installation already in progress",
                    failedComponent = null,
                    suggestion = "Wait for the current installation to complete"
                )
            )
            return@flow
        }

        _state = InstallerState.INSTALLING
        cancelled = false
        installedDuringSession.clear()
        warnings.clear()

        val startTime = System.currentTimeMillis()
        val components = getComponentDescriptions().filter { !it.isOptional }

        try {
            components.forEachIndexed { index, component ->
                if (cancelled) {
                    throw CancellationException("Installation cancelled by user")
                }

                // Skip if already installed
                val alreadyInstalled = when (component.id) {
                    ComponentId.LIBRE_TRANSLATE -> isLibreTranslateInstalled()
                    ComponentId.VC_REDIST -> isVCRedistCompatibleVersionInstalled()
                    ComponentId.PYTHON -> isPythonCompatibleVersionInstalled()
                    else -> isComponentInstalled(component.id)
                }

                if (alreadyInstalled) {
                    logger.info { "Component ${component.name} already installed, skipping" }
                    return@forEachIndexed
                }

                emit(
                    InstallationProgress.Starting(
                        componentId = component.id,
                        componentName = component.name,
                        componentIndex = index + 1,
                        totalComponents = components.size
                    )
                )

                try {
                    val installPath: String

                    if (component.id == ComponentId.VC_REDIST) {
                        // Special handling for VC++ Runtime - runs an installer
                        emit(
                            InstallationProgress.Installing(
                                componentId = component.id,
                                componentName = component.name,
                                percentage = 0.1f,
                                message = "Installing Visual C++ Redistributable..."
                            )
                        )

                        val result = installVCRedist { progressMessage ->
                            logger.info { "VC++ Runtime: $progressMessage" }
                        }

                        if (result == null) {
                            throw RuntimeException("Failed to install Visual C++ Redistributable")
                        }

                        installPath = result
                    } else if (component.id == ComponentId.PYTHON) {
                        // Special handling for Python - runs an installer
                        emit(
                            InstallationProgress.Installing(
                                componentId = component.id,
                                componentName = component.name,
                                percentage = 0.1f,
                                message = "Installing Python $PYTHON_VERSION..."
                            )
                        )

                        val result = installPython { progressMessage ->
                            logger.info { "Python: $progressMessage" }
                        }

                        if (result == null) {
                            throw RuntimeException("Failed to install Python")
                        }

                        installPath = result
                    } else if (component.id == ComponentId.LIBRE_TRANSLATE) {
                        // Special handling for LibreTranslate - install via pip
                        emit(
                            InstallationProgress.Installing(
                                componentId = component.id,
                                componentName = component.name,
                                percentage = 0.1f,
                                message = "Setting up LibreTranslate..."
                            )
                        )

                        val result = installLibreTranslate { progressMessage ->
                            // Emit progress messages during installation
                            // Note: Flow doesn't allow emit from a different coroutine context easily
                            // so we just log here
                            logger.info { "LibreTranslate: $progressMessage" }
                        }

                        if (result == null) {
                            throw RuntimeException("Failed to install LibreTranslate")
                        }

                        installPath = result
                    } else {
                        // Standard download and install flow
                        val downloadedFile = downloadComponent(component) { progress ->
                            // Download progress
                        }

                        if (cancelled) {
                            throw CancellationException("Installation cancelled by user")
                        }

                        emit(
                            InstallationProgress.Installing(
                                componentId = component.id,
                                componentName = component.name,
                                percentage = 0.5f,
                                message = "Extracting..."
                            )
                        )

                        // Extract and install
                        installPath = installComponent(component.id, downloadedFile)

                        if (cancelled) {
                            throw CancellationException("Installation cancelled by user")
                        }

                        // Post-install setup
                        postInstallSetup(component.id, installPath)

                        // Cleanup downloaded file
                        downloadedFile.delete()
                    }

                    // Get version
                    val version = when (component.id) {
                        ComponentId.LIBRE_TRANSLATE -> getLibreTranslateVersion()
                        ComponentId.VC_REDIST -> getVCRedistVersion()
                        ComponentId.PYTHON -> getPythonVersion()
                        else -> getInstalledVersion(component.id)
                    }

                    val installed = InstalledComponent(
                        id = component.id,
                        name = component.name,
                        version = version,
                        installPath = installPath,
                        sizeMb = component.estimatedSizeMb
                    )
                    installedDuringSession.add(installed)

                    emit(
                        InstallationProgress.ComponentCompleted(
                            componentId = component.id,
                            componentName = component.name,
                            installPath = installPath,
                            version = version
                        )
                    )

                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Failed to install ${component.name}" }
                    emit(
                        InstallationProgress.ComponentFailed(
                            componentId = component.id,
                            componentName = component.name,
                            error = e.message ?: "Unknown error",
                            isRetryable = true
                        )
                    )
                    throw e
                }
            }

            val duration = kotlin.time.Duration.parse("${System.currentTimeMillis() - startTime}ms")
            _installationSummary = com.ericjesse.videotranslator.domain.installer.InstallationSummary(
                installedComponents = installedDuringSession.toList(),
                totalSizeMb = installedDuringSession.sumOf { it.sizeMb },
                duration = duration,
                warnings = warnings.toList()
            )

            _state = InstallerState.COMPLETED
            emit(InstallationProgress.Completed(_installationSummary!!))

        } catch (e: kotlinx.coroutines.CancellationException) {
            logger.info { "Installation cancelled, rollback initiated" }
        } catch (e: Exception) {
            _state = InstallerState.FAILED
            emit(
                InstallationProgress.Failed(
                    error = e.message ?: "Installation failed",
                    failedComponent = null,
                    suggestion = "Check the logs for details and try again"
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Override cancel to also handle LibreTranslate cleanup.
     */
    override suspend fun cancel() {
        if (_state != InstallerState.INSTALLING) {
            return
        }

        cancelled = true
        _state = InstallerState.CANCELLING

        logger.info { "Cancelling installation, will remove ${installedDuringSession.size} components" }

        // Wait for current operation to stop
        installationJob?.cancelAndJoin()

        // Rollback - remove components installed during this session
        // This includes LibreTranslate venv cleanup
        rollbackWithLibreTranslate()

        _state = InstallerState.CANCELLED
    }

    /**
     * Rollback that handles special cleanup for LibreTranslate, Python, and VC++ Runtime.
     * Only removes components that were installed during this session (not pre-existing ones).
     */
    private suspend fun rollbackWithLibreTranslate() {
        val preExisting = preInstallState?.existingComponents ?: emptySet()

        installedDuringSession.forEach { installed ->
            // Only remove if it didn't exist before
            if (installed.id !in preExisting) {
                try {
                    when (installed.id) {
                        ComponentId.LIBRE_TRANSLATE -> {
                            // Special cleanup for LibreTranslate (pip packages)
                            uninstallLibreTranslate()
                        }

                        ComponentId.PYTHON -> {
                            // Special cleanup for Python (run uninstaller)
                            uninstallPython()
                        }

                        ComponentId.VC_REDIST -> {
                            // VC++ Runtime uninstall is complex and not recommended
                            // Just log that we're skipping it
                            logger.info { "Skipping VC++ Runtime rollback (system component)" }
                        }

                        else -> {
                            val file = File(installed.installPath)
                            if (file.exists()) {
                                if (file.isDirectory) {
                                    file.deleteRecursively()
                                } else {
                                    file.delete()
                                }
                                logger.info { "Rolled back: removed ${installed.name} from ${installed.installPath}" }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to rollback ${installed.name}" }
                }
            }
        }

        installedDuringSession.clear()
    }

    override fun getComponentDescriptions(): List<ComponentDescription> = listOf(
        ComponentDescription(
            id = ComponentId.VC_REDIST,
            name = "Visual C++ Runtime",
            description = "Microsoft Visual C++ Redistributable (v$VC_RUNTIME_PREFERRED_VERSION)",
            estimatedSizeMb = 25,
            warnings = listOf(
                "Required for whisper.cpp and other native components",
                "Version $VC_RUNTIME_PREFERRED_VERSION will be installed if not present or outdated"
            )
        ),
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
        ),
        ComponentDescription(
            id = ComponentId.PYTHON,
            name = "Python $PYTHON_VERSION",
            description = "Python runtime required for LibreTranslate",
            estimatedSizeMb = 100,
            warnings = listOf(
                "Python will be installed system-wide if not already present",
                "Requires administrator privileges for installation"
            ),
            isOptional = true, // Only needed if LibreTranslate is selected
            dependencies = listOf(ComponentId.VC_REDIST)
        ),
        ComponentDescription(
            id = ComponentId.LIBRE_TRANSLATE,
            name = "LibreTranslate",
            description = "Local machine translation service with PyTorch, ctranslate2, and language models",
            estimatedSizeMb = 2000, // ~2GB total (PyTorch + LibreTranslate + models)
            warnings = listOf(
                "Installation includes PyTorch (~175MB), ctranslate2, and all required dependencies",
                "Language models (~400MB) will be downloaded automatically",
                "Total disk space required: ~2GB"
            ),
            isOptional = true, // User can choose external translation services
            dependencies = listOf(ComponentId.PYTHON, ComponentId.VC_REDIST)
        )
    )

    override fun getPlatformWarnings(): List<String> = listOf(
        "During installation, you may be prompted multiple times to grant permission or accept license agreements for different tools."
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

            ComponentId.VC_REDIST ->
                throw UnsupportedOperationException("VC++ Redistributable is bundled with the application, not downloaded")

            ComponentId.PYTHON ->
                PYTHON_DOWNLOAD_URL
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
            ComponentId.VC_REDIST -> "vc_redist.x64.exe"
            ComponentId.PYTHON -> "python-$PYTHON_VERSION-amd64.exe"
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

        // Check for Visual C++ Redistributable (with version check for LibreTranslate)
        results.add(checkVCRedist())

        // Check Windows version
        results.add(checkWindowsVersion())

        // Check for Python (required for LibreTranslate)
        results.add(checkPython())

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
        logger.info { "Checking Visual C++ Runtime (preferred version: $VC_RUNTIME_PREFERRED_VERSION)..." }
        return try {
            // Try to get the version from registry first
            val version = getVCRedistVersion()
            logger.info { "Registry check for VC++ Runtime: ${version ?: "not found"}" }

            if (version != null) {
                val majorMinor = version.removePrefix("v").split(".").take(2).joinToString(".")
                val versionNum = majorMinor.toDoubleOrNull() ?: 0.0
                val requiredVersionNum = VC_RUNTIME_PREFERRED_VERSION.toDoubleOrNull() ?: 14.29
                logger.info { "VC++ Runtime version number: $versionNum (required: $requiredVersionNum)" }

                when {
                    // Version >= 14.30 is incompatible - FAIL installation
                    versionNum >= 14.30 -> {
                        logger.error { "VC++ Runtime version $version (>= 14.30) is incompatible with LibreTranslate" }
                        PreInstallCheckResult.Failed(
                            checkName = "Visual C++ Runtime",
                            reason = "Visual C++ Runtime $version is installed, but version 14.30+ is incompatible with LibreTranslate due to DLL issues.",
                            suggestion = "Please uninstall the current Visual C++ Redistributable (version $version) and restart the setup. Version $VC_RUNTIME_PREFERRED_VERSION will be installed automatically."
                        )
                    }
                    // Version 14.29.x is compatible - PASS
                    majorMinor.startsWith(VC_RUNTIME_PREFERRED_VERSION) -> {
                        logger.info { "VC++ Runtime version $version is compatible" }
                        PreInstallCheckResult.Passed(
                            checkName = "Visual C++ Runtime",
                            details = "Visual C++ Runtime $version (compatible)"
                        )
                    }
                    // Version < 14.29 - will be upgraded during installation
                    versionNum < requiredVersionNum -> {
                        logger.info { "VC++ Runtime version $version will be upgraded to v$VC_RUNTIME_PREFERRED_VERSION" }
                        PreInstallCheckResult.Passed(
                            checkName = "Visual C++ Runtime",
                            details = "Visual C++ Runtime $version found. Will be upgraded to v$VC_RUNTIME_PREFERRED_VERSION during installation."
                        )
                    }
                    // Any other case (shouldn't happen) - PASS
                    else -> {
                        logger.info { "VC++ Runtime version $version is installed (unexpected version range)" }
                        PreInstallCheckResult.Passed(
                            checkName = "Visual C++ Runtime",
                            details = "Visual C++ Runtime $version is installed"
                        )
                    }
                }
            } else {
                // No version found in registry - check if DLLs exist
                val system32 = System.getenv("SystemRoot")?.let { "$it\\System32" }
                    ?: "C:\\Windows\\System32"
                logger.info { "Checking for VC++ DLLs in $system32" }

                val vcRuntimeFiles = listOf("vcruntime140.dll", "msvcp140.dll")
                val allPresent = vcRuntimeFiles.all { File(system32, it).exists() }
                logger.info { "VC++ DLLs present: $allPresent" }

                if (allPresent) {
                    // DLLs exist but version unknown - proceed with caution
                    logger.info { "VC++ DLLs found but version unknown" }
                    PreInstallCheckResult.Passed(
                        checkName = "Visual C++ Runtime",
                        details = "Visual C++ Runtime detected. Version will be verified during installation."
                    )
                } else {
                    // No runtime found - will be installed
                    logger.info { "VC++ Runtime not found - will be installed during setup" }
                    PreInstallCheckResult.Passed(
                        checkName = "Visual C++ Runtime",
                        details = "Visual C++ Runtime not found. Will be installed during setup."
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Error checking VC++ Runtime status" }
            PreInstallCheckResult.Warning(
                checkName = "Visual C++ Runtime",
                message = "Could not check Visual C++ Runtime status: ${e.message}",
                suggestion = "Installation will proceed. VC++ Runtime will be installed if needed."
            )
        }
    }

    /**
     * Gets the VC++ Redistributable version from the Windows registry.
     */
    private fun getVCRedistVersion(): String? {
        return try {
            val process = ProcessBuilder(
                "reg", "query",
                "HKLM\\SOFTWARE\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\X64",
                "/v", "Version"
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            // Parse output like "Version    REG_SZ    v14.29.30156.00"
            val match = Regex("""Version\s+REG_SZ\s+(v[\d.]+)""").find(output)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            logger.debug { "Could not query VC++ Runtime version from registry: ${e.message}" }
            null
        }
    }

    /**
     * Checks if a compatible version of VC++ Redistributable (14.29.x) is installed.
     * Returns true only if version 14.29.x is installed.
     * Returns false if no runtime, version < 14.29, or version >= 14.30.
     */
    private fun isVCRedistCompatibleVersionInstalled(): Boolean {
        val version = getVCRedistVersion() ?: return false
        val majorMinor = version.removePrefix("v").split(".").take(2).joinToString(".")
        return majorMinor.startsWith(VC_RUNTIME_PREFERRED_VERSION)
    }

    /**
     * Installs Visual C++ Redistributable using the bundled installer from app resources.
     * The installer is included in the application distribution (no download needed).
     *
     * @param onProgress Callback for progress messages
     * @return The version installed, or null if installation failed
     */
    private suspend fun installVCRedist(onProgress: suspend (String) -> Unit = {}): String? {
        return try {
            onProgress("Locating bundled Visual C++ Redistributable installer...")

            // Locate the bundled installer from app resources
            val resourcesDir = System.getProperty("compose.application.resources.dir")
            if (resourcesDir == null) {
                logger.error { "Application resources directory not available (compose.application.resources.dir is not set)" }
                return@installVCRedist null
            }

            val installerFile = File(resourcesDir, "vc_redist.x64.exe")
            if (!installerFile.exists()) {
                logger.error { "Bundled VC++ Redistributable not found at ${installerFile.absolutePath}" }
                return@installVCRedist null
            }

            logger.info { "Found bundled VC++ Redistributable at ${installerFile.absolutePath} (${installerFile.length()} bytes)" }

            onProgress("Running Visual C++ Redistributable installer...")
            logger.info { "Running VC++ Redistributable installer from ${installerFile.absolutePath}" }

            // Run the installer with /passive flag (shows progress but minimal interaction)
            // /norestart prevents automatic restart
            val result = processExecutor.executeAndCapture(
                listOf(installerFile.absolutePath, "/install", "/passive", "/norestart"),
                ProcessConfig(timeoutMinutes = 10)
            )

            // Exit codes:
            // 0 = Success
            // 1638 = Another version is already installed (can happen with exact same version)
            // 3010 = Success, restart required
            // 5100 = System requirements not met
            when (result.exitCode) {
                0, 1638, 3010 -> {
                    val installedVersion = getVCRedistVersion()
                    if (installedVersion != null) {
                        onProgress("Visual C++ Redistributable $installedVersion installed successfully")
                        logger.info { "VC++ Redistributable installed: $installedVersion" }

                        if (result.exitCode == 3010) {
                            warnings.add("A system restart is recommended to complete VC++ Runtime installation")
                        }

                        installedVersion
                    } else {
                        onProgress("Installation completed but version could not be verified")
                        "v$VC_RUNTIME_PREFERRED_VERSION"
                    }
                }

                else -> {
                    logger.error { "VC++ Redistributable installer failed with exit code ${result.exitCode}" }
                    logger.error { "Stdout: ${result.stdout}" }
                    logger.error { "Stderr: ${result.stderr}" }
                    null
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to install VC++ Redistributable" }
            null
        }
    }

    // ==================== Python Installation Methods ====================

    /**
     * Checks if a compatible version of Python (3.8+) is installed.
     */
    private fun isPythonCompatibleVersionInstalled(): Boolean {
        val version = getPythonVersion() ?: return false
        val match = Regex("""(\d+)\.(\d+)""").find(version)
        if (match != null) {
            val major = match.groupValues[1].toIntOrNull() ?: 0
            val minor = match.groupValues[2].toIntOrNull() ?: 0
            return major == REQUIRED_PYTHON_MAJOR && minor >= REQUIRED_PYTHON_MINOR_MIN
        }
        return false
    }

    /**
     * Gets the installed Python version.
     */
    private fun getPythonVersion(): String? {
        return try {
            val commands = listOf(
                listOf("python", "--version"),
                listOf("python3", "--version"),
                listOf("py", "-3", "--version")
            )

            for (cmd in commands) {
                try {
                    val process = ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .start()
                    val output = process.inputStream.bufferedReader().readText().trim()
                    process.waitFor()

                    if (process.exitValue() == 0) {
                        // Parse "Python 3.11.9" or similar
                        val match = Regex("""Python (\d+\.\d+\.\d+)""").find(output)
                        if (match != null) {
                            return match.groupValues[1]
                        }
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            null
        } catch (e: Exception) {
            logger.debug { "Could not get Python version: ${e.message}" }
            null
        }
    }

    /**
     * Installs Python by downloading and running the installer.
     * The installer will prompt the user for consent.
     *
     * @param onProgress Callback for progress messages
     * @return The Python install path, or null if installation failed
     */
    private suspend fun installPython(onProgress: suspend (String) -> Unit = {}): String? {
        return try {
            onProgress("Downloading Python $PYTHON_VERSION...")

            // Download the installer
            val downloadUrl = getDownloadUrl(ComponentId.PYTHON)
            val downloadDir = File(platformPaths.cacheDir)
            downloadDir.mkdirs()
            val installerFile = File(downloadDir, "python-$PYTHON_VERSION-amd64.exe")

            // Download using HTTP client
            httpClient.prepareGet(downloadUrl).execute { response ->
                val channel = response.bodyAsChannel()
                java.io.FileOutputStream(installerFile).use { output ->
                    val buffer = ByteArray(8192)
                    while (!channel.isClosedForRead) {
                        val bytesRead = channel.readAvailable(buffer)
                        if (bytesRead > 0) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }

            if (!installerFile.exists() || installerFile.length() < 1000) {
                logger.error { "Python download failed or file too small" }
                return@installPython null
            }

            onProgress("Running Python installer...")
            logger.info { "Running Python installer from ${installerFile.absolutePath}" }

            // Run the installer with:
            // /passive - shows progress but minimal interaction
            // InstallAllUsers=0 - install for current user only (no admin required)
            // PrependPath=1 - add Python to PATH
            // Include_pip=1 - include pip
            // Include_test=0 - skip test suite to save space
            val result = processExecutor.executeAndCapture(
                listOf(
                    installerFile.absolutePath,
                    "/passive",
                    "InstallAllUsers=0",
                    "PrependPath=1",
                    "Include_pip=1",
                    "Include_test=0",
                    "Include_doc=0"
                ),
                ProcessConfig(timeoutMinutes = 15)
            )

            // Clean up installer
            installerFile.delete()

            // Exit codes:
            // 0 = Success
            // 1602 = User cancelled
            // 1603 = Fatal error during installation
            when (result.exitCode) {
                0 -> {
                    // Refresh PATH by getting it from registry
                    refreshPythonPath()

                    val installedVersion = getPythonVersion()
                    if (installedVersion != null) {
                        onProgress("Python $installedVersion installed successfully")
                        logger.info { "Python installed: $installedVersion" }

                        // Return the Python installation path
                        val pythonPath = System.getenv("LOCALAPPDATA")?.let {
                            "$it\\Programs\\Python\\Python${PYTHON_VERSION.replace(".", "").take(3)}"
                        } ?: "Python"
                        pythonPath
                    } else {
                        onProgress("Installation completed but version could not be verified")
                        "Python $PYTHON_VERSION"
                    }
                }

                1602 -> {
                    logger.info { "Python installation was cancelled by user" }
                    null
                }

                else -> {
                    logger.error { "Python installer failed with exit code ${result.exitCode}" }
                    logger.error { "Stdout: ${result.stdout}" }
                    logger.error { "Stderr: ${result.stderr}" }
                    null
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to install Python" }
            null
        }
    }

    /**
     * Refreshes the cached Python path after installation.
     * This is needed because the PATH environment variable is updated by the installer.
     */
    private fun refreshPythonPath() {
        detectedPythonPath = null
        // Try to find Python in the new location
        val commands = listOf("python", "python3", "py -3")
        for (cmd in commands) {
            try {
                val cmdParts = if (cmd.contains(" ")) cmd.split(" ") else listOf(cmd)
                val process = ProcessBuilder(cmdParts + listOf("--version"))
                    .redirectErrorStream(true)
                    .start()
                process.waitFor()
                if (process.exitValue() == 0) {
                    detectedPythonPath = cmdParts.first()
                    logger.info { "Refreshed Python path: $detectedPythonPath" }
                    break
                }
            } catch (e: Exception) {
                continue
            }
        }
    }

    /**
     * Uninstalls Python that was installed during this session.
     * Uses the Python uninstaller if available.
     */
    private suspend fun uninstallPython() {
        try {
            logger.info { "Attempting to uninstall Python..." }

            // Find the uninstaller
            val localAppData = System.getenv("LOCALAPPDATA") ?: return
            val pythonDir = File(localAppData, "Programs\\Python")

            // Look for the Python version we installed
            val versionShort = PYTHON_VERSION.replace(".", "").take(3) // e.g., "311"
            val pythonInstallDir = File(pythonDir, "Python$versionShort")

            if (!pythonInstallDir.exists()) {
                logger.info { "Python installation directory not found, skipping uninstall" }
                return
            }

            // Run the uninstaller
            val uninstaller = File(pythonInstallDir, "python.exe")
            if (uninstaller.exists()) {
                // Use pip to get the installer ID and uninstall
                val result = processExecutor.executeAndCapture(
                    listOf(
                        "cmd", "/c",
                        "wmic", "product", "where",
                        "name like 'Python $PYTHON_VERSION%'",
                        "call", "uninstall", "/nointeractive"
                    ),
                    ProcessConfig(timeoutMinutes = 5)
                )

                if (result.exitCode == 0) {
                    logger.info { "Python uninstalled successfully" }
                } else {
                    // Fallback: just delete the directory
                    logger.info { "WMIC uninstall failed, removing directory..." }
                    pythonInstallDir.deleteRecursively()
                }
            } else {
                // Just delete the directory
                pythonInstallDir.deleteRecursively()
            }

            detectedPythonPath = null
            logger.info { "Python rollback completed" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to uninstall Python" }
        }
    }

    // ==================== Python Check Methods ====================

    /**
     * Checks if Python 3.8+ is available (required for LibreTranslate).
     * If not found, Python will be installed automatically during setup.
     */
    private suspend fun checkPython(): PreInstallCheckResult {
        logger.info { "Checking Python availability (required version: $REQUIRED_PYTHON_MAJOR.$REQUIRED_PYTHON_MINOR_MIN+)..." }
        return try {
            // Try common Python commands
            val pythonCommands = listOf("python", "python3", "py -3")
            logger.info { "Searching for Python using commands: $pythonCommands" }

            for (cmd in pythonCommands) {
                val result = try {
                    val cmdParts = if (cmd.contains(" ")) cmd.split(" ") else listOf(cmd)
                    processExecutor.executeAndCapture(
                        cmdParts + listOf("--version"),
                        ProcessConfig(timeoutMinutes = 1)
                    )
                } catch (e: Exception) {
                    logger.debug { "Python not found with command '$cmd': ${e.message}" }
                    continue
                }

                if (result.exitCode == 0) {
                    val versionOutput = result.stdout.trim()
                    logger.info { "Found Python with command '$cmd': $versionOutput" }
                    // Parse "Python 3.11.9" or similar
                    val match = Regex("""Python (\d+)\.(\d+)\.(\d+)""").find(versionOutput)

                    if (match != null) {
                        val major = match.groupValues[1].toInt()
                        val minor = match.groupValues[2].toInt()

                        if (major == REQUIRED_PYTHON_MAJOR && minor >= REQUIRED_PYTHON_MINOR_MIN) {
                            // Cache the working Python path
                            detectedPythonPath = if (cmd.contains(" ")) {
                                cmd.split(" ").first()
                            } else {
                                cmd
                            }
                            logger.info { "Python $major.$minor is compatible - cached path: $detectedPythonPath" }

                            return PreInstallCheckResult.Passed(
                                checkName = "Python",
                                details = "Python $major.$minor detected (compatible with LibreTranslate)"
                            )
                        } else {
                            logger.info { "Python $major.$minor found but version is incompatible (need $REQUIRED_PYTHON_MAJOR.$REQUIRED_PYTHON_MINOR_MIN+)" }
                        }
                    }
                }
            }

            // Python not found - will be installed automatically
            logger.info { "No compatible Python found - Python $PYTHON_VERSION will be installed automatically" }
            PreInstallCheckResult.Passed(
                checkName = "Python",
                details = "Python not found. Python $PYTHON_VERSION will be installed automatically."
            )
        } catch (e: Exception) {
            logger.warn(e) { "Error checking Python availability" }
            // If we can't check, we'll install Python anyway
            PreInstallCheckResult.Passed(
                checkName = "Python",
                details = "Could not verify Python installation. Python $PYTHON_VERSION will be installed if needed."
            )
        }
    }

    private fun checkWindowsVersion(): PreInstallCheckResult {
        logger.info { "Checking Windows version..." }
        return try {
            val osVersion = System.getProperty("os.version") ?: "unknown"
            val osName = System.getProperty("os.name") ?: "unknown"
            val majorVersion = osVersion.split(".").firstOrNull()?.toIntOrNull() ?: 0
            logger.info { "Detected: $osName version $osVersion (major: $majorVersion)" }

            // Windows 10 is version 10.x, Windows 11 is also 10.x internally
            if (majorVersion >= 10) {
                logger.info { "Windows version $osVersion is supported" }
                PreInstallCheckResult.Passed(
                    checkName = "Windows Version",
                    details = "Windows version $osVersion is supported"
                )
            } else {
                logger.warn { "Windows version $osVersion may have compatibility issues (< Windows 10)" }
                PreInstallCheckResult.Warning(
                    checkName = "Windows Version",
                    message = "Windows version $osVersion may have compatibility issues",
                    suggestion = "Windows 10 or later is recommended"
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not determine Windows version" }
            PreInstallCheckResult.Warning(
                checkName = "Windows Version",
                message = "Could not determine Windows version",
                suggestion = null
            )
        }
    }

    // ==================== LibreTranslate Installation ====================

    /**
     * Checks if LibreTranslate is installed by looking for the virtual environment.
     */
    fun isLibreTranslateInstalled(): Boolean {
        val venvDir = File(platformPaths.libreTranslateDir, "venv")
        val libreTranslateExe = File(venvDir, "Scripts\\libretranslate.exe")
        return libreTranslateExe.exists()
    }

    /**
     * Gets the path to Python within the LibreTranslate virtual environment.
     */
    fun getLibreTranslatePythonPath(): String {
        val venvDir = File(platformPaths.libreTranslateDir, "venv")
        return "${venvDir.absolutePath}\\Scripts\\python.exe"
    }

    /**
     * Installs LibreTranslate in a virtual environment with all required dependencies.
     *
     * @param onProgress Callback for progress updates
     * @return The installation path if successful, null if failed
     */
    suspend fun installLibreTranslate(
        onProgress: suspend (String) -> Unit = {},
    ): String? {
        val pythonPath = detectedPythonPath ?: findSystemPython()
        if (pythonPath == null) {
            logger.error { "Python not found. Cannot install LibreTranslate." }
            return null
        }

        val libreTranslateDir = File(platformPaths.libreTranslateDir)
        libreTranslateDir.mkdirs()

        val venvDir = File(libreTranslateDir, "venv")

        try {
            // Step 1: Create virtual environment
            onProgress("Creating Python virtual environment...")
            logger.info { "Creating virtual environment at ${venvDir.absolutePath}" }

            val createVenvResult = processExecutor.executeAndCapture(
                listOf(pythonPath, "-m", "venv", venvDir.absolutePath),
                ProcessConfig(timeoutMinutes = 5, workingDir = libreTranslateDir.absolutePath)
            )

            if (createVenvResult.exitCode != 0) {
                logger.error { "Failed to create virtual environment: ${createVenvResult.stderr}" }
                return null
            }

            val venvPython = "${venvDir.absolutePath}\\Scripts\\python.exe"
            val venvPip = "${venvDir.absolutePath}\\Scripts\\pip.exe"

            // Step 2: Upgrade pip
            onProgress("Upgrading pip...")
            logger.info { "Upgrading pip in virtual environment" }

            processExecutor.executeAndCapture(
                listOf(venvPython, "-m", "pip", "install", "--upgrade", "pip"),
                ProcessConfig(timeoutMinutes = 5)
            )

            // Step 3: Install PyTorch (CPU version) - use conservative version 2.0.1
            onProgress("Installing PyTorch $PYTORCH_VERSION (this may take a while)...")
            logger.info { "Installing PyTorch $PYTORCH_VERSION+cpu" }

            val torchResult = processExecutor.executeAndCapture(
                listOf(
                    venvPip, "install",
                    "torch==$PYTORCH_VERSION",
                    "--index-url", PYTORCH_INDEX_URL,
                    "--no-cache-dir"
                ),
                ProcessConfig(timeoutMinutes = 30) // Large download
            )

            if (torchResult.exitCode != 0) {
                logger.error { "Failed to install PyTorch: ${torchResult.stderr}" }
                return null
            }

            // Step 4: Install LibreTranslate with --no-deps to avoid incompatible versions
            onProgress("Installing LibreTranslate $LIBRETRANSLATE_VERSION...")
            logger.info { "Installing LibreTranslate $LIBRETRANSLATE_VERSION (without dependencies)" }

            val ltResult = processExecutor.executeAndCapture(
                listOf(venvPip, "install", "libretranslate==$LIBRETRANSLATE_VERSION", "--no-deps"),
                ProcessConfig(timeoutMinutes = 15)
            )

            if (ltResult.exitCode != 0) {
                logger.error { "Failed to install LibreTranslate: ${ltResult.stderr}" }
                return null
            }

            // Step 5: Install LibreTranslate dependencies
            onProgress("Installing LibreTranslate dependencies...")
            logger.info { "Installing LibreTranslate dependencies" }

            val depsResult = processExecutor.executeAndCapture(
                listOf(venvPip, "install") + LIBRETRANSLATE_DEPENDENCIES,
                ProcessConfig(timeoutMinutes = 15)
            )

            if (depsResult.exitCode != 0) {
                logger.error { "Failed to install LibreTranslate dependencies: ${depsResult.stderr}" }
                return null
            }

            // Step 6: Install ctranslate2 (CRITICAL: must be version 4.0.0)
            onProgress("Installing ctranslate2 $CTRANSLATE2_VERSION (critical)...")
            logger.info { "Installing ctranslate2==$CTRANSLATE2_VERSION (required for stability)" }

            val ct2Result = processExecutor.executeAndCapture(
                listOf(venvPip, "install", "ctranslate2==$CTRANSLATE2_VERSION"),
                ProcessConfig(timeoutMinutes = 10)
            )

            if (ct2Result.exitCode != 0) {
                logger.error { "Failed to install ctranslate2: ${ct2Result.stderr}" }
                return null
            }

            val ltResult2 = processExecutor.executeAndCapture(
                listOf(venvPip, "install", "libretranslate==$LIBRETRANSLATE_VERSION"),
                ProcessConfig(timeoutMinutes = 15)
            )

            if (ltResult2.exitCode != 0) {
                logger.error { "Failed to finalize LibreTranslate: ${ltResult2.stderr}" }
                return null
            }

            // Step 7: Install language models via argospm
            onProgress("Installing language models...")
            logger.info { "Installing language models" }

            val argospmPath = "${venvDir.absolutePath}\\Scripts\\argospm.exe"

            // Update argospm package index
            processExecutor.executeAndCapture(
                listOf(argospmPath, "update"),
                ProcessConfig(timeoutMinutes = 5)
            )

            // Install each language model
            for (model in DEFAULT_LANGUAGE_MODELS) {
                onProgress("Installing language model: $model...")
                logger.info { "Installing language model: $model" }

                val modelResult = processExecutor.executeAndCapture(
                    listOf(argospmPath, "install", model),
                    ProcessConfig(timeoutMinutes = 10)
                )

                if (modelResult.exitCode != 0) {
                    logger.warn { "Failed to install model $model: ${modelResult.stderr}" }
                    // Continue with other models
                }
            }

            // Step 8: Verify installation
            onProgress("Verifying installation...")
            logger.info { "Verifying LibreTranslate installation" }

            val verifyResult = processExecutor.executeAndCapture(
                listOf(venvPython, "-c", "import libretranslate; print('OK')"),
                ProcessConfig(timeoutMinutes = 1)
            )

            if (verifyResult.exitCode != 0 || !verifyResult.stdout.contains("OK")) {
                logger.error { "LibreTranslate verification failed: ${verifyResult.stderr}" }
                return null
            }

            logger.info { "LibreTranslate installed successfully" }
            return "${venvDir.absolutePath}\\Scripts\\libretranslate.exe"

        } catch (e: Exception) {
            logger.error(e) { "Exception during LibreTranslate installation" }
            // Cleanup on failure
            if (venvDir.exists()) {
                try {
                    venvDir.deleteRecursively()
                } catch (cleanupError: Exception) {
                    logger.warn { "Failed to cleanup after installation error: ${cleanupError.message}" }
                }
            }
            return null
        }
    }

    /**
     * Uninstalls LibreTranslate by removing the virtual environment.
     */
    suspend fun uninstallLibreTranslate(): Boolean {
        val venvDir = File(platformPaths.libreTranslateDir, "venv")

        return try {
            if (venvDir.exists()) {
                venvDir.deleteRecursively()
                logger.info { "LibreTranslate uninstalled successfully" }
                true
            } else {
                logger.info { "LibreTranslate was not installed" }
                true
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to uninstall LibreTranslate" }
            false
        }
    }

    /**
     * Gets the installed LibreTranslate version.
     */
    suspend fun getLibreTranslateVersion(): String? {
        if (!isLibreTranslateInstalled()) return null

        return try {
            val venvPython = getLibreTranslatePythonPath()
            val result = processExecutor.executeAndCapture(
                listOf(
                    venvPython, "-c",
                    "import libretranslate; print(libretranslate.__version__)"
                ),
                ProcessConfig(timeoutMinutes = 1)
            )

            if (result.exitCode == 0) {
                result.stdout.trim()
            } else null
        } catch (e: Exception) {
            logger.debug { "Could not get LibreTranslate version: ${e.message}" }
            null
        }
    }

    /**
     * Gets the list of installed Argos Translate models.
     */
    suspend fun getInstalledLanguageModels(): List<String> {
        if (!isLibreTranslateInstalled()) return emptyList()

        return try {
            val argospmPath = "${platformPaths.libreTranslateDir}\\venv\\Scripts\\argospm.exe"
            val result = processExecutor.executeAndCapture(
                listOf(argospmPath, "list"),
                ProcessConfig(timeoutMinutes = 1)
            )

            if (result.exitCode == 0) {
                result.stdout.lines()
                    .map { it.trim() }
                    .filter { it.startsWith("translate-") }
            } else emptyList()
        } catch (e: Exception) {
            logger.debug { "Could not list installed language models: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Installs an additional language model.
     */
    suspend fun installLanguageModel(modelName: String): Boolean {
        if (!isLibreTranslateInstalled()) {
            logger.error { "LibreTranslate is not installed" }
            return false
        }

        return try {
            val argospmPath = "${platformPaths.libreTranslateDir}\\venv\\Scripts\\argospm.exe"

            val result = processExecutor.executeAndCapture(
                listOf(argospmPath, "install", modelName),
                ProcessConfig(timeoutMinutes = 10)
            )

            if (result.exitCode == 0) {
                logger.info { "Installed language model: $modelName" }
                true
            } else {
                logger.error { "Failed to install language model $modelName: ${result.stderr}" }
                false
            }
        } catch (e: Exception) {
            logger.error(e) { "Exception installing language model $modelName" }
            false
        }
    }

    /**
     * Finds the system Python executable (3.8 or higher).
     */
    private suspend fun findSystemPython(): String? {
        val candidates = listOf("python", "python3", "py")

        for (candidate in candidates) {
            try {
                val result = processExecutor.executeAndCapture(
                    listOf(candidate, "--version"),
                    ProcessConfig(timeoutMinutes = 1)
                )

                if (result.exitCode == 0) {
                    val versionOutput = result.stdout.trim()
                    val match = Regex("""Python (\d+)\.(\d+)""").find(versionOutput)

                    if (match != null) {
                        val major = match.groupValues[1].toInt()
                        val minor = match.groupValues[2].toInt()

                        // Accept Python 3.8 or higher (no max version)
                        if (major == REQUIRED_PYTHON_MAJOR && minor >= REQUIRED_PYTHON_MINOR_MIN) {
                            logger.info { "Found compatible Python at: $candidate ($versionOutput)" }
                            return candidate
                        }
                    }
                }
            } catch (e: Exception) {
                // Try next candidate
            }
        }

        logger.warn { "No compatible Python (3.8+) found" }
        return null
    }
}
