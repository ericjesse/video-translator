package com.ericjesse.videotranslator.infrastructure.installer

import com.ericjesse.videotranslator.domain.installer.ComponentDescription
import com.ericjesse.videotranslator.domain.installer.ComponentId
import com.ericjesse.videotranslator.domain.installer.DependencyInstaller
import com.ericjesse.videotranslator.domain.installer.InstallationProgress
import com.ericjesse.videotranslator.domain.installer.InstallationSummary
import com.ericjesse.videotranslator.domain.installer.InstalledComponent
import com.ericjesse.videotranslator.domain.installer.InstallerState
import com.ericjesse.videotranslator.domain.installer.PreInstallCheckResult
import com.ericjesse.videotranslator.domain.installer.PreInstallationState
import com.ericjesse.videotranslator.infrastructure.archive.ArchiveExtractor
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import com.ericjesse.videotranslator.infrastructure.process.ProcessExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

private val logger = KotlinLogging.logger {}

/**
 * Abstract base class for platform-specific dependency installers.
 * Provides common functionality for downloading, extracting, and managing components.
 */
abstract class AbstractDependencyInstaller(
    protected val platformPaths: PlatformPaths,
    protected val httpClient: HttpClient,
    protected val processExecutor: ProcessExecutor,
    protected val archiveExtractor: ArchiveExtractor,
) : DependencyInstaller {

    protected var _state: InstallerState = InstallerState.IDLE
    protected var preInstallState: PreInstallationState? = null
    protected var installedDuringSession: MutableList<InstalledComponent> = mutableListOf()
    protected var installationJob: Job? = null
    protected var _installationSummary: InstallationSummary? = null
    protected val warnings: MutableList<String> = mutableListOf()

    private var cancelled = false

    // ==================== Abstract Methods ====================

    /**
     * Returns the download URL for a component on this platform.
     */
    protected abstract fun getDownloadUrl(componentId: ComponentId): String

    /**
     * Returns the expected binary/file name after extraction.
     */
    protected abstract fun getExpectedFileName(componentId: ComponentId): String

    /**
     * Performs platform-specific post-installation setup (e.g., chmod on Unix).
     */
    protected abstract suspend fun postInstallSetup(componentId: ComponentId, installPath: String)

    /**
     * Gets the version of an installed component.
     */
    protected abstract suspend fun getInstalledVersion(componentId: ComponentId): String?

    /**
     * Returns platform-specific component descriptions.
     */
    abstract override fun getComponentDescriptions(): List<ComponentDescription>

    // ==================== Common Implementation ====================

    override fun getState(): InstallerState = _state

    override fun isInstalling(): Boolean = _state == InstallerState.INSTALLING

    override suspend fun runPreInstallationChecks(): List<PreInstallCheckResult> {
        _state = InstallerState.CHECKING
        val results = mutableListOf<PreInstallCheckResult>()

        // Check disk space
        results.add(checkDiskSpace())

        // Check write permissions
        results.add(checkWritePermissions())

        // Check network connectivity
        results.add(checkNetworkConnectivity())

        // Record which components already exist (for rollback)
        preInstallState = PreInstallationState(
            existingComponents = getExistingComponents()
        )

        // Add platform-specific checks
        results.addAll(platformSpecificChecks())

        _state = if (results.any { it.isFailed() }) InstallerState.FAILED else InstallerState.IDLE
        return results
    }

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
                if (isComponentInstalled(component.id)) {
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
                    // Download
                    val downloadedFile = downloadComponent(component) { progress ->
                        // Emit download progress - need to use a channel for this
                    }

                    if (cancelled) throw CancellationException("Installation cancelled by user")

                    emit(
                        InstallationProgress.Installing(
                            componentId = component.id,
                            componentName = component.name,
                            percentage = 0.5f,
                            message = "Extracting..."
                        )
                    )

                    // Extract and install
                    val installPath = installComponent(component.id, downloadedFile)

                    if (cancelled) throw CancellationException("Installation cancelled by user")

                    // Post-install setup
                    postInstallSetup(component.id, installPath)

                    // Get version
                    val version = getInstalledVersion(component.id)

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

                    // Cleanup downloaded file
                    downloadedFile.delete()

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

            val duration = Duration.parse("${System.currentTimeMillis() - startTime}ms")
            _installationSummary = InstallationSummary(
                installedComponents = installedDuringSession.toList(),
                totalSizeMb = installedDuringSession.sumOf { it.sizeMb },
                duration = duration,
                warnings = warnings.toList()
            )

            _state = InstallerState.COMPLETED
            emit(InstallationProgress.Completed(_installationSummary!!))

        } catch (e: CancellationException) {
            // Rollback will be handled by cancel()
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
        rollback()

        _state = InstallerState.CANCELLED
    }

    override fun getInstallationSummary(): InstallationSummary {
        return _installationSummary ?: InstallationSummary(
            installedComponents = emptyList(),
            totalSizeMb = 0,
            duration = Duration.ZERO
        )
    }

    // ==================== Protected Helper Methods ====================

    /**
     * Downloads a component and reports progress.
     */
    protected suspend fun downloadComponent(
        component: ComponentDescription,
        onProgress: (Float) -> Unit,
    ): File {
        val url = getDownloadUrl(component.id)
        val downloadDir = File(platformPaths.cacheDir)
        downloadDir.mkdirs()

        val fileName = url.substringAfterLast("/")
        val downloadFile = File(downloadDir, fileName)

        logger.info { "Downloading ${component.name} from $url" }

        httpClient.prepareGet(url).execute { response ->
            if (response.status != HttpStatusCode.OK) {
                throw RuntimeException("Failed to download ${component.name}: HTTP ${response.status}")
            }

            val contentLength = response.contentLength() ?: -1
            var downloaded = 0L

            FileOutputStream(downloadFile).use { output ->
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(8192)

                while (true) {
                    val bytesRead = channel.toInputStream().read(buffer)
                    if (bytesRead == -1) break

                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead

                    if (contentLength > 0) {
                        onProgress(downloaded.toFloat() / contentLength)
                    }
                }
            }
        }

        logger.info { "Downloaded ${component.name} to ${downloadFile.absolutePath}" }
        return downloadFile
    }

    /**
     * Installs a component from a downloaded file.
     */
    protected suspend fun installComponent(componentId: ComponentId, downloadedFile: File): String {
        val installDir = getInstallDirectory(componentId)
        File(installDir).mkdirs()

        val fileName = downloadedFile.name.lowercase()
        val isArchive = fileName.endsWith(".zip") ||
                fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz") ||
                fileName.endsWith(".tar.xz") || fileName.endsWith(".7z")

        if (isArchive) {
            archiveExtractor.extractBlocking(
                archivePath = downloadedFile.toPath(),
                destinationDir = File(installDir).toPath()
            )
        } else {
            // Single file, just copy it
            val targetFile = File(installDir, getExpectedFileName(componentId))
            downloadedFile.copyTo(targetFile, overwrite = true)
        }

        return getInstallPath(componentId)
    }

    /**
     * Gets the installation directory for a component.
     */
    protected fun getInstallDirectory(componentId: ComponentId): String {
        return when (componentId) {
            ComponentId.YT_DLP, ComponentId.FFMPEG, ComponentId.WHISPER_CPP -> platformPaths.binDir
            ComponentId.WHISPER_MODEL_BASE, ComponentId.WHISPER_MODEL_SMALL,
            ComponentId.WHISPER_MODEL_MEDIUM, ComponentId.WHISPER_MODEL_LARGE,
                ->
                "${platformPaths.modelsDir}${File.separator}whisper"

            ComponentId.LIBRE_TRANSLATE -> platformPaths.libreTranslateDir
        }
    }

    /**
     * Gets the full installation path for a component.
     */
    protected fun getInstallPath(componentId: ComponentId): String {
        val dir = getInstallDirectory(componentId)
        val fileName = getExpectedFileName(componentId)
        return "$dir${File.separator}$fileName"
    }

    /**
     * Checks if a component is already installed.
     */
    protected fun isComponentInstalled(componentId: ComponentId): Boolean {
        val path = getInstallPath(componentId)
        return File(path).exists()
    }

    /**
     * Gets the set of components that already exist.
     */
    protected fun getExistingComponents(): Set<ComponentId> {
        return ComponentId.entries.filter { isComponentInstalled(it) }.toSet()
    }

    /**
     * Rolls back installation by removing components installed during this session.
     */
    protected suspend fun rollback() {
        val preExisting = preInstallState?.existingComponents ?: emptySet()

        installedDuringSession.forEach { installed ->
            // Only remove if it didn't exist before
            if (installed.id !in preExisting) {
                try {
                    val file = File(installed.installPath)
                    if (file.exists()) {
                        if (file.isDirectory) {
                            file.deleteRecursively()
                        } else {
                            file.delete()
                        }
                        logger.info { "Rolled back: removed ${installed.name} from ${installed.installPath}" }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to rollback ${installed.name}" }
                }
            }
        }

        installedDuringSession.clear()
    }

    // ==================== Pre-installation Checks ====================

    protected open fun checkDiskSpace(): PreInstallCheckResult {
        val requiredMb = getComponentDescriptions().sumOf { it.estimatedSizeMb }
        val dataDir = File(platformPaths.dataDir)
        val availableMb = dataDir.usableSpace / (1024 * 1024)

        return if (availableMb >= requiredMb * 2) { // 2x for safety
            PreInstallCheckResult.Passed(
                checkName = "Disk Space",
                details = "Available: ${availableMb}MB, Required: ~${requiredMb}MB"
            )
        } else if (availableMb >= requiredMb) {
            PreInstallCheckResult.Warning(
                checkName = "Disk Space",
                message = "Low disk space. Available: ${availableMb}MB, Required: ~${requiredMb}MB",
                suggestion = "Consider freeing up disk space before installation"
            )
        } else {
            PreInstallCheckResult.Failed(
                checkName = "Disk Space",
                reason = "Insufficient disk space. Available: ${availableMb}MB, Required: ~${requiredMb}MB",
                suggestion = "Free up at least ${requiredMb - availableMb}MB of disk space"
            )
        }
    }

    protected open fun checkWritePermissions(): PreInstallCheckResult {
        return try {
            val testFile = File(platformPaths.dataDir, ".write_test_${System.currentTimeMillis()}")
            testFile.writeText("test")
            testFile.delete()
            PreInstallCheckResult.Passed(
                checkName = "Write Permissions",
                details = "Can write to ${platformPaths.dataDir}"
            )
        } catch (e: Exception) {
            PreInstallCheckResult.Failed(
                checkName = "Write Permissions",
                reason = "Cannot write to installation directory: ${e.message}",
                suggestion = "Check permissions for ${platformPaths.dataDir}"
            )
        }
    }

    protected open suspend fun checkNetworkConnectivity(): PreInstallCheckResult {
        return try {
            val response = httpClient.head("https://github.com")
            if (response.status.isSuccess()) {
                PreInstallCheckResult.Passed(
                    checkName = "Network Connectivity",
                    details = "Internet connection available"
                )
            } else {
                PreInstallCheckResult.Warning(
                    checkName = "Network Connectivity",
                    message = "Network check returned status ${response.status}",
                    suggestion = "Ensure you have a stable internet connection"
                )
            }
        } catch (e: Exception) {
            PreInstallCheckResult.Failed(
                checkName = "Network Connectivity",
                reason = "Cannot connect to download servers: ${e.message}",
                suggestion = "Check your internet connection and firewall settings"
            )
        }
    }

    /**
     * Override to add platform-specific pre-installation checks.
     */
    protected open suspend fun platformSpecificChecks(): List<PreInstallCheckResult> = emptyList()
}
