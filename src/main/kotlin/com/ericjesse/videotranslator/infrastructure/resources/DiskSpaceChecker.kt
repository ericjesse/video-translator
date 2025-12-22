package com.ericjesse.videotranslator.infrastructure.resources

import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Represents disk space information for a location.
 */
data class DiskSpaceInfo(
    val path: Path,
    val totalBytes: Long,
    val usableBytes: Long,
    val freeBytes: Long,
    val timestamp: Instant = Instant.now()
) {
    val totalGB: Double get() = totalBytes / (1024.0 * 1024.0 * 1024.0)
    val usableGB: Double get() = usableBytes / (1024.0 * 1024.0 * 1024.0)
    val freeGB: Double get() = freeBytes / (1024.0 * 1024.0 * 1024.0)

    val usableMB: Long get() = usableBytes / (1024 * 1024)
    val freeMB: Long get() = freeBytes / (1024 * 1024)

    val usedPercent: Int get() = if (totalBytes > 0) {
        ((totalBytes - freeBytes).toDouble() / totalBytes * 100).toInt()
    } else 0

    /**
     * Checks if disk space is critically low (less than 1GB).
     */
    val isCriticallyLow: Boolean get() = usableBytes < 1L * 1024 * 1024 * 1024

    /**
     * Checks if disk space is low (less than 5GB).
     */
    val isLow: Boolean get() = usableBytes < 5L * 1024 * 1024 * 1024

    fun getSummary(): String = String.format(
        "%.1f GB free of %.1f GB (%d%% used)",
        usableGB, totalGB, usedPercent
    )
}

/**
 * Result of a disk space check for an operation.
 */
sealed class DiskSpaceCheckResult {
    /** Sufficient space available */
    data object Sufficient : DiskSpaceCheckResult()

    /** Space is low but operation can proceed */
    data class LowSpace(
        val availableMB: Long,
        val requiredMB: Long,
        val warningMessage: String
    ) : DiskSpaceCheckResult()

    /** Insufficient space for operation */
    data class InsufficientSpace(
        val availableMB: Long,
        val requiredMB: Long,
        val suggestions: List<String>
    ) : DiskSpaceCheckResult()

    fun isOk(): Boolean = this is Sufficient || this is LowSpace

    fun getMessage(): String = when (this) {
        is Sufficient -> "Sufficient disk space available"
        is LowSpace -> warningMessage
        is InsufficientSpace -> "Insufficient disk space: ${availableMB}MB available, ${requiredMB}MB required"
    }
}

/**
 * Video quality settings for degraded mode.
 */
enum class VideoQuality(val displayName: String, val estimatedSizeFactor: Double) {
    HIGH("High Quality", 1.0),
    MEDIUM("Medium Quality", 0.6),
    LOW("Low Quality", 0.3);

    companion object {
        /**
         * Gets recommended quality based on available space.
         */
        fun forAvailableSpace(availableMB: Long, videoDurationSeconds: Long): VideoQuality {
            // Rough estimate: 1 minute of video = ~100MB at high quality
            val estimatedHighQualityMB = (videoDurationSeconds / 60) * 100

            return when {
                availableMB >= estimatedHighQualityMB * 2 -> HIGH
                availableMB >= estimatedHighQualityMB -> MEDIUM
                else -> LOW
            }
        }
    }
}

/**
 * Estimated disk space requirements for operations.
 */
object DiskSpaceRequirements {
    /** Base overhead for any operation */
    const val BASE_OVERHEAD_MB = 100L

    /** MB per minute of video for download */
    const val MB_PER_MINUTE_DOWNLOAD = 50L

    /** MB per minute for audio extraction */
    const val MB_PER_MINUTE_AUDIO = 10L

    /** MB per minute for transcription temp files */
    const val MB_PER_MINUTE_TRANSCRIPTION = 5L

    /** MB per minute for rendered video (high quality) */
    const val MB_PER_MINUTE_RENDER = 100L

    /**
     * Estimates disk space needed for a translation job.
     *
     * @param videoDurationSeconds Duration of the video in seconds.
     * @param includeDownload Whether video download is needed.
     * @param includeRender Whether video rendering is needed.
     * @return Estimated required space in MB.
     */
    fun estimateForTranslation(
        videoDurationSeconds: Long,
        includeDownload: Boolean = true,
        includeRender: Boolean = true
    ): Long {
        val minutes = (videoDurationSeconds / 60).coerceAtLeast(1)

        var total = BASE_OVERHEAD_MB

        if (includeDownload) {
            total += minutes * MB_PER_MINUTE_DOWNLOAD
        }

        total += minutes * MB_PER_MINUTE_AUDIO
        total += minutes * MB_PER_MINUTE_TRANSCRIPTION

        if (includeRender) {
            total += minutes * MB_PER_MINUTE_RENDER
        }

        // Add 20% buffer
        return (total * 1.2).toLong()
    }

