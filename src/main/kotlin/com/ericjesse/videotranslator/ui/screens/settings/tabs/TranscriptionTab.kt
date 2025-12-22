@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.settings.tabs

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.infrastructure.config.AppSettings
import com.ericjesse.videotranslator.infrastructure.update.DownloadProgress
import com.ericjesse.videotranslator.ui.components.AppButton
import com.ericjesse.videotranslator.ui.components.ButtonSize
import com.ericjesse.videotranslator.ui.components.ButtonStyle
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import com.ericjesse.videotranslator.ui.theme.AppColors
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.File

/**
 * Whisper model information with display characteristics.
 */
data class WhisperModelInfo(
    val id: String,
    val displayName: String,
    val size: String,
    val sizeBytes: Long,
    val speedRating: Int,      // 1-5 stars
    val accuracyRating: Int,   // 1-5 stars
    val description: String
)

/**
 * Available Whisper models with their characteristics.
 */
val WHISPER_MODELS = listOf(
    WhisperModelInfo(
        id = "tiny",
        displayName = "Tiny",
        size = "75 MB",
        sizeBytes = 75L * 1024 * 1024,
        speedRating = 5,
        accuracyRating = 2,
        description = "Fastest, lowest accuracy"
    ),
    WhisperModelInfo(
        id = "base",
        displayName = "Base",
        size = "142 MB",
        sizeBytes = 142L * 1024 * 1024,
        speedRating = 4,
        accuracyRating = 3,
        description = "Good balance for most use cases"
    ),
    WhisperModelInfo(
        id = "small",
        displayName = "Small",
        size = "466 MB",
        sizeBytes = 466L * 1024 * 1024,
        speedRating = 3,
        accuracyRating = 4,
        description = "Better accuracy, moderate speed"
    ),
    WhisperModelInfo(
        id = "medium",
        displayName = "Medium",
        size = "1.5 GB",
        sizeBytes = 1536L * 1024 * 1024,
        speedRating = 2,
        accuracyRating = 4,
        description = "High accuracy, slower processing"
    ),
    WhisperModelInfo(
        id = "large",
        displayName = "Large",
        size = "2.9 GB",
        sizeBytes = 2952L * 1024 * 1024,
        speedRating = 1,
        accuracyRating = 5,
        description = "Best accuracy, slowest"
    )
)

/**
 * Download state for models.
 */
sealed class ModelDownloadState {
    data object Idle : ModelDownloadState()
    data class Downloading(val progress: Float, val message: String) : ModelDownloadState()
    data object Success : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
}

/**
 * Transcription settings tab content.
 *
 * Allows configuration of:
 * - YouTube captions preference
 * - Whisper model selection
 * - Model download and management
 *
 * @param appModule Application module for accessing services.
 * @param settings Current application settings.
 * @param onUpdateSettings Callback to update settings.
 * @param modifier Modifier for the content.
 */
@Composable
fun TranscriptionTabContent(
    appModule: AppModule,
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    val i18n = appModule.i18nManager
    val platformPaths = appModule.platformPaths
    val updateManager = appModule.updateManager
    val scope = rememberCoroutineScope()

    // Track installed models
    var installedModels by remember { mutableStateOf(getInstalledModels(platformPaths.modelsDir)) }

    // Download state
    var downloadState by remember { mutableStateOf<ModelDownloadState>(ModelDownloadState.Idle) }
    var downloadingModelId by remember { mutableStateOf<String?>(null) }

    // Selected model in dropdown
    val currentModelId = settings.transcription.whisperModel
    val currentModel = WHISPER_MODELS.find { it.id == currentModelId } ?: WHISPER_MODELS[1] // Default to base

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // YouTube Captions Preference
        YouTubeCaptionsSection(
            i18n = i18n,
            preferYouTubeCaptions = settings.transcription.preferYouTubeCaptions,
            onPreferenceChange = { checked ->
                onUpdateSettings {
                    it.copy(transcription = it.transcription.copy(preferYouTubeCaptions = checked))
                }
            }
        )

        // Whisper Model Selection
        WhisperModelSection(
            i18n = i18n,
            selectedModel = currentModel,
            installedModels = installedModels,
            onModelSelected = { model ->
                onUpdateSettings {
                    it.copy(transcription = it.transcription.copy(whisperModel = model.id))
                }
            }
        )

        // Model Comparison Table
        ModelComparisonTable(
            i18n = i18n,
            selectedModelId = currentModelId,
            installedModels = installedModels
        )

        // Download Section
        ModelDownloadSection(
            i18n = i18n,
            selectedModel = currentModel,
            isInstalled = currentModelId in installedModels,
            downloadState = downloadState,
            onDownload = {
                downloadingModelId = currentModel.id
                downloadState = ModelDownloadState.Downloading(0f, "Starting download...")

                scope.launch {
                    updateManager.installWhisperModel(currentModel.id)
                        .catch { e ->
                            downloadState = ModelDownloadState.Error(e.message ?: "Download failed")
                        }
                        .collect { progress ->
                            downloadState = ModelDownloadState.Downloading(
                                progress.percentage,
                                progress.message
                            )
                            if (progress.percentage >= 1f) {
                                downloadState = ModelDownloadState.Success
                                installedModels = getInstalledModels(platformPaths.modelsDir)
                                downloadingModelId = null
                            }
                        }
                }
            }
        )

        // Installed Models Section
        if (installedModels.isNotEmpty()) {
            InstalledModelsSection(
                i18n = i18n,
                installedModels = installedModels,
                currentModelId = currentModelId,
                onDeleteModel = { modelId ->
                    deleteModel(platformPaths.modelsDir, modelId)
                    installedModels = getInstalledModels(platformPaths.modelsDir)
                }
            )
        }
    }
}

