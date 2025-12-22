package com.ericjesse.videotranslator.infrastructure.resources

import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Represents a tracked temporary file or directory.
 */
data class TrackedFile(
    val path: Path,
    val operationId: String,
    val createdAt: Instant = Instant.now(),
    val isDirectory: Boolean = false,
    val description: String = ""
) {
    val file: File get() = path.toFile()
    val exists: Boolean get() = file.exists()
    val sizeBytes: Long get() = if (isDirectory) {
        file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } else {
        file.length()
    }
    val sizeMB: Long get() = sizeBytes / (1024 * 1024)
}

/**
 * Cleanup policy for temporary files.
 */
data class CleanupPolicy(
    /** Maximum age of cached files before cleanup */
    val maxCacheAge: Duration = Duration.ofDays(7),
    /** Maximum total cache size in MB */
    val maxCacheSizeMB: Long = 5000,
    /** Whether to clean up on application exit */
    val cleanupOnExit: Boolean = true,
    /** Whether to clean up orphaned files on startup */
    val cleanupOrphansOnStartup: Boolean = true,
    /** File patterns to always clean up */
    val cleanupPatterns: List<String> = listOf(
        "*.tmp",
        "*.partial",
        "download-*.mp4",
        "audio-*.wav",
        "extract-*"
    )
)

/**
 * Manages temporary files and directories with automatic cleanup.
 *
 * Features:
 * - Track temporary files by operation ID
 * - Automatic cleanup on completion, cancellation, or error
 * - Cleanup orphaned files on startup
 * - Age-based and size-based cache cleanup
 * - Cleanup hooks for application exit
 *
 * @param platformPaths Platform-specific paths.
 * @param policy Cleanup policy configuration.
 */
