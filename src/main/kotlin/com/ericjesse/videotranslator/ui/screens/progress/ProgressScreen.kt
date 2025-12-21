@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.progress

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.domain.model.TranslationJob
import com.ericjesse.videotranslator.domain.model.VideoInfo
import com.ericjesse.videotranslator.ui.components.*
import com.ericjesse.videotranslator.ui.components.CardElevation as AppCardElevation
import com.ericjesse.videotranslator.ui.components.dialogs.ConfirmDialog
import com.ericjesse.videotranslator.ui.components.dialogs.ConfirmDialogStyle
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import com.ericjesse.videotranslator.ui.theme.AppColors
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// ========== State Models ==========

/**
 * Pipeline stage identifiers.
 */
enum class PipelineStage {
    DOWNLOADING,
    CHECKING_CAPTIONS,
    TRANSCRIBING,
    TRANSLATING,
    RENDERING
}

/**
 * Status of a pipeline stage.
 */
sealed class StageStatus {
    data object Pending : StageStatus()
    data class InProgress(val progress: Float = 0f, val message: String? = null) : StageStatus()
    data class Complete(val message: String? = null) : StageStatus()
    data class Skipped(val reason: String) : StageStatus()
    data class Failed(val error: String) : StageStatus()
}

/**
 * State for a single pipeline stage.
 */
data class StageState(
    val stage: PipelineStage,
    val status: StageStatus = StageStatus.Pending
)

/**
 * Log entry for the log panel.
 */
data class LogEntry(
    val timestamp: LocalTime,
    val message: String,
    val level: LogLevel = LogLevel.INFO
)

/**
 * Log severity levels.
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR
}

/**
 * Overall progress screen state.
 */
sealed class ProgressState {
    /**
     * Translation is in progress.
     */
    data class Processing(
        val stages: List<StageState>,
        val currentStage: PipelineStage,
        val overallProgress: Float
    ) : ProgressState()

    /**
     * Translation completed successfully.
     */
    data class Complete(
        val outputFiles: List<OutputFile>,
        val outputDirectory: String,
        val processingTime: Long // milliseconds
    ) : ProgressState()

    /**
     * Translation failed with an error.
     */
    data class Error(
        val failedStage: PipelineStage,
        val errorMessage: String,
        val errorDetails: String?,
        val suggestions: List<String>
    ) : ProgressState()
}

/**
 * Output file information.
 */
data class OutputFile(
    val name: String,
    val path: String,
    val size: Long, // bytes
    val type: OutputFileType
)

/**
 * Type of output file.
 */
enum class OutputFileType {
    VIDEO,
    SUBTITLE
}

// ========== Main Screen ==========

/**
 * Progress screen showing translation pipeline status.
 *
 * Displays:
 * - Video info card with title and URL
 * - Pipeline progress with stage indicators
 * - Collapsible log panel
 * - Action buttons (Cancel/Complete/Error actions)
 *
 * @param appModule Application module for services.
 * @param job The translation job being processed.
 * @param progressState Current progress state.
 * @param logs Log entries to display.
 * @param onCancel Called when user cancels the translation.
 * @param onOpenFolder Called to open the output folder.
 * @param onTranslateAnother Called to start a new translation.
 * @param onOpenSettings Called to open settings.
 * @param onRetry Called to retry the translation.
 * @param modifier Modifier for the screen.
 */
