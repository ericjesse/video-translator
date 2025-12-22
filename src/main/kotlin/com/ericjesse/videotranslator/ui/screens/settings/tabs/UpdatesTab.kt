@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.settings.tabs

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.infrastructure.config.AppSettings
import com.ericjesse.videotranslator.infrastructure.config.InstalledVersions
import com.ericjesse.videotranslator.infrastructure.update.DependencyUpdates
import com.ericjesse.videotranslator.ui.components.AppButton
import com.ericjesse.videotranslator.ui.components.ButtonSize
import com.ericjesse.videotranslator.ui.components.ButtonStyle
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import com.ericjesse.videotranslator.ui.theme.AppColors
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Component information for the updates table.
 */
data class ComponentInfo(
    val id: String,
    val displayName: String,
    val installedVersion: String?,
    val latestVersion: String?,
    val hasUpdate: Boolean
)

/**
 * State for update checking operations.
 */
sealed class UpdateCheckState {
    data object Idle : UpdateCheckState()
    data object Checking : UpdateCheckState()
    data class Error(val message: String) : UpdateCheckState()
    data class Complete(val hasUpdates: Boolean) : UpdateCheckState()
}

/**
 * State for component update operations.
 */
sealed class ComponentUpdateState {
    data object Idle : ComponentUpdateState()
    data class Updating(val componentId: String, val progress: Float, val message: String) : ComponentUpdateState()
    data class Error(val componentId: String, val message: String) : ComponentUpdateState()
    data object Complete : ComponentUpdateState()
}

/**
 * Updates settings tab content.
 *
 * Allows configuration of:
 * - Automatic update checking
 * - Check interval
 * - Manual update checks
 * - Component version management
 *
 * @param appModule Application module for accessing services.
 * @param settings Current application settings.
 * @param onUpdateSettings Callback to update settings.
 * @param modifier Modifier for the content.
 */
