@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.main

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.domain.model.BackgroundColor
import com.ericjesse.videotranslator.domain.model.Language
import com.ericjesse.videotranslator.domain.model.SubtitleType
import com.ericjesse.videotranslator.domain.model.TranslationJob
import com.ericjesse.videotranslator.ui.components.*
import com.ericjesse.videotranslator.ui.components.CardElevation as AppCardElevation
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import com.ericjesse.videotranslator.ui.screens.main.components.BurnedInOptions
import com.ericjesse.videotranslator.ui.theme.AppColors

/**
 * Application version - should match build.gradle.kts
 */
private const val APP_VERSION = "1.0.0"

/**
 * Main screen of the application using ViewModel.
 *
 * Displays:
 * - Header with app name
 * - URL input section with paste button and validation
 * - Language selection (source and target)
 * - Output options (subtitle type, burned-in options, export SRT)
 * - Output location with browse button
 * - Footer with settings button and translate button
 *
 * @param appModule Application module for accessing services.
 * @param viewModel ViewModel managing the screen state.
 * @param onTranslate Callback when translation job is created.
 * @param onOpenSettings Callback when the user clicks the Settings button.
 * @param modifier Modifier to be applied to the screen.
 */
@Composable
fun MainScreen(
    appModule: AppModule,
    viewModel: MainViewModel,
    onTranslate: (TranslationJob) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = viewModel.state
    val i18n = appModule.i18nManager

    MainScreenContent(
        i18n = i18n,
        state = state,
        onUrlChange = viewModel::onUrlChanged,
        onPaste = viewModel::onPasteClicked,
        onSourceLanguageChange = viewModel::onSourceLanguageChanged,
        onTargetLanguageChange = viewModel::onTargetLanguageChanged,
        onSubtitleTypeChange = viewModel::onSubtitleTypeChanged,
        onBackgroundColorChange = viewModel::onBackgroundColorChanged,
        onBackgroundOpacityChange = viewModel::onBackgroundOpacityChanged,
        onExportSrtChange = viewModel::onExportSrtChanged,
        onOutputDirectoryChange = viewModel::onOutputDirectoryChanged,
        onBrowse = { viewModel.onBrowseClicked() },
        onTranslate = {
            viewModel.onTranslateClicked()?.let(onTranslate)
        },
        onOpenSettings = onOpenSettings,
        modifier = modifier
    )
}

/**
 * Main screen of the application with direct state management.
 * This overload is provided for simpler use cases or testing.
 *
 * @param appModule Application module for accessing services.
 * @param onTranslate Callback when the user clicks Translate with the job parameters.
 * @param onOpenSettings Callback when the user clicks the Settings button.
 * @param modifier Modifier to be applied to the screen.
 */
@Composable
fun MainScreen(
    appModule: AppModule,
    onTranslate: (url: String, sourceLanguage: Language?, targetLanguage: Language, subtitleType: SubtitleType, exportSrt: Boolean, outputPath: String, backgroundColor: BackgroundColor, backgroundOpacity: Float) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Create ViewModel internally
    val viewModel = remember { MainViewModel(appModule) }

    // Dispose ViewModel when leaving composition
    DisposableEffect(Unit) {
        onDispose { viewModel.dispose() }
    }

    MainScreen(
        appModule = appModule,
        viewModel = viewModel,
        onTranslate = { job ->
            onTranslate(
                job.videoInfo.url,
                job.sourceLanguage,
                job.targetLanguage,
                job.outputOptions.subtitleType,
                job.outputOptions.exportSrt,
                job.outputOptions.outputDirectory,
                job.outputOptions.burnedInStyle?.backgroundColor ?: BackgroundColor.NONE,
                job.outputOptions.burnedInStyle?.backgroundOpacity ?: 0f
            )
        },
        onOpenSettings = onOpenSettings,
        modifier = modifier
    )
}

/**
 * Internal composable that renders the main screen content.
 */
