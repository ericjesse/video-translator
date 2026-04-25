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
import kotlinx.coroutines.delay
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

        // Version of VC++ Redistributable that ships bundled with the app.
        // Only used when no 14.x (or newer) runtime is already present on the machine.
        // Microsoft guarantees ABI compatibility within the 14.x family and any newer
        // major family is a superset, so an already-installed 14.x+ runtime is accepted
        // as-is — we never downgrade the user's system VC++.
        const val VC_RUNTIME_BUNDLED_VERSION = "14.29"

        // Minimum major version of the VC++ redistributable we require.
        // Anything >= 14 satisfies the ABI our bundled components link against.
        const val VC_RUNTIME_MIN_MAJOR = 14
        const val VC_RUNTIME_MAX_MAJOR_PROBE = 20

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
            "numpy<2",
            "packaging==23.1"
        )
    }

    // Cached Python path after detection
    private var detectedPythonPath: String? = null

    /**
     * Override install to handle LibreTranslate specially since it uses pip instead of direct download.
     */
    override fun install(
        whisperModelId: String,
        includeLibreTranslate: Boolean,
    ): Flow<InstallationProgress> = flow {
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
        val components = resolveComponentsToInstall(whisperModelId, includeLibreTranslate)

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
                     ComponentId.WHISPER_CPP -> isValidWhisperBinary(getInstallPath(component.id))
                    else -> isComponentInstalled(component.id)
                }

                if (alreadyInstalled) {
                    logger.info { "Component ${component.name} already installed, skipping" }
                    // Surface the skip to the UI so the row flips from PENDING -> COMPLETE
                    // and the overall progress bar credits the weight of this component.
                    emit(
                        InstallationProgress.ComponentCompleted(
                            componentId = component.id,
                            componentName = component.name,
                            installPath = getInstallPath(component.id),
                            version = null,
                        )
                    )
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
                        val downloadedFile = downloadComponent(component) { pct, dl, total ->
                            emit(
                                InstallationProgress.Downloading(
                                    componentId = component.id,
                                    componentName = component.name,
                                    downloadedBytes = dl,
                                    totalBytes = total,
                                    percentage = pct,
                                )
                            )
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
            description = "Microsoft Visual C++ Redistributable (14.x or newer)",
            estimatedSizeMb = 25,
            warnings = listOf(
                "Required for whisper.cpp and other native components",
                "A bundled v$VC_RUNTIME_BUNDLED_VERSION will be installed only if no 14.x+ runtime is already present"
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

    private fun moveFFmpegBinaries(installPath: String) {
        // `installPath` is the *target* path for the flagship binary (e.g.
        // `bin\ffmpeg.exe`), not the extraction directory. Resolve its parent
        // folder — that's where the archive was unpacked into.
        val binDir = File(installPath).parentFile?.takeIf { it.isDirectory }
            ?: run {
                logger.warn { "moveFFmpegBinaries: cannot resolve parent directory for $installPath" }
                return
            }

        // gyan.dev zip extracts to a folder like "ffmpeg-7.0-essentials_build/bin/*.exe".
        val ffmpegFolder = binDir.listFiles()?.find {
            it.isDirectory && it.name.startsWith("ffmpeg-")
        }

        if (ffmpegFolder != null) {
            val ffmpegBinDir = File(ffmpegFolder, "bin").takeIf { it.isDirectory } ?: ffmpegFolder
            // Move every .exe (ffmpeg.exe, ffprobe.exe, ffplay.exe) up into `bin/`.
            ffmpegBinDir.listFiles()?.filter { it.extension.equals("exe", ignoreCase = true) }?.forEach { exe ->
                val target = File(binDir, exe.name)
                if (!target.exists()) {
                    exe.copyTo(target)
                    logger.info { "Moved ${exe.name} -> ${target.absolutePath}" }
                }
            }
            ffmpegFolder.deleteRecursively()
            logger.debug { "Cleaned up extracted folder: ${ffmpegFolder.absolutePath}" }
        } else {
            // Flat layout: .exe files may already be directly in binDir. Nothing to move.
            logger.debug { "No 'ffmpeg-*' subfolder found under ${binDir.absolutePath}; assuming flat extraction" }
        }

        // Sanity check — log if the expected target is still missing.
        if (!File(installPath).exists()) {
            logger.warn {
                "moveFFmpegBinaries: expected binary at $installPath still missing after extraction. " +
                    "bin/ contents: ${binDir.listFiles()?.joinToString { it.name }}"
            }
        }
    }

     /**
     * Wipes an existing LibreTranslate venv directory so the installer can create a fresh one.
     * If python.exe is locked by a running LibreTranslate service, `python -m venv` would fail
     * with "Permission denied". We stop any python.exe running out of the venv, then delete.
     */
    private suspend fun prepareVenvDirectory(venvDir: File, onProgress: suspend (String) -> Unit) {
        if (!venvDir.exists()) return

        logger.info { "Existing venv found at ${venvDir.absolutePath}; wiping for fresh install" }
        onProgress("Removing previous LibreTranslate environment...")

        if (tryDeleteRecursively(venvDir)) return

        logger.warn { "Initial venv delete failed; stopping locking python.exe processes" }
        stopPythonProcessesInVenv(venvDir)
        delay(500) // Give Windows a moment to release file handles

        if (tryDeleteRecursively(venvDir)) return

        logger.error {
            "Unable to delete existing venv at ${venvDir.absolutePath}. A process is still " +
                "holding files open. Please close the application and re-run setup."
        }
        throw RuntimeException(
            "Cannot recreate LibreTranslate environment at ${venvDir.absolutePath}: " +
                "directory is locked by another process. Please close the application and try again."
        )
    }

    private fun tryDeleteRecursively(dir: File): Boolean = try {
        dir.deleteRecursively() && !dir.exists()
    } catch (e: Exception) {
        logger.debug(e) { "deleteRecursively failed for ${dir.absolutePath}" }
        false
    }

    /**
     * Stops any python.exe processes whose ExecutablePath is under [venvDir].
     * Uses PowerShell (always available on Windows 10+) to avoid killing unrelated python.exe
     * processes elsewhere on the system.
     */
    private suspend fun stopPythonProcessesInVenv(venvDir: File) {
        // PowerShell single-quoted strings take literal backslashes — no escaping needed.
        // Escape any single-quotes in the path (very rare) to avoid breaking the literal.
        val venvPathLiteral = venvDir.absolutePath.replace("'", "''")
        // Kotlin ${'$'} emits a literal $ so PowerShell sees $_ (pipeline current object).
        val d = "${'$'}"
        // Avoid double-quotes in the script — Java's Windows ProcessBuilder silently drops
        // them from CreateProcess command-lines, breaking Get-CimInstance's -Filter. Using
        // Get-Process + Where-Object on .Path keeps the whole script single-quote-only.
        val psScript =
            "Get-Process -Name python -ErrorAction SilentlyContinue | " +
                "Where-Object { ${d}_.Path -like '$venvPathLiteral\\*' } | " +
                "ForEach-Object { " +
                "Write-Output ('Stopping PID ' + ${d}_.Id); " +
                "Stop-Process -Id ${d}_.Id -Force -ErrorAction SilentlyContinue " +
                "}"
        // Pass as -EncodedCommand (UTF-16LE base64) to eliminate any shell-quoting ambiguity.
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(psScript.toByteArray(Charsets.UTF_16LE))
        try {
            val result = processExecutor.executeAndCapture(
                listOf(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-EncodedCommand", encoded
                ),
                ProcessConfig(timeoutMinutes = 1)
            )
            logger.info {
                "venv process cleanup: exit=${result.exitCode}, " +
                    "out=${result.stdout.trim()}, err=${result.stderr.trim()}"
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to stop python.exe processes in venv" }
        }
    }

    /**
     * Detects a valid whisper.cpp binary at the given path. Modern whisper.cpp releases ship
     * `whisper.exe` as a deprecation stub (~100 KB) that prints a warning and exits 1.
     * The real binary (whisper-cli.exe renamed to whisper.exe during install) is >10 MB.
     * We use a size threshold to reject the stub and force a reinstall that picks whisper-cli.exe.
     */
    private fun isValidWhisperBinary(path: String): Boolean {
        val f = File(path)
        if (!f.exists()) return false
        val size = f.length()
        if (size < 1_000_000) {
            logger.warn {
                "whisper binary at $path is only $size bytes — likely a deprecation stub. Will reinstall."
            }
            return false
        }
        return true
    }

    private fun moveWhisperBinaries(installPath: String) {
        val binDir = File(installPath).parentFile?.takeIf { it.isDirectory }
            ?: run {
                logger.warn { "moveWhisperBinaries: cannot resolve parent directory for $installPath" }
                return
            }
        val target = File(installPath) // bin\whisper.exe

        // Recognised whisper.cpp binary names, in priority order:
        //  - whisper-cli.exe (v1.7+, current real binary)
        //  - main.exe (classic)
        //  - whisper.exe (older builds) — modern releases ship this as a deprecation stub
        //    that prints a warning and exits 1, so it must be the LAST fallback.
        val priorityOrder = listOf("whisper-cli.exe", "main.exe", "whisper.exe")

        val foundByName = binDir.walkTopDown()
            .filter { it.isFile && it.name.lowercase() in priorityOrder }
            .groupBy { it.name.lowercase() }

        val whisperExe = priorityOrder.firstNotNullOfOrNull { foundByName[it]?.firstOrNull() }

        if (whisperExe == null) {
            logger.warn {
                "moveWhisperBinaries: no whisper binary found under ${binDir.absolutePath}. " +
                    "bin/ contents: ${binDir.listFiles()?.joinToString { it.name }}"
            }
            return
        }

        // Copy the discovered binary to bin\whisper.exe (same-dir case → rename via copy).
        if (!target.exists() || whisperExe.absolutePath != target.absolutePath) {
            whisperExe.copyTo(target, overwrite = true)
            logger.info { "Installed whisper binary: ${whisperExe.name} -> ${target.absolutePath}" }
        }

        // whisper.cpp needs ggml.dll, whisper.dll and friends in the same dir as the exe.
        // Copy DLLs from the exe's directory (may equal binDir already for flat layouts).
        whisperExe.parentFile?.listFiles()?.filter { it.extension.equals("dll", ignoreCase = true) }
            ?.forEach { dll ->
                val dllTarget = File(binDir, dll.name)
                if (!dllTarget.exists()) {
                    dll.copyTo(dllTarget)
                    logger.debug { "Copied DLL ${dll.name} -> ${dllTarget.absolutePath}" }
                }
            }

        // Clean up any nested subdirectories left by the archive (keeps binDir tidy).
        if (whisperExe.parentFile != binDir) {
            binDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                dir.deleteRecursively()
                logger.debug { "Cleaned up extracted folder: ${dir.absolutePath}" }
            }
        }
    }

    private fun checkVCRedist(): PreInstallCheckResult {
        logger.info { "Checking Visual C++ Runtime (minimum major: $VC_RUNTIME_MIN_MAJOR)..." }
        return try {
            val version = getVCRedistVersion()
            logger.info { "Registry check for VC++ Runtime: ${version ?: "not found"}" }

            if (version != null) {
                val major = version.removePrefix("v").substringBefore('.').toIntOrNull() ?: 0
                logger.info { "VC++ Runtime major version detected: $major" }

                if (major >= VC_RUNTIME_MIN_MAJOR) {
                    // Any 14.x or newer major family is ABI-compatible with our bundled
                    // components. Skip the install and keep the user's system runtime.
                    logger.info { "VC++ Runtime $version satisfies minimum $VC_RUNTIME_MIN_MAJOR.x; skipping install" }
                    PreInstallCheckResult.Passed(
                        checkName = "Visual C++ Runtime",
                        details = "Visual C++ Runtime $version detected (>= $VC_RUNTIME_MIN_MAJOR.x, compatible)"
                    )
                } else {
                    // Older 13.x / earlier — bundled installer will bring us up to 14.x
                    logger.info { "VC++ Runtime $version predates $VC_RUNTIME_MIN_MAJOR.x; will install bundled v$VC_RUNTIME_BUNDLED_VERSION" }
                    PreInstallCheckResult.Passed(
                        checkName = "Visual C++ Runtime",
                        details = "Visual C++ Runtime $version found. Bundled v$VC_RUNTIME_BUNDLED_VERSION will be installed alongside it."
                    )
                }
            } else {
                // No version found in registry — check for universal CRT DLLs in System32
                val system32 = System.getenv("SystemRoot")?.let { "$it\\System32" }
                    ?: "C:\\Windows\\System32"
                logger.info { "Checking for VC++ DLLs in $system32" }

                val vcRuntimeFiles = listOf("vcruntime140.dll", "msvcp140.dll")
                val allPresent = vcRuntimeFiles.all { File(system32, it).exists() }
                logger.info { "VC++ DLLs present: $allPresent" }

                if (allPresent) {
                    // DLLs present = 14.x runtime ships with the OS (Windows 10+/11) — skip install
                    logger.info { "VC++ 14.x+ DLLs found in System32; skipping install" }
                    PreInstallCheckResult.Passed(
                        checkName = "Visual C++ Runtime",
                        details = "Visual C++ 14.x runtime detected in System32 (compatible)"
                    )
                } else {
                    logger.info { "VC++ Runtime not found - will be installed during setup" }
                    PreInstallCheckResult.Passed(
                        checkName = "Visual C++ Runtime",
                        details = "Visual C++ Runtime not found. Bundled v$VC_RUNTIME_BUNDLED_VERSION will be installed during setup."
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
     * Gets the installed VC++ Redistributable version from the Windows registry.
     *
     * Probes every major family from [VC_RUNTIME_MIN_MAJOR] up to [VC_RUNTIME_MAX_MAJOR_PROBE]
     * under both the native and WOW6432 registry views, so a future 15.x/16.x release or a
     * Wow64-only install is still detected. Returns the highest version it finds, or null
     * if no runtime is registered.
     */
    private fun getVCRedistVersion(): String? {
        val registryRoots = listOf(
            "HKLM\\SOFTWARE\\Microsoft\\VisualStudio",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\VisualStudio"
        )

        var best: Pair<IntArray, String>? = null
        for (major in VC_RUNTIME_MAX_MAJOR_PROBE downTo VC_RUNTIME_MIN_MAJOR) {
            for (root in registryRoots) {
                for (arch in listOf("x64", "X64")) {
                    val regPath = "$root\\$major.0\\VC\\Runtimes\\$arch"
                    val version = readVersionFromRegistry(regPath) ?: continue
                    val parsed = version.removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }.toIntArray()
                    if (best == null || compareVersions(parsed, best.first) > 0) {
                        best = parsed to version
                    }
                }
            }
        }
        return best?.second
    }

    private fun readVersionFromRegistry(regPath: String): String? {
        return try {
            val process = ProcessBuilder("reg", "query", regPath, "/v", "Version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            // Parse output like "    Version    REG_SZ    v14.29.30156.00"
            Regex("""Version\s+REG_SZ\s+(v?[\d.]+)""").find(output)?.groupValues?.get(1)
        } catch (e: Exception) {
            logger.debug { "Could not query VC++ version at $regPath: ${e.message}" }
            null
        }
    }

    private fun compareVersions(a: IntArray, b: IntArray): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }

    /**
     * Returns true when a VC++ Redistributable satisfying our minimum ABI requirement
     * (14.x or newer) is already on the system, in which case the installer loop skips
     * running the bundled installer.
     *
     * Deliberately accepts versions newer than [VC_RUNTIME_BUNDLED_VERSION] — Windows 11
     * ships a 14.x runtime by default, and Microsoft guarantees ABI compatibility within
     * the 14.x family. Downgrading a system-wide component would be user-hostile.
     */
    private fun isVCRedistCompatibleVersionInstalled(): Boolean {
        val version = getVCRedistVersion()
        if (version != null) {
            val major = version.removePrefix("v").substringBefore('.').toIntOrNull() ?: 0
            if (major >= VC_RUNTIME_MIN_MAJOR) return true
        }

        // Fallback: Windows 10/11 ships the 14.x universal CRT in System32 even before
        // any redistributable has been registered — treat that as satisfying the requirement.
        val system32 = System.getenv("SystemRoot")?.let { "$it\\System32" } ?: "C:\\Windows\\System32"
        return listOf("vcruntime140.dll", "msvcp140.dll").all { File(system32, it).exists() }
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
                        "v$VC_RUNTIME_BUNDLED_VERSION"
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
            // Step 0: If a venv already exists, wipe it. A previous run may have left
            // python.exe locked by a running LibreTranslate service — `python -m venv`
            // would fail with "Permission denied" trying to overwrite it. Stop any
            // python.exe running out of the venv first, then delete the directory.
            prepareVenvDirectory(venvDir) { onProgress(it) }

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

            // Step 7: Install language models via argostranslate's Python API.
            // We intentionally avoid relying on `argospm.exe`: some argostranslate
            // releases (especially the 2.x line) no longer register that console
            // script in a venv's Scripts/ folder, which previously left this wizard
            // step with a FileNotFoundException. Calling the Python API through
            // `python -c` works across versions.
            onProgress("Installing language models...")
            logger.info { "Installing language models via argostranslate Python API" }

            val modelArgs = DEFAULT_LANGUAGE_MODELS.map { it.removePrefix("translate-") }
            val installModelsScript = """
                import sys, argostranslate.package
                pairs = [a.split('_', 1) for a in sys.argv[1:]]
                argostranslate.package.update_package_index()
                packages = argostranslate.package.get_available_packages()
                installed, failed = 0, []
                for frm, to in pairs:
                    matches = [p for p in packages if p.from_code == frm and p.to_code == to]
                    if not matches:
                        failed.append(f'{frm}->{to}: no package available')
                        continue
                    try:
                        argostranslate.package.install_from_path(str(matches[0].download()))
                        installed += 1
                        print(f'Installed {frm}->{to}')
                    except Exception as e:
                        failed.append(f'{frm}->{to}: {e}')
                print(f'Summary: {installed} installed, {len(failed)} failed')
                for f in failed:
                    print('  - ' + f, file=sys.stderr)
                sys.exit(0 if installed > 0 else 1)
            """.trimIndent()

            val modelResult = processExecutor.executeAndCapture(
                listOf(venvPython, "-c", installModelsScript) + modelArgs,
                ProcessConfig(timeoutMinutes = 30)
            )

            if (modelResult.exitCode != 0) {
                // Fail-soft: LibreTranslate itself is already installed and will
                // start; the user can install missing models from the UI later.
                logger.warn {
                    "Language model installation did not fully succeed. " +
                        "stderr: ${modelResult.stderr.take(500)}. " +
                        "LibreTranslate will still start; users can install missing models from the UI."
                }
            } else {
                logger.info { "Language models installed:\n${modelResult.stdout}" }
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
