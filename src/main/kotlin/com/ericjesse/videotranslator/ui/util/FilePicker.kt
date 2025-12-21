package com.ericjesse.videotranslator.ui.util

import androidx.compose.runtime.*
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import java.util.prefs.Preferences
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

private val logger = KotlinLogging.logger {}

/**
 * File filter configuration for file pickers.
 *
 * @property description Human-readable description (e.g., "Video Files")
 * @property extensions List of file extensions without dots (e.g., ["mp4", "mkv", "avi"])
 */
data class FileFilter(
    val description: String,
    val extensions: List<String>
) {
    /**
     * Creates a Swing FileNameExtensionFilter from this filter.
     */
    fun toSwingFilter(): FileNameExtensionFilter =
        FileNameExtensionFilter(description, *extensions.toTypedArray())

    /**
     * Creates a FilenameFilter for AWT FileDialog.
     */
    fun toAwtFilter(): FilenameFilter = FilenameFilter { _, name ->
        val lowerName = name.lowercase()
        extensions.any { ext -> lowerName.endsWith(".$ext") }
    }
}

/**
 * Types of file picker operations for directory persistence.
 */
enum class PickerType {
    DIRECTORY,
    FILE_OPEN,
    FILE_SAVE
}

/**
 * Operating system detection.
 */
private enum class OS {
    MACOS,
    WINDOWS,
    LINUX;

    companion object {
        val current: OS by lazy {
            val osName = System.getProperty("os.name").lowercase()
            when {
                osName.contains("mac") || osName.contains("darwin") -> MACOS
                osName.contains("win") -> WINDOWS
                else -> LINUX
            }
        }
    }
}

/**
 * Native file picker utility with platform-specific implementations.
 *
 * Uses:
 * - macOS: Native FileDialog for file operations, JFileChooser for directories
 * - Windows: JFileChooser (native look via LAF)
 * - Linux: JFileChooser
 *
 * Features:
 * - Remembers last used directory per picker type
 * - Supports file filters
 * - Thread-safe Swing operations
 */
object FilePicker {

    private val preferences: Preferences by lazy {
        Preferences.userNodeForPackage(FilePicker::class.java)
    }

    private const val PREF_LAST_DIR_PREFIX = "lastDir_"

    init {
        // Set native look and feel for better JFileChooser appearance
        try {
            if (OS.current == OS.WINDOWS) {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            }
        } catch (e: Exception) {
            logger.warn { "Failed to set system look and feel: ${e.message}" }
        }
    }

    /**
     * Opens a directory picker dialog.
     *
     * @param title Dialog title
     * @param initialDirectory Initial directory to show, or null to use last used
     * @return Selected directory path, or null if cancelled
     */
    fun pickDirectory(
        title: String = "Select Folder",
        initialDirectory: String? = null
    ): String? {
        val startDir = initialDirectory ?: getLastDirectory(PickerType.DIRECTORY)

        return runOnSwingThread {
            val result = when (OS.current) {
                OS.MACOS -> pickDirectoryMacOS(title, startDir)
                else -> pickDirectorySwing(title, startDir)
            }

            result?.also { saveLastDirectory(PickerType.DIRECTORY, it) }
        }
    }

    /**
     * Opens a file picker dialog for opening files.
     *
     * @param title Dialog title
     * @param filters File type filters (empty for all files)
     * @param initialDirectory Initial directory to show, or null to use last used
     * @return Selected file path, or null if cancelled
     */
    fun pickFile(
        title: String = "Open File",
        filters: List<FileFilter> = emptyList(),
        initialDirectory: String? = null
    ): String? {
        val startDir = initialDirectory ?: getLastDirectory(PickerType.FILE_OPEN)

        return runOnSwingThread {
            val result = when (OS.current) {
                OS.MACOS -> pickFileMacOS(title, filters, startDir, FileDialog.LOAD)
                else -> pickFileSwing(title, filters, startDir, isOpen = true)
            }

            result?.also {
                saveLastDirectory(PickerType.FILE_OPEN, File(it).parent ?: it)
            }
        }
    }

    /**
     * Opens a file picker dialog for saving files.
     *
     * @param title Dialog title
     * @param defaultName Default file name
     * @param filters File type filters (empty for all files)
     * @param initialDirectory Initial directory to show, or null to use last used
     * @return Selected file path, or null if cancelled
     */
    fun pickSaveLocation(
        title: String = "Save File",
        defaultName: String = "",
        filters: List<FileFilter> = emptyList(),
        initialDirectory: String? = null
    ): String? {
        val startDir = initialDirectory ?: getLastDirectory(PickerType.FILE_SAVE)

        return runOnSwingThread {
            val result = when (OS.current) {
                OS.MACOS -> pickFileMacOS(title, filters, startDir, FileDialog.SAVE, defaultName)
                else -> pickFileSwing(title, filters, startDir, isOpen = false, defaultName)
            }

            result?.also {
                saveLastDirectory(PickerType.FILE_SAVE, File(it).parent ?: it)
            }
        }
    }

