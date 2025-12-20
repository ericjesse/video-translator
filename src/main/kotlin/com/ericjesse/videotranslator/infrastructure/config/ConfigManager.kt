package com.ericjesse.videotranslator.infrastructure.config

import com.ericjesse.videotranslator.infrastructure.security.SecureStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Manages application settings and configuration.
 * Sensitive data like API keys are stored using platform-native secure storage.
 *
 * @property platformPaths Platform-specific paths for configuration files and directories.
 * @property secureStorage Optional secure storage for sensitive data like API keys.
 *                         If null, API keys are stored in plain JSON files.
 */
class ConfigManager(
    private val platformPaths: PlatformPaths,
    private val secureStorage: SecureStorage? = null
) {

    companion object {
        // Keys for secure storage
        private const val KEY_DEEPL_API = "deepl_api_key"
        private const val KEY_OPENAI_API = "openai_api_key"
        private const val KEY_GOOGLE_API = "google_api_key"
    }
    
    private val json = Json { 
        prettyPrint = true 
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    private var cachedSettings: AppSettings? = null
    private var cachedServiceConfig: TranslationServiceConfig? = null
    
    /**
     * Checks if this is the first run (no settings file exists).
     */
    fun isFirstRun(): Boolean {
        return !File(platformPaths.settingsFile).exists()
    }
    
    /**
     * Gets the current application settings.
     */
    fun getSettings(): AppSettings {
        cachedSettings?.let { return it }
        
        val file = File(platformPaths.settingsFile)
        return if (file.exists()) {
            try {
                val settings = json.decodeFromString<AppSettings>(file.readText())
                cachedSettings = settings
                settings
            } catch (e: Exception) {
                logger.warn { "Failed to parse settings, using defaults: ${e.message}" }
                AppSettings()
            }
        } else {
            AppSettings()
        }
    }
    
    /**
     * Saves application settings.
     */
    fun saveSettings(settings: AppSettings) {
        val file = File(platformPaths.settingsFile)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(settings))
        cachedSettings = settings
        logger.info { "Settings saved" }
    }
    
    /**
     * Updates settings with a transform function.
     */
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val current = getSettings()
        val updated = transform(current)
        saveSettings(updated)
    }
    
    /**
     * Gets translation service configuration (API keys, etc.)
     * API keys are retrieved from secure storage if available.
     */
    fun getTranslationServiceConfig(): TranslationServiceConfig {
        cachedServiceConfig?.let { return it }

        val file = File(platformPaths.servicesFile)
        val baseConfig = if (file.exists()) {
            try {
                json.decodeFromString<TranslationServiceConfig>(file.readText())
            } catch (e: Exception) {
                logger.warn { "Failed to parse services config: ${e.message}" }
                TranslationServiceConfig()
            }
        } else {
            TranslationServiceConfig()
        }

        // Retrieve API keys from secure storage
        val config = if (secureStorage != null) {
            baseConfig.copy(
                deeplApiKey = secureStorage.retrieve(KEY_DEEPL_API) ?: baseConfig.deeplApiKey,
                openaiApiKey = secureStorage.retrieve(KEY_OPENAI_API) ?: baseConfig.openaiApiKey,
                googleApiKey = secureStorage.retrieve(KEY_GOOGLE_API) ?: baseConfig.googleApiKey
            )
        } else {
            baseConfig
        }

        cachedServiceConfig = config
        return config
    }
    
    /**
     * Saves translation service configuration.
     * API keys are stored in secure storage if available, with placeholders in the JSON file.
     */
    fun saveTranslationServiceConfig(config: TranslationServiceConfig) {
        val file = File(platformPaths.servicesFile)
        file.parentFile?.mkdirs()

        // Store API keys in secure storage
        val configToSave = if (secureStorage != null) {
            // Store keys securely
            config.deeplApiKey?.let { secureStorage.store(KEY_DEEPL_API, it) }
                ?: secureStorage.delete(KEY_DEEPL_API)
            config.openaiApiKey?.let { secureStorage.store(KEY_OPENAI_API, it) }
                ?: secureStorage.delete(KEY_OPENAI_API)
            config.googleApiKey?.let { secureStorage.store(KEY_GOOGLE_API, it) }
                ?: secureStorage.delete(KEY_GOOGLE_API)

            // Save config without API keys in the JSON file
            config.copy(
                deeplApiKey = null,
                openaiApiKey = null,
                googleApiKey = null
            )
        } else {
            config
        }

        file.writeText(json.encodeToString(configToSave))
        cachedServiceConfig = config
        logger.info { "Service config saved" }
    }
    
    /**
     * Gets installed dependency versions.
     */
    fun getInstalledVersions(): InstalledVersions {
        val file = File(platformPaths.versionsFile)
        return if (file.exists()) {
            try {
                json.decodeFromString(file.readText())
            } catch (e: Exception) {
                InstalledVersions()
            }
        } else {
            InstalledVersions()
        }
    }
    
    /**
     * Saves installed dependency versions.
     */
    fun saveInstalledVersions(versions: InstalledVersions) {
        val file = File(platformPaths.versionsFile)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(versions))
    }
    
    /**
     * Clears all cached configuration.
     */
    fun clearCache() {
        cachedSettings = null
        cachedServiceConfig = null
    }

    /**
     * Migrates existing API keys from plain JSON storage to secure storage.
     * Call this once during application initialization.
     * @return true if migration was performed, false if not needed or failed
     */
    fun migrateApiKeysToSecureStorage(): Boolean {
        if (secureStorage == null) {
            logger.debug { "No secure storage available, skipping migration" }
            return false
        }

        val file = File(platformPaths.servicesFile)
        if (!file.exists()) {
            logger.debug { "No services file found, skipping migration" }
            return false
        }

        return try {
            val existingConfig = json.decodeFromString<TranslationServiceConfig>(file.readText())

            // Check if there are any keys to migrate
            val hasKeys = existingConfig.deeplApiKey != null ||
                    existingConfig.openaiApiKey != null ||
                    existingConfig.googleApiKey != null

            if (!hasKeys) {
                logger.debug { "No API keys to migrate" }
                return false
            }

            // Migrate keys to secure storage
            var migrated = false
            existingConfig.deeplApiKey?.let {
                if (secureStorage.store(KEY_DEEPL_API, it)) {
                    logger.info { "Migrated DeepL API key to secure storage" }
                    migrated = true
                }
            }
            existingConfig.openaiApiKey?.let {
                if (secureStorage.store(KEY_OPENAI_API, it)) {
                    logger.info { "Migrated OpenAI API key to secure storage" }
                    migrated = true
                }
            }
            existingConfig.googleApiKey?.let {
                if (secureStorage.store(KEY_GOOGLE_API, it)) {
                    logger.info { "Migrated Google API key to secure storage" }
                    migrated = true
                }
            }

            if (migrated) {
                // Remove keys from JSON file
                val cleanedConfig = existingConfig.copy(
                    deeplApiKey = null,
                    openaiApiKey = null,
                    googleApiKey = null
                )
                file.writeText(json.encodeToString(cleanedConfig))
                logger.info { "Removed API keys from services.json after migration" }
            }

            migrated
        } catch (e: Exception) {
            logger.error(e) { "Failed to migrate API keys to secure storage" }
            false
        }
    }
}

