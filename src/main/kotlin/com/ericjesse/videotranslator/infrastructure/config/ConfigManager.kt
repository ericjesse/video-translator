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
 */
@Serializable
data class AppSettings(
    val version: Int = 1,
    val language: String = "en",
    val transcription: TranscriptionSettings = TranscriptionSettings(),
    val translation: TranslationSettings = TranslationSettings(),
    val subtitle: SubtitleSettings = SubtitleSettings(),
    val updates: UpdateSettings = UpdateSettings(),
    val resources: ResourceSettings = ResourceSettings(),
    val ui: UiSettings = UiSettings()
)

@Serializable
data class TranscriptionSettings(
    val whisperModel: String = "base",
    val preferYouTubeCaptions: Boolean = true
)

@Serializable
data class TranslationSettings(
    val defaultService: String = "libretranslate",
    val defaultSourceLanguage: String? = null, // null = auto-detect
    val defaultTargetLanguage: String = "en"
)

@Serializable
data class SubtitleSettings(
    val defaultOutputMode: String = "soft",
    val alwaysExportSrt: Boolean = false,
    val burnedIn: BurnedInSettings = BurnedInSettings()
)

@Serializable
data class BurnedInSettings(
    val fontSize: Int = 24,
    val fontColor: String = "#FFFFFF",
    val backgroundColor: String = "none",
    val backgroundOpacity: Float = 0f
)

@Serializable
data class UpdateSettings(
    val checkAutomatically: Boolean = true,
    val checkIntervalDays: Int = 7,
    val autoUpdateDependencies: Boolean = false,
    val lastCheckTimestamp: Long = 0
)

@Serializable
data class ResourceSettings(
    val maxMemoryMB: Int = 4096,
    val maxMemoryPercent: Int = 60
)

@Serializable
data class UiSettings(
    val defaultOutputDirectory: String = "",
    val windowWidth: Int = 700,
    val windowHeight: Int = 650
)

/**
 * Translation service configuration (contains sensitive data like API keys).
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
 */
@Serializable
data class InstalledVersions(
    val ytDlp: String? = null,
    val ffmpeg: String? = null,
    val whisperCpp: String? = null,
    val whisperModel: String? = null
)
