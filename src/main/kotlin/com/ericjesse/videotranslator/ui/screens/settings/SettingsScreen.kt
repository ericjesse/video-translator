@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.infrastructure.config.*
import com.ericjesse.videotranslator.ui.components.AppButton
import com.ericjesse.videotranslator.ui.components.ButtonSize
import com.ericjesse.videotranslator.ui.components.ButtonStyle
import com.ericjesse.videotranslator.ui.components.dialogs.ConfirmDialog
import com.ericjesse.videotranslator.ui.components.dialogs.ConfirmDialogStyle
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import com.ericjesse.videotranslator.ui.i18n.Locale
import com.ericjesse.videotranslator.ui.screens.settings.tabs.GeneralTabContent
import com.ericjesse.videotranslator.ui.screens.settings.tabs.SubtitlesTabContent
import com.ericjesse.videotranslator.ui.screens.settings.tabs.TranscriptionTabContent
import com.ericjesse.videotranslator.ui.screens.settings.tabs.TranslationTabContent
import com.ericjesse.videotranslator.ui.screens.settings.tabs.UpdatesTabContent
import com.ericjesse.videotranslator.ui.theme.AppColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// ========== Settings Tabs ==========

/**
 * Available settings tabs.
 */
enum class SettingsTab(
    val icon: ImageVector,
    val titleKey: String
) {
    GENERAL(Icons.Default.Settings, "settings.tab.general"),
    TRANSLATION(Icons.Default.Translate, "settings.tab.translation"),
    TRANSCRIPTION(Icons.Default.Mic, "settings.tab.transcription"),
    SUBTITLES(Icons.Default.Subtitles, "settings.tab.subtitles"),
    UPDATES(Icons.Default.Update, "settings.tab.updates"),
    ABOUT(Icons.Default.Info, "settings.tab.about")
}

// ========== State Models ==========

/**
 * State for the settings screen.
 *
 * @property settings Current application settings (editable copy).
 * @property serviceConfig Current translation service configuration (editable copy).
 * @property originalSettings Original settings for change detection.
 * @property originalServiceConfig Original service config for change detection.
 * @property selectedTab Currently selected tab.
 * @property isSaving Whether settings are currently being saved.
 * @property saveError Error message if save failed.
 */
data class SettingsScreenState(
    val settings: AppSettings = AppSettings(),
    val serviceConfig: TranslationServiceConfig = TranslationServiceConfig(),
    val originalSettings: AppSettings = AppSettings(),
    val originalServiceConfig: TranslationServiceConfig = TranslationServiceConfig(),
    val selectedTab: SettingsTab = SettingsTab.GENERAL,
    val isSaving: Boolean = false,
    val saveError: String? = null
) {
    /**
     * Whether there are unsaved changes.
     */
    val hasUnsavedChanges: Boolean
        get() = settings != originalSettings || serviceConfig != originalServiceConfig
}

// ========== ViewModel ==========

/**
 * ViewModel for the settings screen.
 */
class SettingsViewModel(
    private val appModule: AppModule,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val configManager = appModule.configManager

    var state by mutableStateOf(loadInitialState())
        private set

    private fun loadInitialState(): SettingsScreenState {
        val settings = configManager.getSettings()
        val serviceConfig = configManager.getTranslationServiceConfig()
        return SettingsScreenState(
            settings = settings,
            serviceConfig = serviceConfig,
            originalSettings = settings,
            originalServiceConfig = serviceConfig
        )
    }

    /**
     * Selects a tab.
     */
    fun selectTab(tab: SettingsTab) {
        state = state.copy(selectedTab = tab)
    }

    /**
     * Updates the application settings.
     */
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        state = state.copy(settings = transform(state.settings))
    }

    /**
     * Updates the service configuration.
     */
    fun updateServiceConfig(transform: (TranslationServiceConfig) -> TranslationServiceConfig) {
        state = state.copy(serviceConfig = transform(state.serviceConfig))
    }

    /**
     * Saves all changes.
     */
    fun saveChanges(onSuccess: () -> Unit = {}) {
        if (!state.hasUnsavedChanges) {
            onSuccess()
            return
        }

        state = state.copy(isSaving = true, saveError = null)

        scope.launch {
            try {
                // Save settings
                configManager.saveSettings(state.settings)
                configManager.saveTranslationServiceConfig(state.serviceConfig)

                // Update original state to reflect saved values
                state = state.copy(
                    originalSettings = state.settings,
                    originalServiceConfig = state.serviceConfig,
                    isSaving = false
                )
                onSuccess()
            } catch (e: Exception) {
                state = state.copy(
                    isSaving = false,
                    saveError = e.message ?: "Failed to save settings"
                )
            }
        }
    }

    /**
     * Discards all changes and reverts to original values.
     */
    fun discardChanges() {
        state = state.copy(
            settings = state.originalSettings,
            serviceConfig = state.originalServiceConfig
        )
    }

    /**
     * Reloads settings from disk.
     */
    fun reload() {
        state = loadInitialState()
    }
}