    /**
     * Opens multiple file picker dialog.
     *
     * @param title Dialog title
     * @param filters File type filters
     * @param initialDirectory Initial directory
     * @return List of selected file paths, or empty if cancelled
     */
    fun pickMultipleFiles(
        title: String = "Select Files",
        filters: List<FileFilter> = emptyList(),
        initialDirectory: String? = null
    ): List<String> {
        val startDir = initialDirectory ?: getLastDirectory(PickerType.FILE_OPEN)

        return runOnSwingThread {
            val results = when (OS.current) {
                OS.MACOS -> pickMultipleFilesMacOS(title, filters, startDir)
                else -> pickMultipleFilesSwing(title, filters, startDir)
            }

            if (results.isNotEmpty()) {
                saveLastDirectory(PickerType.FILE_OPEN, File(results.first()).parent ?: results.first())
            }

            results
        } ?: emptyList()
    }

    // ========== macOS Implementations ==========

    private fun pickDirectoryMacOS(title: String, startDir: String?): String? {
        // macOS FileDialog doesn't support directory-only mode well
        // Use JFileChooser but with macOS-specific properties
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            dialog.directory = startDir ?: System.getProperty("user.home")
            dialog.isVisible = true

            return if (dialog.file != null) {
                File(dialog.directory, dialog.file).absolutePath
            } else null
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }

    private fun pickFileMacOS(
        title: String,
        filters: List<FileFilter>,
        startDir: String?,
        mode: Int,
        defaultName: String = ""
    ): String? {
        val dialog = FileDialog(null as Frame?, title, mode)
        dialog.directory = startDir ?: System.getProperty("user.home")

        if (defaultName.isNotEmpty()) {
            dialog.file = defaultName
        }

        // Apply file filter (FileDialog only supports one filter)
        if (filters.isNotEmpty()) {
            dialog.filenameFilter = createCombinedFilter(filters)
        }

        dialog.isVisible = true

        return if (dialog.file != null) {
            val selectedFile = File(dialog.directory, dialog.file)

            // For save dialogs, ensure proper extension
            if (mode == FileDialog.SAVE && filters.isNotEmpty()) {
                ensureExtension(selectedFile, filters.first())
            } else {
                selectedFile.absolutePath
            }
        } else null
    }

    private fun pickMultipleFilesMacOS(
        title: String,
        filters: List<FileFilter>,
        startDir: String?
    ): List<String> {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.directory = startDir ?: System.getProperty("user.home")
        dialog.isMultipleMode = true

        if (filters.isNotEmpty()) {
            dialog.filenameFilter = createCombinedFilter(filters)
        }

        dialog.isVisible = true

        return dialog.files?.map { it.absolutePath } ?: emptyList()
    }

    // ========== Swing/JFileChooser Implementations ==========

    private fun pickDirectorySwing(title: String, startDir: String?): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false

