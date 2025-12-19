@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.setup

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.di.AppModule

/**
 * Setup wizard state.
 */
enum class SetupStep {
    WELCOME,
    DEPENDENCIES,
    DOWNLOADING,
    TRANSLATION_SERVICE,
    COMPLETE
}

/**
 * Setup wizard - guides first-time users through initial configuration.
 * 
 * Implements the following steps:
 * 1. Welcome - Language selection
 * 2. Dependencies - Show required components and Whisper model selection
 * 3. Downloading - Progress for downloading components
 * 4. Translation Service - Configure translation provider
 * 5. Complete - Success message and start button
 * 
 * See docs/ui-mockups.md for detailed screen specifications.
 */
@Composable
fun SetupWizard(
    appModule: AppModule,
    onComplete: () -> Unit
) {
    var currentStep by remember { mutableStateOf(SetupStep.WELCOME) }
    val i18n = appModule.i18nManager
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (currentStep) {
            SetupStep.WELCOME -> {
                WelcomeStep(
                    i18n = i18n,
                    onNext = { currentStep = SetupStep.DEPENDENCIES }
                )
            }
            SetupStep.DEPENDENCIES -> {
                DependenciesStep(
                    i18n = i18n,
                    onBack = { currentStep = SetupStep.WELCOME },
                    onNext = { currentStep = SetupStep.DOWNLOADING }
                )
            }
            SetupStep.DOWNLOADING -> {
                DownloadingStep(
                    appModule = appModule,
                    onComplete = { currentStep = SetupStep.TRANSLATION_SERVICE },
                    onCancel = { currentStep = SetupStep.DEPENDENCIES }
                )
            }
            SetupStep.TRANSLATION_SERVICE -> {
                TranslationServiceStep(
                    appModule = appModule,
                    onBack = { currentStep = SetupStep.DEPENDENCIES },
                    onNext = { currentStep = SetupStep.COMPLETE }
                )
            }
            SetupStep.COMPLETE -> {
                CompleteStep(
                    i18n = i18n,
                    appModule = appModule,
                    onStart = onComplete
                )
            }
        }
    }
}

/**
 * Step 1: Welcome screen with language selection.
 */
@Composable
private fun WelcomeStep(
    i18n: com.ericjesse.videotranslator.ui.i18n.I18nManager,
    onNext: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // TODO: Add app icon
            
            Text(
                text = i18n["app.name"],
                style = MaterialTheme.typography.displaySmall
            )
            
            Text(
                text = i18n["app.tagline"],
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = i18n["setup.welcome.selectLanguage"],
                style = MaterialTheme.typography.bodyMedium
            )
            
            // TODO: Language dropdown
            // For now, placeholder
            var expanded by remember { mutableStateOf(false) }
            var selectedLanguage by remember { mutableStateOf("English") }
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedLanguage,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor()
                )
                
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("English") },
                        onClick = { 
                            selectedLanguage = "English"
                            i18n.setLocale(com.ericjesse.videotranslator.ui.i18n.Locale.ENGLISH)
                            expanded = false 
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Deutsch") },
                        onClick = { 
                            selectedLanguage = "Deutsch"
                            i18n.setLocale(com.ericjesse.videotranslator.ui.i18n.Locale.GERMAN)
                            expanded = false 
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Français") },
                        onClick = { 
                            selectedLanguage = "Français"
                            i18n.setLocale(com.ericjesse.videotranslator.ui.i18n.Locale.FRENCH)
                            expanded = false 
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(onClick = onNext) {
                Text(i18n["setup.welcome.getStarted"])
            }
        }
    }
}

/**
 * Step 2: Dependencies overview and Whisper model selection.
 */
@Composable
private fun DependenciesStep(
    i18n: com.ericjesse.videotranslator.ui.i18n.I18nManager,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← ${i18n["action.back"]}")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = i18n["setup.dependencies.title"],
                style = MaterialTheme.typography.headlineSmall
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = i18n["setup.dependencies.description"],
            style = MaterialTheme.typography.bodyMedium
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Component list
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // yt-dlp
                ComponentItem(
                    name = i18n["component.ytdlp.name"],
                    description = i18n["component.ytdlp.description"],
                    size = "12.4 MB"
                )
                
                HorizontalDivider()
                
                // FFmpeg
                ComponentItem(
                    name = i18n["component.ffmpeg.name"],
                    description = i18n["component.ffmpeg.description"],
                    size = "85.2 MB"
                )
                
                HorizontalDivider()
                
                // Whisper
                ComponentItem(
                    name = i18n["component.whisper.name"],
                    description = i18n["component.whisper.description"],
                    size = "142 MB"
                )
                
                // TODO: Add Whisper model selection dropdown
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = i18n["setup.dependencies.totalSize", "~240 MB"],
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = onNext,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(i18n["setup.dependencies.downloadInstall"])
        }
    }
}

