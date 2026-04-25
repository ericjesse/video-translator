package com.ericjesse.videotranslator.domain.installer

import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow

/**
 * Strategy interface for installing application dependencies.
 * Each platform (macOS, Windows, Linux) has its own implementation.
 *
 * The installer handles:
 * - Describing required components with size and warnings
 * - Running pre-installation checks
 * - Installing components with progress reporting
 * - Cancellation with automatic rollback of partially installed components
 * - Generating installation summaries
 */
interface DependencyInstaller {

    /**
     * Returns descriptions of all required components for this platform.
     * This information is displayed to the user before installation begins.
     *
     * @return List of component descriptions with sizes and warnings.
     */
    fun getComponentDescriptions(): List<ComponentDescription>

    /**
     * Returns platform-specific warnings that apply to the entire installation process.
     * These warnings should be displayed prominently before installation begins.
     *
     * @return List of warning messages for the user.
     */
    fun getPlatformWarnings(): List<String> = emptyList()

    /**
     * Performs pre-installation checks to ensure the system is ready.
     * Checks may include: disk space, permissions, network connectivity,
     * existing installations, required system dependencies.
     *
     * @return List of check results indicating pass/fail/warning status.
     */
    suspend fun runPreInstallationChecks(): List<PreInstallCheckResult>

    /**
     * Installs all required components, emitting progress updates.
     * The installation can be cancelled via [cancel], which will trigger
     * automatic rollback of any components installed during this session.
     *
     * @param whisperModelId The Whisper model variant to download. Accepts
     *   `"tiny"`, `"base"`, `"small"`, `"medium"`, or `"large"`. Defaults to `"base"`.
     * @param includeLibreTranslate When true, Python + LibreTranslate are installed
     *   alongside the core components. Set to false when the user has selected an
     *   external translation provider and does not need the local pip stack.
     * @return Flow of installation progress events.
     */
    fun install(
        whisperModelId: String = "base",
        includeLibreTranslate: Boolean = true,
    ): Flow<InstallationProgress>

    /**
     * Returns a summary of what was installed and where.
     * Should be called after installation completes successfully.
     *
     * @return Summary containing installed components and their locations.
     */
    fun getInstallationSummary(): InstallationSummary

    /**
     * Cancels any ongoing installation and rolls back changes.
     * Components that were installed during this session will be removed.
     * Components that existed before installation will not be affected.
     */
    suspend fun cancel()

    /**
     * Checks if the installer is currently running an installation.
     */
    fun isInstalling(): Boolean

    /**
     * Gets the current installation state.
     */
    fun getState(): InstallerState
}

/**
 * Description of a component that needs to be installed.
 *
 * @property id Unique identifier for the component.
 * @property name Human-readable name.
 * @property description Brief description of what the component does.
 * @property estimatedSizeMb Approximate download/install size in megabytes.
 * @property warnings List of warnings to display before installation.
 * @property isOptional Whether this component is optional.
 * @property dependencies List of component IDs this component depends on.
 */
data class ComponentDescription(
    val id: ComponentId,
    val name: String,
    val description: String,
    val estimatedSizeMb: Long,
    val warnings: List<String> = emptyList(),
    val isOptional: Boolean = false,
    val dependencies: List<ComponentId> = emptyList(),
)

/**
 * Identifiers for installable components.
 */
enum class ComponentId {
    YT_DLP,
    FFMPEG,
    WHISPER_CPP,
    WHISPER_MODEL_BASE,
    WHISPER_MODEL_SMALL,
    WHISPER_MODEL_MEDIUM,
    WHISPER_MODEL_LARGE,
    LIBRE_TRANSLATE,
    VC_REDIST, // Windows-only: Visual C++ Redistributable
    PYTHON // Windows-only: Python runtime for LibreTranslate
}

/**
 * Maps a Whisper model name (e.g. `"base"`, `"large"`) to its corresponding
 * [ComponentId]. Unknown names (including `"tiny"`, which the strategy pipeline
 * does not yet ship a dedicated ComponentId for) fall back to
 * [ComponentId.WHISPER_MODEL_BASE].
 */
fun whisperModelComponentId(modelName: String): ComponentId = when (modelName.lowercase()) {
    "small" -> ComponentId.WHISPER_MODEL_SMALL
    "medium" -> ComponentId.WHISPER_MODEL_MEDIUM
    "large" -> ComponentId.WHISPER_MODEL_LARGE
    else -> ComponentId.WHISPER_MODEL_BASE
}

