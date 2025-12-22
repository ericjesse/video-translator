package com.ericjesse.videotranslator.ui.screens.settings

import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.infrastructure.config.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for SettingsScreen state management via SettingsViewModel and state models.
 *
 * Tests cover:
 * - Tab navigation
 * - Setting changes
 * - Save/cancel functionality
 * - Unsaved changes detection
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("SettingsScreen Tests")
class SettingsScreenTest {

    private lateinit var appModule: AppModule
    private lateinit var configManager: ConfigManager
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    private val defaultSettings = AppSettings()
    private val defaultServiceConfig = TranslationServiceConfig()

    @BeforeEach
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        Dispatchers.setMain(testDispatcher)

        appModule = mockk(relaxed = true)
        configManager = mockk(relaxed = true)

        every { appModule.configManager } returns configManager
        every { configManager.getSettings() } returns defaultSettings
        every { configManager.getTranslationServiceConfig() } returns defaultServiceConfig
        coEvery { configManager.saveSettings(any()) } just Runs
        coEvery { configManager.saveTranslationServiceConfig(any()) } just Runs
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Nested
    @DisplayName("SettingsTab Enum")
    inner class SettingsTabTest {

        @Test
        @DisplayName("SettingsTab should have all expected values")
        fun settingsTabValues() {
            val values = SettingsTab.entries
            assertTrue(values.contains(SettingsTab.GENERAL))
            assertTrue(values.contains(SettingsTab.TRANSLATION))
            assertTrue(values.contains(SettingsTab.TRANSCRIPTION))
            assertTrue(values.contains(SettingsTab.SUBTITLES))
            assertTrue(values.contains(SettingsTab.UPDATES))
            assertTrue(values.contains(SettingsTab.ABOUT))
            assertEquals(6, values.size)
        }

        @Test
        @DisplayName("Each tab should have an icon and title key")
        fun tabsHaveIconAndTitle() {
            SettingsTab.entries.forEach { tab ->
                assertNotNull(tab.icon)
                assertTrue(tab.titleKey.isNotEmpty())
            }
        }

        @Test
        @DisplayName("Title keys should follow settings.tab.* pattern")
        fun titleKeysFollowPattern() {
            SettingsTab.entries.forEach { tab ->
                assertTrue(tab.titleKey.startsWith("settings.tab."))
            }
        }
    }

    @Nested
    @DisplayName("SettingsScreenState")
    inner class SettingsScreenStateTest {

        @Test
        @DisplayName("Default state should have GENERAL tab selected")
        fun defaultTabIsGeneral() {
            val state = SettingsScreenState()
            assertEquals(SettingsTab.GENERAL, state.selectedTab)
        }

        @Test
        @DisplayName("Default state should not be saving")
        fun defaultNotSaving() {
            val state = SettingsScreenState()
            assertFalse(state.isSaving)
        }

        @Test
        @DisplayName("Default state should have no save error")
        fun defaultNoSaveError() {
            val state = SettingsScreenState()
            assertNull(state.saveError)
        }

        @Test
        @DisplayName("hasUnsavedChanges should be false when settings match original")
        fun noUnsavedChangesWhenMatching() {
            val settings = AppSettings()
            val serviceConfig = TranslationServiceConfig()
            val state = SettingsScreenState(
                settings = settings,
                serviceConfig = serviceConfig,
                originalSettings = settings,
                originalServiceConfig = serviceConfig
            )
            assertFalse(state.hasUnsavedChanges)
        }

        @Test
        @DisplayName("hasUnsavedChanges should be true when settings differ from original")
        fun unsavedChangesWhenSettingsDiffer() {
            val original = AppSettings()
            val modified = original.copy(
                ui = original.ui.copy(defaultOutputDirectory = "/new/path")
            )
            val state = SettingsScreenState(
                settings = modified,
                originalSettings = original
            )
            assertTrue(state.hasUnsavedChanges)
        }

        @Test
        @DisplayName("hasUnsavedChanges should be true when service config differs from original")
        fun unsavedChangesWhenServiceConfigDiffers() {
            val original = TranslationServiceConfig()
            val modified = original.copy(
                deeplApiKey = "new-key"
            )
            val state = SettingsScreenState(
                serviceConfig = modified,
                originalServiceConfig = original
            )
            assertTrue(state.hasUnsavedChanges)
        }
    }

