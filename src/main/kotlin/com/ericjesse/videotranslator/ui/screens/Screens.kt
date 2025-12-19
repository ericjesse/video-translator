package com.ericjesse.videotranslator.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.di.AppModule

/**
 * Main screen - primary interface for starting translations.
 * 
 * TODO: Implement full main screen with:
 * - YouTube URL input with paste button
 * - Source/target language selection
 * - Output options (soft/burned-in subtitles)
 * - Output location picker
 * - Translate button
 * 
 * See docs/ui-mockups.md for detailed screen specifications.
 */
@Composable
fun MainScreen(
    appModule: AppModule,
    onStartTranslation: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val i18n = appModule.i18nManager
    var youtubeUrl by remember { mutableStateOf("") }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = i18n["app.name"],
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        OutlinedTextField(
            value = youtubeUrl,
            onValueChange = { youtubeUrl = it },
            label = { Text(i18n["main.youtubeUrl"]) },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onOpenSettings) {
                Text(i18n["settings.title"])
            }
            
            Button(
                onClick = { onStartTranslation("job-1") },
                enabled = youtubeUrl.isNotBlank()
            ) {
                Text(i18n["main.translate"])
            }
        }
    }
}

/**
 * Progress screen - shows translation pipeline progress.
 * 
 * TODO: Implement full progress screen with:
 * - Video title display
 * - Pipeline stage indicators
 * - Progress bars per stage
 * - Log output panel (collapsible)
 * - Cancel button with confirmation
 * - Complete/error states
 * 
 * See docs/ui-mockups.md for detailed screen specifications.
 */
@Composable
fun ProgressScreen(
    appModule: AppModule,
    jobId: String,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val i18n = appModule.i18nManager
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = i18n["progress.title"],
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        CircularProgressIndicator()
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(i18n["progress.stage.downloading"])
        
        Spacer(modifier = Modifier.weight(1f))
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(onClick = onCancel) {
                Text(i18n["action.cancel"])
            }
        }
    }
}

/**
 * Settings screen - application configuration.
 * 
 * TODO: Implement full settings screen with tabs:
 * - General: language, default output location, default languages
 * - Translation: service selection, API keys
 * - Transcription: Whisper model selection, YouTube caption preference
 * - Subtitles: default output mode, burned-in styling
 * - Updates: auto-check, component versions
 * - About: version, licenses
 * 
 * See docs/ui-mockups.md for detailed screen specifications.
 */
@Composable
fun SettingsScreen(
    appModule: AppModule,
    onBack: () -> Unit
) {
    val i18n = appModule.i18nManager
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← ${i18n["action.back"]}")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = i18n["settings.title"],
                style = MaterialTheme.typography.headlineMedium
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Settings content goes here...")
        
        Spacer(modifier = Modifier.weight(1f))
        
        Button(onClick = onBack) {
            Text(i18n["action.save"])
        }
    }
}