@Composable
private fun MainScreenContent(
    i18n: I18nManager,
    state: MainScreenState,
    onUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    onSourceLanguageChange: (Language?) -> Unit,
    onTargetLanguageChange: (Language) -> Unit,
    onSubtitleTypeChange: (SubtitleType) -> Unit,
    onBackgroundColorChange: (BackgroundColor) -> Unit,
    onBackgroundOpacityChange: (Float) -> Unit,
    onExportSrtChange: (Boolean) -> Unit,
    onOutputDirectoryChange: (String) -> Unit,
    onBrowse: () -> Unit,
    onTranslate: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        MainHeader(
            appName = i18n["app.name"],
            modifier = Modifier.fillMaxWidth()
        )

        // Scrollable content
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // URL Input Section
            UrlInputSection(
                i18n = i18n,
                url = state.youtubeUrl,
                onUrlChange = onUrlChange,
                error = state.urlError,
                onPaste = onPaste
            )

            // Language Selection Section
            LanguageSelectionSection(
                i18n = i18n,
                sourceLanguage = state.sourceLanguage,
                targetLanguage = state.targetLanguage,
                onSourceLanguageChange = onSourceLanguageChange,
                onTargetLanguageChange = onTargetLanguageChange
            )

            // Output Options Section
            OutputOptionsSection(
                i18n = i18n,
                subtitleType = state.subtitleType,
                onSubtitleTypeChange = onSubtitleTypeChange,
                exportSrt = state.exportSrt,
                onExportSrtChange = onExportSrtChange,
                backgroundColor = state.burnedInStyle.backgroundColor,
                onBackgroundColorChange = onBackgroundColorChange,
                backgroundOpacity = state.burnedInStyle.backgroundOpacity,
                onBackgroundOpacityChange = onBackgroundOpacityChange
            )

            // Output Location Section
            OutputLocationSection(
                i18n = i18n,
                path = state.outputDirectory,
                onPathChange = onOutputDirectoryChange,
                onBrowse = onBrowse
            )
        }

        // Footer
        MainFooter(
            i18n = i18n,
            version = APP_VERSION,
            onSettingsClick = onOpenSettings,
            onTranslateClick = onTranslate,
            translateEnabled = state.canTranslate
        )
    }
}

