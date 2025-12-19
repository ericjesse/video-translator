package com.ericjesse.videotranslator.infrastructure.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Manages application settings and configuration.
 */
class ConfigManager(private val platformPaths: PlatformPaths) {
    
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
     */
    fun getTranslationServiceConfig(): TranslationServiceConfig {
        cachedServiceConfig?.let { return it }
        
        val file = File(platformPaths.servicesFile)
        return if (file.exists()) {
            try {
                val config = json.decodeFromString<TranslationServiceConfig>(file.readText())
                cachedServiceConfig = config
                config
            } catch (e: Exception) {
                logger.warn { "Failed to parse services config: ${e.message}" }
                TranslationServiceConfig()
            }
        } else {
            TranslationServiceConfig()
        }
    }
    
    /**
     * Saves translation service configuration.
     */
    fun saveTranslationServiceConfig(config: TranslationServiceConfig) {
        val file = File(platformPaths.servicesFile)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(config))
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
