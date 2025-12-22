package com.ericjesse.videotranslator.ui

import androidx.compose.runtime.*
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.ui.error.GlobalErrorDisplay
import com.ericjesse.videotranslator.ui.screens.*
import com.ericjesse.videotranslator.ui.screens.setup.SetupWizard

/**
 * Navigation destinations for the application.
 */
sealed class Screen {
    data object SetupWizard : Screen()
    data object Main : Screen()
    data class Progress(val jobId: String) : Screen()
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
    val navigateTo: (Screen) -> Unit = { screen ->
        currentScreen = screen
    }
    
    val navigateToMain: () -> Unit = {
        currentScreen = Screen.Main
    }
    
    val navigateToSettings: () -> Unit = {
        currentScreen = Screen.Settings
    }
    
    val navigateToProgress: (String) -> Unit = { jobId ->
        currentScreen = Screen.Progress(jobId)
    }
    
    val navigateBack: () -> Unit = {
        currentScreen = Screen.Main
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
                MainScreen(
                    appModule = appModule,
                    onStartTranslation = navigateToProgress,
                    onOpenSettings = navigateToSettings
                )
            }

            is Screen.Progress -> {
                ProgressScreen(
                    appModule = appModule,
                    jobId = screen.jobId,
                    onComplete = navigateToMain,
                    onCancel = navigateToMain,
                    onOpenSettings = navigateToSettings
                )
            }

            is Screen.Settings -> {
                SettingsScreen(
                    appModule = appModule,
                    onBack = navigateBack
                )
            }
        }
    }
}
