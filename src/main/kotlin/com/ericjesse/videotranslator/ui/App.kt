package com.ericjesse.videotranslator.ui

import androidx.compose.runtime.*
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.domain.model.TranslationJob
import com.ericjesse.videotranslator.ui.error.GlobalErrorDisplay
import com.ericjesse.videotranslator.ui.screens.main.MainScreen
import com.ericjesse.videotranslator.ui.screens.main.MainViewModel
import com.ericjesse.videotranslator.ui.screens.progress.ProgressScreen
import com.ericjesse.videotranslator.ui.screens.progress.ProgressViewModel
import com.ericjesse.videotranslator.ui.screens.settings.SettingsScreen
import com.ericjesse.videotranslator.ui.screens.setup.SetupWizard

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
 */
@Composable
fun App(appModule: AppModule) {
    val configManager = appModule.configManager
    val isFirstRun = configManager.isFirstRun()

    var currentScreen by remember {
        mutableStateOf<Screen>(
            if (isFirstRun) Screen.SetupWizard else Screen.Main
        )
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
                    appModule = appModule,
                    onComplete = navigateToMain
                )
            }

            is Screen.Main -> {
                // Create and remember MainViewModel
                val mainViewModel = remember { MainViewModel(appModule) }

                // Dispose ViewModel when leaving composition
                DisposableEffect(Unit) {
                    onDispose { mainViewModel.dispose() }
                }

                MainScreen(
                    appModule = appModule,
                    viewModel = mainViewModel,
                    onTranslate = navigateToProgress,
                    onOpenSettings = navigateToSettings
                )
            }

            is Screen.Progress -> {
                // Create and remember ProgressViewModel for this job
                val progressViewModel = remember(screen.job) {
                    ProgressViewModel(appModule, screen.job)
                }

                // Dispose ViewModel when leaving composition
                DisposableEffect(screen.job) {
                    onDispose { progressViewModel.dispose() }
                }

                ProgressScreen(
                    appModule = appModule,
                    viewModel = progressViewModel,
                    onTranslateAnother = navigateToMain,
                    onOpenSettings = navigateToSettings
                )
            }

            is Screen.Settings -> {
                SettingsScreen(
                    appModule = appModule,
                    onBack = navigateToMain
                )
            }
        }
    }
}
