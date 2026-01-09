@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.settings.tabs

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.domain.model.Language
import com.ericjesse.videotranslator.infrastructure.config.AppSettings
import com.ericjesse.videotranslator.ui.components.AppButton
import com.ericjesse.videotranslator.ui.components.ButtonSize
import com.ericjesse.videotranslator.ui.components.ButtonStyle
import com.ericjesse.videotranslator.ui.components.dialogs.ConfirmDialog
import com.ericjesse.videotranslator.ui.components.dialogs.ConfirmDialogStyle
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import com.ericjesse.videotranslator.ui.i18n.Locale
import com.ericjesse.videotranslator.ui.theme.AppColors
import com.ericjesse.videotranslator.ui.util.rememberDirectoryPickerLauncher
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        // Spacer to separate dangerous zone
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(16.dp))

        // Factory Reset Section
        FactoryResetSection(
            appModule = appModule,
            i18n = i18n
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
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand),
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
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand),
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

// ========== Factory Reset Section ==========

@Composable
private fun FactoryResetSection(
    appModule: AppModule,
    i18n: I18nManager,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var showConfirmDialog by remember { mutableStateOf(false) }
    var isResetting by remember { mutableStateOf(false) }
    var dataSize by remember { mutableStateOf<Long?>(null) }

    // Calculate data size on first composition
    LaunchedEffect(Unit) {
        dataSize = withContext(Dispatchers.IO) {
            appModule.platformPaths.getTotalDataSize()
        }
    }

    // Confirmation dialog
    if (showConfirmDialog) {
        ConfirmDialog(
            title = i18n["settings.general.factoryReset.confirm.title"],
            message = i18n["settings.general.factoryReset.confirm.message"],
            confirmText = i18n["settings.general.factoryReset.confirm.button"],
            cancelText = i18n["action.cancel"],
            style = ConfirmDialogStyle.Danger,
            onConfirm = {
                showConfirmDialog = false
                isResetting = true
                scope.launch {
                    performFactoryReset(appModule)
                }
            },
            onDismiss = { showConfirmDialog = false }
        )
    }

    // Factory Reset Card with danger styling
    Surface(
        color = AppColors.error.copy(alpha = 0.05f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with warning icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = AppColors.error,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = i18n["settings.general.factoryReset.title"],
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.error,
                    fontWeight = FontWeight.Medium
                )
            }

            // Description
            Text(
                text = i18n["settings.general.factoryReset.description"],
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // What will be deleted
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = i18n["settings.general.factoryReset.willDelete"],
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )

                listOf(
                    i18n["settings.general.factoryReset.item.settings"],
                    i18n["settings.general.factoryReset.item.binaries"],
                    i18n["settings.general.factoryReset.item.models"],
                    i18n["settings.general.factoryReset.item.cache"]
                ).forEach { item ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Circle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(6.dp)
                        )
                        Text(
                            text = item,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Data size
                dataSize?.let { size ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = i18n["settings.general.factoryReset.totalSize", formatBytes(size)],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Reset button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                AppButton(
                    text = i18n["settings.general.factoryReset.button"],
                    onClick = { showConfirmDialog = true },
                    style = ButtonStyle.Danger,
                    size = ButtonSize.Medium,
                    enabled = !isResetting,
                    loading = isResetting,
                    leadingIcon = Icons.Default.DeleteForever
                )
            }
        }
    }
}

/**
 * Performs factory reset: deletes all data and restarts the application.
 */
private suspend fun performFactoryReset(appModule: AppModule) {
    withContext(Dispatchers.IO) {
        // Close any running services
        appModule.close()

        // Delete all application data
        appModule.platformPaths.deleteAllData()

        // Restart the application
        restartApplication()
    }
}

/**
 * Restarts the application by launching a new process and exiting.
 * Handles different packaging formats: macOS .app bundle, Windows .exe, Linux packages, and JAR.
 */
