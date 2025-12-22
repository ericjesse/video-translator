package com.ericjesse.videotranslator.ui.screens.setup

import androidx.compose.ui.test.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for SetupWizard screen and state management.
 *
 * Tests cover:
 * - Navigation between wizard steps
 * - State transitions
 * - Backward/forward navigation rules
 * - Dependency download flow
 */
@DisplayName("SetupWizard Tests")
class SetupWizardTest {

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Nested
    @DisplayName("SetupWizardState Navigation")
    inner class NavigationTest {

        @Test
        @DisplayName("Initial state should be WELCOME step")
        fun initialStateIsWelcome() {
            val state = SetupWizardState()
            assertEquals(SetupStep.WELCOME, state.currentStep)
        }

        @Test
        @DisplayName("goNext from WELCOME should navigate to DEPENDENCIES")
        fun welcomeToDependendencies() {
            val state = SetupWizardState()
            state.goNext()
            assertEquals(SetupStep.DEPENDENCIES, state.currentStep)
            assertEquals(1, state.navigationDirection)
        }

        @Test
        @DisplayName("goNext from DEPENDENCIES should navigate to DOWNLOADING")
        fun dependenciesToDownloading() {
            val state = SetupWizardState(initialStep = SetupStep.DEPENDENCIES)
            state.goNext()
            assertEquals(SetupStep.DOWNLOADING, state.currentStep)
        }

        @Test
        @DisplayName("goNext from DOWNLOADING should navigate to TRANSLATION_SERVICE")
        fun downloadingToTranslationService() {
            val state = SetupWizardState(initialStep = SetupStep.DOWNLOADING)
            state.goNext()
            assertEquals(SetupStep.TRANSLATION_SERVICE, state.currentStep)
        }

        @Test
        @DisplayName("goNext from TRANSLATION_SERVICE should navigate to COMPLETE")
        fun translationServiceToComplete() {
            val state = SetupWizardState(initialStep = SetupStep.TRANSLATION_SERVICE)
            state.goNext()
            assertEquals(SetupStep.COMPLETE, state.currentStep)
        }

        @Test
        @DisplayName("goNext from COMPLETE should stay at COMPLETE")
        fun completeStaysAtComplete() {
            val state = SetupWizardState(initialStep = SetupStep.COMPLETE)
            state.goNext()
            assertEquals(SetupStep.COMPLETE, state.currentStep)
        }

        @Test
        @DisplayName("Full navigation sequence should progress through all steps")
        fun fullNavigationSequence() {
            val state = SetupWizardState()

            assertEquals(SetupStep.WELCOME, state.currentStep)
            state.goNext()
            assertEquals(SetupStep.DEPENDENCIES, state.currentStep)
            state.goNext()
            assertEquals(SetupStep.DOWNLOADING, state.currentStep)
            state.goNext()
            assertEquals(SetupStep.TRANSLATION_SERVICE, state.currentStep)
            state.goNext()
            assertEquals(SetupStep.COMPLETE, state.currentStep)
        }
    }