    @Nested
    @DisplayName("Tab Navigation")
    inner class TabNavigationTest {

        @Test
        @DisplayName("selectTab should change selected tab")
        fun selectTabChangesTab() {
            val viewModel = SettingsViewModel(appModule, testScope)

            assertEquals(SettingsTab.GENERAL, viewModel.state.selectedTab)

            viewModel.selectTab(SettingsTab.TRANSLATION)
            assertEquals(SettingsTab.TRANSLATION, viewModel.state.selectedTab)
        }

        @Test
        @DisplayName("selectTab should work for all tabs")
        fun selectTabWorksForAllTabs() {
            val viewModel = SettingsViewModel(appModule, testScope)

            SettingsTab.entries.forEach { tab ->
                viewModel.selectTab(tab)
                assertEquals(tab, viewModel.state.selectedTab)
            }
        }

        @Test
        @DisplayName("Tab selection should not affect unsaved changes")
        fun tabSelectionDoesNotAffectChanges() {
            val viewModel = SettingsViewModel(appModule, testScope)

            // Make a change
            viewModel.updateSettings { it.copy(ui = it.ui.copy(defaultOutputDirectory = "/new/path")) }
            assertTrue(viewModel.state.hasUnsavedChanges)

            // Change tabs
            viewModel.selectTab(SettingsTab.ABOUT)

            // Changes should still be detected
            assertTrue(viewModel.state.hasUnsavedChanges)
        }
    }

    @Nested
    @DisplayName("Settings Updates")
    inner class SettingsUpdatesTest {

        @Test
        @DisplayName("updateSettings should modify settings")
        fun updateSettingsModifies() {
            val viewModel = SettingsViewModel(appModule, testScope)

            val newPath = "/custom/output/path"
            viewModel.updateSettings { it.copy(ui = it.ui.copy(defaultOutputDirectory = newPath)) }

            assertEquals(newPath, viewModel.state.settings.ui.defaultOutputDirectory)
        }

        @Test
        @DisplayName("updateSettings should mark state as having unsaved changes")
        fun updateSettingsMarksUnsaved() {
            val viewModel = SettingsViewModel(appModule, testScope)

            assertFalse(viewModel.state.hasUnsavedChanges)

            viewModel.updateSettings { it.copy(ui = it.ui.copy(defaultOutputDirectory = "/new/path")) }

            assertTrue(viewModel.state.hasUnsavedChanges)
        }

        @Test
        @DisplayName("updateSettings should not modify original settings")
        fun updateSettingsDoesNotModifyOriginal() {
            val viewModel = SettingsViewModel(appModule, testScope)
            val originalPath = viewModel.state.originalSettings.ui.defaultOutputDirectory

            viewModel.updateSettings { it.copy(ui = it.ui.copy(defaultOutputDirectory = "/new/path")) }

            assertEquals(originalPath, viewModel.state.originalSettings.ui.defaultOutputDirectory)
        }

        @Test
        @DisplayName("updateServiceConfig should modify service config")
        fun updateServiceConfigModifies() {
            val viewModel = SettingsViewModel(appModule, testScope)

            val newKey = "new-api-key"
            viewModel.updateServiceConfig {
                it.copy(deeplApiKey = newKey)
            }

            assertEquals(newKey, viewModel.state.serviceConfig.deeplApiKey)
        }

        @Test
        @DisplayName("updateServiceConfig should mark state as having unsaved changes")
        fun updateServiceConfigMarksUnsaved() {
            val viewModel = SettingsViewModel(appModule, testScope)

            assertFalse(viewModel.state.hasUnsavedChanges)

            viewModel.updateServiceConfig { it.copy(deeplApiKey = "new-key") }

            assertTrue(viewModel.state.hasUnsavedChanges)
        }

        @Test
        @DisplayName("Multiple updates should accumulate")
        fun multipleUpdatesAccumulate() {
            val viewModel = SettingsViewModel(appModule, testScope)

            viewModel.updateSettings { it.copy(ui = it.ui.copy(defaultOutputDirectory = "/path1")) }
            viewModel.updateSettings { it.copy(ui = it.ui.copy(windowWidth = 1200)) }

            assertEquals("/path1", viewModel.state.settings.ui.defaultOutputDirectory)
            assertEquals(1200, viewModel.state.settings.ui.windowWidth)
        }
    }

