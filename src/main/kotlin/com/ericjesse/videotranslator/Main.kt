package com.ericjesse.videotranslator

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.ui.App
import com.ericjesse.videotranslator.ui.theme.VideoTranslatorTheme
import java.awt.Dimension
import kotlin.system.exitProcess

fun main() = application {
    // Initialize Koin dependency injection
    AppModule.init()

    val windowState = rememberWindowState(
        width = 750.dp,
        height = 700.dp
    )

    Window(
        onCloseRequest = {
            AppModule.close()
            exitApplication()
            // Force JVM exit to ensure all background threads are terminated
            exitProcess(0)
        },
        title = "Linguini",
        state = windowState,
        resizable = true
    ) {
        // Set minimum window size
        window.minimumSize = Dimension(600, 500)

        VideoTranslatorTheme {
            App()
        }
    }
}