@Composable
fun UpdatesTabContent(
    appModule: AppModule,
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    val i18n = appModule.i18nManager
    val updateManager = appModule.updateManager
    val configManager = appModule.configManager
    val scope = rememberCoroutineScope()

    // Installed versions
    var installedVersions by remember { mutableStateOf(configManager.getInstalledVersions()) }

    // Update check state
    var checkState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    var dependencyUpdates by remember { mutableStateOf<DependencyUpdates?>(null) }

    // Component update state
    var updateState by remember { mutableStateOf<ComponentUpdateState>(ComponentUpdateState.Idle) }

    // Build component list
    val components = remember(installedVersions, dependencyUpdates) {
        listOf(
            ComponentInfo(
                id = "ytdlp",
                displayName = "yt-dlp",
                installedVersion = installedVersions.ytDlp,
                latestVersion = dependencyUpdates?.ytDlpAvailable ?: installedVersions.ytDlp,
                hasUpdate = dependencyUpdates?.ytDlpAvailable != null
            ),
            ComponentInfo(
                id = "ffmpeg",
                displayName = "FFmpeg",
                installedVersion = installedVersions.ffmpeg,
                latestVersion = dependencyUpdates?.ffmpegAvailable ?: installedVersions.ffmpeg,
                hasUpdate = dependencyUpdates?.ffmpegAvailable != null
            ),
            ComponentInfo(
                id = "whisper",
                displayName = "Whisper.cpp",
                installedVersion = installedVersions.whisperCpp,
                latestVersion = dependencyUpdates?.whisperCppAvailable ?: installedVersions.whisperCpp,
                hasUpdate = dependencyUpdates?.whisperCppAvailable != null
            )
        )
    }

    val hasAnyUpdates = components.any { it.hasUpdate }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // Application Updates Section
        ApplicationUpdatesSection(
            i18n = i18n,
            settings = settings,
            checkState = checkState,
            onUpdateSettings = onUpdateSettings,
            onCheckNow = {
                checkState = UpdateCheckState.Checking
                scope.launch {
                    try {
                        val updates = updateManager.checkDependencyUpdates()
                        dependencyUpdates = updates

                        // Update last check timestamp
                        onUpdateSettings {
                            it.copy(
                                updates = it.updates.copy(
                                    lastCheckTimestamp = System.currentTimeMillis()
                                )
                            )
                        }

                        checkState = UpdateCheckState.Complete(updates.hasUpdates)

                        // Refresh installed versions
                        installedVersions = configManager.getInstalledVersions()
                    } catch (e: Exception) {
                        checkState = UpdateCheckState.Error(e.message ?: "Failed to check for updates")
                    }
                }
            }
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        // Component Updates Section
        ComponentUpdatesSection(
            i18n = i18n,
            components = components,
            checkState = checkState,
            updateState = updateState,
            onUpdateComponent = { componentId ->
                updateState = ComponentUpdateState.Updating(componentId, 0f, "Starting...")
                scope.launch {
                    try {
                        val flow = when (componentId) {
                            "ytdlp" -> updateManager.installYtDlp()
                            "ffmpeg" -> updateManager.installFfmpeg()
                            "whisper" -> updateManager.installWhisperCpp()
                            else -> return@launch
                        }

                        flow.catch { e ->
                            updateState = ComponentUpdateState.Error(componentId, e.message ?: "Update failed")
                        }.collect { progress ->
                            updateState = ComponentUpdateState.Updating(
                                componentId,
                                progress.percentage,
                                progress.message
                            )
                            if (progress.percentage >= 1f) {
                                updateState = ComponentUpdateState.Complete
                                installedVersions = configManager.getInstalledVersions()
                                // Clear the available update for this component
                                dependencyUpdates = dependencyUpdates?.let { updates ->
                                    when (componentId) {
                                        "ytdlp" -> updates.copy(ytDlpAvailable = null)
                                        "ffmpeg" -> updates.copy(ffmpegAvailable = null)
                                        "whisper" -> updates.copy(whisperCppAvailable = null)
                                        else -> updates
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        updateState = ComponentUpdateState.Error(componentId, e.message ?: "Update failed")
                    }
                }
            },
            onUpdateAll = {
                val componentsToUpdate = components.filter { it.hasUpdate }
                if (componentsToUpdate.isEmpty()) return@ComponentUpdatesSection

                scope.launch {
                    for (component in componentsToUpdate) {
                        updateState = ComponentUpdateState.Updating(component.id, 0f, "Updating ${component.displayName}...")
                        try {
                            val flow = when (component.id) {
                                "ytdlp" -> updateManager.installYtDlp()
                                "ffmpeg" -> updateManager.installFfmpeg()
                                "whisper" -> updateManager.installWhisperCpp()
                                else -> continue
                            }

                            flow.catch { e ->
                                updateState = ComponentUpdateState.Error(component.id, e.message ?: "Update failed")
                            }.collect { progress ->
                                updateState = ComponentUpdateState.Updating(
                                    component.id,
                                    progress.percentage,
                                    progress.message
                                )
                            }

                            // Clear the update flag for this component
                            dependencyUpdates = dependencyUpdates?.let { updates ->
                                when (component.id) {
                                    "ytdlp" -> updates.copy(ytDlpAvailable = null)
                                    "ffmpeg" -> updates.copy(ffmpegAvailable = null)
                                    "whisper" -> updates.copy(whisperCppAvailable = null)
                                    else -> updates
                                }
                            }
                        } catch (e: Exception) {
                            updateState = ComponentUpdateState.Error(component.id, e.message ?: "Update failed")
                            break
                        }
                    }

                    updateState = ComponentUpdateState.Complete
                    installedVersions = configManager.getInstalledVersions()
                }
            }
        )
    }
}

// ========== Application Updates Section ==========

@Composable
private fun ApplicationUpdatesSection(
    i18n: I18nManager,
    settings: AppSettings,
    checkState: UpdateCheckState,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onCheckNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Section header
        Text(
            text = i18n["settings.updates.applicationUpdates"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        // Check automatically checkbox
        AutoCheckSection(
            i18n = i18n,
            checked = settings.updates.checkAutomatically,
            onCheckedChange = { checked ->
                onUpdateSettings {
                    it.copy(updates = it.updates.copy(checkAutomatically = checked))
                }
            }
        )

        // Check interval dropdown (only shown if auto-check is enabled)
        AnimatedVisibility(
            visible = settings.updates.checkAutomatically,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            CheckIntervalSection(
                i18n = i18n,
                currentInterval = settings.updates.checkIntervalDays,
                onIntervalChange = { days ->
                    onUpdateSettings {
                        it.copy(updates = it.updates.copy(checkIntervalDays = days))
                    }
                }
            )
        }

        // Check Now button and last checked info
        CheckNowSection(
            i18n = i18n,
            checkState = checkState,
            lastCheckTimestamp = settings.updates.lastCheckTimestamp,
            onCheckNow = onCheckNow
        )
    }
}

@Composable
private fun AutoCheckSection(
    i18n: I18nManager,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = i18n["settings.updates.checkAutomatically"],
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = i18n["settings.updates.checkAutomatically.description"],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.Update,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun CheckIntervalSection(
    i18n: I18nManager,
    currentInterval: Int,
    onIntervalChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val intervals = listOf(
        1 to i18n["settings.updates.interval.daily"],
        7 to i18n["settings.updates.interval.weekly"],
        30 to i18n["settings.updates.interval.monthly"]
    )

    val selectedLabel = intervals.find { it.first == currentInterval }?.second
        ?: i18n["settings.updates.interval.weekly"]

    SettingsSectionCard(
        title = i18n["settings.updates.checkInterval"],
        description = i18n["settings.updates.checkInterval.description"],
        modifier = modifier
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Schedule,
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
                intervals.forEach { (days, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onIntervalChange(days)
                            expanded = false
                        },
                        leadingIcon = if (days == currentInterval) {
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
}

@Composable
private fun CheckNowSection(
    i18n: I18nManager,
    checkState: UpdateCheckState,
    lastCheckTimestamp: Long,
    onCheckNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppButton(
            text = i18n["settings.updates.checkNow"],
            onClick = onCheckNow,
            style = ButtonStyle.Secondary,
            size = ButtonSize.Medium,
            enabled = checkState !is UpdateCheckState.Checking,
            loading = checkState is UpdateCheckState.Checking,
            leadingIcon = Icons.Default.Refresh
        )

        // Last checked text or status
        when (checkState) {
            is UpdateCheckState.Checking -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = i18n["settings.updates.checking"],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is UpdateCheckState.Complete -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (checkState.hasUpdates) Icons.Default.NewReleases else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (checkState.hasUpdates) AppColors.warning else AppColors.success,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (checkState.hasUpdates) {
                            i18n["settings.updates.updatesAvailable"]
                        } else {
                            i18n["settings.updates.upToDate"]
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (checkState.hasUpdates) AppColors.warning else AppColors.success
                    )
                }
            }
            is UpdateCheckState.Error -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = AppColors.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = checkState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.error
                    )
                }
            }
            is UpdateCheckState.Idle -> {
                Text(
                    text = formatLastChecked(i18n, lastCheckTimestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ========== Component Updates Section ==========

@Composable
private fun ComponentUpdatesSection(
    i18n: I18nManager,
    components: List<ComponentInfo>,
    checkState: UpdateCheckState,
    updateState: ComponentUpdateState,
    onUpdateComponent: (String) -> Unit,
    onUpdateAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasAnyUpdates = components.any { it.hasUpdate }
    val isUpdating = updateState is ComponentUpdateState.Updating

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section header
        Text(
            text = i18n["settings.updates.componentUpdates"],
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        // Component table
        ComponentTable(
            i18n = i18n,
            components = components,
            updateState = updateState,
            onUpdateComponent = onUpdateComponent
        )

        // Update progress
        AnimatedVisibility(
            visible = updateState is ComponentUpdateState.Updating,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            if (updateState is ComponentUpdateState.Updating) {
                UpdateProgressCard(
                    componentName = components.find { it.id == updateState.componentId }?.displayName
                        ?: updateState.componentId,
                    progress = updateState.progress,
                    message = updateState.message
                )
            }
        }

        // Error message
        AnimatedVisibility(
            visible = updateState is ComponentUpdateState.Error,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            if (updateState is ComponentUpdateState.Error) {
                Surface(
                    color = AppColors.error.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = AppColors.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = updateState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.error
                        )
                    }
                }
            }
        }

        // Update All button
        AnimatedVisibility(
            visible = hasAnyUpdates && !isUpdating,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AppButton(
                text = i18n["settings.updates.updateAll"],
                onClick = onUpdateAll,
                style = ButtonStyle.Primary,
                size = ButtonSize.Medium,
                leadingIcon = Icons.Default.SystemUpdateAlt
            )
        }
    }
}

@Composable
private fun ComponentTable(
    i18n: I18nManager,
    components: List<ComponentInfo>,
    updateState: ComponentUpdateState,
    onUpdateComponent: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(2.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = i18n["settings.updates.table.component"],
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1.5f)
                )
                Text(
                    text = i18n["settings.updates.table.installed"],
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1.2f),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = i18n["settings.updates.table.latest"],
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1.2f),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = i18n["settings.updates.table.status"],
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(0.8f),
                    textAlign = TextAlign.Center
                )
            }

            // Component rows
            components.forEachIndexed { index, component ->
                val isCurrentlyUpdating = updateState is ComponentUpdateState.Updating &&
                        updateState.componentId == component.id

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (component.hasUpdate) {
                                Modifier
                                    .background(AppColors.warning.copy(alpha = 0.05f))
                                    .border(
                                        1.dp,
                                        AppColors.warning.copy(alpha = 0.2f),
                                        RoundedCornerShape(4.dp)
                                    )
                            } else Modifier
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Component name
                    Row(
                        modifier = Modifier.weight(1.5f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = component.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (isCurrentlyUpdating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Installed version
                    Text(
                        text = component.installedVersion ?: "-",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1.2f),
                        textAlign = TextAlign.Center
                    )

                    // Latest version
                    Text(
                        text = component.latestVersion ?: "-",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (component.hasUpdate) {
                            AppColors.warning
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (component.hasUpdate) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1.2f),
                        textAlign = TextAlign.Center
                    )

                    // Status
                    Box(
                        modifier = Modifier.weight(0.8f),
                        contentAlignment = Alignment.Center
                    ) {
                        ComponentStatus(
                            hasUpdate = component.hasUpdate,
                            isInstalled = component.installedVersion != null,
                            isUpdating = isCurrentlyUpdating,
                            onUpdate = { onUpdateComponent(component.id) }
                        )
                    }
                }

                if (index < components.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ComponentStatus(
    hasUpdate: Boolean,
    isInstalled: Boolean,
    isUpdating: Boolean,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        isUpdating -> {
            CircularProgressIndicator(
                modifier = modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        hasUpdate -> {
            IconButton(
                onClick = onUpdate,
                modifier = modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = "Update available",
                    tint = AppColors.warning,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        isInstalled -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Up to date",
                tint = AppColors.success,
                modifier = modifier.size(20.dp)
            )
        }
        else -> {
            Text(
                text = "-",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UpdateProgressCard(
    componentName: String,
    progress: Float,
    message: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Updating $componentName...",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ========== Helper Functions ==========

/**
 * Formats the last checked timestamp into a human-readable string.
 */
private fun formatLastChecked(i18n: I18nManager, timestamp: Long): String {
    if (timestamp == 0L) {
        return i18n["settings.updates.lastChecked", i18n["settings.updates.never"]]
    }

    val now = Instant.now()
    val lastCheck = Instant.ofEpochMilli(timestamp)
    val duration = Duration.between(lastCheck, now)

    val timeAgo = when {
        duration.toMinutes() < 1 -> i18n["settings.updates.justNow"]
        duration.toMinutes() < 60 -> i18n["settings.updates.minutesAgo", duration.toMinutes().toString()]
        duration.toHours() < 24 -> i18n["settings.updates.hoursAgo", duration.toHours().toString()]
        duration.toDays() < 7 -> i18n["settings.updates.daysAgo", duration.toDays().toString()]
        else -> i18n["settings.updates.weeksAgo", (duration.toDays() / 7).toString()]
    }

    return i18n["settings.updates.lastChecked", timeAgo]
}