@Composable
private fun MainHeader(
    appName: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = appName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun UrlInputSection(
    i18n: I18nManager,
    url: String,
    onUrlChange: (String) -> Unit,
    error: String?,
    onPaste: () -> Unit
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = AppCardElevation.Low
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = i18n["main.youtubeUrl"],
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    placeholder = {
                        Text(
                            text = "https://www.youtube.com/watch?v=...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    isError = error != null,
                    supportingText = if (error != null) {
                        { Text(error) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                // Paste button
                IconButton(
                    onClick = onPaste,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = "Paste",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageSelectionSection(
    i18n: I18nManager,
    sourceLanguage: Language?,
    targetLanguage: Language,
    onSourceLanguageChange: (Language?) -> Unit,
    onTargetLanguageChange: (Language) -> Unit
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = AppCardElevation.Low
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Source Language
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = i18n["main.sourceLanguage"],
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )

                LanguageDropdown(
                    selectedLanguage = sourceLanguage,
                    onLanguageSelected = onSourceLanguageChange,
                    includeAutoDetect = true,
                    autoDetectLabel = i18n["main.autoDetect"],
                    i18n = i18n,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Target Language
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = i18n["main.targetLanguage"],
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )

                LanguageDropdown(
                    selectedLanguage = targetLanguage,
                    onLanguageSelected = { it?.let(onTargetLanguageChange) },
                    includeAutoDetect = false,
                    i18n = i18n,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun LanguageDropdown(
    selectedLanguage: Language?,
    onLanguageSelected: (Language?) -> Unit,
    includeAutoDetect: Boolean,
    i18n: I18nManager,
    modifier: Modifier = Modifier,
    autoDetectLabel: String = "Auto-detect"
) {
    var expanded by remember { mutableStateOf(false) }

    val displayText = selectedLanguage?.nativeName ?: autoDetectLabel

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // Auto-detect option
            if (includeAutoDetect) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = autoDetectLabel,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        onLanguageSelected(null)
                        expanded = false
                    },
                    leadingIcon = if (selectedLanguage == null) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // Language options
            Language.entries.forEach { language ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = language.nativeName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (language.nativeName != language.displayName) {
                                Text(
                                    text = language.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = {
                        onLanguageSelected(language)
                        expanded = false
                    },
                    leadingIcon = if (language == selectedLanguage) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null
                )
            }
        }
    }
}

@Composable
private fun OutputOptionsSection(
    i18n: I18nManager,
    subtitleType: SubtitleType,
    onSubtitleTypeChange: (SubtitleType) -> Unit,
    exportSrt: Boolean,
    onExportSrtChange: (Boolean) -> Unit,
    backgroundColor: BackgroundColor,
    onBackgroundColorChange: (BackgroundColor) -> Unit,
    backgroundOpacity: Float,
    onBackgroundOpacityChange: (Float) -> Unit
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = AppCardElevation.Low
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = i18n["main.outputOptions"],
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )

            // Subtitle Type label
            Text(
                text = i18n["main.subtitleType"],
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Soft subtitles option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSubtitleTypeChange(SubtitleType.SOFT) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = subtitleType == SubtitleType.SOFT,
                    onClick = { onSubtitleTypeChange(SubtitleType.SOFT) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = i18n["main.subtitleType.soft"],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Burned-in subtitles option
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSubtitleTypeChange(SubtitleType.BURNED_IN) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = subtitleType == SubtitleType.BURNED_IN,
                        onClick = { onSubtitleTypeChange(SubtitleType.BURNED_IN) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = i18n["main.subtitleType.burnedIn"],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Burned-in options (animated expand/collapse)
                BurnedInOptions(
                    i18n = i18n,
                    visible = subtitleType == SubtitleType.BURNED_IN,
                    backgroundColor = backgroundColor,
                    onBackgroundColorChange = onBackgroundColorChange,
                    backgroundOpacity = backgroundOpacity,
                    onBackgroundOpacityChange = onBackgroundOpacityChange
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            // Export SRT checkbox
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onExportSrtChange(!exportSrt) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = exportSrt,
                    onCheckedChange = onExportSrtChange
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = i18n["main.exportSrt"],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun OutputLocationSection(
    i18n: I18nManager,
    path: String,
    onPathChange: (String) -> Unit,
    onBrowse: () -> Unit
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = AppCardElevation.Low
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = i18n["main.outputLocation"],
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = path,
                    onValueChange = onPathChange,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                // Browse button
                IconButton(
                    onClick = onBrowse,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = i18n["action.browse"],
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun MainFooter(
    i18n: I18nManager,
    version: String,
    onSettingsClick: () -> Unit,
    onTranslateClick: () -> Unit,
    translateEnabled: Boolean
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side: Settings button and version
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Settings button
                TextButton(
                    onClick = onSettingsClick,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = i18n["settings.title"],
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // Version info
                Text(
                    text = "v$version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Right side: Translate button
            AppButton(
                text = i18n["main.translate"],
                onClick = onTranslateClick,
                style = ButtonStyle.Primary,
                size = ButtonSize.Large,
                enabled = translateEnabled,
                leadingIcon = Icons.Default.Translate
            )
        }
    }
}

/**
 * Parses a hex color string (e.g., "#FFFFFF") to a Compose Color.
 */
private fun parseHexColor(hex: String): Color {
    val colorString = hex.removePrefix("#")
    val colorLong = colorString.toLong(16)
    return when (colorString.length) {
        6 -> Color(
            red = ((colorLong shr 16) and 0xFF).toInt(),
            green = ((colorLong shr 8) and 0xFF).toInt(),
            blue = (colorLong and 0xFF).toInt()
        )
        8 -> Color(
            alpha = ((colorLong shr 24) and 0xFF).toInt(),
            red = ((colorLong shr 16) and 0xFF).toInt(),
            green = ((colorLong shr 8) and 0xFF).toInt(),
            blue = (colorLong and 0xFF).toInt()
        )
        else -> Color.White
    }
}
