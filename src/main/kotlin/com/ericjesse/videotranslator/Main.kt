package com.ericjesse.videotranslator

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.ui.App
import com.ericjesse.videotranslator.ui.theme.VideoTranslatorTheme

fun main() = application {
    val appModule = AppModule()
    
    Window(
        onCloseRequest = ::exitApplication,
        title = "Video Translator",
        state = rememberWindowState(width = 700.dp, height = 650.dp)
    ) {
        VideoTranslatorTheme {
            App(appModule)
        }
    }
}
