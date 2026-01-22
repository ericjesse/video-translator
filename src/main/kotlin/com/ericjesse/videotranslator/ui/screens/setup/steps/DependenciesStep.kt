@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.setup.steps

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.domain.installer.DependencyInstaller
import com.ericjesse.videotranslator.domain.installer.PreInstallCheckResult
import com.ericjesse.videotranslator.ui.components.AppButton
import com.ericjesse.videotranslator.ui.components.AppCard
import com.ericjesse.videotranslator.ui.components.ButtonSize
import com.ericjesse.videotranslator.ui.components.ButtonStyle
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import com.ericjesse.videotranslator.ui.components.CardElevation as AppCardElevation

private val logger = KotlinLogging.logger {}

/**
 * Whisper model information with size and description.
 */
enum class WhisperModel(
    val id: String,
    val sizeBytes: Long,
    val displaySize: String,
    val descriptionKey: String
) {
    TINY("tiny", 75_000_000L, "75 MB", "model.tiny"),
    BASE("base", 142_000_000L, "142 MB", "model.base"),
    SMALL("small", 466_000_000L, "466 MB", "model.small"),
    MEDIUM("medium", 1_500_000_000L, "1.5 GB", "model.medium"),
    LARGE("large", 2_900_000_000L, "2.9 GB", "model.large");

    companion object {
        fun fromId(id: String): WhisperModel = entries.find { it.id == id } ?: BASE
        val recommended = BASE
    }
}

/**
 * Component information for display.
 */
data class ComponentInfo(
    val nameKey: String,
    val descriptionKey: String,
    val sizeBytes: Long,
    val displaySize: String,
    val isRequired: Boolean = true
)

/**
 * Dependencies step of the setup wizard.
 *
 * Displays:
 * - yt-dlp with checkbox (read-only, always required)
 * - FFmpeg with checkbox (read-only, always required)
 * - Whisper with model selector dropdown
 * - Total download size (updates when model changes)
 * - "Download & Install" button
 *
 * @param i18n The i18n manager for localized strings.
 * @param selectedModel The currently selected Whisper model ID.
 * @param onModelSelected Callback when a model is selected.
 * @param onDownload Callback when the download button is clicked.
 * @param isDownloading Whether a download is in progress (disables interactions).
 * @param modifier Modifier to be applied to the step.
 */
