package com.ericjesse.videotranslator.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ericjesse.videotranslator.domain.model.TranslationJob
import com.ericjesse.videotranslator.domain.model.TranslationService
import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.translation.LibreTranslateService
import com.ericjesse.videotranslator.infrastructure.translation.ServerStatus
import com.ericjesse.videotranslator.ui.error.GlobalErrorDisplay
import com.ericjesse.videotranslator.ui.screens.main.MainScreen
import com.ericjesse.videotranslator.ui.screens.main.MainViewModel
import com.ericjesse.videotranslator.ui.screens.progress.ProgressScreen
import com.ericjesse.videotranslator.ui.screens.progress.ProgressViewModel
import com.ericjesse.videotranslator.ui.screens.settings.SettingsScreen
import com.ericjesse.videotranslator.ui.screens.setup.SetupWizard
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.compose.koinInject

private val logger = KotlinLogging.logger {}

/**
 * Navigation destinations for the application.
 */
sealed class Screen {
    data object SetupWizard : Screen()
    data object Main : Screen()
    data class Progress(val job: TranslationJob) : Screen()
    data object Settings : Screen()
}

/**
 * Main application composable.
 * Handles navigation between screens.
 * Uses Koin dependency injection for services.
 */
@Composable
fun App() {
    // Inject dependencies using Koin
    val configManager: ConfigManager = koinInject()
    val libreTranslate: LibreTranslateService = koinInject()

    val isFirstRun = configManager.isFirstRun()

    var currentScreen by remember {
        mutableStateOf<Screen>(
            if (isFirstRun) Screen.SetupWizard else Screen.Main
        )
    }

    // Start LibreTranslate server on app startup (if setup is complete)
    LaunchedEffect(currentScreen) {
        // Only start when we're on Main screen (after setup is complete)
        if (currentScreen is Screen.Main) {
            val settings = configManager.getSettings()

            // Start server if LibreTranslate is the selected service and server is not running
            if (settings.translation.defaultService == TranslationService.LIBRE_TRANSLATE &&
                libreTranslate.status.value == ServerStatus.STOPPED) {
                logger.info { "Starting LibreTranslate server on app startup..." }
                val started = libreTranslate.start()
                if (started) {
                    logger.info { "LibreTranslate server started successfully" }
                } else {
                    logger.warn { "Failed to start LibreTranslate server" }
                }
            }
        }
    }

    // Navigation callbacks
    val navigateToMain: () -> Unit = {
        currentScreen = Screen.Main
    }

    val navigateToSettings: () -> Unit = {
        currentScreen = Screen.Settings
    }

    val navigateToProgress: (TranslationJob) -> Unit = { job ->
        currentScreen = Screen.Progress(job)
    }

    // Wrap everything with global error display
    GlobalErrorDisplay(
        onNavigateToSettings = navigateToSettings
    ) {
        // Render current screen
        when (val screen = currentScreen) {
            is Screen.SetupWizard -> {
                SetupWizard(
                    onComplete = navigateToMain
                )
            }

            is Screen.Main -> {
                // Create and remember MainViewModel
                val mainViewModel = remember { MainViewModel() }

                // Dispose ViewModel when leaving composition
                DisposableEffect(Unit) {
                    onDispose { mainViewModel.dispose() }
                }

                MainScreen(
                    viewModel = mainViewModel,
                    onTranslate = navigateToProgress,
                    onOpenSettings = navigateToSettings
                )
            }

            is Screen.Progress -> {
                // Create and remember ProgressViewModel for this job
                val progressViewModel = remember(screen.job) {
                    ProgressViewModel(screen.job)
                }

                // Dispose ViewModel when leaving composition
                DisposableEffect(screen.job) {
                    onDispose { progressViewModel.dispose() }
                }

                ProgressScreen(
                    viewModel = progressViewModel,
                    onTranslateAnother = navigateToMain,
                    onOpenSettings = navigateToSettings
                )
            }

            is Screen.Settings -> {
                SettingsScreen(
                    onBack = navigateToMain
                )
            }
        }
    }
}
