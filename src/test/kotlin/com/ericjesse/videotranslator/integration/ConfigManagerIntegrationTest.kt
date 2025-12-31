package com.ericjesse.videotranslator.integration

import com.ericjesse.videotranslator.infrastructure.config.AppSettings
import com.ericjesse.videotranslator.infrastructure.config.BurnedInSettings
import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.config.InstalledVersions
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import com.ericjesse.videotranslator.infrastructure.config.ResourceSettings
import com.ericjesse.videotranslator.infrastructure.config.SetupProgress
import com.ericjesse.videotranslator.infrastructure.config.SubtitleSettings
import com.ericjesse.videotranslator.infrastructure.config.TranscriptionSettings
import com.ericjesse.videotranslator.infrastructure.config.TranslationServiceConfig
import com.ericjesse.videotranslator.infrastructure.config.TranslationSettings
import com.ericjesse.videotranslator.infrastructure.config.UiSettings
import com.ericjesse.videotranslator.infrastructure.config.UpdateSettings
import com.ericjesse.videotranslator.infrastructure.security.SecureStorage
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Integration tests for ConfigManager.
 * Tests settings persistence, default values, and migration scenarios.
 */
class ConfigManagerIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var platformPaths: PlatformPaths
    private lateinit var configManager: ConfigManager

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @BeforeEach
    fun setup() {
        platformPaths = mockk()
        every { platformPaths.settingsFile } returns tempDir.resolve("settings.json").toString()
        every { platformPaths.servicesFile } returns tempDir.resolve("services.json").toString()
        every { platformPaths.versionsFile } returns tempDir.resolve("versions.json").toString()

        configManager = ConfigManager(platformPaths, secureStorage = null)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // ==================== Settings Persistence Tests ====================

    @Nested
    inner class SettingsPersistenceTest {

        @Test
        fun `saveSettings persists to file`() {
            val settings = AppSettings(
                language = "de",
                transcription = TranscriptionSettings(whisperModel = "medium"),
                translation = TranslationSettings(defaultService = "deepl")
            )

            configManager.saveSettings(settings)

            val file = File(platformPaths.settingsFile)
            assertTrue(file.exists())

            val content = file.readText()
            assertTrue(content.contains("\"language\": \"de\""))
            assertTrue(content.contains("\"whisperModel\": \"medium\""))
        }

        @Test
        fun `getSettings returns saved settings`() {
            val settings = AppSettings(
                language = "fr",
                transcription = TranscriptionSettings(whisperModel = "large")
            )

            configManager.saveSettings(settings)
            configManager.clearCache()

            val loaded = configManager.getSettings()
            assertEquals("fr", loaded.language)
            assertEquals("large", loaded.transcription.whisperModel)
        }

        @Test
        fun `updateSettings applies transformation`() {
            val initial = AppSettings(language = "en")
            configManager.saveSettings(initial)

            configManager.updateSettings { it.copy(language = "es") }

            val updated = configManager.getSettings()
            assertEquals("es", updated.language)
        }

        @Test
        fun `settings survive cache clear`() {
            val settings = AppSettings(language = "it")
            configManager.saveSettings(settings)

            configManager.clearCache()

            val loaded = configManager.getSettings()
            assertEquals("it", loaded.language)
        }

        @Test
        fun `nested settings are persisted correctly`() {
            val settings = AppSettings(
                transcription = TranscriptionSettings(
                    whisperModel = "small",
                    preferYouTubeCaptions = false
                ),
                subtitle = SubtitleSettings(
                    defaultOutputMode = "hard",
                    alwaysExportSrt = true,
                    burnedIn = BurnedInSettings(
                        fontSize = 32,
                        fontColor = "#FF0000",
                        backgroundOpacity = 0.5f
                    )
                )
            )

            configManager.saveSettings(settings)
            configManager.clearCache()

            val loaded = configManager.getSettings()
            assertEquals("small", loaded.transcription.whisperModel)
            assertFalse(loaded.transcription.preferYouTubeCaptions)
            assertEquals("hard", loaded.subtitle.defaultOutputMode)
            assertTrue(loaded.subtitle.alwaysExportSrt)
            assertEquals(32, loaded.subtitle.burnedIn.fontSize)
            assertEquals("#FF0000", loaded.subtitle.burnedIn.fontColor)
            assertEquals(0.5f, loaded.subtitle.burnedIn.backgroundOpacity)
        }
    }

    // ==================== Default Values Tests ====================

    @Nested
    inner class DefaultValuesTest {

        @Test
        fun `returns default settings when no file exists`() {
            val settings = configManager.getSettings()

            assertEquals(1, settings.version)
            assertEquals("en", settings.language)
            assertEquals("base", settings.transcription.whisperModel)
            assertFalse(settings.transcription.preferYouTubeCaptions)  // Default is false - use local Whisper
            assertEquals("libretranslate", settings.translation.defaultService)
            assertEquals("burned_in", settings.subtitle.defaultOutputMode)
            assertTrue(settings.updates.checkAutomatically)
            assertEquals(7, settings.updates.checkIntervalDays)
        }

        @Test
        fun `default TranscriptionSettings has expected values`() {
            val defaults = TranscriptionSettings()

            assertEquals("base", defaults.whisperModel)
            assertFalse(defaults.preferYouTubeCaptions)  // Default is false - use local Whisper
        }

        @Test
        fun `default TranslationSettings has expected values`() {
            val defaults = TranslationSettings()

            assertEquals("libretranslate", defaults.defaultService)
            assertNull(defaults.defaultSourceLanguage)
            assertEquals("en", defaults.defaultTargetLanguage)
        }

        @Test
        fun `default SubtitleSettings has expected values`() {
            val defaults = SubtitleSettings()

            assertEquals("burned_in", defaults.defaultOutputMode)
            assertFalse(defaults.alwaysExportSrt)
            assertEquals(24, defaults.burnedIn.fontSize)
            assertEquals("#FFFFFF", defaults.burnedIn.fontColor)
        }

        @Test
        fun `default UpdateSettings has expected values`() {
            val defaults = UpdateSettings()

            assertTrue(defaults.checkAutomatically)
            assertEquals(7, defaults.checkIntervalDays)
            assertFalse(defaults.autoUpdateDependencies)
            assertEquals(0L, defaults.lastCheckTimestamp)
        }

        @Test
        fun `default ResourceSettings has expected values`() {
            val defaults = ResourceSettings()

            assertEquals(4096, defaults.maxMemoryMB)
            assertEquals(60, defaults.maxMemoryPercent)
        }

        @Test
        fun `default UiSettings has expected values`() {
            val defaults = UiSettings()

            assertEquals("", defaults.defaultOutputDirectory)
            assertEquals(700, defaults.windowWidth)
            assertEquals(650, defaults.windowHeight)
        }

        @Test
        fun `isFirstRun returns true when no settings file`() {
            assertTrue(configManager.isFirstRun())
        }

        @Test
        fun `isFirstRun returns false after saving settings with completed setup`() {
            configManager.saveSettings(AppSettings(setupProgress = SetupProgress(completed = true)))
            assertFalse(configManager.isFirstRun())
        }

        @Test
        fun `isFirstRun returns true if setup not completed`() {
            configManager.saveSettings(AppSettings(setupProgress = SetupProgress(completed = false)))
            assertTrue(configManager.isFirstRun())
        }
    }

    // ==================== Migration Tests ====================

    @Nested
    inner class MigrationTest {

        @Test
        fun `loads settings with missing fields using defaults`() {
            // Write minimal JSON without all fields
            val file = File(platformPaths.settingsFile)
            file.parentFile?.mkdirs()
            file.writeText("""{"language": "de"}""")

            val settings = configManager.getSettings()

            assertEquals("de", settings.language)
            // All other fields should have defaults
            assertEquals("base", settings.transcription.whisperModel)
            assertEquals("libretranslate", settings.translation.defaultService)
        }

        @Test
        fun `handles unknown fields gracefully`() {
            val file = File(platformPaths.settingsFile)
            file.parentFile?.mkdirs()
            file.writeText("""
                {
                    "language": "en",
                    "unknownField": "value",
                    "transcription": {
                        "whisperModel": "tiny",
                        "futureOption": true
                    }
                }
            """.trimIndent())

            val settings = configManager.getSettings()

            assertEquals("en", settings.language)
            assertEquals("tiny", settings.transcription.whisperModel)
        }

        @Test
        fun `handles corrupt JSON by returning defaults`() {
            val file = File(platformPaths.settingsFile)
            file.parentFile?.mkdirs()
            file.writeText("{invalid json content")

            val settings = configManager.getSettings()

            // Should return defaults due to parse failure
            assertEquals("en", settings.language)
            assertEquals("base", settings.transcription.whisperModel)
        }

        @Test
        fun `migrates v1 settings format`() {
            // Simulate v1 settings format
            val file = File(platformPaths.settingsFile)
            file.parentFile?.mkdirs()
            file.writeText("""
                {
                    "version": 1,
                    "language": "de",
                    "transcription": {
                        "whisperModel": "small"
                    }
                }
            """.trimIndent())

            val settings = configManager.getSettings()

            assertEquals(1, settings.version)
            assertEquals("de", settings.language)
            assertEquals("small", settings.transcription.whisperModel)
        }

        @Test
        fun `preserves setup progress during migration`() {
            val file = File(platformPaths.settingsFile)
            file.parentFile?.mkdirs()
            file.writeText("""
                {
                    "setupProgress": {
                        "completed": true,
                        "currentStep": 5,
                        "selectedWhisperModel": "medium",
                        "selectedTranslationService": "deepl",
                        "dependenciesDownloaded": true
                    }
                }
            """.trimIndent())

            val settings = configManager.getSettings()

            assertTrue(settings.setupProgress.completed)
            assertEquals(5, settings.setupProgress.currentStep)
            assertEquals("medium", settings.setupProgress.selectedWhisperModel)
            assertEquals("deepl", settings.setupProgress.selectedTranslationService)
            assertTrue(settings.setupProgress.dependenciesDownloaded)
        }
    }

    // ==================== Service Config Tests ====================

    @Nested
    inner class ServiceConfigTest {

        @Test
        fun `saves and loads translation service config`() {
            val config = TranslationServiceConfig(
                deeplApiKey = "test-deepl-key",
                openaiApiKey = "test-openai-key"
            )

            configManager.saveTranslationServiceConfig(config)
            configManager.clearCache()

            val loaded = configManager.getTranslationServiceConfig()
            assertEquals("test-deepl-key", loaded.deeplApiKey)
            assertEquals("test-openai-key", loaded.openaiApiKey)
        }

        @Test
        fun `returns default service config when no file exists`() {
            val config = configManager.getTranslationServiceConfig()

            assertNull(config.deeplApiKey)
            assertNull(config.openaiApiKey)
            assertNull(config.googleApiKey)
        }
    }

    // ==================== Secure Storage Tests ====================

    @Nested
    inner class SecureStorageTest {

        @Test
        fun `uses secure storage for API keys when available`() {
            val secureStorage = mockk<SecureStorage>(relaxed = true)
            every { secureStorage.retrieve("deepl_api_key") } returns "secure-deepl-key"
            every { secureStorage.retrieve("openai_api_key") } returns "secure-openai-key"
            every { secureStorage.retrieve("google_api_key") } returns null

            val configWithSecure = ConfigManager(platformPaths, secureStorage)

            // Save config (keys go to secure storage)
            val config = TranslationServiceConfig(
                deeplApiKey = "secure-deepl-key",
                openaiApiKey = "secure-openai-key"
            )
            configWithSecure.saveTranslationServiceConfig(config)

            // Verify keys were stored securely
            verify { secureStorage.store("deepl_api_key", "secure-deepl-key") }
            verify { secureStorage.store("openai_api_key", "secure-openai-key") }
        }

        @Test
        fun `retrieves API keys from secure storage`() {
            val secureStorage = mockk<SecureStorage>(relaxed = true)
            every { secureStorage.retrieve("deepl_api_key") } returns "secure-deepl-key"
            every { secureStorage.retrieve("openai_api_key") } returns null
            every { secureStorage.retrieve("google_api_key") } returns null

            // Write services file without API keys
            val file = File(platformPaths.servicesFile)
            file.parentFile?.mkdirs()
            file.writeText("""{}""")

            val configWithSecure = ConfigManager(platformPaths, secureStorage)
            val config = configWithSecure.getTranslationServiceConfig()

            assertEquals("secure-deepl-key", config.deeplApiKey)
        }

        @Test
        fun `migrateApiKeysToSecureStorage moves keys to secure storage`() {
            val secureStorage = mockk<SecureStorage>(relaxed = true)
            every { secureStorage.store(any(), any()) } returns true

            // Write services file WITH API keys (old format)
            val file = File(platformPaths.servicesFile)
            file.parentFile?.mkdirs()
            file.writeText("""
                {
                    "libreTranslateUrl": "https://libretranslate.com",
                    "deeplApiKey": "plain-text-deepl-key",
                    "openaiApiKey": "plain-text-openai-key"
                }
            """.trimIndent())

            val configWithSecure = ConfigManager(platformPaths, secureStorage)
            val migrated = configWithSecure.migrateApiKeysToSecureStorage()

            assertTrue(migrated)
            verify { secureStorage.store("deepl_api_key", "plain-text-deepl-key") }
            verify { secureStorage.store("openai_api_key", "plain-text-openai-key") }

            // Verify keys were removed from JSON file
            val updatedContent = file.readText()
            assertFalse(updatedContent.contains("plain-text-deepl-key"))
            assertFalse(updatedContent.contains("plain-text-openai-key"))
        }

        @Test
        fun `migrateApiKeysToSecureStorage returns false when no keys to migrate`() {
            val secureStorage = mockk<SecureStorage>(relaxed = true)

            // Write services file without API keys
            val file = File(platformPaths.servicesFile)
            file.parentFile?.mkdirs()
            file.writeText("""{"libreTranslateUrl": "https://libretranslate.com"}""")

            val configWithSecure = ConfigManager(platformPaths, secureStorage)
            val migrated = configWithSecure.migrateApiKeysToSecureStorage()

            assertFalse(migrated)
        }

        @Test
        fun `migrateApiKeysToSecureStorage returns false when no secure storage`() {
            val configWithoutSecure = ConfigManager(platformPaths, secureStorage = null)
            val migrated = configWithoutSecure.migrateApiKeysToSecureStorage()

            assertFalse(migrated)
        }
    }

    // ==================== Installed Versions Tests ====================

    @Nested
    inner class InstalledVersionsTest {

        @Test
        fun `saves and loads installed versions`() {
            val versions = InstalledVersions(
                ytDlp = "2024.01.01",
                ffmpeg = "7.0",
                whisperCpp = "v1.5.4",
                whisperModel = "base"
            )

            configManager.saveInstalledVersions(versions)
            configManager.clearCache()

            val loaded = configManager.getInstalledVersions()
            assertEquals("2024.01.01", loaded.ytDlp)
            assertEquals("7.0", loaded.ffmpeg)
            assertEquals("v1.5.4", loaded.whisperCpp)
            assertEquals("base", loaded.whisperModel)
        }

        @Test
        fun `returns empty versions when no file exists`() {
            val versions = configManager.getInstalledVersions()

            assertNull(versions.ytDlp)
            assertNull(versions.ffmpeg)
            assertNull(versions.whisperCpp)
            assertNull(versions.whisperModel)
        }

        @Test
        fun `handles partial version updates`() {
            val initial = InstalledVersions(ytDlp = "2024.01.01")
            configManager.saveInstalledVersions(initial)

            val updated = configManager.getInstalledVersions().copy(ffmpeg = "7.0")
            configManager.saveInstalledVersions(updated)

            val loaded = configManager.getInstalledVersions()
            assertEquals("2024.01.01", loaded.ytDlp)
            assertEquals("7.0", loaded.ffmpeg)
        }
    }

    // ==================== File Creation Tests ====================

    @Nested
    inner class FileCreationTest {

        @Test
        fun `creates parent directories when saving settings`() {
            val deepPath = tempDir.resolve("deep/nested/path/settings.json")
            every { platformPaths.settingsFile } returns deepPath.toString()

            configManager.saveSettings(AppSettings(language = "de"))

            assertTrue(File(deepPath.toString()).exists())
        }

        @Test
        fun `creates parent directories when saving service config`() {
            val deepPath = tempDir.resolve("deep/nested/path/services.json")
            every { platformPaths.servicesFile } returns deepPath.toString()

            configManager.saveTranslationServiceConfig(TranslationServiceConfig())

            assertTrue(File(deepPath.toString()).exists())
        }

        @Test
        fun `creates parent directories when saving versions`() {
            val deepPath = tempDir.resolve("deep/nested/path/versions.json")
            every { platformPaths.versionsFile } returns deepPath.toString()

            configManager.saveInstalledVersions(InstalledVersions())

            assertTrue(File(deepPath.toString()).exists())
        }
    }

    // ==================== Caching Tests ====================

    @Nested
    inner class CachingTest {

        @Test
        fun `settings are cached after first load`() {
            configManager.saveSettings(AppSettings(language = "de"))

            // First call loads from file
            val first = configManager.getSettings()
            // Modify file directly
            File(platformPaths.settingsFile).writeText("""{"language": "fr"}""")
            // Second call should return cached value
            val second = configManager.getSettings()

            assertEquals("de", second.language)
        }

        @Test
        fun `clearCache forces reload from file`() {
            configManager.saveSettings(AppSettings(language = "de"))
            configManager.getSettings() // Cache it

            // Modify file directly
            File(platformPaths.settingsFile).writeText("""{"language": "fr"}""")

            configManager.clearCache()
            val loaded = configManager.getSettings()

            assertEquals("fr", loaded.language)
        }

        @Test
        fun `saveSettings updates cache`() {
            configManager.getSettings() // Cache defaults

            configManager.saveSettings(AppSettings(language = "it"))

            // Should return new value without clearCache
            val loaded = configManager.getSettings()
            assertEquals("it", loaded.language)
        }
    }
}
