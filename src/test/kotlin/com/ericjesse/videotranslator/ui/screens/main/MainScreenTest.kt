package com.ericjesse.videotranslator.ui.screens.main

import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.domain.model.*
import com.ericjesse.videotranslator.domain.service.VideoDownloader
import com.ericjesse.videotranslator.infrastructure.config.AppSettings
import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.network.ConnectivityCheckResult
import com.ericjesse.videotranslator.infrastructure.network.ConnectivityChecker
import com.ericjesse.videotranslator.infrastructure.network.ConnectivityState
import com.ericjesse.videotranslator.infrastructure.network.ConnectivityStatus
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for MainScreen state management via MainViewModel and MainScreenState.
 *
 * Tests cover:
 * - URL input validation
 * - Language selection
 * - Output options
 * - Form submission validation
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("MainScreen Tests")
class MainScreenTest {

    private lateinit var appModule: AppModule
    private lateinit var configManager: ConfigManager
    private lateinit var videoDownloader: VideoDownloader
    private lateinit var i18nManager: I18nManager
    private lateinit var connectivityChecker: ConnectivityChecker
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    @BeforeEach
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        Dispatchers.setMain(testDispatcher)

        appModule = mockk(relaxed = true)
        configManager = mockk(relaxed = true)
        videoDownloader = mockk(relaxed = true)
        i18nManager = mockk(relaxed = true)
        connectivityChecker = mockk(relaxed = true)

        every { appModule.configManager } returns configManager
        every { appModule.videoDownloader } returns videoDownloader
        every { appModule.i18nManager } returns i18nManager
        every { appModule.connectivityChecker } returns connectivityChecker

        every { configManager.getSettings() } returns AppSettings()
        every { i18nManager[any()] } returns "Test String"
        every { connectivityChecker.currentStatus } returns ConnectivityStatus(
            state = ConnectivityState.CONNECTED,
            internetAvailable = true
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Nested
    @DisplayName("MainScreenState Form Validation")
    inner class FormValidationTest {

        @Test
        @DisplayName("Empty form should not be valid")
        fun emptyFormNotValid() {
            val state = MainScreenState()
            assertFalse(state.isFormValid)
        }

        @Test
        @DisplayName("Form with valid URL and output directory should be valid")
        fun validFormIsValid() {
            val state = MainScreenState(
                youtubeUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                outputDirectory = "/home/user/videos"
            )
            assertTrue(state.isFormValid)
        }

        @Test
        @DisplayName("Form with URL error should not be valid")
        fun formWithUrlErrorNotValid() {
            val state = MainScreenState(
                youtubeUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                urlError = "Invalid URL",
                outputDirectory = "/home/user/videos"
            )
            assertFalse(state.isFormValid)
        }

        @Test
        @DisplayName("Form without output directory should not be valid")
        fun formWithoutOutputDirectoryNotValid() {
            val state = MainScreenState(
                youtubeUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                outputDirectory = ""
            )
            assertFalse(state.isFormValid)
        }

        @Test
        @DisplayName("Form while validating should not be valid")
        fun formWhileValidatingNotValid() {
            val state = MainScreenState(
                youtubeUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                outputDirectory = "/home/user/videos",
                isValidating = true
            )
            assertFalse(state.isFormValid)
        }

        @Test
        @DisplayName("canTranslate should require videoInfo and output directory")
        fun canTranslateRequirements() {
            // canTranslate requires videoInfo (from successful metadata fetch) and output directory
            val mockVideoInfo = VideoInfo(
                url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                id = "dQw4w9WgXcQ",
                title = "Test Video",
                duration = 60000L
            )

            val validState = MainScreenState(
                youtubeUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                outputDirectory = "/home/user/videos",
                videoInfo = mockVideoInfo
            )
            assertTrue(validState.canTranslate)

            // Without videoInfo, canTranslate should be false
            val noVideoInfoState = validState.copy(videoInfo = null)
            assertFalse(noVideoInfoState.canTranslate)

            // Without output directory, canTranslate should be false
            val noOutputState = validState.copy(outputDirectory = "")
            assertFalse(noOutputState.canTranslate)
        }
    }

    @Nested
    @DisplayName("URL Validation")
    inner class UrlValidationTest {

        @Test
        @DisplayName("Standard YouTube watch URL should be valid")
        fun standardWatchUrlValid() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onUrlChanged("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.state.urlError)
        }

        @Test
        @DisplayName("Short YouTube URL (youtu.be) should be valid")
        fun shortUrlValid() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onUrlChanged("https://youtu.be/dQw4w9WgXcQ")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.state.urlError)
        }