@Composable
private fun ComponentItem(
    name: String,
    description: String,
    size: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = size,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Step 3: Downloading progress.
 */
@Composable
private fun DownloadingStep(
    appModule: AppModule,
    onComplete: () -> Unit,
    onCancel: () -> Unit
) {
    val i18n = appModule.i18nManager
    
    // TODO: Implement actual download logic using UpdateManager
    // For now, show placeholder progress
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = i18n["setup.downloading.title"],
            style = MaterialTheme.typography.headlineSmall
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Progress indicators
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // yt-dlp - complete
                DownloadItem(
                    name = "yt-dlp",
                    status = i18n["setup.downloading.status.complete"],
                    progress = 1f,
                    isComplete = true
                )
                
                // FFmpeg - in progress
                DownloadItem(
                    name = "FFmpeg",
                    status = "67%",
                    progress = 0.67f,
                    isComplete = false
                )
                
                // Whisper - pending
                DownloadItem(
                    name = "Whisper base",
                    status = i18n["setup.downloading.status.pending"],
                    progress = 0f,
                    isComplete = false
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Overall progress
        Text(
            text = i18n["setup.downloading.overall"],
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { 0.45f },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // TODO: Replace with actual download completion detection
        // For now, add a "Skip" button for development
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            OutlinedButton(onClick = onCancel) {
                Text(i18n["action.cancel"])
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onComplete) {
                Text("Skip (Dev)") // TODO: Remove in production
            }
        }
    }
}

@Composable
private fun DownloadItem(
    name: String,
    status: String,
    progress: Float,
    isComplete: Boolean
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isComplete) {
                    Text("✓", color = MaterialTheme.colorScheme.primary)
                } else if (progress > 0) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("○", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(name)
            }
            Text(status)
        }
        if (progress > 0 && !isComplete) {
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Step 4: Translation service configuration.
 */
@Composable
private fun TranslationServiceStep(
    appModule: AppModule,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val i18n = appModule.i18nManager
    var selectedService by remember { mutableStateOf("libretranslate") }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← ${i18n["action.back"]}")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = i18n["setup.translation.title"],
                style = MaterialTheme.typography.headlineSmall
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = i18n["setup.translation.description"],
            style = MaterialTheme.typography.bodyMedium
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Service options
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // LibreTranslate
                ServiceOption(
                    name = i18n["service.libretranslate.name"],
                    description = i18n["service.libretranslate.description"],
                    isSelected = selectedService == "libretranslate",
                    isRecommended = true,
                    onSelect = { selectedService = "libretranslate" }
                )
                
                HorizontalDivider()
                
                // DeepL
                ServiceOption(
                    name = i18n["service.deepl.name"],
                    description = i18n["service.deepl.description"],
                    isSelected = selectedService == "deepl",
                    onSelect = { selectedService = "deepl" }
                )
                
                HorizontalDivider()
                
                // OpenAI
                ServiceOption(
                    name = i18n["service.openai.name"],
                    description = i18n["service.openai.description"],
                    isSelected = selectedService == "openai",
                    onSelect = { selectedService = "openai" }
                )
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = onNext,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(i18n["action.continue"])
        }
    }
}

@Composable
private fun ServiceOption(
    name: String,
    description: String,
    isSelected: Boolean,
    isRecommended: Boolean = false,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onSelect
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall
                )
                if (isRecommended) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "Recommended",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Step 5: Setup complete.
 */
@Composable
private fun CompleteStep(
    i18n: com.ericjesse.videotranslator.ui.i18n.I18nManager,
    appModule: AppModule,
    onStart: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Success icon
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Text(
                text = i18n["setup.complete.title"],
                style = MaterialTheme.typography.headlineMedium
            )
            
            Text(
                text = i18n["setup.complete.description"],
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Summary card
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("✓ yt-dlp")
                    Text("✓ FFmpeg")
                    Text("✓ Whisper base model")
                    Text("✓ LibreTranslate configured")
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(onClick = onStart) {
                Text(i18n["setup.complete.startTranslating"])
            }
        }
    }
}
