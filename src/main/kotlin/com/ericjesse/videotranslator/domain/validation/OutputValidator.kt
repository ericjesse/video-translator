package com.ericjesse.videotranslator.domain.validation

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isWritable

private val logger = KotlinLogging.logger {}

/**
 * Thresholds for output validation.
 */
object OutputThresholds {
    /** Maximum path length for Windows */
    const val WINDOWS_MAX_PATH = 260

    /** Maximum filename length for most filesystems */
    const val MAX_FILENAME_LENGTH = 255

    /** Reserved filenames on Windows */
    val WINDOWS_RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    /** Characters not allowed in filenames */
    val INVALID_FILENAME_CHARS = charArrayOf(
        '<', '>', ':', '"', '/', '\\', '|', '?', '*',
        '\u0000', '\u0001', '\u0002', '\u0003', '\u0004', '\u0005', '\u0006', '\u0007',
        '\u0008', '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u000E', '\u000F',
        '\u0010', '\u0011', '\u0012', '\u0013', '\u0014', '\u0015', '\u0016', '\u0017',
        '\u0018', '\u0019', '\u001A', '\u001B', '\u001C', '\u001D', '\u001E', '\u001F'
    )

    /** Additional invalid chars on Windows */
    val WINDOWS_INVALID_CHARS = charArrayOf(':', '*', '?', '"', '<', '>', '|')
}

/**
 * Actions for handling existing files.
 */
enum class FileExistsAction {
    /** Prompt user for action */
    ASK,
    /** Overwrite existing file */
    OVERWRITE,
    /** Rename new file with suffix */
    RENAME,
    /** Skip and return error */
    SKIP
}

/**
 * Result of output path validation.
 */
sealed class OutputValidationResult {
    /** Path is valid */
    data class Valid(
        val resolvedPath: Path,
        val sanitizedFilename: String? = null
    ) : OutputValidationResult()

    /** Path is valid but has warnings */
    data class ValidWithWarnings(
        val resolvedPath: Path,
        val warnings: List<OutputWarning>,
        val sanitizedFilename: String? = null
    ) : OutputValidationResult()

    /** Path requires user action */
    data class RequiresAction(
        val action: OutputAction,
        val currentPath: Path
    ) : OutputValidationResult()

    /** Path is invalid */
    data class Invalid(
        val error: OutputError
    ) : OutputValidationResult()

    fun isValid(): Boolean = this is Valid || this is ValidWithWarnings
}

/**
 * Actions that require user input.
 */
sealed class OutputAction {
    abstract val message: String
    abstract val options: List<String>

    /** File already exists */
    data class FileExists(
        val existingFile: Path,
        val proposedAlternative: Path?
    ) : OutputAction() {
        override val message = "File already exists: ${existingFile.fileName}"
        override val options = listOf("Overwrite", "Rename", "Cancel")
    }

    /** Directory doesn't exist */
    data class DirectoryMissing(
        val directory: Path
    ) : OutputAction() {
        override val message = "Directory does not exist: $directory"
        override val options = listOf("Create Directory", "Choose Different Location", "Cancel")
    }
}

/**
 * Types of output warnings.
 */
sealed class OutputWarning {
    abstract val message: String
    abstract val suggestion: String?

    /** Filename was sanitized */
    data class FilenameSanitized(
        val original: String,
        val sanitized: String,
        val removedChars: List<Char>
    ) : OutputWarning() {
        override val message = "Filename contained invalid characters and was sanitized"
        override val suggestion = "Original: '$original' -> '$sanitized'"
    }

    /** Filename was truncated */
    data class FilenameTruncated(
        val original: String,
        val truncated: String,
        val maxLength: Int
    ) : OutputWarning() {
        override val message = "Filename was too long and was truncated"
        override val suggestion = "Reduced from ${original.length} to $maxLength characters"
    }

    /** Path is long (Windows compatibility) */
    data class LongPath(
        val length: Int,
        val limit: Int
    ) : OutputWarning() {
        override val message = "Path is $length characters (Windows limit: $limit)"
        override val suggestion = "This may cause issues on Windows systems."
    }

    /** Low disk space on target */
    data class LowDiskSpace(
        val availableMB: Long,
        val estimatedRequiredMB: Long
    ) : OutputWarning() {
        override val message = "Low disk space: ${availableMB}MB available, ~${estimatedRequiredMB}MB needed"
        override val suggestion = "Consider freeing up space or using a different location."
    }

    /** Network/external drive */
    data class NetworkLocation(
        val path: String
    ) : OutputWarning() {
        override val message = "Output location appears to be on a network drive"
        override val suggestion = "Network locations may be slower. Consider using local storage."
    }
}