        @Test
        @DisplayName("YouTube Shorts URL should be valid")
        fun shortsUrlValid() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onUrlChanged("https://www.youtube.com/shorts/dQw4w9WgXcQ")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.state.urlError)
        }

        @Test
        @DisplayName("HTTP URL (without HTTPS) should be valid")
        fun httpUrlValid() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onUrlChanged("http://www.youtube.com/watch?v=dQw4w9WgXcQ")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.state.urlError)
        }

        @Test
        @DisplayName("URL without www should be valid")
        fun urlWithoutWwwValid() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onUrlChanged("https://youtube.com/watch?v=dQw4w9WgXcQ")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.state.urlError)
        }

        @Test
        @DisplayName("Invalid URL should set error")
        fun invalidUrlSetsError() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onUrlChanged("https://vimeo.com/123456")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNotNull(viewModel.state.urlError)
        }

        @Test
        @DisplayName("Random text should set error")
        fun randomTextSetsError() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onUrlChanged("not a url at all")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNotNull(viewModel.state.urlError)
        }

        @Test
        @DisplayName("Empty URL should not set error")
        fun emptyUrlNoError() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onUrlChanged("")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.state.urlError)
        }

        @Test
        @DisplayName("URL with extra parameters should be valid")
        fun urlWithParametersValid() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onUrlChanged("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s&list=PLtest")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.state.urlError)
        }
    }

    @Nested
    @DisplayName("Language Selection")
    inner class LanguageSelectionTest {

        @Test
        @DisplayName("Source language should default to English")
        fun sourceLanguageDefaultsToEnglish() {
            val viewModel = MainViewModel(appModule, testScope)
            assertEquals(Language.ENGLISH, viewModel.state.sourceLanguage)
        }

        @Test
        @DisplayName("Target language should have default value")
        fun targetLanguageHasDefault() {
            val viewModel = MainViewModel(appModule, testScope)
            assertEquals(Language.ENGLISH, viewModel.state.targetLanguage)
        }

        @Test
        @DisplayName("Source language should be changeable")
        fun sourceLanguageChangeable() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onSourceLanguageChanged(Language.GERMAN)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(Language.GERMAN, viewModel.state.sourceLanguage)
        }

        @Test
        @DisplayName("Source language can be set back to null")
        fun sourceLanguageCanBeNull() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onSourceLanguageChanged(Language.GERMAN)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(Language.GERMAN, viewModel.state.sourceLanguage)

            viewModel.onSourceLanguageChanged(null)
            testDispatcher.scheduler.advanceUntilIdle()
            assertNull(viewModel.state.sourceLanguage)
        }

        @Test
        @DisplayName("Target language should be changeable")
        fun targetLanguageChangeable() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onTargetLanguageChanged(Language.FRENCH)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(Language.FRENCH, viewModel.state.targetLanguage)
        }

        @Test
        @DisplayName("Language changes should be persisted")
        fun languageChangesPersisted() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onSourceLanguageChanged(Language.GERMAN)
            viewModel.onTargetLanguageChanged(Language.FRENCH)
            testDispatcher.scheduler.advanceUntilIdle()

            verify(atLeast = 1) { configManager.updateSettings(any()) }
        }
    }

    @Nested
    @DisplayName("Output Options")
    inner class OutputOptionsTest {

        @Test
        @DisplayName("Default subtitle type should be BURNED_IN")
        fun defaultSubtitleTypeBurnedIn() {
            val viewModel = MainViewModel(appModule, testScope)
            assertEquals(SubtitleType.BURNED_IN, viewModel.state.subtitleType)
        }

        @Test
        @DisplayName("Subtitle type should be changeable")
        fun subtitleTypeChangeable() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onSubtitleTypeChanged(SubtitleType.BURNED_IN)

            assertEquals(SubtitleType.BURNED_IN, viewModel.state.subtitleType)
        }

        @Test
        @DisplayName("Export SRT should be changeable")
        fun exportSrtChangeable() {
            val viewModel = MainViewModel(appModule, testScope)
            assertFalse(viewModel.state.exportSrt)

            viewModel.onExportSrtChanged(true)

            assertTrue(viewModel.state.exportSrt)
        }

        @Test
        @DisplayName("Output directory should be changeable")
        fun outputDirectoryChangeable() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onOutputDirectoryChanged("/new/path")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("/new/path", viewModel.state.outputDirectory)
        }

        @Test
        @DisplayName("Output directory changes should be persisted")
        fun outputDirectoryPersisted() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onOutputDirectoryChanged("/new/path")
            testDispatcher.scheduler.advanceUntilIdle()

            verify(atLeast = 1) { configManager.updateSettings(any()) }
        }
    }

    @Nested
    @DisplayName("Burned-In Subtitle Style")
    inner class BurnedInStyleTest {

        @Test
        @DisplayName("Default burned-in style should be set")
        fun defaultBurnedInStyle() {
            val viewModel = MainViewModel(appModule, testScope)
            val style = viewModel.state.burnedInStyle

            assertNotNull(style)
            assertEquals(24, style.fontSize)  // Default from BurnedInSubtitleStyle
        }

        @Test
        @DisplayName("Burned-in style should be changeable")
        fun burnedInStyleChangeable() {
            val viewModel = MainViewModel(appModule, testScope)
            val newStyle = BurnedInSubtitleStyle(
                fontSize = 32,
                fontColor = "#FF0000",
                backgroundColor = BackgroundColor.BLACK,
                backgroundOpacity = 0.8f
            )

            viewModel.onBurnedInStyleChanged(newStyle)

            assertEquals(newStyle, viewModel.state.burnedInStyle)
        }

        @Test
        @DisplayName("Background color should be changeable")
        fun backgroundColorChangeable() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onBackgroundColorChanged(BackgroundColor.BLACK)

            assertEquals(BackgroundColor.BLACK, viewModel.state.burnedInStyle.backgroundColor)
        }

        @Test
        @DisplayName("Background opacity should be changeable")
        fun backgroundOpacityChangeable() {
            val viewModel = MainViewModel(appModule, testScope)

            viewModel.onBackgroundOpacityChanged(0.5f)

            assertEquals(0.5f, viewModel.state.burnedInStyle.backgroundOpacity)
        }
    }

    @Nested
    @DisplayName("Form Submission")
    inner class FormSubmissionTest {

        @Test
        @DisplayName("onTranslateClicked should return null if form is invalid")
        fun translateWithInvalidFormReturnsNull() {
            val viewModel = MainViewModel(appModule, testScope)

            val result = viewModel.onTranslateClicked()

            assertNull(result)
        }

        @Test
        @DisplayName("onTranslateClicked should return TranslationJob if form is valid")
        fun translateWithValidFormReturnsJob() {
            every { connectivityChecker.currentStatus } returns ConnectivityStatus(
                state = ConnectivityState.CONNECTED,
                internetAvailable = true
            )

            val viewModel = MainViewModel(appModule, testScope)
            viewModel.onUrlChanged("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            viewModel.onOutputDirectoryChanged("/home/user/videos")
            testDispatcher.scheduler.advanceUntilIdle()

            val result = viewModel.onTranslateClicked()

            assertNotNull(result)
            assertEquals(Language.ENGLISH, result?.targetLanguage)
        }

        @Test
        @DisplayName("onTranslateClicked should show connectivity dialog if no internet")
        fun translateWithNoInternetShowsDialog() {
            every { connectivityChecker.currentStatus } returns ConnectivityStatus(
                state = ConnectivityState.DISCONNECTED,
                internetAvailable = false
            )

            val viewModel = MainViewModel(appModule, testScope)
            viewModel.onUrlChanged("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            viewModel.onOutputDirectoryChanged("/home/user/videos")
            testDispatcher.scheduler.advanceUntilIdle()

            val result = viewModel.onTranslateClicked()

            assertNull(result)
            assertTrue(viewModel.state.showConnectivityDialog)
        }

        @Test
        @DisplayName("TranslationJob should contain correct video info")
        fun translationJobHasCorrectVideoInfo() {
            every { connectivityChecker.currentStatus } returns ConnectivityStatus(
                state = ConnectivityState.CONNECTED,
                internetAvailable = true
            )

            val videoInfo = VideoInfo(
                url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                id = "dQw4w9WgXcQ",
                title = "Test Video",
                duration = 213L
            )

            val viewModel = MainViewModel(appModule, testScope)
            viewModel.onUrlChanged("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            viewModel.onOutputDirectoryChanged("/home/user/videos")

            // Simulate video info being fetched
            coEvery { videoDownloader.fetchVideoInfo(any()) } returns videoInfo
            testDispatcher.scheduler.advanceUntilIdle()

            val result = viewModel.onTranslateClicked()

            assertNotNull(result)
        }

        @Test
        @DisplayName("TranslationJob should contain correct output options for SOFT subtitles")
        fun translationJobHasCorrectSoftSubtitleOptions() {
            every { connectivityChecker.currentStatus } returns ConnectivityStatus(
                state = ConnectivityState.CONNECTED,
                internetAvailable = true
            )

            val viewModel = MainViewModel(appModule, testScope)
            viewModel.onUrlChanged("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            viewModel.onOutputDirectoryChanged("/home/user/videos")
            viewModel.onSubtitleTypeChanged(SubtitleType.SOFT)
            viewModel.onExportSrtChanged(true)
            testDispatcher.scheduler.advanceUntilIdle()

            val result = viewModel.onTranslateClicked()

            assertNotNull(result)
            assertEquals(SubtitleType.SOFT, result?.outputOptions?.subtitleType)
            assertTrue(result?.outputOptions?.exportSrt == true)
            assertNull(result?.outputOptions?.burnedInStyle)
        }

        @Test
        @DisplayName("TranslationJob should contain correct output options for BURNED_IN subtitles")
        fun translationJobHasCorrectBurnedInSubtitleOptions() {
            every { connectivityChecker.currentStatus } returns ConnectivityStatus(
                state = ConnectivityState.CONNECTED,
                internetAvailable = true
            )

            val viewModel = MainViewModel(appModule, testScope)
            viewModel.onUrlChanged("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            viewModel.onOutputDirectoryChanged("/home/user/videos")
            viewModel.onSubtitleTypeChanged(SubtitleType.BURNED_IN)
            viewModel.onBurnedInStyleChanged(BurnedInSubtitleStyle(fontSize = 28))
            testDispatcher.scheduler.advanceUntilIdle()

            val result = viewModel.onTranslateClicked()

            assertNotNull(result)
            assertEquals(SubtitleType.BURNED_IN, result?.outputOptions?.subtitleType)
            assertEquals(28, result?.outputOptions?.burnedInStyle?.fontSize)
        }
    }

    @Nested
    @DisplayName("Connectivity Dialog")
    inner class ConnectivityDialogTest {

        @Test
        @DisplayName("Connectivity dialog should be dismissable")
        fun connectivityDialogDismissable() {
            val viewModel = MainViewModel(appModule, testScope)
            viewModel.onUrlChanged("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            viewModel.onOutputDirectoryChanged("/home/user/videos")

            every { connectivityChecker.currentStatus } returns ConnectivityStatus(
                state = ConnectivityState.DISCONNECTED,
                internetAvailable = false
            )
            viewModel.onTranslateClicked()

            assertTrue(viewModel.state.showConnectivityDialog)

            viewModel.dismissConnectivityDialog()

            assertFalse(viewModel.state.showConnectivityDialog)
        }

        @Test
        @DisplayName("proceedAnywayWithTranslation should dismiss dialog and return job")
        fun proceedAnywayCreatesJob() {
            every { connectivityChecker.currentStatus } returns ConnectivityStatus(
                state = ConnectivityState.DISCONNECTED,
                internetAvailable = false
            )

            val viewModel = MainViewModel(appModule, testScope)
            viewModel.onUrlChanged("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            viewModel.onOutputDirectoryChanged("/home/user/videos")
            testDispatcher.scheduler.advanceUntilIdle()

            // First trigger the dialog
            viewModel.onTranslateClicked()
            assertTrue(viewModel.state.showConnectivityDialog)

            // Now proceed anyway
            val result = viewModel.proceedAnywayWithTranslation()

            assertFalse(viewModel.state.showConnectivityDialog)
            assertNotNull(result)
        }
    }

    @Nested
    @DisplayName("Reset Functionality")
    inner class ResetTest {

        @Test
        @DisplayName("reset should clear all form state")
        fun resetClearsState() {
            val viewModel = MainViewModel(appModule, testScope)

            // Set various state values
            viewModel.onUrlChanged("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            viewModel.onSourceLanguageChanged(Language.GERMAN)
            viewModel.onTargetLanguageChanged(Language.FRENCH)
            viewModel.onSubtitleTypeChanged(SubtitleType.BURNED_IN)
            testDispatcher.scheduler.advanceUntilIdle()

            // Reset
            viewModel.reset()
            testDispatcher.scheduler.advanceUntilIdle()

            // Verify URL is cleared
            assertEquals("", viewModel.state.youtubeUrl)
        }
    }
}
