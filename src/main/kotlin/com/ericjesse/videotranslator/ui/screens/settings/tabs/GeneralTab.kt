@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.settings.tabs

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.domain.model.Language
import com.ericjesse.videotranslator.infrastructure.config.AppSettings
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import com.ericjesse.videotranslator.ui.i18n.Locale
import com.ericjesse.videotranslator.ui.util.rememberDirectoryPickerLauncher

/**
 * General settings tab content.
 *
 * Allows configuration of:
 * - UI Language (applies immediately)
 * - Default output location
 * - Default source language for translations
 * - Default target language for translations
 *
 * @param appModule Application module for accessing services.
 * @param settings Current application settings.
 * @param onUpdateSettings Callback to update settings.
 * @param onLanguageChange Callback when UI language is changed (for immediate application).
 * @param modifier Modifier for the content.
 */
@Composable
fun GeneralTabContent(
    appModule: AppModule,
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onLanguageChange: ((Locale) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val i18n = appModule.i18nManager

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // UI Language Section
        LanguageSection(
            i18n = i18n,
            currentLanguage = settings.language,
            onLanguageChange = { locale ->
                // Update settings
                onUpdateSettings { it.copy(language = locale.code) }
                // Apply immediately if callback provided
                onLanguageChange?.invoke(locale)
            }
        )

        // Default Output Location Section
        OutputLocationSection(
            i18n = i18n,
            currentPath = settings.ui.defaultOutputDirectory,
            onPathChange = { path ->
                onUpdateSettings { it.copy(ui = it.ui.copy(defaultOutputDirectory = path)) }
            }
        )

        // Default Source Language Section
        SourceLanguageSection(
            i18n = i18n,
            currentLanguage = settings.translation.defaultSourceLanguage,
            onLanguageChange = { langCode ->
                onUpdateSettings {
                    it.copy(translation = it.translation.copy(defaultSourceLanguage = langCode))
                }
            }
        )

        // Default Target Language Section
        TargetLanguageSection(
            i18n = i18n,
            currentLanguage = settings.translation.defaultTargetLanguage,
            onLanguageChange = { langCode ->
                onUpdateSettings {
                    it.copy(translation = it.translation.copy(defaultTargetLanguage = langCode))
                }
            }
        )
    }
}

// ========== Language Section ==========

@Composable
private fun LanguageSection(
    i18n: I18nManager,
    currentLanguage: String,
    onLanguageChange: (Locale) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentLocale = Locale.fromCode(currentLanguage) ?: Locale.ENGLISH

    SettingsSectionCard(
        title = i18n["settings.general.language"],
        description = i18n["settings.general.language.description"],
        modifier = modifier
    ) {
        LocaleDropdown(
            selectedLocale = currentLocale,
            onLocaleSelected = onLanguageChange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LocaleDropdown(
    selectedLocale: Locale,
    onLocaleSelected: (Locale) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedLocale.nativeName,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
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
            Locale.entries.forEach { locale ->
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = locale.nativeName,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (locale.nativeName != locale.displayName) {
                                    Text(
                                        text = locale.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    },
                    onClick = {
                        onLocaleSelected(locale)
                        expanded = false
                    },
                    leadingIcon = if (locale == selectedLocale) {
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

// ========== Output Location Section ==========

@Composable
private fun OutputLocationSection(
    i18n: I18nManager,
    currentPath: String,
    onPathChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Directory picker
    val directoryPicker = rememberDirectoryPickerLauncher(
        title = i18n["settings.general.selectOutputFolder"],
        initialDirectory = currentPath.ifEmpty { null },
        onResult = { path ->
            path?.let { onPathChange(it) }
        }
    )

    SettingsSectionCard(
        title = i18n["settings.general.outputLocation"],
        description = i18n["settings.general.outputLocation.description"],
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = currentPath,
                onValueChange = onPathChange,
                placeholder = {
                    Text(
                        text = i18n["settings.general.outputLocation.placeholder"],
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            // Browse button
            FilledTonalIconButton(
                onClick = { directoryPicker.launch() },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = i18n["action.browse"],
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Help text
        if (currentPath.isEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = i18n["settings.general.outputLocation.empty"],
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ========== Source Language Section ==========

@Composable
private fun SourceLanguageSection(
    i18n: I18nManager,
    currentLanguage: String?,
    onLanguageChange: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsSectionCard(
        title = i18n["settings.general.defaultSourceLanguage"],
        description = i18n["settings.general.defaultSourceLanguage.description"],
        modifier = modifier
    ) {
        TranslationLanguageDropdown(
            selectedLanguageCode = currentLanguage,
            onLanguageSelected = onLanguageChange,
            includeAutoDetect = true,
            autoDetectLabel = i18n["main.autoDetect"],
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ========== Target Language Section ==========

@Composable
private fun TargetLanguageSection(
    i18n: I18nManager,
    currentLanguage: String,
    onLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsSectionCard(
        title = i18n["settings.general.defaultTargetLanguage"],
        description = i18n["settings.general.defaultTargetLanguage.description"],
        modifier = modifier
    ) {
        TranslationLanguageDropdown(
            selectedLanguageCode = currentLanguage,
            onLanguageSelected = { code -> code?.let { onLanguageChange(it) } },
            includeAutoDetect = false,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ========== Shared Components ==========

/**
 * Dropdown for selecting translation languages (source/target).
 */
@Composable
private fun TranslationLanguageDropdown(
    selectedLanguageCode: String?,
    onLanguageSelected: (String?) -> Unit,
    includeAutoDetect: Boolean,
    modifier: Modifier = Modifier,
    autoDetectLabel: String = "Auto-detect"
) {
    var expanded by remember { mutableStateOf(false) }

    val selectedLanguage = selectedLanguageCode?.let { Language.fromCode(it) }
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
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            leadingIcon = {
                Icon(
                    imageVector = if (selectedLanguage == null) Icons.Default.AutoMode else Icons.Default.Translate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
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
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoMode,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = autoDetectLabel,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    },
                    onClick = {
                        onLanguageSelected(null)
                        expanded = false
                    },
                    leadingIcon = if (selectedLanguageCode == null) {
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
                        onLanguageSelected(language.code)
                        expanded = false
                    },
                    leadingIcon = if (language.code == selectedLanguageCode) {
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

/**
 * Card wrapper for a settings section with title and description.
 */
@Composable
fun SettingsSectionCard(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Title and description
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Content
        content()
    }
}

/**
 * Clickable row for selection items within settings.
 */
@Composable
fun SettingsSelectionRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            isHovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        animationSpec = tween(150),
        label = "row_bg"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Selection indicator
        RadioButton(
            selected = isSelected,
            onClick = onClick
        )

        // Leading icon
        leadingIcon?.invoke()

        // Text content
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Check icon when selected
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