/**
 * Types of output errors.
 */
sealed class OutputError {
    abstract val code: String
    abstract val message: String
    abstract val suggestion: String?

    /** Directory doesn't exist and can't be created */
    data class DirectoryNotFound(
        val directory: String
    ) : OutputError() {
        override val code = "DIRECTORY_NOT_FOUND"
        override val message = "Directory does not exist: $directory"
        override val suggestion = "Choose an existing directory or create it first."
    }

    /** Permission denied */
    data class PermissionDenied(
        val path: String,
        val operation: String
    ) : OutputError() {
        override val code = "PERMISSION_DENIED"
        override val message = "Permission denied: Cannot $operation '$path'"
        override val suggestion = "Check folder permissions or choose a different location."
    }

    /** Invalid path format */
    data class InvalidPath(
        val path: String,
        val reason: String
    ) : OutputError() {
        override val code = "INVALID_PATH"
        override val message = "Invalid path: $reason"
        override val suggestion = "Use a valid file path without special characters."
    }

    /** Path too long */
    data class PathTooLong(
        val path: String,
        val length: Int,
        val maxLength: Int
    ) : OutputError() {
        override val code = "PATH_TOO_LONG"
        override val message = "Path exceeds maximum length: $length characters (max: $maxLength)"
        override val suggestion = "Use a shorter path or filename."
    }

    /** Reserved filename */
    data class ReservedFilename(
        val filename: String
    ) : OutputError() {
        override val code = "RESERVED_FILENAME"
        override val message = "'$filename' is a reserved system name"
        override val suggestion = "Choose a different filename."
    }

    /** Disk full */
    data class DiskFull(
        val availableMB: Long,
        val requiredMB: Long
    ) : OutputError() {
        override val code = "DISK_FULL"
        override val message = "Insufficient disk space: ${availableMB}MB available, ${requiredMB}MB required"
        override val suggestion = "Free up disk space or choose a different location."
    }

    /** Write error */
    data class WriteError(
        val path: String,
        val reason: String?
    ) : OutputError() {
        override val code = "WRITE_ERROR"
        override val message = "Cannot write to '$path': ${reason ?: "unknown error"}"
        override val suggestion = "Check if the file is in use or the disk is write-protected."
    }

    /** File locked */
    data class FileLocked(
        val path: String
    ) : OutputError() {
        override val code = "FILE_LOCKED"
        override val message = "File is locked or in use: $path"
        override val suggestion = "Close any applications using this file and try again."
    }
}

/**
 * Validates output paths and filenames.
 */
class OutputValidator {
    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")