/**
 * Application settings.
 *
 * @property version Settings schema version for migration purposes.
 * @property language UI language code (e.g., "en", "fr", "de").
 * @property setupProgress Tracks setup wizard progress for resume functionality.
 * @property transcription Settings for audio transcription (Whisper).
 * @property translation Settings for subtitle translation.
 * @property subtitle Settings for subtitle output format and styling.
 * @property updates Settings for automatic update checks.
 * @property resources Resource limits (memory, CPU) for processing.
 * @property ui User interface preferences.
 */
@Serializable
data class AppSettings(
    val version: Int = 1,
    val language: String = "en",
    val setupProgress: SetupProgress = SetupProgress(),
    val transcription: TranscriptionSettings = TranscriptionSettings(),
    val translation: TranslationSettings = TranslationSettings(),
    val subtitle: SubtitleSettings = SubtitleSettings(),
    val updates: UpdateSettings = UpdateSettings(),
    val resources: ResourceSettings = ResourceSettings(),
    val ui: UiSettings = UiSettings()
)

/**
 * Tracks setup wizard progress for resume functionality.
 *
 * @property completed Whether the setup wizard has been completed.
 * @property currentStep The current step index (0-based) if setup is in progress.
 * @property selectedWhisperModel The Whisper model selected during setup.
 * @property selectedTranslationService The translation service selected during setup.
 * @property dependenciesDownloaded Whether dependencies have been downloaded.
 */
@Serializable
data class SetupProgress(
    val completed: Boolean = false,
    val currentStep: Int = 0,
    val selectedWhisperModel: String = "base",
    val selectedTranslationService: String = "libretranslate",
    val dependenciesDownloaded: Boolean = false
)

