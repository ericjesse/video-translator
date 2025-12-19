package com.ericjesse.videotranslator.infrastructure.archive

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private val logger = KotlinLogging.logger {}

/**
 * Progress update during archive extraction.
 *
 * @property bytesExtracted Total bytes extracted so far.
 * @property totalBytes Total bytes in the archive (may be -1 if unknown).
 * @property currentFile Name of the file currently being extracted.
 * @property filesExtracted Number of files extracted so far.
 * @property totalFiles Total number of files (may be -1 if unknown).
 */
data class ExtractionProgress(
    val bytesExtracted: Long,
    val totalBytes: Long,
    val currentFile: String,
    val filesExtracted: Int,
    val totalFiles: Int
) {
    /**
     * Progress as a percentage (0.0 to 1.0).
     * Returns -1.0 if progress cannot be determined.
     */
    val percentage: Float
        get() = when {
            totalFiles > 0 -> filesExtracted.toFloat() / totalFiles
            totalBytes > 0 -> bytesExtracted.toFloat() / totalBytes
            else -> -1f
        }
}

/**
 * Configuration for archive extraction.
 *
 * @property overwriteExisting If true, overwrites existing files during extraction.
 * @property preservePermissions If true, preserves Unix file permissions (where applicable).
 * @property flattenSingleRoot If true and archive contains a single root directory,
 *                             extracts contents directly without the root directory.
 */
data class ExtractionConfig(
    val overwriteExisting: Boolean = true,
    val preservePermissions: Boolean = true,
    val flattenSingleRoot: Boolean = true
)

/**
 * Result of an extraction operation.
 *
 * @property extractedPath The root path where files were extracted.
 * @property filesExtracted Total number of files extracted.
 * @property totalBytes Total bytes extracted.
 */
data class ExtractionResult(
    val extractedPath: Path,
    val filesExtracted: Int,
    val totalBytes: Long
)

/**
 * Archive extraction utilities supporting ZIP, TAR.XZ, and 7z formats.
 * Provides progress reporting and binary discovery features.
 *
 * Features:
 * - ZIP extraction using JDK built-in classes
 * - TAR.XZ extraction using Apache Commons Compress
 * - 7z extraction using Apache Commons Compress
 * - Progress reporting during extraction
 * - Automatic flattening of single-root archives
 * - Unix permission preservation
 * - Helper to find binaries within extracted archives
 */
class ArchiveExtractor {

    companion object {
        private val IS_WINDOWS = System.getProperty("os.name").lowercase().contains("win")
        private val IS_UNIX = !IS_WINDOWS

        // Common binary names for FFmpeg
        private val FFMPEG_BINARIES = setOf("ffmpeg", "ffmpeg.exe", "ffprobe", "ffprobe.exe")

        // Common binary extensions
        private val BINARY_EXTENSIONS = if (IS_WINDOWS) {
            setOf(".exe", ".bat", ".cmd")
        } else {
            emptySet() // Unix binaries typically have no extension
        }

        /**
         * Detects archive type from file extension.
         */
        fun detectArchiveType(path: Path): ArchiveType? {
            val name = path.fileName.toString().lowercase()
            return when {
                name.endsWith(".zip") -> ArchiveType.ZIP
                name.endsWith(".tar.xz") || name.endsWith(".txz") -> ArchiveType.TAR_XZ
                name.endsWith(".7z") -> ArchiveType.SEVEN_ZIP
                name.endsWith(".tar.gz") || name.endsWith(".tgz") -> ArchiveType.TAR_GZ
                else -> null
            }
        }
    }

