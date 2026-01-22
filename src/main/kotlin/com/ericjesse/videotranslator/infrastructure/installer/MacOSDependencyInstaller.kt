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
 * macOS-specific dependency installer.
 * Downloads and installs pre-built binaries for macOS (both Intel and Apple Silicon).
 */
class MacOSDependencyInstaller(
    platformPaths: PlatformPaths,
    httpClient: HttpClient,
    processExecutor: ProcessExecutor,
    archiveExtractor: ArchiveExtractor,
) : AbstractDependencyInstaller(platformPaths, httpClient, processExecutor, archiveExtractor) {

    companion object {
        // Python version requirements for LibreTranslate
        const val REQUIRED_PYTHON_MAJOR = 3
        const val REQUIRED_PYTHON_MINOR_MIN = 8

        // Python installer URL (latest stable release)
        // Using Python 3.11 as the installed version for best compatibility with LibreTranslate
        const val PYTHON_VERSION = "3.11.9"

        // macOS universal installer (works on both Intel and Apple Silicon)
        const val PYTHON_DOWNLOAD_URL =
            "https://www.python.org/ftp/python/$PYTHON_VERSION/python-$PYTHON_VERSION-macos11.pkg"

        // LibreTranslate pip packages (same as Windows - conservative config)
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

            ComponentId.VC_REDIST ->
                throw UnsupportedOperationException("VC++ Redistributable is Windows-only")

            ComponentId.PYTHON ->
                PYTHON_DOWNLOAD_URL
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
            ComponentId.PYTHON -> "python-$PYTHON_VERSION-macos11.pkg"
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

        // Check for Python (required for LibreTranslate)
        results.add(checkPython())

        return results
    }

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
                        // Special handling for Python - runs installer
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
        logger.info { "Checking Rosetta 2 availability on Apple Silicon Mac..." }
        return try {
            val result = ProcessBuilder("arch", "-x86_64", "uname", "-m")
                .redirectErrorStream(true)
                .start()
                .waitFor()

            if (result == 0) {
                logger.info { "Rosetta 2 check: x86_64 emulation available" }
                PreInstallCheckResult.Passed(
                    checkName = "Rosetta 2",
                    details = "Rosetta 2 is available for x86_64 compatibility"
                )
            } else {
                logger.warn { "Rosetta 2 check: x86_64 emulation test returned exit code $result" }
                PreInstallCheckResult.Warning(
                    checkName = "Rosetta 2",
                    message = "Rosetta 2 may not be installed",
                    suggestion = "Some components may require Rosetta 2. Install it via: softwareupdate --install-rosetta"
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Rosetta 2 check failed with exception" }
            PreInstallCheckResult.Warning(
                checkName = "Rosetta 2",
                message = "Could not check Rosetta 2 status",
                suggestion = "Some x86_64 binaries may not run without Rosetta 2"
            )
        }
    }

    private fun checkXcodeCommandLineTools(): PreInstallCheckResult {
        logger.info { "Checking Xcode Command Line Tools..." }
        return try {
            val result = ProcessBuilder("xcode-select", "-p")
                .redirectErrorStream(true)
                .start()
                .waitFor()

            if (result == 0) {
                logger.info { "Xcode Command Line Tools are installed" }
                PreInstallCheckResult.Passed(
                    checkName = "Xcode Command Line Tools",
                    details = "Xcode Command Line Tools are installed"
                )
            } else {
                logger.warn { "Xcode Command Line Tools check returned exit code $result" }
                PreInstallCheckResult.Warning(
                    checkName = "Xcode Command Line Tools",
                    message = "Xcode Command Line Tools may not be installed",
                    suggestion = "Install via: xcode-select --install"
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not check Xcode Command Line Tools status" }
            PreInstallCheckResult.Warning(
                checkName = "Xcode Command Line Tools",
                message = "Could not check Xcode Command Line Tools status",
                suggestion = null
            )
        }
    }

    // ==================== Python and LibreTranslate Methods ====================

    /**
     * Checks if Python 3.8+ is available.
     * If not found, Python will be installed automatically during setup.
     */
    private suspend fun checkPython(): PreInstallCheckResult {
        logger.info { "Checking Python availability (required version: $REQUIRED_PYTHON_MAJOR.$REQUIRED_PYTHON_MINOR_MIN+)..." }
        return try {
            val pythonCommands = listOf("python3", "python", "/usr/local/bin/python3", "/opt/homebrew/bin/python3")
            logger.info { "Searching for Python in paths: $pythonCommands" }

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
                listOf("python3", "--version"),
                listOf("python", "--version"),
                listOf("/usr/local/bin/python3", "--version"),
                listOf("/opt/homebrew/bin/python3", "--version"),
                listOf("/Library/Frameworks/Python.framework/Versions/3.11/bin/python3", "--version")
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
     * Installs Python by downloading and running the PKG installer.
     * Requires administrator privileges (will prompt for password via osascript).
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
            val installerFile = File(downloadDir, "python-$PYTHON_VERSION-macos11.pkg")

            // Download using HTTP client
            val component = getComponentDescriptions().find { it.id == ComponentId.PYTHON }!!
            val downloadedFile = downloadComponent(component) { _ -> }
            downloadedFile.copyTo(installerFile, overwrite = true)
            downloadedFile.delete()

            if (!installerFile.exists() || installerFile.length() < 1000) {
                logger.error { "Python download failed or file too small" }
                return@installPython null
            }

            onProgress("Running Python installer (may require password)...")
            logger.info { "Running Python installer from ${installerFile.absolutePath}" }

            // Run the PKG installer using installer command with admin privileges
            // Using osascript to prompt for admin password
            val installScript = """
                do shell script "installer -pkg '${installerFile.absolutePath}' -target /" with administrator privileges
            """.trimIndent()

            val result = processExecutor.executeAndCapture(
                listOf("osascript", "-e", installScript),
                ProcessConfig(timeoutMinutes = 10)
            )

            // Clean up installer
            installerFile.delete()

            if (result.exitCode == 0) {
                // Refresh PATH by checking for Python
                refreshPythonPath()

                val installedVersion = getPythonVersion()
                if (installedVersion != null) {
                    onProgress("Python $installedVersion installed successfully")
                    logger.info { "Python installed: $installedVersion" }

                    // Return the Python installation path
                    "/Library/Frameworks/Python.framework/Versions/3.11"
                } else {
                    onProgress("Installation completed but version could not be verified")
                    "Python $PYTHON_VERSION"
                }
            } else {
                // Check if user cancelled
                if (result.stderr.contains("canceled") || result.stderr.contains("cancelled")) {
                    logger.info { "Python installation was cancelled by user" }
                } else {
                    logger.error { "Python installer failed with exit code ${result.exitCode}" }
                    logger.error { "Stdout: ${result.stdout}" }
                    logger.error { "Stderr: ${result.stderr}" }
                }
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to install Python" }
            null
        }
    }

    /**
     * Refreshes the cached Python path after installation.
     */
    private fun refreshPythonPath() {
        detectedPythonPath = null
        // Try to find Python in the new location
        val candidates = listOf(
            "/Library/Frameworks/Python.framework/Versions/3.11/bin/python3",
            "/usr/local/bin/python3",
            "/opt/homebrew/bin/python3",
            "python3"
        )
        for (candidate in candidates) {
            try {
                val process = ProcessBuilder(listOf(candidate, "--version"))
                    .redirectErrorStream(true)
                    .start()
                process.waitFor()
                if (process.exitValue() == 0) {
                    detectedPythonPath = candidate
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
     * Note: This is a best-effort cleanup; Python installed via PKG is difficult to fully remove.
     */
    private suspend fun uninstallPython() {
        try {
            logger.info { "Attempting to uninstall Python..." }

            // Python installed via PKG goes to /Library/Frameworks/Python.framework
            val pythonFramework = File("/Library/Frameworks/Python.framework/Versions/3.11")

            if (pythonFramework.exists()) {
                // Need admin privileges to remove
                val uninstallScript = """
                    do shell script "rm -rf '/Library/Frameworks/Python.framework/Versions/3.11'" with administrator privileges
                """.trimIndent()

                val result = processExecutor.executeAndCapture(
                    listOf("osascript", "-e", uninstallScript),
                    ProcessConfig(timeoutMinutes = 5)
                )

                if (result.exitCode == 0) {
                    logger.info { "Python uninstalled successfully" }
                } else {
                    logger.warn { "Could not fully uninstall Python: ${result.stderr}" }
                }
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
        val candidates = listOf(
            "/Library/Frameworks/Python.framework/Versions/3.11/bin/python3",
            "python3",
            "python",
            "/usr/local/bin/python3",
            "/opt/homebrew/bin/python3"
        )

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
            logger.error { "Install Python via: brew install python@3.11" }
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
