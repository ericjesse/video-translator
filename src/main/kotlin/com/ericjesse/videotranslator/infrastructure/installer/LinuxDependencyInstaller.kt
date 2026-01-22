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
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

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

    companion object {
        // Python version requirements for LibreTranslate
        const val REQUIRED_PYTHON_MAJOR = 3
        const val REQUIRED_PYTHON_MINOR_MIN = 8

        // Python standalone build version (from python-build-standalone project)
        // Using Python 3.11 as the installed version for best compatibility with LibreTranslate
        const val PYTHON_VERSION = "3.11.9"

        // python-build-standalone releases - these are self-contained Python builds that don't require sudo
        const val PYTHON_STANDALONE_VERSION = "20240415"
        private const val PYTHON_X86_64_URL =
            "https://github.com/indygreg/python-build-standalone/releases/download/$PYTHON_STANDALONE_VERSION/cpython-$PYTHON_VERSION+$PYTHON_STANDALONE_VERSION-x86_64-unknown-linux-gnu-install_only.tar.gz"
        private const val PYTHON_AARCH64_URL =
            "https://github.com/indygreg/python-build-standalone/releases/download/$PYTHON_STANDALONE_VERSION/cpython-$PYTHON_VERSION+$PYTHON_STANDALONE_VERSION-aarch64-unknown-linux-gnu-install_only.tar.gz"

        // LibreTranslate pip packages (same as Windows/macOS - conservative config)
        const val PYTORCH_VERSION = "2.0.1"
        const val LIBRETRANSLATE_VERSION = "1.6.0"
        const val CTRANSLATE2_VERSION = "4.0.0"

        // LibreTranslate language models to install by default
        val DEFAULT_LANGUAGE_MODELS = listOf(
            "translate-en_fr",
            "translate-fr_en",
            "translate-en_de",
            "translate-de_en",
            "translate-en_es",
            "translate-es_en"
        )

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
        ),
        ComponentDescription(
            id = ComponentId.PYTHON,
            name = "Python $PYTHON_VERSION",
            description = "Python runtime required for LibreTranslate (standalone build)",
            estimatedSizeMb = 100,
            warnings = listOf(
                "Self-contained Python installation - no sudo required",
                "Installed in user directory"
            ),
            isOptional = true // Only needed if LibreTranslate is selected
        ),
        ComponentDescription(
            id = ComponentId.LIBRE_TRANSLATE,
            name = "LibreTranslate",
            description = "Local machine translation service with PyTorch, ctranslate2, and language models",
            estimatedSizeMb = 2000,
            warnings = listOf(
                "Installation includes PyTorch (~175MB), ctranslate2, and all required dependencies",
                "Language models (~400MB) will be downloaded automatically",
                "Total disk space required: ~2GB"
            ),
            isOptional = true,
            dependencies = listOf(ComponentId.PYTHON)
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

            ComponentId.VC_REDIST ->
                throw UnsupportedOperationException("VC++ Redistributable is Windows-only")

            ComponentId.PYTHON ->
                if (isArm64) PYTHON_AARCH64_URL else PYTHON_X86_64_URL
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
            ComponentId.VC_REDIST -> throw UnsupportedOperationException("VC++ Redistributable is Windows-only")
            ComponentId.PYTHON -> "python"
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

        // Check for Python (required for LibreTranslate)
        results.add(checkPython())

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
        logger.info { "Checking GNU C Library (glibc) version..." }
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
            logger.info { "Detected glibc version: $version" }

            // Most binaries require glibc 2.17 or higher
            if (version >= 2.17) {
                logger.info { "glibc version $version is compatible (>= 2.17 required)" }
                PreInstallCheckResult.Passed(
                    checkName = "GNU C Library",
                    details = "glibc version ${versionMatch?.groupValues?.get(1)} is installed"
                )
            } else {
                logger.warn { "glibc version $version may be too old (>= 2.17 required)" }
                PreInstallCheckResult.Warning(
                    checkName = "GNU C Library",
                    message = "glibc version may be too old",
                    suggestion = "Update your system or use a newer Linux distribution"
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not check glibc version" }
            PreInstallCheckResult.Warning(
                checkName = "GNU C Library",
                message = "Could not check glibc version: ${e.message}",
                suggestion = null
            )
        }
    }

    private fun checkCuda(): PreInstallCheckResult {
        logger.info { "Checking for NVIDIA GPU and CUDA support..." }
        return try {
            // Check if nvidia-smi is available (indicates CUDA support)
            val result = ProcessBuilder("nvidia-smi", "--query-gpu=driver_version", "--format=csv,noheader")
                .redirectErrorStream(true)
                .start()

            val output = result.inputStream.bufferedReader().readText().trim()
            val exitCode = result.waitFor()

            if (exitCode == 0 && output.isNotEmpty()) {
                logger.info { "NVIDIA GPU detected with driver version: $output" }
                PreInstallCheckResult.Passed(
                    checkName = "NVIDIA GPU",
                    details = "NVIDIA driver version $output detected - GPU acceleration available"
                )
            } else {
                logger.info { "No NVIDIA GPU detected (nvidia-smi returned exit code $exitCode)" }
                PreInstallCheckResult.Warning(
                    checkName = "NVIDIA GPU",
                    message = "No NVIDIA GPU detected",
                    suggestion = "GPU acceleration won't be available. Transcription will use CPU only."
                )
            }
        } catch (e: Exception) {
            logger.info { "NVIDIA GPU not detected (nvidia-smi not available): ${e.message}" }
            PreInstallCheckResult.Warning(
                checkName = "NVIDIA GPU",
                message = "NVIDIA GPU not detected",
                suggestion = "GPU acceleration won't be available. Transcription will use CPU only."
            )
        }
    }

    // ==================== Install Override for LibreTranslate ====================

    /**
     * Override install to handle LibreTranslate specially since it uses pip.
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

                    if (component.id == ComponentId.PYTHON) {
                        // Special handling for Python - download standalone build
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
                            logger.info { "LibreTranslate: $progressMessage" }
                        }

                        if (result == null) {
                            throw RuntimeException("Failed to install LibreTranslate")
                        }

                        installPath = result
                    } else {
                        // Standard download and install flow
                        val downloadedFile = downloadComponent(component) { _ -> }

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

                        installPath = installComponent(component.id, downloadedFile)

                        if (cancelled) {
                            throw CancellationException("Installation cancelled by user")
                        }

                        postInstallSetup(component.id, installPath)
                        downloadedFile.delete()
                    }

                    // Get version
                    val version = when (component.id) {
                        ComponentId.LIBRE_TRANSLATE -> getLibreTranslateVersion()
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

                } catch (e: CancellationException) {
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

        } catch (e: CancellationException) {
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

        installationJob?.cancelAndJoin()
        rollbackWithLibreTranslate()

        _state = InstallerState.CANCELLED
    }

    /**
     * Rollback that handles LibreTranslate and Python cleanup.
     */
    private suspend fun rollbackWithLibreTranslate() {
        val preExisting = preInstallState?.existingComponents ?: emptySet()

        installedDuringSession.forEach { installed ->
            if (installed.id !in preExisting) {
                try {
                    when (installed.id) {
                        ComponentId.LIBRE_TRANSLATE -> {
                            uninstallLibreTranslate()
                        }

                        ComponentId.PYTHON -> {
                            uninstallPython()
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

    // ==================== Python and LibreTranslate Methods ====================

    /**
     * Checks if Python 3.8+ is available.
     * If not found, Python will be installed automatically during setup.
     */
    private suspend fun checkPython(): PreInstallCheckResult {
        logger.info { "Checking Python availability (required version: $REQUIRED_PYTHON_MAJOR.$REQUIRED_PYTHON_MINOR_MIN+)..." }
        return try {
            // First check our standalone Python installation
            val standalonePython = getStandalonePythonPath()
            logger.info { "Checking for standalone Python at: $standalonePython" }
            if (File(standalonePython).exists()) {
                detectedPythonPath = standalonePython
                logger.info { "Standalone Python $PYTHON_VERSION found" }
                return PreInstallCheckResult.Passed(
                    checkName = "Python",
                    details = "Standalone Python $PYTHON_VERSION detected"
                )
            }

            val pythonCommands = listOf("python3", "python", "/usr/bin/python3", "/usr/local/bin/python3")
            logger.info { "Searching for system Python in paths: $pythonCommands" }

            for (cmd in pythonCommands) {
                val result = try {
                    processExecutor.executeAndCapture(
                        listOf(cmd, "--version"),
                        ProcessConfig(timeoutMinutes = 1)
                    )
                } catch (e: Exception) {
                    logger.debug { "Python not found at $cmd: ${e.message}" }
                    continue
                }

                if (result.exitCode == 0) {
                    val versionOutput = result.stdout.trim()
                    logger.info { "Found Python at $cmd: $versionOutput" }
                    val match = Regex("""Python (\d+)\.(\d+)\.(\d+)""").find(versionOutput)

                    if (match != null) {
                        val major = match.groupValues[1].toInt()
                        val minor = match.groupValues[2].toInt()

                        if (major == REQUIRED_PYTHON_MAJOR && minor >= REQUIRED_PYTHON_MINOR_MIN) {
                            detectedPythonPath = cmd
                            logger.info { "Python $major.$minor is compatible - using $cmd" }
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

    // ==================== Python Installation Methods ====================

    /**
     * Gets the path to our standalone Python installation.
     */
    private fun getStandalonePythonPath(): String {
        return "${platformPaths.dataDir}/python/bin/python3"
    }

    /**
     * Checks if a compatible version of Python (3.8+) is installed.
     */
    private fun isPythonCompatibleVersionInstalled(): Boolean {
        // First check our standalone Python
        val standalonePython = getStandalonePythonPath()
        if (File(standalonePython).exists()) {
            return true
        }

        // Then check system Python
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
            // First try our standalone Python
            val standalonePython = getStandalonePythonPath()
            val commands = mutableListOf<List<String>>()
            if (File(standalonePython).exists()) {
                commands.add(listOf(standalonePython, "--version"))
            }
            commands.addAll(
                listOf(
                    listOf("python3", "--version"),
                    listOf("python", "--version"),
                    listOf("/usr/bin/python3", "--version"),
                    listOf("/usr/local/bin/python3", "--version")
                )
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
     * Installs Python by downloading and extracting the standalone build.
     * This is a self-contained Python installation that doesn't require sudo.
     *
     * @param onProgress Callback for progress messages
     * @return The Python install path, or null if installation failed
     */
    private suspend fun installPython(onProgress: suspend (String) -> Unit = {}): String? {
        return try {
            onProgress("Downloading Python $PYTHON_VERSION standalone build...")

            // Download the standalone Python build
            val component = getComponentDescriptions().find { it.id == ComponentId.PYTHON }!!
            val downloadedFile = downloadComponent(component) { _ -> }

            if (!downloadedFile.exists() || downloadedFile.length() < 1000) {
                logger.error { "Python download failed or file too small" }
                return@installPython null
            }

            onProgress("Extracting Python...")
            logger.info { "Extracting Python from ${downloadedFile.absolutePath}" }

            // Create the python directory
            val pythonDir = File(platformPaths.dataDir, "python")
            pythonDir.mkdirs()

            // Extract the archive
            archiveExtractor.extractBlocking(
                archivePath = downloadedFile.toPath(),
                destinationDir = pythonDir.toPath()
            )

            // The standalone build extracts to a "python" subdirectory
            // We need to move contents up one level
            val extractedDir = File(pythonDir, "python")
            if (extractedDir.exists() && extractedDir.isDirectory) {
                extractedDir.listFiles()?.forEach { file ->
                    val target = File(pythonDir, file.name)
                    if (!target.exists()) {
                        file.copyRecursively(target, overwrite = true)
                    }
                }
                extractedDir.deleteRecursively()
            }

            // Clean up downloaded file
            downloadedFile.delete()

            // Make Python executable
            val pythonBin = File(pythonDir, "bin/python3")
            if (pythonBin.exists()) {
                pythonBin.setExecutable(true, false)
                // Also make pip executable
                File(pythonDir, "bin/pip3").setExecutable(true, false)
                File(pythonDir, "bin/pip").setExecutable(true, false)
            }

            // Refresh the cached path
            detectedPythonPath = pythonBin.absolutePath

            val installedVersion = getPythonVersion()
            if (installedVersion != null) {
                onProgress("Python $installedVersion installed successfully")
                logger.info { "Python installed: $installedVersion" }
                pythonDir.absolutePath
            } else {
                onProgress("Installation completed but version could not be verified")
                pythonDir.absolutePath
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to install Python" }
            null
        }
    }

    /**
     * Uninstalls Python by removing the standalone installation directory.
     */
    private fun uninstallPython() {
        try {
            logger.info { "Uninstalling Python standalone build..." }

            val pythonDir = File(platformPaths.dataDir, "python")
            if (pythonDir.exists()) {
                pythonDir.deleteRecursively()
                logger.info { "Python uninstalled successfully" }
            } else {
                logger.info { "Python installation directory not found, skipping uninstall" }
            }

            detectedPythonPath = null
            logger.info { "Python rollback completed" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to uninstall Python" }
        }
    }

    /**
     * Finds the system Python executable (3.8 or higher).
     */
    private suspend fun findSystemPython(): String? {
        // First check our standalone Python
        val standalonePython = getStandalonePythonPath()
        if (File(standalonePython).exists()) {
            return standalonePython
        }

        val candidates = listOf("python3", "python", "/usr/bin/python3", "/usr/local/bin/python3")

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

    /**
     * Checks if LibreTranslate is installed in the virtual environment.
     */
    private fun isLibreTranslateInstalled(): Boolean {
        val venvDir = File(platformPaths.libreTranslateDir, "venv")
        val libretranslatePath = File(venvDir, "bin/libretranslate")
        return libretranslatePath.exists()
    }

    /**
     * Gets the LibreTranslate version.
     */
    private fun getLibreTranslateVersion(): String? {
        return try {
            val venvPython = "${platformPaths.libreTranslateDir}/venv/bin/python"
            val process = ProcessBuilder(venvPython, "-c", "import libretranslate; print(libretranslate.__version__)")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (process.exitValue() == 0 && output.isNotEmpty()) output else LIBRETRANSLATE_VERSION
        } catch (e: Exception) {
            LIBRETRANSLATE_VERSION
        }
    }

    /**
     * Installs LibreTranslate in a virtual environment with all required dependencies.
     */
    private suspend fun installLibreTranslate(onProgress: suspend (String) -> Unit = {}): String? {
        val pythonPath = detectedPythonPath ?: findSystemPython()
        if (pythonPath == null) {
            logger.error { "Python not found. Cannot install LibreTranslate." }
            logger.error { "Install Python via: sudo apt install python3 python3-venv" }
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

            val venvPython = "${venvDir.absolutePath}/bin/python"
            val venvPip = "${venvDir.absolutePath}/bin/pip"

            // Step 2: Upgrade pip
            onProgress("Upgrading pip...")
            processExecutor.executeAndCapture(
                listOf(venvPython, "-m", "pip", "install", "--upgrade", "pip"),
                ProcessConfig(timeoutMinutes = 5)
            )

            // Step 3: Install PyTorch (CPU version)
            onProgress("Installing PyTorch $PYTORCH_VERSION (this may take a while)...")
            logger.info { "Installing PyTorch $PYTORCH_VERSION" }

            val torchResult = processExecutor.executeAndCapture(
                listOf(venvPip, "install", "torch==$PYTORCH_VERSION", "--no-cache-dir"),
                ProcessConfig(timeoutMinutes = 30)
            )

            if (torchResult.exitCode != 0) {
                logger.error { "Failed to install PyTorch: ${torchResult.stderr}" }
                return null
            }

            // Step 4: Install LibreTranslate with --no-deps
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

            // Step 5: Install dependencies
            onProgress("Installing LibreTranslate dependencies...")
            val depsResult = processExecutor.executeAndCapture(
                listOf(venvPip, "install") + LIBRETRANSLATE_DEPENDENCIES,
                ProcessConfig(timeoutMinutes = 15)
            )

            if (depsResult.exitCode != 0) {
                logger.error { "Failed to install LibreTranslate dependencies: ${depsResult.stderr}" }
                return null
            }

            // Step 6: Install ctranslate2 (CRITICAL)
            onProgress("Installing ctranslate2 $CTRANSLATE2_VERSION...")
            val ct2Result = processExecutor.executeAndCapture(
                listOf(venvPip, "install", "ctranslate2==$CTRANSLATE2_VERSION"),
                ProcessConfig(timeoutMinutes = 10)
            )

            if (ct2Result.exitCode != 0) {
                logger.error { "Failed to install ctranslate2: ${ct2Result.stderr}" }
                return null
            }

            // Re-install LibreTranslate to finalize
            processExecutor.executeAndCapture(
                listOf(venvPip, "install", "libretranslate==$LIBRETRANSLATE_VERSION"),
                ProcessConfig(timeoutMinutes = 15)
            )

            // Step 7: Install language models
            onProgress("Installing language models...")
            val argospmPath = "${venvDir.absolutePath}/bin/argospm"

            processExecutor.executeAndCapture(
                listOf(argospmPath, "update"),
                ProcessConfig(timeoutMinutes = 5)
            )

            for (model in DEFAULT_LANGUAGE_MODELS) {
                onProgress("Installing language model: $model...")
                logger.info { "Installing language model: $model" }

                val modelResult = processExecutor.executeAndCapture(
                    listOf(argospmPath, "install", model),
                    ProcessConfig(timeoutMinutes = 10)
                )

                if (modelResult.exitCode != 0) {
                    logger.warn { "Failed to install model $model: ${modelResult.stderr}" }
                }
            }

            // Step 8: Verify installation
            onProgress("Verifying installation...")
            val verifyResult = processExecutor.executeAndCapture(
                listOf(venvPython, "-c", "import libretranslate; print('OK')"),
                ProcessConfig(timeoutMinutes = 1)
            )

            if (verifyResult.exitCode != 0 || !verifyResult.stdout.contains("OK")) {
                logger.error { "LibreTranslate verification failed: ${verifyResult.stderr}" }
                return null
            }

            logger.info { "LibreTranslate installed successfully" }
            return "${venvDir.absolutePath}/bin/libretranslate"

        } catch (e: Exception) {
            logger.error(e) { "Exception during LibreTranslate installation" }
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
    private fun uninstallLibreTranslate() {
        try {
            val venvDir = File(platformPaths.libreTranslateDir, "venv")
            if (venvDir.exists()) {
                venvDir.deleteRecursively()
                logger.info { "Removed LibreTranslate virtual environment" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to uninstall LibreTranslate" }
        }
    }
}