    @Nested
    @DisplayName("Save Functionality")
    inner class SaveFunctionalityTest {

        @Test
        @DisplayName("saveChanges should call onSuccess when no changes")
        fun saveNoChangesCallsSuccess() = runTest {
            val viewModel = SettingsViewModel(appModule, this)

            var successCalled = false
            viewModel.saveChanges { successCalled = true }
            advanceUntilIdle()

            assertTrue(successCalled)
        }

        @Test
        @DisplayName("saveChanges should save settings to config manager")
        fun saveChangesSavesSettings() = runTest {
            val viewModel = SettingsViewModel(appModule, this)

            viewModel.updateSettings { it.copy(ui = it.ui.copy(defaultOutputDirectory = "/new/path")) }
            viewModel.saveChanges {}
            advanceUntilIdle()

            coVerify { configManager.saveSettings(any()) }
        }

        @Test
        @DisplayName("saveChanges should save service config to config manager")
        fun saveChangesSavesServiceConfig() = runTest {
            val viewModel = SettingsViewModel(appModule, this)

            viewModel.updateServiceConfig { it.copy(deeplApiKey = "new-key") }
            viewModel.saveChanges {}
            advanceUntilIdle()

            coVerify { configManager.saveTranslationServiceConfig(any()) }
        }

        @Test
        @DisplayName("saveChanges should set isSaving to false after save completes")
        fun saveChangesSetsSavingFalseAfterComplete() = runTest {
            val viewModel = SettingsViewModel(appModule, this)
            viewModel.updateSettings { it.copy(ui = it.ui.copy(defaultOutputDirectory = "/new/path")) }

            viewModel.saveChanges {}
            advanceUntilIdle()

            // After save completes, isSaving should be false
            assertFalse(viewModel.state.isSaving)
        }

        @Test
        @DisplayName("saveChanges should update original settings after save")
        fun saveChangesUpdatesOriginal() = runTest {
            val viewModel = SettingsViewModel(appModule, this)

            val newPath = "/new/path"
            viewModel.updateSettings { it.copy(ui = it.ui.copy(defaultOutputDirectory = newPath)) }
            viewModel.saveChanges {}
            advanceUntilIdle()

            assertEquals(newPath, viewModel.state.originalSettings.ui.defaultOutputDirectory)
            assertFalse(viewModel.state.hasUnsavedChanges)
        }

        @Test
        @DisplayName("saveChanges should call onSuccess callback")
        fun saveChangesCallsSuccess() = runTest {
            val viewModel = SettingsViewModel(appModule, this)

            viewModel.updateSettings { it.copy(ui = it.ui.copy(defaultOutputDirectory = "/new/path")) }

            var successCalled = false
            viewModel.saveChanges { successCalled = true }
            advanceUntilIdle()

            assertTrue(successCalled)
        }

        @Test
        @DisplayName("saveChanges should set saveError on failure")
        fun saveChangesSetsSaveError() = runTest {
            coEvery { configManager.saveSettings(any()) } throws RuntimeException("Save failed")

            val viewModel = SettingsViewModel(appModule, this)
            viewModel.updateSettings { it.copy(ui = it.ui.copy(defaultOutputDirectory = "/new/path")) }

            viewModel.saveChanges {}
            advanceUntilIdle()

            assertNotNull(viewModel.state.saveError)
            assertTrue(viewModel.state.saveError?.contains("Save failed") == true)
        }

        @Test
        @DisplayName("saveChanges should clear saveError on retry")
        fun saveChangesClearsSaveErrorOnRetry() = runTest {
            coEvery { configManager.saveSettings(any()) } throws RuntimeException("Save failed")

            val viewModel = SettingsViewModel(appModule, this)
            viewModel.updateSettings { it.copy(ui = it.ui.copy(defaultOutputDirectory = "/new/path")) }

            // First save fails
            viewModel.saveChanges {}
            advanceUntilIdle()
            assertNotNull(viewModel.state.saveError)

            // Reset mock to succeed
            coEvery { configManager.saveSettings(any()) } just Runs

            // Retry
            viewModel.saveChanges {}
            advanceUntilIdle()

            assertNull(viewModel.state.saveError)
        }
    }