@Composable
fun ProgressScreen(
    appModule: AppModule,
    job: TranslationJob,
    progressState: ProgressState,
    logs: List<LogEntry>,
    onCancel: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onTranslateAnother: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val i18n = appModule.i18nManager
    var showCancelConfirmation by remember { mutableStateOf(false) }
    var logsExpanded by remember { mutableStateOf(true) }

    // Cancel confirmation dialog
    if (showCancelConfirmation) {
        ConfirmDialog(
            title = i18n["progress.cancel.title"],
            message = i18n["progress.cancel.description"],
            confirmText = i18n["progress.cancel.confirm"],
            cancelText = i18n["progress.cancel.continue"],
            style = ConfirmDialogStyle.Warning,
            onConfirm = {
                showCancelConfirmation = false
                onCancel()
            },
            onDismiss = { showCancelConfirmation = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        ProgressHeader(
            title = when (progressState) {
                is ProgressState.Processing -> i18n["progress.title"]
                is ProgressState.Complete -> i18n["progress.complete.title"]
                is ProgressState.Error -> i18n["progress.error.title"]
            },
            showSuccessIcon = progressState is ProgressState.Complete,
            showErrorIcon = progressState is ProgressState.Error
        )

        // Scrollable content
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Video Info Card
            VideoInfoCard(
                videoInfo = job.videoInfo,
                i18n = i18n
            )

            // Main content based on state
            when (progressState) {
                is ProgressState.Processing -> {
                    // Pipeline Progress Card
                    PipelineProgressCard(
                        stages = progressState.stages,
                        i18n = i18n
                    )

                    // Overall progress
                    OverallProgressBar(
                        progress = progressState.overallProgress,
                        i18n = i18n
                    )
                }

                is ProgressState.Complete -> {
                    // Success Card
                    SuccessCard(
                        outputFiles = progressState.outputFiles,
                        outputDirectory = progressState.outputDirectory,
                        processingTime = progressState.processingTime,
                        i18n = i18n,
                        onOpenFolder = { onOpenFolder(progressState.outputDirectory) }
                    )
                }

                is ProgressState.Error -> {
                    // Error Card
                    ErrorCard(
                        failedStage = progressState.failedStage,
                        errorMessage = progressState.errorMessage,
                        errorDetails = progressState.errorDetails,
                        suggestions = progressState.suggestions,
                        i18n = i18n
                    )
                }
            }

            // Collapsible Log Panel
            LogPanel(
                logs = logs,
                expanded = logsExpanded,
                onToggleExpanded = { logsExpanded = !logsExpanded },
                i18n = i18n
            )
        }

        // Footer with action buttons
        ProgressFooter(
            state = progressState,
            i18n = i18n,
            onCancel = { showCancelConfirmation = true },
            onOpenFolder = onOpenFolder,
            onTranslateAnother = onTranslateAnother,
            onOpenSettings = onOpenSettings,
            onRetry = onRetry
        )
    }
}

// ========== Header ==========

@Composable
private fun ProgressHeader(
    title: String,
    showSuccessIcon: Boolean,
    showErrorIcon: Boolean,
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
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                showSuccessIcon -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = AppColors.success,
                        modifier = Modifier.size(28.dp)
                    )
                }
                showErrorIcon -> {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = AppColors.error,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ========== Video Info Card ==========

@Composable
private fun VideoInfoCard(
    videoInfo: VideoInfo,
    i18n: I18nManager,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier.fillMaxWidth(),
        elevation = AppCardElevation.Low
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail placeholder
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(80.dp, 45.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Video title
                Text(
                    text = videoInfo.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // URL (truncated)
                Text(
                    text = truncateUrl(videoInfo.url),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Duration
                if (videoInfo.duration > 0) {
                    Text(
                        text = formatDuration(videoInfo.duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ========== Pipeline Progress Card ==========

@Composable
private fun PipelineProgressCard(
    stages: List<StageState>,
    i18n: I18nManager,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier.fillMaxWidth(),
        elevation = AppCardElevation.Low
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = i18n["progress.pipeline.title"],
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            stages.forEachIndexed { index, stageState ->
                StageRow(
                    stage = stageState.stage,
                    status = stageState.status,
                    i18n = i18n
                )

                if (index < stages.lastIndex) {
                    // Connector line
                    Box(
                        modifier = Modifier
                            .padding(start = 15.dp)
                            .width(2.dp)
                            .height(12.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    )
                }
            }
        }
    }
}

@Composable
private fun StageRow(
    stage: PipelineStage,
    status: StageStatus,
    i18n: I18nManager
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status icon
        StageIcon(status = status)

        // Stage info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = getStageName(stage, i18n),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (status is StageStatus.InProgress) FontWeight.Medium else FontWeight.Normal
            )

            // Progress bar for in-progress stages
            if (status is StageStatus.InProgress && status.progress > 0) {
                LinearProgressIndicator(
                    progress = { status.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            // Status message
            val message = when (status) {
                is StageStatus.InProgress -> status.message
                is StageStatus.Complete -> status.message
                is StageStatus.Skipped -> status.reason
                is StageStatus.Failed -> status.error
                else -> null
            }

            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (status) {
                        is StageStatus.Failed -> AppColors.error
                        is StageStatus.Skipped -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Status label
        StatusLabel(status = status, i18n = i18n)
    }
}

@Composable
private fun StageIcon(status: StageStatus) {
    val size = 32.dp
    val iconSize = 18.dp

    when (status) {
        is StageStatus.Pending -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
                modifier = Modifier.size(size)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outline)
                    )
                }
            }
        }

        is StageStatus.InProgress -> {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                shape = CircleShape,
                modifier = Modifier.size(size)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Spinning indicator
                    val infiniteTransition = rememberInfiniteTransition(label = "spin")
                    val rotation by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "rotation"
                    )

                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(iconSize)
                            .rotate(rotation)
                    )
                }
            }
        }

        is StageStatus.Complete -> {
            Surface(
                color = AppColors.success.copy(alpha = 0.15f),
                shape = CircleShape,
                modifier = Modifier.size(size)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = AppColors.success,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
        }

        is StageStatus.Skipped -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
                modifier = Modifier.size(size)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
        }

        is StageStatus.Failed -> {
            Surface(
                color = AppColors.error.copy(alpha = 0.15f),
                shape = CircleShape,
                modifier = Modifier.size(size)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = AppColors.error,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLabel(
    status: StageStatus,
    i18n: I18nManager
) {
    val (text, color) = when (status) {
        is StageStatus.Pending -> i18n["progress.status.pending"] to MaterialTheme.colorScheme.onSurfaceVariant
        is StageStatus.InProgress -> {
            val progressText = if (status.progress > 0) {
                "${(status.progress * 100).toInt()}%"
            } else {
                i18n["progress.status.inProgress"]
            }
            progressText to MaterialTheme.colorScheme.primary
        }
        is StageStatus.Complete -> i18n["progress.status.complete"] to AppColors.success
        is StageStatus.Skipped -> i18n["progress.status.skipped"] to MaterialTheme.colorScheme.onSurfaceVariant
        is StageStatus.Failed -> i18n["progress.status.failed"] to AppColors.error
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.Medium
    )
}

// ========== Overall Progress Bar ==========

@Composable
private fun OverallProgressBar(
    progress: Float,
    i18n: I18nManager,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = i18n["progress.overall"],
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
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
    }
}

// ========== Success Card ==========

@Composable
private fun SuccessCard(
    outputFiles: List<OutputFile>,
    outputDirectory: String,
    processingTime: Long,
    i18n: I18nManager,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier.fillMaxWidth(),
        elevation = AppCardElevation.Low
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Success header with icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = AppColors.success.copy(alpha = 0.15f),
                        shape = CircleShape,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = AppColors.success,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }

                    Text(
                        text = i18n["progress.complete.message"],
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        text = i18n["progress.complete.time", formatProcessingTime(processingTime)],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // Output files section
            Text(
                text = i18n["progress.complete.outputFiles"],
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )

            outputFiles.forEach { file ->
                OutputFileRow(file = file)
            }

            // Output location
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenFolder() }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )

                Text(
                    text = outputDirectory,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun OutputFileRow(file: OutputFile) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // File type icon
        Icon(
            imageVector = when (file.type) {
                OutputFileType.VIDEO -> Icons.Default.VideoFile
                OutputFileType.SUBTITLE -> Icons.Default.Subtitles
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )

        // File name
        Text(
            text = file.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // File size
        Text(
            text = formatFileSize(file.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ========== Error Card ==========

@Composable
private fun ErrorCard(
    failedStage: PipelineStage,
    errorMessage: String,
    errorDetails: String?,
    suggestions: List<String>,
    i18n: I18nManager,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier.fillMaxWidth(),
        elevation = AppCardElevation.Low
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Error header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = AppColors.error.copy(alpha = 0.15f),
                        shape = CircleShape,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = AppColors.error,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }

                    Text(
                        text = i18n["progress.error.message"],
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        text = i18n["progress.error.stage", getStageName(failedStage, i18n)],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // Error details
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = i18n["progress.error.details"],
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )

                Surface(
                    color = AppColors.error.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.error
                        )

                        if (errorDetails != null) {
                            Text(
                                text = errorDetails,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Suggestions
            if (suggestions.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = i18n["progress.error.suggestions"],
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )

                    suggestions.forEach { suggestion ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ========== Log Panel ==========

@Composable
private fun LogPanel(
    logs: List<LogEntry>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    i18n: I18nManager,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier.fillMaxWidth(),
        elevation = AppCardElevation.Low
    ) {
        Column {
            // Header (always visible)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = i18n["progress.log.title"],
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (expanded) i18n["action.hide"] else i18n["action.show"],
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Log content (expandable)
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                    LogContent(logs = logs)
                }
            }
        }
    }
}

@Composable
private fun LogContent(logs: List<LogEntry>) {
    val listState = rememberLazyListState()
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }

    // Auto-scroll to bottom when new logs are added
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Surface(
        color = Color(0xFF1A1A1A),
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(logs) { entry ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Timestamp
                    Text(
                        text = "[${entry.timestamp.format(timeFormatter)}]",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = Color(0xFF888888)
                    )

                    // Message
                    Text(
                        text = entry.message,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = when (entry.level) {
                            LogLevel.DEBUG -> Color(0xFF888888)
                            LogLevel.INFO -> Color(0xFFCCCCCC)
                            LogLevel.WARNING -> Color(0xFFF59E0B)
                            LogLevel.ERROR -> Color(0xFFEF4444)
                        }
                    )
                }
            }
        }
    }
}

// ========== Footer ==========

@Composable
private fun ProgressFooter(
    state: ProgressState,
    i18n: I18nManager,
    onCancel: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onTranslateAnother: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (state) {
                is ProgressState.Processing -> {
                    AppButton(
                        text = i18n["action.cancel"],
                        onClick = onCancel,
                        style = ButtonStyle.Secondary,
                        size = ButtonSize.Large
                    )
                }

                is ProgressState.Complete -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        AppButton(
                            text = i18n["progress.complete.openFolder"],
                            onClick = { onOpenFolder(state.outputDirectory) },
                            style = ButtonStyle.Secondary,
                            size = ButtonSize.Large,
                            leadingIcon = Icons.Default.Folder
                        )

                        AppButton(
                            text = i18n["progress.complete.translateAnother"],
                            onClick = onTranslateAnother,
                            style = ButtonStyle.Primary,
                            size = ButtonSize.Large
                        )
                    }
                }

                is ProgressState.Error -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        AppButton(
                            text = i18n["progress.error.openSettings"],
                            onClick = onOpenSettings,
                            style = ButtonStyle.Secondary,
                            size = ButtonSize.Large,
                            leadingIcon = Icons.Default.Settings
                        )

                        AppButton(
                            text = i18n["progress.error.tryAgain"],
                            onClick = onRetry,
                            style = ButtonStyle.Primary,
                            size = ButtonSize.Large,
                            leadingIcon = Icons.Default.Refresh
                        )
                    }
                }
            }
        }
    }
}

// ========== Helper Functions ==========

private fun getStageName(stage: PipelineStage, i18n: I18nManager): String {
    return when (stage) {
        PipelineStage.DOWNLOADING -> i18n["progress.stage.downloading"]
        PipelineStage.CHECKING_CAPTIONS -> i18n["progress.stage.checkingCaptions"]
        PipelineStage.TRANSCRIBING -> i18n["progress.stage.transcribing"]
        PipelineStage.TRANSLATING -> i18n["progress.stage.translating"]
        PipelineStage.RENDERING -> i18n["progress.stage.rendering"]
    }
}

private fun truncateUrl(url: String, maxLength: Int = 50): String {
    return if (url.length <= maxLength) url
    else url.take(maxLength - 3) + "..."
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

private fun formatProcessingTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60

    return if (minutes > 0) {
        "$minutes min $seconds sec"
    } else {
        "$seconds seconds"
    }
}

private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0

    return when {
        gb >= 1 -> String.format("%.1f GB", gb)
        mb >= 1 -> String.format("%.1f MB", mb)
        kb >= 1 -> String.format("%.1f KB", kb)
        else -> "$bytes B"
    }
}