            startDir?.let { dir ->
                val file = File(dir)
                if (file.exists()) {
                    currentDirectory = file
                }
            }
        }

        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.absolutePath
        } else null
    }

    private fun pickFileSwing(
        title: String,
        filters: List<FileFilter>,
        startDir: String?,
        isOpen: Boolean,
        defaultName: String = ""
    ): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_ONLY

            startDir?.let { dir ->
                val file = File(dir)
                if (file.exists()) {
                    currentDirectory = file
                }
            }

            if (defaultName.isNotEmpty()) {
                selectedFile = File(currentDirectory, defaultName)
            }

            // Add file filters
            if (filters.isNotEmpty()) {
                isAcceptAllFileFilterUsed = filters.size > 1
                filters.forEach { filter ->
                    addChoosableFileFilter(filter.toSwingFilter())
                }
                fileFilter = filters.first().toSwingFilter()
            }
        }

        val result = if (isOpen) {
            chooser.showOpenDialog(null)
        } else {
            chooser.showSaveDialog(null)
        }

        return if (result == JFileChooser.APPROVE_OPTION) {
            val selectedFile = chooser.selectedFile

            // For save dialogs, ensure proper extension based on selected filter
            if (!isOpen && chooser.fileFilter is FileNameExtensionFilter) {
                val swingFilter = chooser.fileFilter as FileNameExtensionFilter
                val matchingFilter = filters.find { it.description == swingFilter.description }
                if (matchingFilter != null) {
                    ensureExtension(selectedFile, matchingFilter)
                } else {
                    selectedFile.absolutePath
                }
            } else {
                selectedFile.absolutePath
            }
        } else null
    }

    private fun pickMultipleFilesSwing(
        title: String,
        filters: List<FileFilter>,
        startDir: String?
    ): List<String> {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = true

            startDir?.let { dir ->
                val file = File(dir)
                if (file.exists()) {
                    currentDirectory = file
                }
            }

            if (filters.isNotEmpty()) {
                isAcceptAllFileFilterUsed = filters.size > 1
                filters.forEach { filter ->
                    addChoosableFileFilter(filter.toSwingFilter())
                }
                fileFilter = filters.first().toSwingFilter()
            }
        }

        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFiles.map { it.absolutePath }
        } else emptyList()
    }

    // ========== Helper Functions ==========

    /**
     * Combines multiple filters into a single FilenameFilter.
     */
    private fun createCombinedFilter(filters: List<FileFilter>): FilenameFilter {
        val allExtensions = filters.flatMap { it.extensions }.toSet()
        return FilenameFilter { _, name ->
            val lowerName = name.lowercase()
            allExtensions.any { ext -> lowerName.endsWith(".$ext") }
        }
    }

    /**
     * Ensures the file has the correct extension.
     */
    private fun ensureExtension(file: File, filter: FileFilter): String {
        val path = file.absolutePath
        val hasValidExtension = filter.extensions.any { ext ->
            path.lowercase().endsWith(".$ext")
        }

        return if (hasValidExtension) {
            path
        } else {
            // Add the first extension from the filter
            "$path.${filter.extensions.first()}"
        }
    }

    /**
     * Gets the last used directory for a picker type.
     */
    private fun getLastDirectory(type: PickerType): String? {
        return try {
            preferences.get("$PREF_LAST_DIR_PREFIX${type.name}", null)?.let { path ->
                if (File(path).exists()) path else null
            }
        } catch (e: Exception) {
            logger.warn { "Failed to get last directory: ${e.message}" }
            null
        }
    }

    /**
     * Saves the last used directory for a picker type.
     */
    private fun saveLastDirectory(type: PickerType, path: String) {
        try {
            preferences.put("$PREF_LAST_DIR_PREFIX${type.name}", path)
            preferences.flush()
        } catch (e: Exception) {
            logger.warn { "Failed to save last directory: ${e.message}" }
        }
    }

    /**
     * Runs a block on the Swing EDT and waits for the result.
     */
    private fun <T> runOnSwingThread(block: () -> T): T? {
        var result: T? = null
        var exception: Exception? = null

        if (SwingUtilities.isEventDispatchThread()) {
            result = block()
        } else {
            SwingUtilities.invokeAndWait {
                try {
                    result = block()
                } catch (e: Exception) {
                    exception = e
                }
            }
        }

        exception?.let {
            logger.error(it) { "Error in file picker: ${it.message}" }
            throw it
        }

        return result
    }
}

// ========== Composable Wrappers ==========

/**
 * State holder for directory picker launcher.
 */
class DirectoryPickerLauncher(
    private val title: String,
    private val initialDirectory: String?,
    private val onResult: (String?) -> Unit
) {
    /**
     * Launches the directory picker dialog.
     */
    fun launch() {
        val result = FilePicker.pickDirectory(
            title = title,
            initialDirectory = initialDirectory
        )
        onResult(result)
    }
}

/**
 * State holder for file picker launcher.
 */
class FilePickerLauncher(
    private val title: String,
    private val filters: List<FileFilter>,
    private val initialDirectory: String?,
    private val multiSelect: Boolean,
    private val onResult: (List<String>) -> Unit
) {
    /**
     * Launches the file picker dialog.
     */
    fun launch() {
        val results = if (multiSelect) {
            FilePicker.pickMultipleFiles(
                title = title,
                filters = filters,
                initialDirectory = initialDirectory
            )
        } else {
            val result = FilePicker.pickFile(
                title = title,
                filters = filters,
                initialDirectory = initialDirectory
            )
            if (result != null) listOf(result) else emptyList()
        }
        onResult(results)
    }
}

/**
 * State holder for save file picker launcher.
 */
class SaveFilePickerLauncher(
    private val title: String,
    private val defaultName: String,
    private val filters: List<FileFilter>,
    private val initialDirectory: String?,
    private val onResult: (String?) -> Unit
) {
    /**
     * Launches the save file picker dialog.
     */
    fun launch() {
        val result = FilePicker.pickSaveLocation(
            title = title,
            defaultName = defaultName,
            filters = filters,
            initialDirectory = initialDirectory
        )
        onResult(result)
    }
}

/**
 * Creates and remembers a directory picker launcher.
 *
 * @param title Dialog title
 * @param initialDirectory Initial directory to show
 * @param onResult Callback with selected path or null if cancelled
 * @return DirectoryPickerLauncher to launch the picker
 *
 * Example usage:
 * ```
 * val directoryPicker = rememberDirectoryPickerLauncher(
 *     title = "Select Output Folder",
 *     onResult = { path ->
 *         path?.let { selectedDirectory = it }
 *     }
 * )
 *
 * Button(onClick = { directoryPicker.launch() }) {
 *     Text("Browse")
 * }
 * ```
 */