/**
 * Settings for audio transcription.
 *
 * @property whisperModel Whisper model size to use (tiny, base, small, medium, large).
 * @property preferYouTubeCaptions Whether to use YouTube's auto-captions when available
 *                                  instead of running local transcription.
 */
@Serializable
data class TranscriptionSettings(
    val whisperModel: String = "base",
    val preferYouTubeCaptions: Boolean = true
)

/**
 * Settings for subtitle translation.
 *
 * @property defaultService Default translation service (libretranslate, deepl, openai, google).
 * @property defaultSourceLanguage Default source language code, or null for auto-detection.
 * @property defaultTargetLanguage Default target language code for translations.
 */
@Serializable
data class TranslationSettings(
    val defaultService: String = "libretranslate",
    val defaultSourceLanguage: String? = null,
    val defaultTargetLanguage: String = "en"
)

/**
 * Settings for subtitle output.
 *
 * @property defaultOutputMode How subtitles are added to video: "soft" (selectable track),
 *                              "hard" (burned into video), or "srt" (separate file only).
 * @property alwaysExportSrt Whether to always export a separate SRT file alongside the video.
 * @property burnedIn Styling options for burned-in (hardcoded) subtitles.
 */
@Serializable
data class SubtitleSettings(
    val defaultOutputMode: String = "soft",
    val alwaysExportSrt: Boolean = false,
    val burnedIn: BurnedInSettings = BurnedInSettings()
)

/**
 * Styling options for burned-in (hardcoded) subtitles.
 *
 * @property fontSize Font size in pixels for subtitle text.
 * @property fontColor Font color as a hex string (e.g., "#FFFFFF" for white).
 * @property backgroundColor Background color as a hex string, or "none" for transparent.
 * @property backgroundOpacity Opacity of the background (0.0 = transparent, 1.0 = opaque).
 */
@Serializable
data class BurnedInSettings(
    val fontSize: Int = 24,
    val fontColor: String = "#FFFFFF",
    val backgroundColor: String = "none",
    val backgroundOpacity: Float = 0f
)

/**
 * Settings for automatic update checks.
 *
 * @property checkAutomatically Whether to automatically check for updates on startup.
 * @property checkIntervalDays Minimum days between automatic update checks.
 * @property autoUpdateDependencies Whether to automatically update dependencies (yt-dlp, FFmpeg, etc.).
 * @property lastCheckTimestamp Unix timestamp (millis) of the last update check.
 */
@Serializable
data class UpdateSettings(
    val checkAutomatically: Boolean = true,
    val checkIntervalDays: Int = 7,
    val autoUpdateDependencies: Boolean = false,
    val lastCheckTimestamp: Long = 0
)

/**
 * Resource limits for processing operations.
 *
 * @property maxMemoryMB Maximum memory in megabytes for processing tasks.
 * @property maxMemoryPercent Maximum percentage of system memory to use (0-100).
 */
@Serializable
data class ResourceSettings(
    val maxMemoryMB: Int = 4096,
    val maxMemoryPercent: Int = 60
)

/**
 * User interface preferences.
 *
 * @property defaultOutputDirectory Default directory for saving output files, or empty for source directory.
 * @property windowWidth Main window width in pixels.
 * @property windowHeight Main window height in pixels.
 */
@Serializable
data class UiSettings(
    val defaultOutputDirectory: String = "",
    val windowWidth: Int = 700,
    val windowHeight: Int = 650
)

/**
 * Translation service configuration (contains sensitive data like API keys).
 *
 * @property libreTranslateUrl URL of the LibreTranslate server to use.
 * @property deeplApiKey API key for DeepL translation service.
 * @property openaiApiKey API key for OpenAI translation service.
 * @property googleApiKey API key for Google Cloud Translation service.
 */
@Serializable
data class TranslationServiceConfig(
    val libreTranslateUrl: String? = "https://libretranslate.com",
    val deeplApiKey: String? = null,
    val openaiApiKey: String? = null,
    val googleApiKey: String? = null
)

/**
 * Tracks installed versions of dependencies.
 *
 * @property ytDlp Installed version of yt-dlp, or null if not installed.
 * @property ffmpeg Installed version of FFmpeg, or null if not installed.
 * @property whisperCpp Installed version of whisper.cpp, or null if not installed.
 * @property whisperModel Name of the installed Whisper model (tiny, base, small, medium, large), or null.
 */
@Serializable
data class InstalledVersions(
    val ytDlp: String? = null,
    val ffmpeg: String? = null,
    val whisperCpp: String? = null,
    val whisperModel: String? = null
)
