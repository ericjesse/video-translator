@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.setup.steps

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.infrastructure.update.DownloadProgress
import com.ericjesse.videotranslator.ui.components.*
import com.ericjesse.videotranslator.ui.components.dialogs.ConfirmDialog
import com.ericjesse.videotranslator.ui.components.dialogs.ConfirmDialogStyle
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import com.ericjesse.videotranslator.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/**
 * Component download status.
 */
enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    COMPLETE,
    ERROR
}

/**
 * State for a single component download.
 */
data class ComponentDownloadState(
    val name: String,
    val displayName: String,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val progress: Float = 0f,
    val downloadedSize: Long = 0L,
    val totalSize: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val message: String = "",
    val errorMessage: String? = null
)

/**
 * Downloading step of the setup wizard.
 *
 * Displays:
 * - Component list with status icons (Pending, Downloading, Complete, Error)
 * - Per-component progress bar with size and speed
 * - Overall progress bar
 * - Status message showing current action
 * - Cancel button with confirmation
 *
 * @param appModule Application module for accessing services.
 * @param selectedWhisperModel The selected Whisper model to download.
 * @param onComplete Callback when all downloads complete successfully.
 * @param onCancel Callback when the user cancels the download.
 * @param modifier Modifier to be applied to the step.
 */
@Composable
fun DownloadingStep(
    appModule: AppModule,
    selectedWhisperModel: String,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val i18n = appModule.i18nManager
    val updateManager = appModule.updateManager
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Download states
    var ytDlpState by remember {
        mutableStateOf(
            ComponentDownloadState(
                name = "ytdlp",
                displayName = "yt-dlp",
                totalSize = 12_400_000L
            )
        )
    }
    var ffmpegState by remember {
        mutableStateOf(
            ComponentDownloadState(
                name = "ffmpeg",
                displayName = "FFmpeg",
                totalSize = 85_200_000L
            )
        )
    }
    var whisperCppState by remember {
        mutableStateOf(
            ComponentDownloadState(
                name = "whispercpp",
                displayName = "whisper.cpp",
                totalSize = 5_000_000L // ~5MB for the binary
            )
        )
    }
    var whisperModelState by remember {
        mutableStateOf(
            ComponentDownloadState(
                name = "whispermodel",
                displayName = "Whisper $selectedWhisperModel model",
                totalSize = WhisperModel.fromId(selectedWhisperModel).sizeBytes
            )
        )
    }

    // Overall state
    var overallProgress by remember { mutableFloatStateOf(0f) }
    var currentStatusMessage by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }
    var showCancelConfirmation by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }

    // Start downloads when component mounts
    LaunchedEffect(Unit) {
        isDownloading = true

        // Download yt-dlp
        currentStatusMessage = i18n["setup.downloading.from", "yt-dlp", "github.com"]
        ytDlpState = ytDlpState.copy(status = DownloadStatus.DOWNLOADING)

        try {
            updateManager.installYtDlp()
                .catch { e ->
                    ytDlpState = ytDlpState.copy(
                        status = DownloadStatus.ERROR,
                        errorMessage = e.message ?: "Download failed"
                    )
                    hasError = true
                }
                .collect { progress ->
                    ytDlpState = ytDlpState.copy(
                        progress = progress.percentage,
                        downloadedSize = (ytDlpState.totalSize * progress.percentage).toLong(),
                        message = progress.message
                    )
                    overallProgress = progress.percentage * 0.25f
                }

            if (ytDlpState.status != DownloadStatus.ERROR) {
                ytDlpState = ytDlpState.copy(status = DownloadStatus.COMPLETE, progress = 1f)
            }
        } catch (e: Exception) {
            ytDlpState = ytDlpState.copy(
                status = DownloadStatus.ERROR,
                errorMessage = e.message ?: "Download failed"
            )
            hasError = true
        }

        if (hasError) {
            isDownloading = false
            return@LaunchedEffect
        }

        // Download FFmpeg (25% - 45%)
        currentStatusMessage = i18n["setup.downloading.from", "FFmpeg", "gyan.dev"]
        ffmpegState = ffmpegState.copy(status = DownloadStatus.DOWNLOADING)

        try {
            updateManager.installFfmpeg()
                .catch { e ->
                    ffmpegState = ffmpegState.copy(
                        status = DownloadStatus.ERROR,
                        errorMessage = e.message ?: "Download failed"
                    )
                    hasError = true
                }
                .collect { progress ->
                    ffmpegState = ffmpegState.copy(
                        progress = progress.percentage,
                        downloadedSize = (ffmpegState.totalSize * progress.percentage).toLong(),
                        message = progress.message
                    )
                    overallProgress = 0.25f + progress.percentage * 0.20f
                }

            if (ffmpegState.status != DownloadStatus.ERROR) {
                ffmpegState = ffmpegState.copy(status = DownloadStatus.COMPLETE, progress = 1f)
            }
        } catch (e: Exception) {
            ffmpegState = ffmpegState.copy(
                status = DownloadStatus.ERROR,
                errorMessage = e.message ?: "Download failed"
            )
            hasError = true
        }

        if (hasError) {
            isDownloading = false
            return@LaunchedEffect
        }

        // Download whisper.cpp binary (45% - 60%)
        currentStatusMessage = i18n["setup.downloading.from", "whisper.cpp", "github.com"]
        whisperCppState = whisperCppState.copy(status = DownloadStatus.DOWNLOADING)

        try {
            updateManager.installWhisperCpp()
                .catch { e ->
                    whisperCppState = whisperCppState.copy(
                        status = DownloadStatus.ERROR,
                        errorMessage = e.message ?: "Download failed"
                    )
                    hasError = true
                }
                .collect { progress ->
                    whisperCppState = whisperCppState.copy(
                        progress = progress.percentage,
                        downloadedSize = (whisperCppState.totalSize * progress.percentage).toLong(),
                        message = progress.message
                    )
                    overallProgress = 0.45f + progress.percentage * 0.15f
                }

            if (whisperCppState.status != DownloadStatus.ERROR) {
                whisperCppState = whisperCppState.copy(status = DownloadStatus.COMPLETE, progress = 1f)
            }
        } catch (e: Exception) {
            whisperCppState = whisperCppState.copy(
                status = DownloadStatus.ERROR,
                errorMessage = e.message ?: "Download failed"
            )
            hasError = true
        }

        if (hasError) {
            isDownloading = false
            return@LaunchedEffect
        }

        // Download Whisper model (60% - 100%)
        currentStatusMessage = i18n["setup.downloading.from", "Whisper $selectedWhisperModel model", "huggingface.co"]
        whisperModelState = whisperModelState.copy(status = DownloadStatus.DOWNLOADING)

        try {
            updateManager.installWhisperModel(selectedWhisperModel)
                .catch { e ->
                    whisperModelState = whisperModelState.copy(
                        status = DownloadStatus.ERROR,
                        errorMessage = e.message ?: "Download failed"
                    )
                    hasError = true
                }
                .collect { progress ->
                    whisperModelState = whisperModelState.copy(
                        progress = progress.percentage,
                        downloadedSize = (whisperModelState.totalSize * progress.percentage).toLong(),
                        message = progress.message
                    )
                    overallProgress = 0.60f + progress.percentage * 0.40f
                }

            if (whisperModelState.status != DownloadStatus.ERROR) {
                whisperModelState = whisperModelState.copy(status = DownloadStatus.COMPLETE, progress = 1f)
            }
        } catch (e: Exception) {
            whisperModelState = whisperModelState.copy(
                status = DownloadStatus.ERROR,
                errorMessage = e.message ?: "Download failed"
            )
            hasError = true
        }

        isDownloading = false

        if (!hasError) {
            overallProgress = 1f
            currentStatusMessage = i18n["setup.downloading.status.complete"]
            delay(500) // Brief pause to show completion
            onComplete()
        }
    }

    // Cancel confirmation dialog
    if (showCancelConfirmation) {
        ConfirmDialog(
            title = i18n["cancel.title"],
            message = i18n["cancel.description"],
            onConfirm = {
                showCancelConfirmation = false
                onCancel()
            },
            onDismiss = { showCancelConfirmation = false },
            confirmText = i18n["action.cancel"],
            cancelText = i18n["action.continue"],
            style = ConfirmDialogStyle.Warning
        )
    }

    // Animation state
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .alpha(contentAlpha)
            .verticalScroll(scrollState)
            .padding(24.dp)
    ) {
        // Components card
        AppCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = com.ericjesse.videotranslator.ui.components.CardElevation.Low
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // yt-dlp
                DownloadComponentItem(
                    state = ytDlpState,
                    i18n = i18n
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // FFmpeg
                DownloadComponentItem(
                    state = ffmpegState,
                    i18n = i18n
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // whisper.cpp binary
                DownloadComponentItem(
                    state = whisperCppState,
                    i18n = i18n
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // Whisper model
                DownloadComponentItem(
                    state = whisperModelState,
                    i18n = i18n
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Overall progress
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = i18n["setup.downloading.overall"],
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "${(overallProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            AppLinearProgressBar(
                progress = overallProgress,
                color = ProgressColor.Primary,
                size = ProgressSize.Medium,
                showPercentage = false
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Status message
        if (currentStatusMessage.isNotEmpty()) {
            Text(
                text = currentStatusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasError) {
                // Error state: Show Retry and Back buttons
                AppButton(
                    text = i18n["action.back"],
                    onClick = onCancel,
                    style = ButtonStyle.Secondary,
                    size = ButtonSize.Medium
                )

                Spacer(modifier = Modifier.width(16.dp))

                AppButton(
                    text = i18n["action.retry"],
                    onClick = {
                        // Reset error states and retry
                        hasError = false
                        if (ytDlpState.status == DownloadStatus.ERROR) {
                            ytDlpState = ytDlpState.copy(
                                status = DownloadStatus.PENDING,
                                errorMessage = null,
                                progress = 0f
                            )
                        }
                        if (ffmpegState.status == DownloadStatus.ERROR) {
                            ffmpegState = ffmpegState.copy(
                                status = DownloadStatus.PENDING,
                                errorMessage = null,
                                progress = 0f
                            )
                        }
                        if (whisperCppState.status == DownloadStatus.ERROR) {
                            whisperCppState = whisperCppState.copy(
                                status = DownloadStatus.PENDING,
                                errorMessage = null,
                                progress = 0f
                            )
                        }
                        if (whisperModelState.status == DownloadStatus.ERROR) {
                            whisperModelState = whisperModelState.copy(
                                status = DownloadStatus.PENDING,
                                errorMessage = null,
                                progress = 0f
                            )
                        }
                        overallProgress = 0f
                    },
                    style = ButtonStyle.Primary,
                    size = ButtonSize.Medium,
                    leadingIcon = Icons.Default.Refresh
                )
            } else {
                // Normal state: Show Cancel button
                AppButton(
                    text = i18n["action.cancel"],
                    onClick = { showCancelConfirmation = true },
                    style = ButtonStyle.Secondary,
                    size = ButtonSize.Medium,
                    enabled = isDownloading
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun DownloadComponentItem(
    state: ComponentDownloadState,
    i18n: I18nManager
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Status icon
                StatusIcon(status = state.status)

                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }

            // Status text
            StatusText(
                status = state.status,
                progress = state.progress,
                i18n = i18n,
                errorMessage = state.errorMessage
            )
        }

        // Progress bar (only when downloading)
        if (state.status == DownloadStatus.DOWNLOADING) {
            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier.padding(start = 36.dp)
            ) {
                AppLinearProgressBar(
                    progress = state.progress,
                    color = ProgressColor.Primary,
                    size = ProgressSize.Small,
                    showPercentage = false
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Size info
                Text(
                    text = "${formatBytes(state.downloadedSize)} / ${formatBytes(state.totalSize)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Error message
        if (state.status == DownloadStatus.ERROR && state.errorMessage != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 36.dp)
            )
        }
    }
}

@Composable
private fun StatusIcon(status: DownloadStatus) {
    when (status) {
        DownloadStatus.PENDING -> {
            // Empty circle (○)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
        }

        DownloadStatus.DOWNLOADING -> {
            // Animated spinning circle (◉)
            val infiniteTransition = rememberInfiniteTransition(label = "downloading")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "rotation"
            )

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(rotation)
                )
            }
        }

        DownloadStatus.COMPLETE -> {
            // Check mark (✓)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(AppColors.success.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = AppColors.success,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        DownloadStatus.ERROR -> {
            // Error X (✗)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusText(
    status: DownloadStatus,
    progress: Float,
    i18n: I18nManager,
    errorMessage: String?
) {
    val (text, color) = when (status) {
        DownloadStatus.PENDING -> i18n["setup.downloading.status.pending"] to MaterialTheme.colorScheme.onSurfaceVariant
        DownloadStatus.DOWNLOADING -> "${(progress * 100).toInt()}%" to MaterialTheme.colorScheme.primary
        DownloadStatus.COMPLETE -> i18n["setup.downloading.status.complete"] to AppColors.success
        DownloadStatus.ERROR -> "Error" to MaterialTheme.colorScheme.error
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        fontWeight = if (status == DownloadStatus.COMPLETE) FontWeight.Medium else FontWeight.Normal
    )
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