private fun restartApplication() {
    try {
        val osName = System.getProperty("os.name").lowercase()
        val launched = when {
            osName.contains("mac") || osName.contains("darwin") -> restartMacOS()
            osName.contains("win") -> restartWindows()
            else -> restartLinux()
        }

        if (!launched) {
            // Fall back to JAR-based restart
            restartFromJar()
        }
    } catch (e: Exception) {
        // If restart fails, just exit - user can manually restart
        e.printStackTrace()
    }

    // Exit the current process
    exitProcess(0)
}

/**
 * Attempts to restart the application on macOS.
 * Handles both .app bundles and development/JAR scenarios.
 */
private fun restartMacOS(): Boolean {
    // Check if running from an .app bundle
    val appBundlePath = System.getProperty("java.home")?.let { javaHome ->
        // In a bundled app, java.home is something like:
        // /Applications/VideoTranslator.app/Contents/runtime/Contents/Home
        val appMatch = Regex("(.+\\.app)/Contents/").find(javaHome)
        appMatch?.groupValues?.get(1)
    }

    if (appBundlePath != null && File(appBundlePath).exists()) {
        // Launch the .app bundle using 'open' command
        ProcessBuilder("open", "-n", appBundlePath)
            .inheritIO()
            .start()
        return true
    }

    // Check common installation paths
    val commonPaths = listOf(
        "/Applications/VideoTranslator.app",
        "${System.getProperty("user.home")}/Applications/VideoTranslator.app"
    )

    for (path in commonPaths) {
        if (File(path).exists()) {
            ProcessBuilder("open", "-n", path)
                .inheritIO()
                .start()
            return true
        }
    }

    return false
}

/**
 * Attempts to restart the application on Windows.
 * Handles both installed .exe and development scenarios.
 */
private fun restartWindows(): Boolean {
    // Try to find the executable from the current process
    val processHandle = ProcessHandle.current()
    val command = processHandle.info().command().orElse(null)

    if (command != null && command.endsWith(".exe")) {
        ProcessBuilder(command)
            .inheritIO()
            .start()
        return true
    }

    // Check common installation paths
    val localAppData = System.getenv("LOCALAPPDATA") ?: return false
    val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
    val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"

    val commonPaths = listOf(
        "$localAppData\\VideoTranslator\\VideoTranslator.exe",
        "$programFiles\\VideoTranslator\\VideoTranslator.exe",
        "$programFilesX86\\VideoTranslator\\VideoTranslator.exe"
    )

    for (path in commonPaths) {
        if (File(path).exists()) {
            ProcessBuilder(path)
                .inheritIO()
                .start()
            return true
        }
    }

    return false
}

/**
 * Attempts to restart the application on Linux.
 * Handles both installed packages and development scenarios.
 */
private fun restartLinux(): Boolean {
    // Try to find the executable from the current process
    val processHandle = ProcessHandle.current()
    val command = processHandle.info().command().orElse(null)

    if (command != null && !command.contains("java")) {
        // Running from a native launcher
        ProcessBuilder(command)
            .inheritIO()
            .start()
        return true
    }

    // Check common installation paths for .deb packages
    val commonPaths = listOf(
        "/opt/videotranslator/bin/VideoTranslator",
        "/usr/bin/videotranslator",
        "/usr/local/bin/videotranslator",
        "${System.getProperty("user.home")}/.local/bin/videotranslator"
    )

    for (path in commonPaths) {
        if (File(path).exists() && File(path).canExecute()) {
            ProcessBuilder(path)
                .inheritIO()
                .start()
            return true
        }
    }

    return false
}

/**
 * Falls back to restarting from JAR file (development scenario).
 */
private fun restartFromJar(): Boolean {
    return try {
        val javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
        val currentJar = File(
            object {}.javaClass.protectionDomain.codeSource.location.toURI()
        )

        if (currentJar.name.endsWith(".jar") && currentJar.exists()) {
            val command = listOf(javaBin, "-jar", currentJar.absolutePath)
            ProcessBuilder(command)
                .inheritIO()
                .start()
            true
        } else {
            false
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * Formats bytes to human-readable string.
 */
private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}