    /**
     * Estimates disk space for a video download.
     */
    fun estimateForDownload(videoDurationSeconds: Long): Long {
        val minutes = (videoDurationSeconds / 60).coerceAtLeast(1)
        return BASE_OVERHEAD_MB + (minutes * MB_PER_MINUTE_DOWNLOAD * 1.2).toLong()
    }
}

/**
 * Checks and monitors disk space.
 *
 * Features:
 * - Check available space for operations
 * - Suggest quality degradation when space is low
 * - Monitor space during operations
 * - Trigger cleanup when needed
 *
 * @param platformPaths Platform-specific paths.
 * @param tempFileManager Temp file manager for cleanup operations.
 */
class DiskSpaceChecker(
    private val platformPaths: PlatformPaths,
    private val tempFileManager: TempFileManager? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Minimum space thresholds
    companion object {
        const val CRITICAL_SPACE_MB = 500L
        const val WARNING_SPACE_MB = 2000L
        const val COMFORTABLE_SPACE_MB = 5000L
    }

    /**
     * Gets disk space info for the cache directory.
     */
    fun getCacheSpaceInfo(): DiskSpaceInfo = getSpaceInfo(File(platformPaths.cacheDir).toPath())

    /**
     * Gets disk space info for a specific path.
     */
    fun getSpaceInfo(path: Path): DiskSpaceInfo {
        val fileStore = Files.getFileStore(path)
        return DiskSpaceInfo(
            path = path,
            totalBytes = fileStore.totalSpace,
            usableBytes = fileStore.usableSpace,
            freeBytes = fileStore.unallocatedSpace
        )
    }

    /**
     * Gets disk space info for a directory.
     */
    fun getSpaceInfo(directory: File): DiskSpaceInfo = getSpaceInfo(directory.toPath())

    /**
     * Checks if there's enough space for an operation.
     *
     * @param requiredMB Required space in MB.
     * @param targetPath Path where space is needed.
     * @return DiskSpaceCheckResult indicating if operation can proceed.
     */
    fun checkSpace(requiredMB: Long, targetPath: Path = File(platformPaths.cacheDir).toPath()): DiskSpaceCheckResult {
        val info = getSpaceInfo(targetPath)
        val availableMB = info.usableMB

        logger.debug { "Disk space check: required=${requiredMB}MB, available=${availableMB}MB" }

        return when {
            availableMB < requiredMB -> {
                val suggestions = generateSpaceSuggestions(requiredMB - availableMB)
                DiskSpaceCheckResult.InsufficientSpace(
                    availableMB = availableMB,
                    requiredMB = requiredMB,
                    suggestions = suggestions
                )
            }
            availableMB < requiredMB + WARNING_SPACE_MB -> {
                DiskSpaceCheckResult.LowSpace(
                    availableMB = availableMB,
                    requiredMB = requiredMB,
                    warningMessage = "Disk space is low. ${availableMB}MB available, ${requiredMB}MB required."
                )
            }
            else -> DiskSpaceCheckResult.Sufficient
        }
    }

    /**
     * Checks space for a video download.
     *
     * @param videoDurationSeconds Duration of the video.
     * @param outputPath Where the video will be saved.
     * @return DiskSpaceCheckResult.
     */
    fun checkSpaceForDownload(
        videoDurationSeconds: Long,
        outputPath: Path = File(platformPaths.cacheDir).toPath()
    ): DiskSpaceCheckResult {
        val requiredMB = DiskSpaceRequirements.estimateForDownload(videoDurationSeconds)
        return checkSpace(requiredMB, outputPath)
    }

    /**
     * Checks space for a full translation job.
     *
     * @param videoDurationSeconds Duration of the video.
     * @param includeDownload Whether download is needed.
     * @param includeRender Whether rendering is needed.
     * @param outputPath Where output will be saved.
     * @return DiskSpaceCheckResult.
     */
    fun checkSpaceForTranslation(
        videoDurationSeconds: Long,
        includeDownload: Boolean = true,
        includeRender: Boolean = true,
        outputPath: Path = File(platformPaths.cacheDir).toPath()
    ): DiskSpaceCheckResult {
        val requiredMB = DiskSpaceRequirements.estimateForTranslation(
            videoDurationSeconds, includeDownload, includeRender
        )
        return checkSpace(requiredMB, outputPath)
    }

    /**
     * Gets recommended video quality based on available space.
     *
     * @param videoDurationSeconds Duration of the video.
     * @param outputPath Where output will be saved.
     * @return Recommended VideoQuality.
     */
    fun getRecommendedQuality(
        videoDurationSeconds: Long,
        outputPath: Path = File(platformPaths.cacheDir).toPath()
    ): VideoQuality {
        val info = getSpaceInfo(outputPath)
        return VideoQuality.forAvailableSpace(info.usableMB, videoDurationSeconds)
    }

    /**
     * Generates suggestions for freeing up disk space.
     */
    private fun generateSpaceSuggestions(neededMB: Long): List<String> {
        val suggestions = mutableListOf<String>()

        // Check cache size
        tempFileManager?.let { tfm ->
            val cacheSizeMB = tfm.getCacheSizeMB()
            if (cacheSizeMB > 100) {
                suggestions.add("Clear cache to free up ~${cacheSizeMB}MB")
            }
        }

        suggestions.add("Free up at least ${neededMB}MB on this drive")
        suggestions.add("Choose a different output location with more space")
        suggestions.add("Use lower quality settings to reduce space requirements")

        return suggestions
    }

    /**
     * Attempts to free up space by cleaning cache.
     *
     * @param targetMB Target amount of space to free.
     * @return Amount of space freed in MB.
     */
    suspend fun freeUpSpace(targetMB: Long): Long = withContext(Dispatchers.IO) {
        if (tempFileManager == null) return@withContext 0L

        val initialSpace = getCacheSpaceInfo().usableMB
        val result = tempFileManager.runFullCacheCleanup()
        val newSpace = getCacheSpaceInfo().usableMB

        val freedMB = newSpace - initialSpace
        logger.info { "Freed ${freedMB}MB (deleted ${result.filesDeleted} files)" }

        freedMB
    }

    /**
     * Checks space and attempts cleanup if needed.
     *
     * @param requiredMB Required space.
     * @param targetPath Target path.
     * @return Final DiskSpaceCheckResult after potential cleanup.
     */
    suspend fun checkSpaceWithCleanup(
        requiredMB: Long,
        targetPath: Path = File(platformPaths.cacheDir).toPath()
    ): DiskSpaceCheckResult {
        val initialResult = checkSpace(requiredMB, targetPath)

        if (initialResult is DiskSpaceCheckResult.InsufficientSpace && tempFileManager != null) {
            val neededMB = requiredMB - initialResult.availableMB
            logger.info { "Attempting to free ${neededMB}MB by cleaning cache" }

            freeUpSpace(neededMB)
            return checkSpace(requiredMB, targetPath)
        }

        return initialResult
    }

    /**
     * Monitors disk space during an operation.
     *
     * @param targetPath Path to monitor.
     * @param intervalMs Check interval.
     * @param onLowSpace Callback when space gets low.
     * @param onCritical Callback when space is critical.
     * @return Job that can be cancelled to stop monitoring.
     */
    fun monitorDuringOperation(
        targetPath: Path = File(platformPaths.cacheDir).toPath(),
        intervalMs: Long = 10000L,
        onLowSpace: (DiskSpaceInfo) -> Unit = {},
        onCritical: (DiskSpaceInfo) -> Unit = {}
    ): Job {
        return scope.launch {
            while (isActive) {
                val info = getSpaceInfo(targetPath)

                when {
                    info.usableMB < CRITICAL_SPACE_MB -> {
                        logger.warn { "Critical disk space: ${info.getSummary()}" }
                        onCritical(info)
                    }
                    info.usableMB < WARNING_SPACE_MB -> {
                        logger.debug { "Low disk space: ${info.getSummary()}" }
                        onLowSpace(info)
                    }
                }

                delay(intervalMs)
            }
        }
    }

    /**
     * Creates a disk space flow for observing space changes.
     */
    fun observeSpace(
        targetPath: Path = File(platformPaths.cacheDir).toPath(),
        intervalMs: Long = 5000L
    ): Flow<DiskSpaceInfo> = flow {
        while (true) {
            emit(getSpaceInfo(targetPath))
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Closes the disk space checker.
     */
    fun close() {
        scope.cancel()
    }
}

/**
 * Exception thrown when disk space is insufficient.
 */
class InsufficientDiskSpaceException(
    val availableMB: Long,
    val requiredMB: Long,
    val suggestions: List<String>
) : Exception(
    "Insufficient disk space: ${availableMB}MB available, ${requiredMB}MB required"
)

/**
 * Extension to check space and throw if insufficient.
 */
fun DiskSpaceChecker.requireSpace(requiredMB: Long, targetPath: Path) {
    when (val result = checkSpace(requiredMB, targetPath)) {
        is DiskSpaceCheckResult.InsufficientSpace -> {
            throw InsufficientDiskSpaceException(
                result.availableMB,
                result.requiredMB,
                result.suggestions
            )
        }
        else -> { /* OK */ }
    }
}