// ========== Main Screen ==========

/**
 * Settings screen with tab-based navigation.
 *
 * @param appModule Application module for accessing services.
 * @param onBack Callback when the back button is clicked.
 * @param modifier Modifier for the screen.
 */
@Composable
fun SettingsScreen(
    appModule: AppModule,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = remember { SettingsViewModel(appModule) }
    val i18n = appModule.i18nManager
    val state = viewModel.state

    var showUnsavedChangesDialog by remember { mutableStateOf(false) }

    // Handle back with unsaved changes
    val handleBack: () -> Unit = {
        if (state.hasUnsavedChanges) {
            showUnsavedChangesDialog = true
        } else {
            onBack()
        }
    }

    // Unsaved changes confirmation dialog
    if (showUnsavedChangesDialog) {
        ConfirmDialog(
            title = i18n["settings.unsaved.title"],
            message = i18n["settings.unsaved.message"],
            confirmText = i18n["settings.unsaved.discard"],
            cancelText = i18n["settings.unsaved.cancel"],
            style = ConfirmDialogStyle.Warning,
            onConfirm = {
                showUnsavedChangesDialog = false
                viewModel.discardChanges()
                onBack()
            },
            onDismiss = { showUnsavedChangesDialog = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header with back button
        SettingsHeader(
            i18n = i18n,
            onBack = handleBack
        )

        // Main content: Two-column layout
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Left column: Tab list
            SettingsTabList(
                i18n = i18n,
                selectedTab = state.selectedTab,
                onTabSelected = viewModel::selectTab,
                modifier = Modifier
                    .width(200.dp)
                    .fillMaxHeight()
            )

            // Divider
            VerticalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxHeight()
            )

            // Right column: Tab content
            SettingsTabContent(
                appModule = appModule,
                state = state,
                onUpdateSettings = viewModel::updateSettings,
                onUpdateServiceConfig = viewModel::updateServiceConfig,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        // Footer with save button
        SettingsFooter(
            i18n = i18n,
            hasUnsavedChanges = state.hasUnsavedChanges,
            isSaving = state.isSaving,
            saveError = state.saveError,
            onSave = { viewModel.saveChanges() }
        )
    }
}

// ========== Header ==========

@Composable
private fun SettingsHeader(
    i18n: I18nManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Back button
            TextButton(
                onClick = onBack,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = i18n["action.back"],
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Title (centered)
            Text(
                text = i18n["settings.title"],
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ========== Tab List ==========

@Composable
private fun SettingsTabList(
    i18n: I18nManager,
    selectedTab: SettingsTab,
    onTabSelected: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SettingsTab.entries.forEach { tab ->
                SettingsTabItem(
                    tab = tab,
                    isSelected = tab == selectedTab,
                    label = i18n[tab.titleKey],
                    onClick = { onTabSelected(tab) }
                )
            }
        }
    }
}

@Composable
private fun SettingsTabItem(
    tab: SettingsTab,
    isSelected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            isHovered -> MaterialTheme.colorScheme.surfaceVariant
            else -> Color.Transparent
        },
        animationSpec = tween(150),
        label = "tab_bg"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(150),
        label = "tab_content"
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )

        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
        )

        // Selection indicator
        if (isSelected) {
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = contentColor.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ========== Tab Content ==========

@Composable
private fun SettingsTabContent(
    appModule: AppModule,
    state: SettingsScreenState,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onUpdateServiceConfig: ((TranslationServiceConfig) -> TranslationServiceConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val i18n = appModule.i18nManager

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // Tab title
        Text(
            text = i18n[state.selectedTab.titleKey],
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        Spacer(modifier = Modifier.height(24.dp))

        // Tab-specific content
        AnimatedContent(
            targetState = state.selectedTab,
            transitionSpec = {
                fadeIn(animationSpec = tween(200)) togetherWith
                        fadeOut(animationSpec = tween(200))
            },
            label = "tab_content"
        ) { tab ->
            when (tab) {
                SettingsTab.GENERAL -> GeneralTabContent(
                    appModule = appModule,
                    settings = state.settings,
                    onUpdateSettings = onUpdateSettings,
                    onLanguageChange = { locale ->
                        // Apply language change immediately
                        appModule.i18nManager.setLocale(locale)
                    }
                )
                SettingsTab.TRANSLATION -> TranslationTabContent(
                    appModule = appModule,
                    settings = state.settings,
                    serviceConfig = state.serviceConfig,
                    onUpdateSettings = onUpdateSettings,
                    onUpdateServiceConfig = onUpdateServiceConfig
                )
                SettingsTab.TRANSCRIPTION -> TranscriptionTabContent(
                    appModule = appModule,
                    settings = state.settings,
                    onUpdateSettings = onUpdateSettings
                )
                SettingsTab.SUBTITLES -> SubtitlesTabContent(
                    appModule = appModule,
                    settings = state.settings,
                    onUpdateSettings = onUpdateSettings
                )
                SettingsTab.UPDATES -> UpdatesTabContent(
                    appModule = appModule,
                    settings = state.settings,
                    onUpdateSettings = onUpdateSettings
                )
                SettingsTab.ABOUT -> AboutTabContent(
                    appModule = appModule
                )
            }
        }
    }
}

// ========== Tab Content Placeholders ==========
// These will be replaced with proper implementations in separate files

@Composable
private fun AboutTabContent(
    appModule: AppModule
) {
    val i18n = appModule.i18nManager

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // App icon placeholder
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.size(80.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        // App name and version
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = i18n["app.name"],
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Version 1.0.0",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = i18n["app.tagline"],
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Links
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsLinkRow(
                    icon = Icons.Default.Public,
                    text = "github.com/ericjesse/video-translator",
                    onClick = { /* TODO: Open browser */ }
                )
                SettingsLinkRow(
                    icon = Icons.Default.Description,
                    text = "Apache License 2.0",
                    onClick = { /* TODO: Show license */ }
                )
            }
        }

        // Open source licenses
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = i18n["settings.about.licenses"],
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )

                listOf(
                    "yt-dlp" to "Unlicense",
                    "FFmpeg" to "LGPL 2.1",
                    "whisper.cpp" to "MIT",
                    "Compose Multiplatform" to "Apache 2.0",
                    "Ktor" to "Apache 2.0"
                ).forEach { (name, license) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = license,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ========== Footer ==========

@Composable
private fun SettingsFooter(
    i18n: I18nManager,
    hasUnsavedChanges: Boolean,
    isSaving: Boolean,
    saveError: String?,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            // Error message
            AnimatedVisibility(visible = saveError != null) {
                saveError?.let { error ->
                    Surface(
                        color = AppColors.error.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = AppColors.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.error
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppButton(
                    text = i18n["settings.saveChanges"],
                    onClick = onSave,
                    style = ButtonStyle.Primary,
                    size = ButtonSize.Large,
                    enabled = hasUnsavedChanges && !isSaving,
                    loading = isSaving,
                    leadingIcon = Icons.Default.Save
                )
            }
        }
    }
}

// ========== Reusable Settings Components ==========

@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsDropdown(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == value }?.second ?: value

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (optionValue, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        onValueChange(optionValue)
                        expanded = false
                    },
                    leadingIcon = if (optionValue == value) {
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
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun SettingsPasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) {
            androidx.compose.ui.text.input.VisualTransformation.None
        } else {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Hide" else "Show"
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun SettingsPathField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp)
        )

        IconButton(onClick = onBrowse) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "Browse",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SettingsCheckbox(
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )

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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsLinkRow(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