    /**
     * Extracts an archive to the specified destination directory.
     * Automatically detects archive type from file extension.
     *
     * @param archivePath Path to the archive file.
     * @param destinationDir Directory to extract files to.
     * @param config Extraction configuration options.
     * @return Flow emitting progress updates.
     * @throws ArchiveException if extraction fails or archive type is unsupported.
     */
    fun extract(
        archivePath: Path,
        destinationDir: Path,
        config: ExtractionConfig = ExtractionConfig()
    ): Flow<ExtractionProgress> = flow {
        val archiveType = detectArchiveType(archivePath)
            ?: throw ArchiveException(
                archivePath.toString(),
                "Unsupported archive format: ${archivePath.fileName}"
            )

        logger.info { "Extracting ${archiveType.name} archive: $archivePath -> $destinationDir" }

        // Create destination directory if it doesn't exist
        withContext(Dispatchers.IO) {
            Files.createDirectories(destinationDir)
        }

        when (archiveType) {
            ArchiveType.ZIP -> extractZip(archivePath, destinationDir, config).collect { emit(it) }
            ArchiveType.TAR_XZ -> extractTarXz(archivePath, destinationDir, config).collect { emit(it) }
            ArchiveType.TAR_GZ -> extractTarGz(archivePath, destinationDir, config).collect { emit(it) }
            ArchiveType.SEVEN_ZIP -> extract7z(archivePath, destinationDir, config).collect { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Extracts an archive and returns the result without progress streaming.
     *
     * @param archivePath Path to the archive file.
     * @param destinationDir Directory to extract files to.
     * @param config Extraction configuration options.
     * @param onProgress Optional callback for progress updates.
     * @return Extraction result with summary information.
     */
    suspend fun extractBlocking(
        archivePath: Path,
        destinationDir: Path,
        config: ExtractionConfig = ExtractionConfig(),
        onProgress: suspend (ExtractionProgress) -> Unit = {}
    ): ExtractionResult = withContext(Dispatchers.IO) {
        var lastProgress: ExtractionProgress? = null

        extract(archivePath, destinationDir, config).collect { progress ->
            lastProgress = progress
            onProgress(progress)
        }

        val finalProgress = lastProgress ?: throw ArchiveException(
            archivePath.toString(),
            "Extraction produced no output"
        )

        // Determine the actual extracted path (may be flattened)
        val extractedPath = if (config.flattenSingleRoot) {
            findSingleRootOrSelf(destinationDir)
        } else {
            destinationDir
        }

        ExtractionResult(
            extractedPath = extractedPath,
            filesExtracted = finalProgress.filesExtracted,
            totalBytes = finalProgress.bytesExtracted
        )
    }

    /**
     * Finds a specific binary within an extracted archive directory.
     *
     * @param directory Root directory to search in.
     * @param binaryName Name of the binary to find (e.g., "ffmpeg").
     * @param maxDepth Maximum directory depth to search.
     * @return Path to the binary if found, null otherwise.
     */
    suspend fun findBinary(
        directory: Path,
        binaryName: String,
        maxDepth: Int = 5
    ): Path? = withContext(Dispatchers.IO) {
        logger.debug { "Searching for binary '$binaryName' in $directory (max depth: $maxDepth)" }

        val targetNames = buildSet {
            add(binaryName)
            add(binaryName.lowercase())
            if (IS_WINDOWS && !binaryName.endsWith(".exe")) {
                add("$binaryName.exe")
                add("${binaryName.lowercase()}.exe")
            }
        }

        findFileRecursive(directory.toFile(), targetNames, maxDepth, 0)?.toPath()
    }

    /**
     * Finds all executables/binaries within an extracted archive directory.
     *
     * @param directory Root directory to search in.
     * @param maxDepth Maximum directory depth to search.
     * @return Map of binary name to path.
     */
    suspend fun findAllBinaries(
        directory: Path,
        maxDepth: Int = 5
    ): Map<String, Path> = withContext(Dispatchers.IO) {
        logger.debug { "Searching for all binaries in $directory" }

        val binaries = mutableMapOf<String, Path>()

        findBinariesRecursive(directory.toFile(), binaries, maxDepth, 0)

        logger.info { "Found ${binaries.size} binaries: ${binaries.keys}" }
        binaries
    }

    /**
     * Finds FFmpeg binaries (ffmpeg and ffprobe) within an extracted directory.
     *
     * @param directory Root directory to search in.
     * @return Pair of (ffmpeg path, ffprobe path), either may be null if not found.
     */
    suspend fun findFfmpegBinaries(
        directory: Path
    ): Pair<Path?, Path?> = withContext(Dispatchers.IO) {
        val ffmpeg = findBinary(directory, "ffmpeg")
        val ffprobe = findBinary(directory, "ffprobe")

        logger.info { "FFmpeg binaries: ffmpeg=$ffmpeg, ffprobe=$ffprobe" }
        Pair(ffmpeg, ffprobe)
    }

    // ==================== ZIP Extraction ====================

    private fun extractZip(
        archivePath: Path,
        destinationDir: Path,
        config: ExtractionConfig
    ): Flow<ExtractionProgress> = flow {
        val archiveFile = archivePath.toFile()
        val archiveSize = archiveFile.length()

        logger.debug { "ZIP archive size: $archiveSize bytes" }

        var filesExtracted = 0
        var bytesExtracted = 0L

        ZipInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { zipIn ->
            var entry: ZipEntry? = zipIn.nextEntry

            while (entry != null) {
                yield() // Allow cancellation

                val currentEntry = entry!! // Capture for smart cast
                val entryPath = resolveEntryPath(destinationDir, currentEntry.name, config)
                val entryName = currentEntry.name

                if (currentEntry.isDirectory) {
                    Files.createDirectories(entryPath)
                } else {
                    // Ensure parent directory exists
                    Files.createDirectories(entryPath.parent)

                    if (config.overwriteExisting || !Files.exists(entryPath)) {
                        extractFile(zipIn, entryPath)
                        bytesExtracted += currentEntry.compressedSize.coerceAtLeast(0)
                        filesExtracted++

                        // Set executable permission for binaries on Unix
                        if (config.preservePermissions && IS_UNIX) {
                            setUnixPermissions(entryPath, entryName)
                        }

                        emit(
                            ExtractionProgress(
                                bytesExtracted = bytesExtracted,
                                totalBytes = archiveSize,
                                currentFile = entryName,
                                filesExtracted = filesExtracted,
                                totalFiles = -1 // ZIP doesn't expose total count upfront
                            )
                        )

                        logger.trace { "Extracted: $entryName" }
                    }
                }

                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }

        logger.info { "ZIP extraction complete: $filesExtracted files, $bytesExtracted bytes" }
    }

    // ==================== TAR.XZ Extraction ====================

    private fun extractTarXz(
        archivePath: Path,
        destinationDir: Path,
        config: ExtractionConfig
    ): Flow<ExtractionProgress> = flow {
        val archiveFile = archivePath.toFile()
        val archiveSize = archiveFile.length()

        logger.debug { "TAR.XZ archive size: $archiveSize bytes" }

        var filesExtracted = 0
        var bytesExtracted = 0L

        BufferedInputStream(FileInputStream(archiveFile)).use { fileIn ->
            XZCompressorInputStream(fileIn).use { xzIn ->
                TarArchiveInputStream(xzIn).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextEntry

                    while (entry != null) {
                        yield() // Allow cancellation

                        val currentEntry = entry!! // Capture for smart cast
                        val entryName = currentEntry.name
                        val entryPath = resolveEntryPath(destinationDir, entryName, config)

                        if (currentEntry.isDirectory) {
                            Files.createDirectories(entryPath)
                        } else if (currentEntry.isFile) {
                            // Ensure parent directory exists
                            Files.createDirectories(entryPath.parent)

                            if (config.overwriteExisting || !Files.exists(entryPath)) {
                                extractFile(tarIn, entryPath)
                                bytesExtracted += currentEntry.size
                                filesExtracted++

                                // Preserve Unix permissions from TAR
                                if (config.preservePermissions && IS_UNIX) {
                                    setUnixPermissions(entryPath, currentEntry.mode)
                                }

                                emit(
                                    ExtractionProgress(
                                        bytesExtracted = bytesExtracted,
                                        totalBytes = -1, // Compressed size doesn't reflect actual
                                        currentFile = entryName,
                                        filesExtracted = filesExtracted,
                                        totalFiles = -1
                                    )
                                )

                                logger.trace { "Extracted: $entryName" }
                            }
                        }
                        // Skip symlinks, hard links, and special files for now

                        entry = tarIn.nextEntry
                    }
                }
            }
        }

        logger.info { "TAR.XZ extraction complete: $filesExtracted files, $bytesExtracted bytes" }
    }

    // ==================== TAR.GZ Extraction ====================

    private fun extractTarGz(
        archivePath: Path,
        destinationDir: Path,
        config: ExtractionConfig
    ): Flow<ExtractionProgress> = flow {
        val archiveFile = archivePath.toFile()

        logger.debug { "TAR.GZ archive size: ${archiveFile.length()} bytes" }

        var filesExtracted = 0
        var bytesExtracted = 0L

        BufferedInputStream(FileInputStream(archiveFile)).use { fileIn ->
            java.util.zip.GZIPInputStream(fileIn).use { gzIn ->
                TarArchiveInputStream(gzIn).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextEntry

                    while (entry != null) {
                        yield() // Allow cancellation

                        val currentEntry = entry!! // Capture for smart cast
                        val entryName = currentEntry.name
                        val entryPath = resolveEntryPath(destinationDir, entryName, config)

                        if (currentEntry.isDirectory) {
                            Files.createDirectories(entryPath)
                        } else if (currentEntry.isFile) {
                            Files.createDirectories(entryPath.parent)

                            if (config.overwriteExisting || !Files.exists(entryPath)) {
                                extractFile(tarIn, entryPath)
                                bytesExtracted += currentEntry.size
                                filesExtracted++

                                if (config.preservePermissions && IS_UNIX) {
                                    setUnixPermissions(entryPath, currentEntry.mode)
                                }

                                emit(
                                    ExtractionProgress(
                                        bytesExtracted = bytesExtracted,
                                        totalBytes = -1,
                                        currentFile = entryName,
                                        filesExtracted = filesExtracted,
                                        totalFiles = -1
                                    )
                                )

                                logger.trace { "Extracted: $entryName" }
                            }
                        }

                        entry = tarIn.nextEntry
                    }
                }
            }
        }

        logger.info { "TAR.GZ extraction complete: $filesExtracted files, $bytesExtracted bytes" }
    }

    // ==================== 7z Extraction ====================

    private fun extract7z(
        archivePath: Path,
        destinationDir: Path,
        config: ExtractionConfig
    ): Flow<ExtractionProgress> = flow {
        logger.debug { "7z archive: $archivePath" }

        var filesExtracted = 0
        var bytesExtracted = 0L

        SevenZFile.builder().setFile(archivePath.toFile()).get().use { sevenZFile ->
            // First pass: count total files for progress
            val entries = mutableListOf<SevenZArchiveEntry>()
            var entry: SevenZArchiveEntry? = sevenZFile.nextEntry

            while (entry != null) {
                entries.add(entry)
                entry = sevenZFile.nextEntry
            }

            val totalFiles = entries.count { !it.isDirectory }
            val totalBytes = entries.sumOf { it.size }

            logger.debug { "7z archive contains $totalFiles files, $totalBytes bytes uncompressed" }

            // Close and reopen for extraction (SevenZFile doesn't support reset)
            sevenZFile.close()

            SevenZFile.builder().setFile(archivePath.toFile()).get().use { extractFile ->
                entry = extractFile.nextEntry

                while (entry != null) {
                    yield() // Allow cancellation

                    val currentEntry = entry!! // Smart cast
                    val entryPath = resolveEntryPath(destinationDir, currentEntry.name, config)

                    if (currentEntry.isDirectory) {
                        Files.createDirectories(entryPath)
                    } else {
                        Files.createDirectories(entryPath.parent)

                        if (config.overwriteExisting || !Files.exists(entryPath)) {
                            extract7zEntry(extractFile, currentEntry, entryPath)
                            bytesExtracted += currentEntry.size
                            filesExtracted++

                            emit(
                                ExtractionProgress(
                                    bytesExtracted = bytesExtracted,
                                    totalBytes = totalBytes,
                                    currentFile = currentEntry.name,
                                    filesExtracted = filesExtracted,
                                    totalFiles = totalFiles
                                )
                            )

                            logger.trace { "Extracted: ${currentEntry.name}" }
                        }
                    }

                    entry = extractFile.nextEntry
                }
            }
        }

        logger.info { "7z extraction complete: $filesExtracted files, $bytesExtracted bytes" }
    }

    private fun extract7zEntry(
        sevenZFile: SevenZFile,
        entry: SevenZArchiveEntry,
        destination: Path
    ) {
        FileOutputStream(destination.toFile()).use { fos ->
            val content = ByteArray(entry.size.toInt())
            sevenZFile.read(content)
            fos.write(content)
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Resolves the destination path for an archive entry.
     * Handles path flattening if configured and archive has single root.
     */
    private fun resolveEntryPath(
        destinationDir: Path,
        entryName: String,
        config: ExtractionConfig
    ): Path {
        // Normalize path separators and remove leading slashes
        val normalizedName = entryName
            .replace('\\', '/')
            .trimStart('/')

        // Security: prevent path traversal attacks
        val resolved = destinationDir.resolve(normalizedName).normalize()
        if (!resolved.startsWith(destinationDir)) {
            throw ArchiveException(
                entryName,
                "Path traversal detected: entry '$entryName' escapes destination"
            )
        }

        return resolved
    }

    /**
     * Extracts a file from an input stream to the destination path.
     */
    private fun extractFile(inputStream: java.io.InputStream, destination: Path) {
        FileOutputStream(destination.toFile()).use { fos ->
            val buffer = ByteArray(8192)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                fos.write(buffer, 0, bytesRead)
            }
        }
    }

    /**
     * Sets Unix file permissions based on TAR entry mode.
     */
    private fun setUnixPermissions(path: Path, mode: Int) {
        try {
            val permissions = mutableSetOf<PosixFilePermission>()

            // Owner permissions
            if ((mode and 0b100_000_000) != 0) permissions.add(PosixFilePermission.OWNER_READ)
            if ((mode and 0b010_000_000) != 0) permissions.add(PosixFilePermission.OWNER_WRITE)
            if ((mode and 0b001_000_000) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE)

            // Group permissions
            if ((mode and 0b000_100_000) != 0) permissions.add(PosixFilePermission.GROUP_READ)
            if ((mode and 0b000_010_000) != 0) permissions.add(PosixFilePermission.GROUP_WRITE)
            if ((mode and 0b000_001_000) != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE)

            // Others permissions
            if ((mode and 0b000_000_100) != 0) permissions.add(PosixFilePermission.OTHERS_READ)
            if ((mode and 0b000_000_010) != 0) permissions.add(PosixFilePermission.OTHERS_WRITE)
            if ((mode and 0b000_000_001) != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE)

            Files.setPosixFilePermissions(path, permissions)
        } catch (e: UnsupportedOperationException) {
            // Windows doesn't support POSIX permissions
            logger.trace { "Cannot set POSIX permissions on this platform" }
        } catch (e: Exception) {
            logger.warn { "Failed to set permissions for $path: ${e.message}" }
        }
    }

    /**
     * Sets Unix permissions based on file name heuristics (for ZIP archives).
     */
    private fun setUnixPermissions(path: Path, entryName: String) {
        val fileName = path.fileName.toString().lowercase()

        // Make files executable if they look like binaries
        val isLikelyExecutable = fileName in FFMPEG_BINARIES ||
                entryName.contains("/bin/") ||
                BINARY_EXTENSIONS.any { fileName.endsWith(it) } ||
                (!fileName.contains('.') && path.parent?.fileName?.toString() == "bin")

        if (isLikelyExecutable) {
            try {
                val permissions = Files.getPosixFilePermissions(path).toMutableSet()
                permissions.add(PosixFilePermission.OWNER_EXECUTE)
                permissions.add(PosixFilePermission.GROUP_EXECUTE)
                Files.setPosixFilePermissions(path, permissions)
                logger.debug { "Set executable: $path" }
            } catch (e: Exception) {
                logger.trace { "Cannot set executable permission: ${e.message}" }
            }
        }
    }

    /**
     * Finds a single root directory if the destination contains only one subdirectory.
     */
    private fun findSingleRootOrSelf(directory: Path): Path {
        val contents = directory.toFile().listFiles() ?: return directory

        // If there's exactly one directory and no files, return that directory
        if (contents.size == 1 && contents[0].isDirectory) {
            logger.debug { "Found single root directory: ${contents[0].name}" }
            return contents[0].toPath()
        }

        return directory
    }

    /**
     * Recursively searches for a file by name.
     */
    private fun findFileRecursive(
        directory: File,
        targetNames: Set<String>,
        maxDepth: Int,
        currentDepth: Int
    ): File? {
        if (currentDepth > maxDepth || !directory.isDirectory) return null

        val files = directory.listFiles() ?: return null

        // First, check files in current directory
        for (file in files) {
            if (file.isFile && file.name in targetNames) {
                logger.debug { "Found binary: ${file.absolutePath}" }
                return file
            }
        }

        // Then recurse into subdirectories
        for (file in files) {
            if (file.isDirectory) {
                val found = findFileRecursive(file, targetNames, maxDepth, currentDepth + 1)
                if (found != null) return found
            }
        }

        return null
    }

    /**
     * Recursively finds all executables/binaries.
     */
    private fun findBinariesRecursive(
        directory: File,
        binaries: MutableMap<String, Path>,
        maxDepth: Int,
        currentDepth: Int
    ) {
        if (currentDepth > maxDepth || !directory.isDirectory) return

        val files = directory.listFiles() ?: return

        for (file in files) {
            if (file.isFile && isLikelyBinary(file)) {
                val name = file.nameWithoutExtension.lowercase()
                binaries[name] = file.toPath()
            } else if (file.isDirectory) {
                findBinariesRecursive(file, binaries, maxDepth, currentDepth + 1)
            }
        }
    }

    /**
     * Heuristic to determine if a file is likely an executable binary.
     */
    private fun isLikelyBinary(file: File): Boolean {
        val name = file.name.lowercase()

        // Check known binaries
        if (name in FFMPEG_BINARIES) return true

        // Check extensions on Windows
        if (IS_WINDOWS && BINARY_EXTENSIONS.any { name.endsWith(it) }) return true

        // On Unix, check if file is in a bin directory and has no extension
        if (IS_UNIX) {
            val parentName = file.parentFile?.name?.lowercase()
            if (parentName == "bin" && !name.contains('.')) {
                // Additional check: file should be executable
                return file.canExecute()
            }
        }

        return false
    }
}

/**
 * Supported archive types.
 */
enum class ArchiveType {
    ZIP,
    TAR_XZ,
    TAR_GZ,
    SEVEN_ZIP
}

/**
 * Exception thrown when archive extraction fails.
 *
 * @property archiveName The name/path of the archive that failed.
 * @property message Description of the failure.
 */
class ArchiveException(
    val archiveName: String,
    override val message: String
) : Exception("Archive extraction failed for '$archiveName': $message")