/** Inverse of [whisperModelComponentId] — returns the canonical name. */
fun ComponentId.whisperModelName(): String? = when (this) {
    ComponentId.WHISPER_MODEL_BASE -> "base"
    ComponentId.WHISPER_MODEL_SMALL -> "small"
    ComponentId.WHISPER_MODEL_MEDIUM -> "medium"
    ComponentId.WHISPER_MODEL_LARGE -> "large"
    else -> null
}

/**
 * Result of a pre-installation check.
 */
sealed class PreInstallCheckResult {
    abstract val checkName: String

    /**
     * Check passed successfully.
     */
    data class Passed(
        override val checkName: String,
        val details: String? = null,
    ) : PreInstallCheckResult()

    /**
     * Check failed - installation cannot proceed.
     */
    data class Failed(
        override val checkName: String,
        val reason: String,
        val suggestion: String? = null,
    ) : PreInstallCheckResult()

    /**
     * Check passed but with a warning.
     */
    data class Warning(
        override val checkName: String,
        val message: String,
        val suggestion: String? = null,
    ) : PreInstallCheckResult()

    fun isPassed(): Boolean = this is Passed
    fun isFailed(): Boolean = this is Failed
    fun isWarning(): Boolean = this is Warning
}

/**
 * Progress event emitted during installation.
 */
sealed class InstallationProgress {
    /**
     * Starting installation of a component.
     */
    data class Starting(
        val componentId: ComponentId,
        val componentName: String,
        val componentIndex: Int,
        val totalComponents: Int,
    ) : InstallationProgress()

    /**
     * Download progress for a component.
     */
    data class Downloading(
        val componentId: ComponentId,
        val componentName: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val percentage: Float,
    ) : InstallationProgress()

    /**
     * Extraction/installation progress for a component.
     */
    data class Installing(
        val componentId: ComponentId,
        val componentName: String,
        val percentage: Float,
        val message: String,
    ) : InstallationProgress()

    /**
     * Component installation completed.
     */
    data class ComponentCompleted(
        val componentId: ComponentId,
        val componentName: String,
        val installPath: String,
        val version: String?,
    ) : InstallationProgress()

    /**
     * Component installation failed.
     */
    data class ComponentFailed(
        val componentId: ComponentId,
        val componentName: String,
        val error: String,
        val isRetryable: Boolean,
    ) : InstallationProgress()

    /**
     * All installations completed successfully.
     */
    data class Completed(
        val summary: InstallationSummary,
    ) : InstallationProgress()

    /**
     * Installation was cancelled and rollback is in progress.
     */
    data class Cancelling(
        val message: String,
        val componentsToRemove: Int,
    ) : InstallationProgress()

    /**
     * Rollback completed after cancellation.
     */
    data class Cancelled(
        val removedComponents: List<ComponentId>,
        val message: String,
    ) : InstallationProgress()

    /**
     * Installation failed completely.
     */
    data class Failed(
        val error: String,
        val failedComponent: ComponentId?,
        val suggestion: String?,
    ) : InstallationProgress()
}

/**
 * Summary of what was installed.
 *
 * @property installedComponents List of components that were installed.
 * @property totalSizeMb Total size of all installed components.
 * @property duration How long the installation took.
 * @property warnings Any warnings that occurred during installation.
 */
data class InstallationSummary(
    val installedComponents: List<InstalledComponent>,
    val totalSizeMb: Long,
    val duration: Duration,
    val warnings: List<String> = emptyList(),
)

/**
 * Information about an installed component.
 *
 * @property id Component identifier.
 * @property name Human-readable name.
 * @property version Installed version, if available.
 * @property installPath Where the component was installed.
 * @property sizeMb Size of the installed component.
 */
data class InstalledComponent(
    val id: ComponentId,
    val name: String,
    val version: String?,
    val installPath: String,
    val sizeMb: Long,
)

/**
 * Current state of the installer.
 */
enum class InstallerState {
    IDLE,
    CHECKING,
    INSTALLING,
    CANCELLING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Tracks which components existed before installation for rollback purposes.
 */
data class PreInstallationState(
    val existingComponents: Set<ComponentId>,
    val timestamp: Long = System.currentTimeMillis(),
)
