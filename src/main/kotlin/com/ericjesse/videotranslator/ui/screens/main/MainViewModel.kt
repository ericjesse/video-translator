package com.ericjesse.videotranslator.ui.screens.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.domain.model.*
import com.ericjesse.videotranslator.infrastructure.config.AppSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import javax.swing.JFileChooser

private val logger = KotlinLogging.logger {}

/**
 * YouTube URL validation regex.
 * Matches:
 * - https://www.youtube.com/watch?v=VIDEO_ID
 * - https://youtube.com/watch?v=VIDEO_ID
 * - https://youtu.be/VIDEO_ID
 * - https://www.youtube.com/shorts/VIDEO_ID
 * - http variants of the above
 */
private val YOUTUBE_URL_REGEX = Regex(
    """^(https?://)?(www\.)?(youtube\.com/watch\?v=|youtu\.be/|youtube\.com/shorts/)[\w-]+.*$"""
)

/**
 * Extracts YouTube video ID from various URL formats.
 */
private val VIDEO_ID_REGEX = Regex(
    """(?:youtube\.com/watch\?v=|youtu\.be/|youtube\.com/shorts/)([\w-]+)"""
)

/**
 * State for the main screen.
 *
 * @property youtubeUrl The YouTube video URL entered by the user.
 * @property urlError Error message for URL validation, or null if valid.
 * @property sourceLanguage Source language for translation, or null for auto-detect.
 * @property targetLanguage Target language for translation.
 * @property subtitleType Type of subtitle output (soft or burned-in).
 * @property burnedInStyle Styling options for burned-in subtitles.
 * @property exportSrt Whether to also export a separate SRT file.
 * @property outputDirectory Directory to save output files.
 * @property isValidating Whether URL validation/video info fetch is in progress.
 * @property isFetchingVideoInfo Whether video info is being fetched.
 * @property videoInfo Fetched video information, or null if not yet fetched.
 * @property videoInfoError Error message from video info fetch, or null.
 */
data class MainScreenState(
    val youtubeUrl: String = "",
    val urlError: String? = null,
    val sourceLanguage: Language? = null,
    val targetLanguage: Language = Language.ENGLISH,
    val subtitleType: SubtitleType = SubtitleType.SOFT,
    val burnedInStyle: BurnedInSubtitleStyle = BurnedInSubtitleStyle(),
    val exportSrt: Boolean = false,
    val outputDirectory: String = "",
    val isValidating: Boolean = false,
    val isFetchingVideoInfo: Boolean = false,
    val videoInfo: VideoInfo? = null,
    val videoInfoError: String? = null
) {
    /**
     * Whether the form is valid and ready for translation.
     */
    val isFormValid: Boolean
        get() = youtubeUrl.isNotBlank() &&
                urlError == null &&
                outputDirectory.isNotBlank() &&
                !isValidating

    /**
     * Whether a translation job can be started.
     */
    val canTranslate: Boolean
        get() = isFormValid && !isFetchingVideoInfo
}

/**
 * ViewModel for the main screen.
 *
 * Manages form state, validation, and persistence of user preferences.
 *
 * @property appModule Application module for accessing services.
 * @property scope Coroutine scope for async operations.
 */