@Composable
fun rememberDirectoryPickerLauncher(
    title: String = "Select Folder",
    initialDirectory: String? = null,
    onResult: (String?) -> Unit
): DirectoryPickerLauncher {
    val currentOnResult by rememberUpdatedState(onResult)
    val currentInitialDirectory by rememberUpdatedState(initialDirectory)

    return remember(title) {
        DirectoryPickerLauncher(
            title = title,
            initialDirectory = currentInitialDirectory,
            onResult = { currentOnResult(it) }
        )
    }
}

/**
 * Creates and remembers a file picker launcher.
 *
 * @param title Dialog title
 * @param filters File type filters
 * @param initialDirectory Initial directory to show
 * @param multiSelect Whether to allow multiple file selection
 * @param onResult Callback with list of selected paths (empty if cancelled)
 * @return FilePickerLauncher to launch the picker
 *
 * Example usage:
 * ```
 * val filePicker = rememberFilePickerLauncher(
 *     title = "Select Video",
 *     filters = listOf(
 *         FileFilter("Video Files", listOf("mp4", "mkv", "avi"))
 *     ),
 *     onResult = { paths ->
 *         paths.firstOrNull()?.let { selectedFile = it }
 *     }
 * )
 *
 * Button(onClick = { filePicker.launch() }) {
 *     Text("Open")
 * }
 * ```
 */
@Composable
fun rememberFilePickerLauncher(
    title: String = "Open File",
    filters: List<FileFilter> = emptyList(),
    initialDirectory: String? = null,
    multiSelect: Boolean = false,
    onResult: (List<String>) -> Unit
): FilePickerLauncher {
    val currentOnResult by rememberUpdatedState(onResult)
    val currentInitialDirectory by rememberUpdatedState(initialDirectory)

    return remember(title, filters, multiSelect) {
        FilePickerLauncher(
            title = title,
            filters = filters,
            initialDirectory = currentInitialDirectory,
            multiSelect = multiSelect,
            onResult = { currentOnResult(it) }
        )
    }
}

/**
 * Creates and remembers a save file picker launcher.
 *
 * @param title Dialog title
 * @param defaultName Default file name
 * @param filters File type filters
 * @param initialDirectory Initial directory to show
 * @param onResult Callback with selected path or null if cancelled
 * @return SaveFilePickerLauncher to launch the picker
 *
 * Example usage:
 * ```
 * val savePicker = rememberSaveFilePickerLauncher(
 *     title = "Save Subtitles",
 *     defaultName = "subtitles.srt",
 *     filters = listOf(
 *         FileFilter("SRT Files", listOf("srt"))
 *     ),
 *     onResult = { path ->
 *         path?.let { saveSubtitles(it) }
 *     }
 * )
 *
 * Button(onClick = { savePicker.launch() }) {
 *     Text("Save")
 * }
 * ```
 */
@Composable
fun rememberSaveFilePickerLauncher(
    title: String = "Save File",
    defaultName: String = "",
    filters: List<FileFilter> = emptyList(),
    initialDirectory: String? = null,
    onResult: (String?) -> Unit
): SaveFilePickerLauncher {
    val currentOnResult by rememberUpdatedState(onResult)
    val currentInitialDirectory by rememberUpdatedState(initialDirectory)

    return remember(title, defaultName, filters) {
        SaveFilePickerLauncher(
            title = title,
            defaultName = defaultName,
            filters = filters,
            initialDirectory = currentInitialDirectory,
            onResult = { currentOnResult(it) }
        )
    }
}

// ========== Common File Filters ==========

/**
 * Pre-defined file filters for common use cases.
 */
object CommonFileFilters {
    val VIDEO = FileFilter(
        description = "Video Files",
        extensions = listOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv")
    )

    val AUDIO = FileFilter(
        description = "Audio Files",
        extensions = listOf("mp3", "wav", "flac", "aac", "ogg", "m4a")
    )

    val SUBTITLE = FileFilter(
        description = "Subtitle Files",
        extensions = listOf("srt", "vtt", "ass", "ssa", "sub")
    )

    val SRT = FileFilter(
        description = "SRT Files",
        extensions = listOf("srt")
    )

    val JSON = FileFilter(
        description = "JSON Files",
        extensions = listOf("json")
    )

    val TEXT = FileFilter(
        description = "Text Files",
        extensions = listOf("txt", "text")
    )

    val IMAGE = FileFilter(
        description = "Image Files",
        extensions = listOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
    )

    val ALL = FileFilter(
        description = "All Files",
        extensions = listOf("*")
    )
}
