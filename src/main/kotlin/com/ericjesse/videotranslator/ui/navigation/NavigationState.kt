package com.ericjesse.videotranslator.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ericjesse.videotranslator.domain.model.TranslationJob

/**
 * Represents the different screens in the application.
 */
sealed class Screen {
    data object SetupWizard : Screen()
    data object Main : Screen()
    data class Progress(val job: TranslationJob) : Screen()
    data object Settings : Screen()
}

/**
 * Manages navigation state for the application.
 */
class NavigationState {
    var currentScreen: Screen? by mutableStateOf(null)
        private set
    
    private val backStack = mutableListOf<Screen>()
    
    /**
     * Navigates to a new screen.
     */
    fun navigateTo(screen: Screen) {
        currentScreen?.let { 
            if (it !is Screen.Progress) { // Don't add progress to back stack
                backStack.add(it) 
            }
        }
        currentScreen = screen
    }
    
    /**
     * Navigates back to the previous screen.
     * Returns true if navigation occurred, false if there was no back stack.
     */
    fun navigateBack(): Boolean {
        return if (backStack.isNotEmpty()) {
            currentScreen = backStack.removeLast()
            true
        } else {
            false
        }
    }
    
    /**
     * Clears the back stack and navigates to a screen.
     */
    fun navigateAndClearStack(screen: Screen) {
        backStack.clear()
        currentScreen = screen
    }
    
    /**
     * Checks if back navigation is possible.
     */
    fun canNavigateBack(): Boolean = backStack.isNotEmpty()
}