class MainViewModel(
    private val appModule: AppModule,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val configManager = appModule.configManager
    private val videoDownloader = appModule.videoDownloader
    private val i18n = appModule.i18nManager

    /**
     * Current screen state.
     */
    var state by mutableStateOf(MainScreenState())
        private set

    private var videoInfoJob: Job? = null

    init {
        loadDefaults()
    }

    /**
     * Loads default values from settings.
     */
    private fun loadDefaults() {
        val settings = configManager.getSettings()

        state = state.copy(
            sourceLanguage = settings.translation.defaultSourceLanguage?.let { Language.fromCode(it) },
            targetLanguage = Language.fromCode(settings.translation.defaultTargetLanguage) ?: Language.ENGLISH,
            subtitleType = when (settings.subtitle.defaultOutputMode) {
                "soft" -> SubtitleType.SOFT
                "hard", "burned_in" -> SubtitleType.BURNED_IN
                else -> SubtitleType.SOFT
            },
            burnedInStyle = BurnedInSubtitleStyle(
                fontSize = settings.subtitle.burnedIn.fontSize,
                fontColor = settings.subtitle.burnedIn.fontColor,
                backgroundColor = BackgroundColor.entries.find {
                    it.hex == settings.subtitle.burnedIn.backgroundColor
                } ?: BackgroundColor.NONE,
                backgroundOpacity = settings.subtitle.burnedIn.backgroundOpacity
            ),
            exportSrt = settings.subtitle.alwaysExportSrt,
            outputDirectory = settings.ui.defaultOutputDirectory.ifEmpty {
                System.getProperty("user.home") + File.separator + "Videos"
            }
        )

        logger.debug { "Loaded defaults from settings" }
    }

    /**
     * Called when the YouTube URL changes.
     * Validates the URL format and optionally fetches video info.
     */
    fun onUrlChanged(url: String) {
        // Cancel any pending video info fetch
        videoInfoJob?.cancel()

        val trimmedUrl = url.trim()

        // Clear previous state
        state = state.copy(
            youtubeUrl = trimmedUrl,
            urlError = null,
            videoInfo = null,
            videoInfoError = null,
            isValidating = false
        )

        // Validate if non-empty
        if (trimmedUrl.isNotEmpty()) {
            validateUrl(trimmedUrl)
        }
    }

    /**
     * Validates the YouTube URL format.
     */
    private fun validateUrl(url: String) {
        if (!YOUTUBE_URL_REGEX.matches(url)) {
            state = state.copy(
                urlError = i18n["validation.invalidUrl"]
            )
            return
        }

        // URL is valid format
        state = state.copy(urlError = null)

        // Optionally fetch video info in background
        fetchVideoInfo(url)
    }

    /**
     * Fetches video information for the given URL.
     */
    private fun fetchVideoInfo(url: String) {
        videoInfoJob?.cancel()
        videoInfoJob = scope.launch {
            state = state.copy(isFetchingVideoInfo = true)

            try {
                val videoInfo = videoDownloader.fetchVideoInfo(url)
                state = state.copy(
                    videoInfo = videoInfo,
                    videoInfoError = null,
                    isFetchingVideoInfo = false
                )
                logger.info { "Fetched video info: ${videoInfo.title}" }
            } catch (e: Exception) {
                logger.warn { "Failed to fetch video info: ${e.message}" }
                state = state.copy(
                    videoInfoError = e.message,
                    isFetchingVideoInfo = false
                )
                // Don't set urlError here - the URL format is valid,
                // we just couldn't fetch info (might be network issue)
            }
        }
    }

    /**
     * Called when the paste button is clicked.
     * Reads URL from system clipboard.
     */
    fun onPasteClicked() {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val contents = clipboard.getContents(null)

            if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                val text = contents.getTransferData(DataFlavor.stringFlavor) as String
                onUrlChanged(text)
                logger.debug { "Pasted URL from clipboard" }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to read clipboard: ${e.message}" }
        }
    }

    /**
     * Called when the source language changes.
     */
    fun onSourceLanguageChanged(language: Language?) {
        state = state.copy(sourceLanguage = language)
        persistLanguageSettings()
    }

    /**
     * Called when the target language changes.
     */
    fun onTargetLanguageChanged(language: Language) {
        state = state.copy(targetLanguage = language)
        persistLanguageSettings()
    }

    /**
     * Called when the subtitle type changes.
     */
    fun onSubtitleTypeChanged(type: SubtitleType) {
        state = state.copy(subtitleType = type)
    }

    /**
     * Called when burned-in style options change.
     */
    fun onBurnedInStyleChanged(style: BurnedInSubtitleStyle) {
        state = state.copy(burnedInStyle = style)
    }

    /**
     * Convenience method to update just the background color.
     */
    fun onBackgroundColorChanged(color: BackgroundColor) {
        state = state.copy(
            burnedInStyle = state.burnedInStyle.copy(backgroundColor = color)
        )
    }

    /**
     * Convenience method to update just the background opacity.
     */
    fun onBackgroundOpacityChanged(opacity: Float) {
        state = state.copy(
            burnedInStyle = state.burnedInStyle.copy(backgroundOpacity = opacity)
        )
    }

    /**
     * Called when the export SRT option changes.
     */
    fun onExportSrtChanged(export: Boolean) {
        state = state.copy(exportSrt = export)
    }

    /**
     * Called when the output directory changes.
     */
    fun onOutputDirectoryChanged(directory: String) {
        state = state.copy(outputDirectory = directory)
        persistOutputDirectory()
    }

    /**
     * Called when the browse button is clicked.
     * Opens a native file picker for directory selection.
     *
     * @return The selected directory path, or null if cancelled.
     */
    fun onBrowseClicked(): String? {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            currentDirectory = File(state.outputDirectory).takeIf { it.exists() }
                ?: File(System.getProperty("user.home"))
            dialogTitle = i18n["main.outputLocation"]
        }

        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            val selectedPath = chooser.selectedFile.absolutePath
            onOutputDirectoryChanged(selectedPath)
            selectedPath
        } else {
            null
        }
    }

    /**
     * Called when the translate button is clicked.
     * Validates the form and creates a TranslationJob if valid.
     *
     * @return TranslationJob if valid, null otherwise.
     */
    fun onTranslateClicked(): TranslationJob? {
        if (!state.isFormValid) {
            logger.warn { "Form is not valid for translation" }
            return null
        }

        // If we don't have video info yet, create a minimal one
        val videoInfo = state.videoInfo ?: run {
            val videoId = extractVideoId(state.youtubeUrl) ?: return null
            VideoInfo(
                url = state.youtubeUrl,
                id = videoId,
                title = "YouTube Video",
                duration = 0L
            )
        }

        val outputOptions = OutputOptions(
            outputDirectory = state.outputDirectory,
            subtitleType = state.subtitleType,
            exportSrt = state.exportSrt,
            burnedInStyle = if (state.subtitleType == SubtitleType.BURNED_IN) {
                state.burnedInStyle
            } else null
        )

        val job = TranslationJob(
            videoInfo = videoInfo,
            sourceLanguage = state.sourceLanguage,
            targetLanguage = state.targetLanguage,
            outputOptions = outputOptions
        )

        logger.info { "Created translation job for ${videoInfo.title}" }
        return job
    }

    /**
     * Extracts the video ID from a YouTube URL.
     */
    private fun extractVideoId(url: String): String? {
        return VIDEO_ID_REGEX.find(url)?.groupValues?.get(1)
    }

    /**
     * Persists language settings to config.
     */
    private fun persistLanguageSettings() {
        scope.launch {
            try {
                configManager.updateSettings { settings ->
                    settings.copy(
                        translation = settings.translation.copy(
                            defaultSourceLanguage = state.sourceLanguage?.code,
                            defaultTargetLanguage = state.targetLanguage.code
                        )
                    )
                }
                logger.debug { "Persisted language settings" }
            } catch (e: Exception) {
                logger.warn { "Failed to persist language settings: ${e.message}" }
            }
        }
    }

    /**
     * Persists output directory to config.
     */
    private fun persistOutputDirectory() {
        scope.launch {
            try {
                configManager.updateSettings { settings ->
                    settings.copy(
                        ui = settings.ui.copy(
                            defaultOutputDirectory = state.outputDirectory
                        )
                    )
                }
                logger.debug { "Persisted output directory" }
            } catch (e: Exception) {
                logger.warn { "Failed to persist output directory: ${e.message}" }
            }
        }
    }

    /**
     * Resets the form to default state.
     */
    fun reset() {
        videoInfoJob?.cancel()
        state = MainScreenState()
        loadDefaults()
    }

    /**
     * Cleans up resources.
     */
    fun dispose() {
        videoInfoJob?.cancel()
    }
}