@Composable
fun DependenciesStep(
    i18n: I18nManager,
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    onDownload: () -> Unit,
    isDownloading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val dependencyInstaller: DependencyInstaller = koinInject()
    val scrollState = rememberScrollState()
    val currentModel = WhisperModel.fromId(selectedModel)

    // State for pre-installation check results
    var preInstallCheckResults by remember { mutableStateOf<List<PreInstallCheckResult>?>(null) }
    var isCheckingPrerequisites by remember { mutableStateOf(true) }

    // Run pre-installation checks when the step is first displayed
    LaunchedEffect(Unit) {
        logger.info { "DependenciesStep: Starting pre-installation checks after language selection" }
        isCheckingPrerequisites = true
        try {
            preInstallCheckResults = dependencyInstaller.runPreInstallationChecks()
            logger.info { "DependenciesStep: Pre-installation checks completed" }
        } catch (e: Exception) {
            logger.error(e) { "DependenciesStep: Pre-installation checks failed with exception" }
        } finally {
            isCheckingPrerequisites = false
        }
    }

    // Component definitions
    val ytDlp = ComponentInfo(
        nameKey = "component.ytdlp.name",
        descriptionKey = "component.ytdlp.description",
        sizeBytes = 12_400_000L,
        displaySize = "12.4 MB"
    )

    val ffmpeg = ComponentInfo(
        nameKey = "component.ffmpeg.name",
        descriptionKey = "component.ffmpeg.description",
        sizeBytes = 85_200_000L,
        displaySize = "85.2 MB"
    )

    val libreTranslate = ComponentInfo(
        nameKey = "component.libretranslate.name",
        descriptionKey = "component.libretranslate.description",
        sizeBytes = 150_000_000L,
        displaySize = "150 MB"
    )

    // Calculate total size
    val totalSizeBytes = ytDlp.sizeBytes + ffmpeg.sizeBytes + libreTranslate.sizeBytes + currentModel.sizeBytes
    val totalSizeDisplay = formatSize(totalSizeBytes)

    // Animation states
    var showContent by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(100)
        showContent = true
    }

    val contentAlpha by animateFloatAsState(
        targetValue = if (showContent) 1f else 0f,
        animationSpec = tween(400),
        label = "contentAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(contentAlpha)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp)
                .padding(end = 12.dp) // Extra padding for scrollbar
        ) {
            // Description
            Text(
            text = i18n["setup.dependencies.description"],
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Components card
        AppCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = AppCardElevation.Low
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // yt-dlp
                ComponentItem(
                    name = i18n[ytDlp.nameKey],
                    description = i18n[ytDlp.descriptionKey],
                    size = ytDlp.displaySize,
                    isRequired = true,
                    isEnabled = !isDownloading
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // FFmpeg
                ComponentItem(
                    name = i18n[ffmpeg.nameKey],
                    description = i18n[ffmpeg.descriptionKey],
                    size = ffmpeg.displaySize,
                    isRequired = true,
                    isEnabled = !isDownloading
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // LibreTranslate
                ComponentItem(
                    name = i18n[libreTranslate.nameKey],
                    description = i18n[libreTranslate.descriptionKey],
                    size = libreTranslate.displaySize,
                    isRequired = true,
                    isEnabled = !isDownloading
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // Whisper with model selector
                WhisperComponentItem(
                    i18n = i18n,
                    selectedModel = currentModel,
                    onModelSelected = { onModelSelected(it.id) },
                    isEnabled = !isDownloading
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Model descriptions
        ModelDescriptions(i18n = i18n)

        Spacer(modifier = Modifier.height(16.dp))

        // Total size
        TotalSizeIndicator(
            size = totalSizeDisplay,
            i18n = i18n
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Download button
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            AppButton(
                text = i18n["setup.dependencies.downloadInstall"],
                onClick = onDownload,
                style = ButtonStyle.Primary,
                size = ButtonSize.Large,
                leadingIcon = Icons.Default.Download,
                enabled = !isDownloading,
                loading = isDownloading
            )
        }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Scrollbar
        VerticalScrollbar(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
            adapter = rememberScrollbarAdapter(scrollState)
        )
    }
}

@Composable
private fun ComponentItem(
    name: String,
    description: String,
    size: String,
    isRequired: Boolean,
    isEnabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Checkbox (read-only for required components)
        Checkbox(
            checked = true,
            onCheckedChange = null,
            enabled = false,
            colors = CheckboxDefaults.colors(
                disabledCheckedColor = MaterialTheme.colorScheme.primary,
                disabledUncheckedColor = MaterialTheme.colorScheme.outline
            )
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Name and description
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )

                if (isRequired) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "Required",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Size
        Text(
            text = size,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun WhisperComponentItem(
    i18n: I18nManager,
    selectedModel: WhisperModel,
    onModelSelected: (WhisperModel) -> Unit,
    isEnabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Checkbox
            Checkbox(
                checked = true,
                onCheckedChange = null,
                enabled = false,
                colors = CheckboxDefaults.colors(
                    disabledCheckedColor = MaterialTheme.colorScheme.primary,
                    disabledUncheckedColor = MaterialTheme.colorScheme.outline
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Name and description
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = i18n["component.whisper.name"],
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "Required",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = i18n["component.whisper.description"],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Model selector
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = i18n["model.select"],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    ExposedDropdownMenuBox(
                        expanded = expanded && isEnabled,
                        onExpandedChange = { if (isEnabled) expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = "${selectedModel.id} (${selectedModel.displaySize})",
                            onValueChange = {},
                            readOnly = true,
                            enabled = isEnabled,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                            },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .width(200.dp)
                                .pointerHoverIcon(if (isEnabled) PointerIcon.Hand else PointerIcon.Default),
                            textStyle = MaterialTheme.typography.bodySmall,
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors()
                        )

                        ExposedDropdownMenu(
                            expanded = expanded && isEnabled,
                            onDismissRequest = { expanded = false }
                        ) {
                            WhisperModel.entries.forEach { model ->
                                val isRecommended = model == WhisperModel.recommended

                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = model.id,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )

                                                Text(
                                                    text = "(${model.displaySize})",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )

                                                if (isRecommended) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = MaterialTheme.colorScheme.primaryContainer
                                                    ) {
                                                        Text(
                                                            text = i18n["setup.translation.recommended"],
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onClick = {
                                        onModelSelected(model)
                                        expanded = false
                                    },
                                    leadingIcon = if (model == selectedModel) {
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
        }
    }
}

@Composable
private fun ModelDescriptions(
    i18n: I18nManager
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(12.dp)
    ) {
        Text(
            text = i18n["model.available"],
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        WhisperModel.entries.forEach { model ->
            val description = i18n[model.descriptionKey, model.displaySize]
            val isRecommended = model == WhisperModel.recommended

            Row(
                modifier = Modifier.padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isRecommended) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isRecommended) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isRecommended) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun TotalSizeIndicator(
    size: String,
    i18n: I18nManager
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )

            Text(
                text = i18n["setup.dependencies.totalSize", size],
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        ) {
            Text(
                text = size,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * Formats a size in bytes to a human-readable string.
 */
private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> "~${String.format("%.1f", bytes / 1_000_000_000.0)} GB"
        bytes >= 1_000_000 -> "~${bytes / 1_000_000} MB"
        bytes >= 1_000 -> "~${bytes / 1_000} KB"
        else -> "$bytes B"
    }
}