    @Nested
    @DisplayName("Backward Navigation")
    inner class BackwardNavigationTest {

        @Test
        @DisplayName("canGoBack should be false on WELCOME step")
        fun cannotGoBackFromWelcome() {
            val state = SetupWizardState(initialStep = SetupStep.WELCOME)
            assertFalse(state.canGoBack())
        }

        @Test
        @DisplayName("canGoBack should be true on DEPENDENCIES step")
        fun canGoBackFromDependencies() {
            val state = SetupWizardState(initialStep = SetupStep.DEPENDENCIES)
            assertTrue(state.canGoBack())
        }

        @Test
        @DisplayName("canGoBack should be false on DOWNLOADING step")
        fun cannotGoBackFromDownloading() {
            val state = SetupWizardState(initialStep = SetupStep.DOWNLOADING)
            assertFalse(state.canGoBack())
        }

        @Test
        @DisplayName("canGoBack should be true on TRANSLATION_SERVICE step")
        fun canGoBackFromTranslationService() {
            val state = SetupWizardState(initialStep = SetupStep.TRANSLATION_SERVICE)
            assertTrue(state.canGoBack())
        }

        @Test
        @DisplayName("canGoBack should be true on COMPLETE step")
        fun canGoBackFromComplete() {
            val state = SetupWizardState(initialStep = SetupStep.COMPLETE)
            assertTrue(state.canGoBack())
        }

        @Test
        @DisplayName("goBack from DEPENDENCIES should navigate to WELCOME")
        fun backFromDependenciesToWelcome() {
            val state = SetupWizardState(initialStep = SetupStep.DEPENDENCIES)
            state.goBack()
            assertEquals(SetupStep.WELCOME, state.currentStep)
            assertEquals(-1, state.navigationDirection)
        }

        @Test
        @DisplayName("goBack from DOWNLOADING should navigate to DEPENDENCIES")
        fun backFromDownloadingToDependencies() {
            val state = SetupWizardState(initialStep = SetupStep.DOWNLOADING)
            state.goBack()
            assertEquals(SetupStep.DEPENDENCIES, state.currentStep)
        }

        @Test
        @DisplayName("goBack from TRANSLATION_SERVICE should navigate to DEPENDENCIES")
        fun backFromTranslationServiceToDependencies() {
            val state = SetupWizardState(initialStep = SetupStep.TRANSLATION_SERVICE)
            state.goBack()
            assertEquals(SetupStep.DEPENDENCIES, state.currentStep)
        }

        @Test
        @DisplayName("goBack from COMPLETE should navigate to TRANSLATION_SERVICE")
        fun backFromCompleteToTranslationService() {
            val state = SetupWizardState(initialStep = SetupStep.COMPLETE)
            state.goBack()
            assertEquals(SetupStep.TRANSLATION_SERVICE, state.currentStep)
        }

        @Test
        @DisplayName("goBack from WELCOME should stay at WELCOME")
        fun backFromWelcomeStaysAtWelcome() {
            val state = SetupWizardState(initialStep = SetupStep.WELCOME)
            state.goBack()
            assertEquals(SetupStep.WELCOME, state.currentStep)
        }
    }

    @Nested
    @DisplayName("Skip to Translation Service")
    inner class SkipToTranslationServiceTest {

        @Test
        @DisplayName("skipToTranslationService should navigate directly to TRANSLATION_SERVICE")
        fun skipToTranslationService() {
            val state = SetupWizardState(initialStep = SetupStep.DOWNLOADING)
            state.skipToTranslationService()
            assertEquals(SetupStep.TRANSLATION_SERVICE, state.currentStep)
        }

        @Test
        @DisplayName("skipToTranslationService should set dependenciesDownloaded to true")
        fun skipSetsDependenciesDownloaded() {
            val state = SetupWizardState(initialStep = SetupStep.DOWNLOADING)
            assertFalse(state.dependenciesDownloaded)
            state.skipToTranslationService()
            assertTrue(state.dependenciesDownloaded)
        }

        @Test
        @DisplayName("skipToTranslationService should set navigation direction to forward")
        fun skipSetsForwardDirection() {
            val state = SetupWizardState(initialStep = SetupStep.DOWNLOADING)
            state.skipToTranslationService()
            assertEquals(1, state.navigationDirection)
        }
    }