// ========== YouTube Captions Section ==========

@Composable
private fun YouTubeCaptionsSection(
    i18n: I18nManager,
    preferYouTubeCaptions: Boolean,
    onPreferenceChange: (Boolean) -> Unit,
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
                .clickable { onPreferenceChange(!preferYouTubeCaptions) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Checkbox(
                checked = preferYouTubeCaptions,
                onCheckedChange = onPreferenceChange
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = i18n["settings.transcription.preferYouTubeCaptions"],
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = i18n["settings.transcription.preferYouTubeCaptions.description"],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // YouTube icon
            Icon(
                imageVector = Icons.Default.ClosedCaption,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// ========== Whisper Model Section ==========

@Composable
private fun WhisperModelSection(
    i18n: I18nManager,
    selectedModel: WhisperModelInfo,
    installedModels: Set<String>,
    onModelSelected: (WhisperModelInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsSectionCard(
        title = i18n["settings.transcription.whisperModel"],
        description = i18n["settings.transcription.whisperModel.description"],
        modifier = modifier
    ) {
        WhisperModelDropdown(
            selectedModel = selectedModel,
            installedModels = installedModels,
            onModelSelected = onModelSelected,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun WhisperModelDropdown(
    selectedModel: WhisperModelInfo,
    installedModels: Set<String>,
    onModelSelected: (WhisperModelInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val isInstalled = selectedModel.id in installedModels

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = "${selectedModel.displayName} (${selectedModel.size})",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            },
            suffix = {
                if (isInstalled) {
                    Surface(
                        color = AppColors.success.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Installed",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.success,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
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
            WHISPER_MODELS.forEach { model ->
                val modelInstalled = model.id in installedModels
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = model.displayName,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "(${model.size})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = model.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (modelInstalled) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Installed",
                                    tint = AppColors.success,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        onModelSelected(model)
                        expanded = false
                    },
                    leadingIcon = if (model.id == selectedModel.id) {
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

// ========== Model Comparison Table ==========

@Composable
private fun ModelComparisonTable(
    i18n: I18nManager,
    selectedModelId: String,
    installedModels: Set<String>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = i18n["settings.transcription.modelComparison"],
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(2.dp)) {
                // Header row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Model",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1.5f)
                    )
                    Text(
                        text = "Size",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Speed",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1.2f),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Accuracy",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1.2f),
                        textAlign = TextAlign.Center
                    )
                }

                // Model rows
                WHISPER_MODELS.forEach { model ->
                    val isSelected = model.id == selectedModelId
                    val isInstalled = model.id in installedModels

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isSelected) {
                                    Modifier
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                            RoundedCornerShape(4.dp)
                                        )
                                } else Modifier
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Model name with installed indicator
                        Row(
                            modifier = Modifier.weight(1.5f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                            )
                            if (isInstalled) {
                                Surface(
                                    color = AppColors.success,
                                    shape = CircleShape,
                                    modifier = Modifier.size(6.dp)
                                ) {}
                            }
                        }

                        // Size
                        Text(
                            text = model.size,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center
                        )

                        // Speed stars
                        StarRating(
                            rating = model.speedRating,
                            modifier = Modifier.weight(1.2f)
                        )

                        // Accuracy stars
                        StarRating(
                            rating = model.accuracyRating,
                            modifier = Modifier.weight(1.2f)
                        )
                    }

                    if (model != WHISPER_MODELS.last()) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StarRating(
    rating: Int,
    modifier: Modifier = Modifier,
    maxStars: Int = 5
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center
    ) {
        repeat(maxStars) { index ->
            Icon(
                imageVector = if (index < rating) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = null,
                tint = if (index < rating) {
                    AppColors.warning
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                },
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// ========== Download Section ==========

@Composable
private fun ModelDownloadSection(
    i18n: I18nManager,
    selectedModel: WhisperModelInfo,
    isInstalled: Boolean,
    downloadState: ModelDownloadState,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (isInstalled) {
            AppColors.success.copy(alpha = 0.1f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        },
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isInstalled) {
                            "${selectedModel.displayName} model is installed"
                        } else {
                            "Download ${selectedModel.displayName} model"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Size: ${selectedModel.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isInstalled) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = AppColors.success,
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    AppButton(
                        text = i18n["settings.transcription.downloadModel"],
                        onClick = onDownload,
                        style = ButtonStyle.Primary,
                        size = ButtonSize.Medium,
                        enabled = downloadState !is ModelDownloadState.Downloading,
                        loading = downloadState is ModelDownloadState.Downloading,
                        leadingIcon = Icons.Default.Download
                    )
                }
            }

            // Download progress
            AnimatedVisibility(
                visible = downloadState is ModelDownloadState.Downloading,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (downloadState is ModelDownloadState.Downloading) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            progress = { downloadState.progress },
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
                                text = downloadState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${(downloadState.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Error message
            AnimatedVisibility(
                visible = downloadState is ModelDownloadState.Error,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (downloadState is ModelDownloadState.Error) {
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
                            text = downloadState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.error
                        )
                    }
                }
            }
        }
    }
}

// ========== Installed Models Section ==========

@Composable
private fun InstalledModelsSection(
    i18n: I18nManager,
    installedModels: Set<String>,
    currentModelId: String,
    onDeleteModel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var modelToDelete by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = i18n["settings.transcription.installedModels"],
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                installedModels.forEach { modelId ->
                    val modelInfo = WHISPER_MODELS.find { it.id == modelId }
                    val isCurrentModel = modelId == currentModelId

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = if (isCurrentModel) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = modelInfo?.displayName ?: modelId,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isCurrentModel) FontWeight.Medium else FontWeight.Normal
                                    )
                                    if (isCurrentModel) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "Active",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = modelInfo?.size ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Delete button (disabled for current model)
                        IconButton(
                            onClick = { modelToDelete = modelId },
                            enabled = !isCurrentModel,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = if (isCurrentModel) {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                } else {
                                    AppColors.error
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (modelId != installedModels.last()) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    modelToDelete?.let { modelId ->
        val modelInfo = WHISPER_MODELS.find { it.id == modelId }
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = AppColors.warning
                )
            },
            title = {
                Text(text = "Delete Model?")
            },
            text = {
                Text(
                    text = "Are you sure you want to delete the ${modelInfo?.displayName ?: modelId} model? You will need to download it again to use it."
                )
            },
            confirmButton = {
                AppButton(
                    text = "Delete",
                    onClick = {
                        onDeleteModel(modelId)
                        modelToDelete = null
                    },
                    style = ButtonStyle.Danger,
                    size = ButtonSize.Small
                )
            },
            dismissButton = {
                AppButton(
                    text = "Cancel",
                    onClick = { modelToDelete = null },
                    style = ButtonStyle.Secondary,
                    size = ButtonSize.Small
                )
            }
        )
    }
}

// ========== Helper Functions ==========

/**
 * Gets the set of installed Whisper model IDs.
 */
private fun getInstalledModels(modelsDir: String): Set<String> {
    val whisperDir = File(modelsDir, "whisper")
    if (!whisperDir.exists()) return emptySet()

    return whisperDir.listFiles()
        ?.filter { it.isFile && it.name.startsWith("ggml-") && it.name.endsWith(".bin") }
        ?.mapNotNull { file ->
            val name = file.name
                .removePrefix("ggml-")
                .removeSuffix(".bin")
            // Map specific model names
            when (name) {
                "large-v3" -> "large"
                else -> name
            }
        }
        ?.toSet()
        ?: emptySet()
}

/**
 * Deletes a Whisper model file.
 */
private fun deleteModel(modelsDir: String, modelId: String) {
    val whisperDir = File(modelsDir, "whisper")
    val modelFileName = when (modelId) {
        "large" -> "ggml-large-v3.bin"
        else -> "ggml-$modelId.bin"
    }
    val modelFile = File(whisperDir, modelFileName)
    if (modelFile.exists()) {
        modelFile.delete()
    }
}