    @Nested
    @DisplayName("Discard Functionality")
    inner class DiscardFunctionalityTest {

        @Test
        @DisplayName("discardChanges should revert settings to original")
        fun discardRevertsSettings() {
            val viewModel = SettingsViewModel(appModule, testScope)
            val originalPath = viewModel.state.settings.ui.defaultOutputDirectory

            viewModel.updateSettings { it.copy(ui = it.ui.copy(defaultOutputDirectory = "/new/path")) }
            assertNotEquals(originalPath, viewModel.state.settings.ui.defaultOutputDirectory)

            viewModel.discardChanges()

            assertEquals(originalPath, viewModel.state.settings.ui.defaultOutputDirectory)
        }

        @Test
        @DisplayName("discardChanges should revert service config to original")
        fun discardRevertsServiceConfig() {
            val viewModel = SettingsViewModel(appModule, testScope)
            val originalKey = viewModel.state.serviceConfig.deeplApiKey

            viewModel.updateServiceConfig { it.copy(deeplApiKey = "new-key") }
            assertNotEquals(originalKey, viewModel.state.serviceConfig.deeplApiKey)

            viewModel.discardChanges()

            assertEquals(originalKey, viewModel.state.serviceConfig.deeplApiKey)
        }

        @Test
        @DisplayName("discardChanges should clear unsaved changes flag")
        fun discardClearsUnsavedChanges() {
            val viewModel = SettingsViewModel(appModule, testScope)

            viewModel.updateSettings { it.copy(ui = it.ui.copy(defaultOutputDirectory = "/new/path")) }
            assertTrue(viewModel.state.hasUnsavedChanges)

            viewModel.discardChanges()

            assertFalse(viewModel.state.hasUnsavedChanges)
        }

        @Test
        @DisplayName("discardChanges should preserve selected tab")
        fun discardPreservesTab() {
            val viewModel = SettingsViewModel(appModule, testScope)

            viewModel.selectTab(SettingsTab.ABOUT)
            viewModel.updateSettings { it.copy(ui = it.ui.copy(defaultOutputDirectory = "/new/path")) }

            viewModel.discardChanges()

            assertEquals(SettingsTab.ABOUT, viewModel.state.selectedTab)
        }
    }

    @Nested
    @DisplayName("Reload Functionality")
    inner class ReloadFunctionalityTest {

        @Test
        @DisplayName("reload should fetch fresh settings from config manager")
        fun reloadFetchesFreshSettings() {
            val viewModel = SettingsViewModel(appModule, testScope)

            // Configure new settings to be returned
            val newSettings = AppSettings().copy(
                ui = UiSettings(defaultOutputDirectory = "/reloaded/path")
            )
            every { configManager.getSettings() } returns newSettings

            viewModel.reload()

            assertEquals("/reloaded/path", viewModel.state.settings.ui.defaultOutputDirectory)
            assertEquals("/reloaded/path", viewModel.state.originalSettings.ui.defaultOutputDirectory)
        }

        @Test
        @DisplayName("reload should clear unsaved changes")
        fun reloadClearsUnsavedChanges() {
            val viewModel = SettingsViewModel(appModule, testScope)

            viewModel.updateSettings { it.copy(ui = it.ui.copy(defaultOutputDirectory = "/new/path")) }
            assertTrue(viewModel.state.hasUnsavedChanges)

            viewModel.reload()

            assertFalse(viewModel.state.hasUnsavedChanges)
        }

        @Test
        @DisplayName("reload should reset selected tab to GENERAL")
        fun reloadResetsTab() {
            val viewModel = SettingsViewModel(appModule, testScope)

            viewModel.selectTab(SettingsTab.ABOUT)
            assertEquals(SettingsTab.ABOUT, viewModel.state.selectedTab)

            viewModel.reload()

            assertEquals(SettingsTab.GENERAL, viewModel.state.selectedTab)
        }
    }

    @Nested
    @DisplayName("ViewModel Initialization")
    inner class ViewModelInitializationTest {

        @Test
        @DisplayName("ViewModel should load settings from config manager")
        fun viewModelLoadsSettings() {
            val customSettings = AppSettings().copy(
                ui = UiSettings(defaultOutputDirectory = "/custom/path")
            )
            every { configManager.getSettings() } returns customSettings

            val viewModel = SettingsViewModel(appModule, testScope)

            assertEquals("/custom/path", viewModel.state.settings.ui.defaultOutputDirectory)
        }

        @Test
        @DisplayName("ViewModel should load service config from config manager")
        fun viewModelLoadsServiceConfig() {
            val customConfig = TranslationServiceConfig(
                deeplApiKey = "loaded-key"
            )
            every { configManager.getTranslationServiceConfig() } returns customConfig

            val viewModel = SettingsViewModel(appModule, testScope)

            assertEquals("loaded-key", viewModel.state.serviceConfig.deeplApiKey)
        }

        @Test
        @DisplayName("ViewModel should set original values equal to current on init")
        fun viewModelSetsOriginalOnInit() {
            val viewModel = SettingsViewModel(appModule, testScope)

            assertEquals(viewModel.state.settings, viewModel.state.originalSettings)
            assertEquals(viewModel.state.serviceConfig, viewModel.state.originalServiceConfig)
        }

        @Test
        @DisplayName("ViewModel should start with no unsaved changes")
        fun viewModelStartsNoUnsavedChanges() {
            val viewModel = SettingsViewModel(appModule, testScope)

            assertFalse(viewModel.state.hasUnsavedChanges)
        }
    }
}