    @Nested
    @DisplayName("State Initialization")
    inner class StateInitializationTest {

        @Test
        @DisplayName("State should use provided initial step")
        fun initialStepIsRespected() {
            val state = SetupWizardState(initialStep = SetupStep.TRANSLATION_SERVICE)
            assertEquals(SetupStep.TRANSLATION_SERVICE, state.currentStep)
        }

        @Test
        @DisplayName("State should use provided initial Whisper model")
        fun initialWhisperModelIsRespected() {
            val state = SetupWizardState(initialWhisperModel = "large")
            assertEquals("large", state.selectedWhisperModel)
        }

        @Test
        @DisplayName("State should use provided initial translation service")
        fun initialTranslationServiceIsRespected() {
            val state = SetupWizardState(initialTranslationService = "deepl")
            assertEquals("deepl", state.selectedTranslationService)
        }

        @Test
        @DisplayName("State should have default values")
        fun defaultValues() {
            val state = SetupWizardState()
            assertEquals(SetupStep.WELCOME, state.currentStep)
            assertEquals("base", state.selectedWhisperModel)
            assertEquals("libretranslate", state.selectedTranslationService)
            assertFalse(state.dependenciesDownloaded)
            assertFalse(state.showCloseConfirmation)
            assertEquals(1, state.navigationDirection)
        }
    }

    @Nested
    @DisplayName("Model and Service Selection")
    inner class SelectionTest {

        @Test
        @DisplayName("selectedWhisperModel should be mutable")
        fun whisperModelIsMutable() {
            val state = SetupWizardState()
            assertEquals("base", state.selectedWhisperModel)
            state.selectedWhisperModel = "medium"
            assertEquals("medium", state.selectedWhisperModel)
        }

        @Test
        @DisplayName("selectedTranslationService should be mutable")
        fun translationServiceIsMutable() {
            val state = SetupWizardState()
            assertEquals("libretranslate", state.selectedTranslationService)
            state.selectedTranslationService = "openai"
            assertEquals("openai", state.selectedTranslationService)
        }

        @Test
        @DisplayName("showCloseConfirmation should be mutable")
        fun closeConfirmationIsMutable() {
            val state = SetupWizardState()
            assertFalse(state.showCloseConfirmation)
            state.showCloseConfirmation = true
            assertTrue(state.showCloseConfirmation)
        }
    }

    @Nested
    @DisplayName("SetupStep Enum")
    inner class SetupStepEnumTest {

        @Test
        @DisplayName("SetupStep.fromIndex should return correct step")
        fun fromIndexReturnsCorrectStep() {
            assertEquals(SetupStep.WELCOME, SetupStep.fromIndex(0))
            assertEquals(SetupStep.DEPENDENCIES, SetupStep.fromIndex(1))
            assertEquals(SetupStep.DOWNLOADING, SetupStep.fromIndex(2))
            assertEquals(SetupStep.TRANSLATION_SERVICE, SetupStep.fromIndex(3))
            assertEquals(SetupStep.COMPLETE, SetupStep.fromIndex(4))
        }

        @Test
        @DisplayName("SetupStep.fromIndex should return WELCOME for invalid index")
        fun fromIndexReturnsWelcomeForInvalidIndex() {
            assertEquals(SetupStep.WELCOME, SetupStep.fromIndex(-1))
            assertEquals(SetupStep.WELCOME, SetupStep.fromIndex(100))
        }

        @Test
        @DisplayName("SetupStep.visibleSteps should exclude DOWNLOADING")
        fun visibleStepsExcludesDownloading() {
            val visibleSteps = SetupStep.visibleSteps
            assertTrue(visibleSteps.contains(SetupStep.WELCOME))
            assertTrue(visibleSteps.contains(SetupStep.DEPENDENCIES))
            assertFalse(visibleSteps.contains(SetupStep.DOWNLOADING))
            assertTrue(visibleSteps.contains(SetupStep.TRANSLATION_SERVICE))
            assertTrue(visibleSteps.contains(SetupStep.COMPLETE))
        }

        @Test
        @DisplayName("SetupStep indices should be sequential")
        fun indicesAreSequential() {
            assertEquals(0, SetupStep.WELCOME.index)
            assertEquals(1, SetupStep.DEPENDENCIES.index)
            assertEquals(2, SetupStep.DOWNLOADING.index)
            assertEquals(3, SetupStep.TRANSLATION_SERVICE.index)
            assertEquals(4, SetupStep.COMPLETE.index)
        }
    }
}