    /**
     * Validates an output path.
     *
     * @param directory Output directory path.
     * @param filename Desired filename.
     * @param fileExistsAction How to handle existing files.
     * @param estimatedSizeMB Estimated output file size for disk space check.
     * @return OutputValidationResult indicating validity and any issues.
     */
    fun validateOutput(
        directory: String,
        filename: String,
        fileExistsAction: FileExistsAction = FileExistsAction.ASK,
        estimatedSizeMB: Long = 0
    ): OutputValidationResult {
        val warnings = mutableListOf<OutputWarning>()

        // Validate directory
        val dirPath: Path
        try {
            dirPath = Path.of(directory)
        } catch (e: InvalidPathException) {
            return OutputValidationResult.Invalid(
                OutputError.InvalidPath(directory, "Invalid directory path: ${e.message}")
            )
        }

        // Check if directory exists
        if (!dirPath.exists()) {
            return OutputValidationResult.RequiresAction(
                OutputAction.DirectoryMissing(dirPath),
                dirPath
            )
        }

        // Check if it's actually a directory
        if (!dirPath.isDirectory()) {
            return OutputValidationResult.Invalid(
                OutputError.InvalidPath(directory, "Path is not a directory")
            )
        }

        // Check write permission
        if (!dirPath.isWritable()) {
            return OutputValidationResult.Invalid(
                OutputError.PermissionDenied(directory, "write to directory")
            )
        }

        // Sanitize filename
        val (sanitizedFilename, filenameWarnings) = sanitizeFilename(filename)
        warnings.addAll(filenameWarnings)

        // Build full path
        val fullPath: Path
        try {
            fullPath = dirPath.resolve(sanitizedFilename)
        } catch (e: InvalidPathException) {
            return OutputValidationResult.Invalid(
                OutputError.InvalidPath("$directory/$sanitizedFilename", "Invalid path: ${e.message}")
            )
        }

        // Check path length (Windows)
        val pathString = fullPath.toString()
        if (isWindows && pathString.length > OutputThresholds.WINDOWS_MAX_PATH) {
            return OutputValidationResult.Invalid(
                OutputError.PathTooLong(
                    pathString,
                    pathString.length,
                    OutputThresholds.WINDOWS_MAX_PATH
                )
            )
        } else if (pathString.length > OutputThresholds.WINDOWS_MAX_PATH) {
            // Warn for cross-platform compatibility
            warnings.add(OutputWarning.LongPath(
                pathString.length,
                OutputThresholds.WINDOWS_MAX_PATH
            ))
        }

        // Check if file exists
        if (fullPath.exists()) {
            when (fileExistsAction) {
                FileExistsAction.ASK -> {
                    val alternative = generateAlternativePath(fullPath)
                    return OutputValidationResult.RequiresAction(
                        OutputAction.FileExists(fullPath, alternative),
                        fullPath
                    )
                }
                FileExistsAction.SKIP -> {
                    return OutputValidationResult.Invalid(
                        OutputError.WriteError(pathString, "File already exists")
                    )
                }
                FileExistsAction.OVERWRITE -> {
                    // Continue with validation
                }
                FileExistsAction.RENAME -> {
                    val alternative = generateAlternativePath(fullPath)
                    if (alternative != null) {
                        return validateOutput(
                            directory,
                            alternative.fileName.toString(),
                            FileExistsAction.SKIP, // Prevent infinite recursion
                            estimatedSizeMB
                        )
                    }
                }
            }
        }

        // Check disk space
        if (estimatedSizeMB > 0) {
            val diskSpaceCheck = checkDiskSpace(dirPath, estimatedSizeMB)
            when (diskSpaceCheck) {
                is DiskSpaceCheck.Insufficient -> {
                    return OutputValidationResult.Invalid(
                        OutputError.DiskFull(diskSpaceCheck.availableMB, estimatedSizeMB)
                    )
                }
                is DiskSpaceCheck.Low -> {
                    warnings.add(OutputWarning.LowDiskSpace(
                        diskSpaceCheck.availableMB,
                        estimatedSizeMB
                    ))
                }
                is DiskSpaceCheck.Sufficient -> { /* OK */ }
            }
        }

        // Check for network location
        if (isNetworkPath(pathString)) {
            warnings.add(OutputWarning.NetworkLocation(pathString))
        }

        return if (warnings.isEmpty()) {
            OutputValidationResult.Valid(
                resolvedPath = fullPath,
                sanitizedFilename = if (sanitizedFilename != filename) sanitizedFilename else null
            )
        } else {
            OutputValidationResult.ValidWithWarnings(
                resolvedPath = fullPath,
                warnings = warnings,
                sanitizedFilename = if (sanitizedFilename != filename) sanitizedFilename else null
            )
        }
    }

    /**
     * Sanitizes a filename by removing/replacing invalid characters.
     *
     * @param filename Original filename.
     * @return Pair of sanitized filename and any warnings.
     */
    fun sanitizeFilename(filename: String): Pair<String, List<OutputWarning>> {
        val warnings = mutableListOf<OutputWarning>()
        var sanitized = filename
        val removedChars = mutableListOf<Char>()

        // Get invalid chars based on platform
        val invalidChars = if (isWindows) {
            OutputThresholds.INVALID_FILENAME_CHARS + OutputThresholds.WINDOWS_INVALID_CHARS
        } else {
            OutputThresholds.INVALID_FILENAME_CHARS
        }

        // Remove invalid characters
        for (char in invalidChars) {
            if (sanitized.contains(char)) {
                removedChars.add(char)
                sanitized = sanitized.replace(char, '_')
            }
        }

        // Check for reserved names (Windows)
        if (isWindows) {
            val nameWithoutExt = sanitized.substringBeforeLast(".")
            if (nameWithoutExt.uppercase() in OutputThresholds.WINDOWS_RESERVED_NAMES) {
                sanitized = "_$sanitized"
            }
        }

        // Remove leading/trailing spaces and dots
        sanitized = sanitized.trim().trimEnd('.')

        // Ensure we have a valid filename
        if (sanitized.isEmpty() || sanitized == "." || sanitized == "..") {
            sanitized = "output"
        }

        // Truncate if too long
        val maxLength = OutputThresholds.MAX_FILENAME_LENGTH
        if (sanitized.length > maxLength) {
            val extension = sanitized.substringAfterLast(".", "")
            val nameWithoutExt = sanitized.substringBeforeLast(".")
            val maxNameLength = maxLength - extension.length - 1
            sanitized = if (extension.isNotEmpty()) {
                "${nameWithoutExt.take(maxNameLength)}.$extension"
            } else {
                nameWithoutExt.take(maxLength)
            }
            warnings.add(OutputWarning.FilenameTruncated(filename, sanitized, maxLength))
        }

        // Add warning if chars were removed
        if (removedChars.isNotEmpty()) {
            warnings.add(OutputWarning.FilenameSanitized(filename, sanitized, removedChars))
        }

        return sanitized to warnings
    }