class TempFileManager(
    private val platformPaths: PlatformPaths,
    private val policy: CleanupPolicy = CleanupPolicy()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track files by operation ID
    private val trackedFiles = ConcurrentHashMap<String, MutableList<TrackedFile>>()

    // All tracked files for global cleanup
    private val allTrackedFiles = ConcurrentHashMap<Path, TrackedFile>()

    // Cleanup callbacks
    private val exitCleanupCallbacks = mutableListOf<() -> Unit>()

    init {
        // Register shutdown hook for cleanup
        if (policy.cleanupOnExit) {
            Runtime.getRuntime().addShutdownHook(Thread {
                runBlocking {
                    cleanupAllTrackedFiles()
                    exitCleanupCallbacks.forEach { it() }
                }
            })
        }

        // Clean up orphaned files from previous runs
        if (policy.cleanupOrphansOnStartup) {
            scope.launch {
                cleanupOrphanedFiles()
            }
        }
    }

    /**
     * Gets the cache directory path.
     */
    val cacheDir: File get() = File(platformPaths.cacheDir)

    /**
     * Creates a new temporary file and tracks it.
     *
     * @param operationId The operation this file belongs to.
     * @param prefix Filename prefix.
     * @param suffix Filename suffix (extension).
     * @param description Human-readable description.
     * @return The created temporary file.
     */
    fun createTempFile(
        operationId: String,
        prefix: String = "temp",
        suffix: String = ".tmp",
        description: String = ""
    ): File {
        val file = File.createTempFile(prefix, suffix, cacheDir)
        trackFile(file.toPath(), operationId, false, description)
        logger.debug { "Created temp file: ${file.name} for operation $operationId" }
        return file
    }

    /**
     * Creates a new temporary directory and tracks it.
     *
     * @param operationId The operation this directory belongs to.
     * @param prefix Directory name prefix.
     * @param description Human-readable description.
     * @return The created temporary directory.
     */
    fun createTempDirectory(
        operationId: String,
        prefix: String = "temp",
        description: String = ""
    ): File {
        val dir = Files.createTempDirectory(cacheDir.toPath(), prefix).toFile()
        trackFile(dir.toPath(), operationId, true, description)
        logger.debug { "Created temp directory: ${dir.name} for operation $operationId" }
        return dir
    }

    /**
     * Gets a path in the cache directory.
     * Does not create the file, but tracks it for cleanup.
     *
     * @param operationId The operation this file belongs to.
     * @param filename The filename.
     * @param description Human-readable description.
     * @return The file path.
     */
    fun getCachePath(
        operationId: String,
        filename: String,
        description: String = ""
    ): File {
        val file = File(cacheDir, filename)
        trackFile(file.toPath(), operationId, false, description)
        return file
    }

    /**
     * Tracks an existing file for cleanup.
     *
     * @param path The file or directory path.
     * @param operationId The operation this file belongs to.
     * @param isDirectory Whether this is a directory.
     * @param description Human-readable description.
     */
    fun trackFile(
        path: Path,
        operationId: String,
        isDirectory: Boolean = false,
        description: String = ""
    ) {
        val tracked = TrackedFile(
            path = path,
            operationId = operationId,
            isDirectory = isDirectory,
            description = description
        )

        trackedFiles.getOrPut(operationId) { mutableListOf() }.add(tracked)
        allTrackedFiles[path] = tracked
    }

    /**
     * Untracks a file (e.g., when it becomes a permanent output).
     *
     * @param path The file path to untrack.
     */
    fun untrackFile(path: Path) {
        allTrackedFiles.remove(path)
        trackedFiles.values.forEach { list ->
            list.removeIf { it.path == path }
        }
        logger.debug { "Untracked file: ${path.fileName}" }
    }

    /**
     * Gets all tracked files for an operation.
     */
    fun getTrackedFiles(operationId: String): List<TrackedFile> =
        trackedFiles[operationId]?.toList() ?: emptyList()

    /**
     * Gets the total size of tracked files for an operation.
     */
    fun getOperationDiskUsage(operationId: String): Long =
        getTrackedFiles(operationId).sumOf { it.sizeBytes }

    /**
     * Cleans up all files for an operation.
     *
     * @param operationId The operation to clean up.
     * @param reason The reason for cleanup (for logging).
     * @return Number of files deleted.
     */
    suspend fun cleanupOperation(
        operationId: String,
        reason: String = "operation completed"
    ): Int = withContext(Dispatchers.IO) {
        val files = trackedFiles.remove(operationId) ?: return@withContext 0

        var deleted = 0
        files.forEach { tracked ->
            try {
                if (tracked.file.exists()) {
                    if (tracked.isDirectory) {
                        tracked.file.deleteRecursively()
                    } else {
                        tracked.file.delete()
                    }
                    deleted++
                    logger.debug { "Deleted ${tracked.path.fileName} ($reason)" }
                }
                allTrackedFiles.remove(tracked.path)
            } catch (e: Exception) {
                logger.warn { "Failed to delete ${tracked.path}: ${e.message}" }
            }
        }

        logger.info { "Cleaned up $deleted files for operation $operationId ($reason)" }
        deleted
    }

    /**
     * Cleans up all tracked files.
     */
    suspend fun cleanupAllTrackedFiles(): Int = withContext(Dispatchers.IO) {
        var total = 0
        trackedFiles.keys.toList().forEach { operationId ->
            total += cleanupOperation(operationId, "shutdown")
        }
        total
    }

    /**
     * Cleans up orphaned temporary files from previous runs.
     */
    suspend fun cleanupOrphanedFiles(): Int = withContext(Dispatchers.IO) {
        var deleted = 0

        policy.cleanupPatterns.forEach { pattern ->
            cacheDir.listFiles()?.forEach { file ->
                if (matchesPattern(file.name, pattern) && !allTrackedFiles.containsKey(file.toPath())) {
                    try {
                        if (file.isDirectory) {
                            file.deleteRecursively()
                        } else {
                            file.delete()
                        }
                        deleted++
                        logger.debug { "Deleted orphaned file: ${file.name}" }
                    } catch (e: Exception) {
                        logger.warn { "Failed to delete orphaned file ${file.name}: ${e.message}" }
                    }
                }
            }
        }

        if (deleted > 0) {
            logger.info { "Cleaned up $deleted orphaned files" }
        }
        deleted
    }

    /**
     * Cleans up old cache files based on age.
     */
    suspend fun cleanupOldCacheFiles(): CleanupResult = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val maxAge = policy.maxCacheAge
        var deleted = 0
        var freedBytes = 0L

        cacheDir.listFiles()?.forEach { file ->
            if (!allTrackedFiles.containsKey(file.toPath())) {
                val lastModified = Instant.ofEpochMilli(file.lastModified())
                val age = Duration.between(lastModified, now)

                if (age > maxAge) {
                    val size = if (file.isDirectory) {
                        file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    } else {
                        file.length()
                    }

                    try {
                        if (file.isDirectory) {
                            file.deleteRecursively()
                        } else {
                            file.delete()
                        }
                        deleted++
                        freedBytes += size
                        logger.debug { "Deleted old cache file: ${file.name} (age: ${age.toDays()} days)" }
                    } catch (e: Exception) {
                        logger.warn { "Failed to delete old cache file ${file.name}: ${e.message}" }
                    }
                }
            }
        }

        if (deleted > 0) {
            logger.info { "Cleaned up $deleted old cache files, freed ${freedBytes / (1024 * 1024)}MB" }
        }

        CleanupResult(deleted, freedBytes)
    }

    /**
     * Cleans up cache to stay within size limit.
     */
    suspend fun cleanupCacheBySize(): CleanupResult = withContext(Dispatchers.IO) {
        val maxSizeBytes = policy.maxCacheSizeMB * 1024 * 1024
        var deleted = 0
        var freedBytes = 0L

        // Get all cache files sorted by last modified (oldest first)
        val cacheFiles = cacheDir.listFiles()
            ?.filter { !allTrackedFiles.containsKey(it.toPath()) }
            ?.sortedBy { it.lastModified() }
            ?: return@withContext CleanupResult(0, 0)

        var currentSize = cacheFiles.sumOf {
            if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() }
            else it.length()
        }

        for (file in cacheFiles) {
            if (currentSize <= maxSizeBytes) break

            val fileSize = if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
                file.length()
            }

            try {
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
                deleted++
                freedBytes += fileSize
                currentSize -= fileSize
                logger.debug { "Deleted cache file for size limit: ${file.name}" }
            } catch (e: Exception) {
                logger.warn { "Failed to delete cache file ${file.name}: ${e.message}" }
            }
        }

        if (deleted > 0) {
            logger.info { "Cleaned up $deleted cache files for size limit, freed ${freedBytes / (1024 * 1024)}MB" }
        }

        CleanupResult(deleted, freedBytes)
    }

    /**
     * Runs a full cache cleanup (age + size).
     */
    suspend fun runFullCacheCleanup(): CleanupResult {
        val ageResult = cleanupOldCacheFiles()
        val sizeResult = cleanupCacheBySize()
        return CleanupResult(
            ageResult.filesDeleted + sizeResult.filesDeleted,
            ageResult.bytesFreed + sizeResult.bytesFreed
        )
    }

    /**
     * Gets the current cache size.
     */
    fun getCacheSize(): Long {
        return cacheDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    /**
     * Gets the cache size in MB.
     */
    fun getCacheSizeMB(): Long = getCacheSize() / (1024 * 1024)

    /**
     * Registers a callback to run on application exit.
     */
    fun onExit(callback: () -> Unit) {
        exitCleanupCallbacks.add(callback)
    }

    /**
     * Checks if a filename matches a glob pattern.
     */
    private fun matchesPattern(filename: String, pattern: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .toRegex()
        return regex.matches(filename)
    }

    /**
     * Closes the temp file manager.
     */
    fun close() {
        scope.cancel()
    }
}

/**
 * Result of a cleanup operation.
 */
data class CleanupResult(
    val filesDeleted: Int,
    val bytesFreed: Long
) {
    val mbFreed: Long get() = bytesFreed / (1024 * 1024)
}

/**
 * Extension to create a cleanup scope that automatically cleans up on completion.
 */
suspend fun <T> TempFileManager.withCleanup(
    operationId: String,
    cleanupOnError: Boolean = true,
    block: suspend () -> T
): T {
    return try {
        block()
    } catch (e: CancellationException) {
        cleanupOperation(operationId, "cancelled")
        throw e
    } catch (e: Exception) {
        if (cleanupOnError) {
            cleanupOperation(operationId, "error: ${e.message}")
        }
        throw e
    } finally {
        cleanupOperation(operationId, "completed")
    }
}