    /**
     * Generates an alternative filename when file exists.
     */
    fun generateAlternativePath(path: Path): Path? {
        val parent = path.parent ?: return null
        val filename = path.fileName.toString()
        val nameWithoutExt = filename.substringBeforeLast(".")
        val extension = filename.substringAfterLast(".", "")

        for (i in 1..99) {
            val newFilename = if (extension.isNotEmpty()) {
                "${nameWithoutExt}_$i.$extension"
            } else {
                "${nameWithoutExt}_$i"
            }
            val newPath = parent.resolve(newFilename)
            if (!newPath.exists()) {
                return newPath
            }
        }

        // Fallback with timestamp
        val timestamp = System.currentTimeMillis()
        val newFilename = if (extension.isNotEmpty()) {
            "${nameWithoutExt}_$timestamp.$extension"
        } else {
            "${nameWithoutExt}_$timestamp"
        }
        return parent.resolve(newFilename)
    }

    /**
     * Creates output directory if it doesn't exist.
     *
     * @param directory Directory path to create.
     * @return True if directory exists or was created, false otherwise.
     */
    fun ensureDirectoryExists(directory: Path): OutputValidationResult {
        return try {
            if (!directory.exists()) {
                Files.createDirectories(directory)
            }
            OutputValidationResult.Valid(directory)
        } catch (e: SecurityException) {
            OutputValidationResult.Invalid(
                OutputError.PermissionDenied(directory.toString(), "create directory")
            )
        } catch (e: Exception) {
            OutputValidationResult.Invalid(
                OutputError.WriteError(directory.toString(), e.message)
            )
        }
    }

    /**
     * Checks available disk space.
     */
    private fun checkDiskSpace(directory: Path, requiredMB: Long): DiskSpaceCheck {
        return try {
            val store = Files.getFileStore(directory)
            val availableBytes = store.usableSpace
            val availableMB = availableBytes / (1024 * 1024)

            when {
                availableMB < requiredMB -> DiskSpaceCheck.Insufficient(availableMB)
                availableMB < requiredMB * 2 -> DiskSpaceCheck.Low(availableMB)
                else -> DiskSpaceCheck.Sufficient(availableMB)
            }
        } catch (e: Exception) {
            logger.warn { "Could not check disk space: ${e.message}" }
            DiskSpaceCheck.Sufficient(Long.MAX_VALUE) // Assume OK if we can't check
        }
    }

    /**
     * Checks if path is a network location.
     */
    private fun isNetworkPath(path: String): Boolean {
        return path.startsWith("\\\\") || // UNC path
               path.startsWith("//") ||   // Unix network path
               path.matches(Regex("^[a-zA-Z]:\\\\\\\\.*")) || // Mapped network drive pattern
               path.contains("://") // URL-style path
    }

    private sealed class DiskSpaceCheck {
        data class Sufficient(val availableMB: Long) : DiskSpaceCheck()
        data class Low(val availableMB: Long) : DiskSpaceCheck()
        data class Insufficient(val availableMB: Long) : DiskSpaceCheck()
    }

    companion object {
        /**
         * Generates a safe filename from a video title.
         */
        fun safeFilenameFromTitle(title: String, extension: String = "mp4"): String {
            val validator = OutputValidator()
            val baseFilename = title
                .replace(Regex("[^a-zA-Z0-9\\s_-]"), "")
                .replace(Regex("\\s+"), "_")
                .take(100)
                .trim('_')
                .ifEmpty { "video" }

            val (sanitized, _) = validator.sanitizeFilename("$baseFilename.$extension")
            return sanitized
        }

        /**
         * Generates output filename for translated video.
         */
        fun generateTranslatedFilename(
            originalTitle: String,
            targetLanguage: String,
            extension: String = "mp4"
        ): String {
            val safeTitle = originalTitle
                .replace(Regex("[^a-zA-Z0-9\\s_-]"), "")
                .replace(Regex("\\s+"), "_")
                .take(80)
                .trim('_')
                .ifEmpty { "video" }

            return "${safeTitle}_${targetLanguage}.$extension"
        }
    }
}

/**
 * Extension to validate an output file path.
 */
fun File.validateAsOutput(estimatedSizeMB: Long = 0): OutputValidationResult {
    return OutputValidator().validateOutput(
        directory = this.parent ?: ".",
        filename = this.name,
        estimatedSizeMB = estimatedSizeMB
    )
}
