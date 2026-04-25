@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.setup.steps

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.domain.installer.ComponentId
import com.ericjesse.videotranslator.domain.installer.DependencyInstaller
import com.ericjesse.videotranslator.domain.installer.InstallationProgress
import com.ericjesse.videotranslator.ui.components.AppButton
import com.ericjesse.videotranslator.ui.components.AppCard
import com.ericjesse.videotranslator.ui.components.AppLinearProgressBar
import com.ericjesse.videotranslator.ui.components.ButtonSize
import com.ericjesse.videotranslator.ui.components.ButtonStyle
import com.ericjesse.videotranslator.ui.components.ProgressColor
import com.ericjesse.videotranslator.ui.components.ProgressSize
import com.ericjesse.videotranslator.ui.components.dialogs.ConfirmDialog
import com.ericjesse.videotranslator.ui.components.dialogs.ConfirmDialogStyle
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import com.ericjesse.videotranslator.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import org.koin.compose.koinInject

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
    selectedWhisperModel: String,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val i18n: I18nManager = koinInject()
    val dependencyInstaller: DependencyInstaller = koinInject()
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
    var pythonState by remember {
        mutableStateOf(
            ComponentDownloadState(
                name = "python",
                displayName = "Python",
                totalSize = 50_000_000L // ~50MB for Python installation
            )
        )
    }
    var libreTranslateState by remember {
        mutableStateOf(
            ComponentDownloadState(
                name = "libretranslate",
                displayName = "LibreTranslate",
                totalSize = 150_000_000L // ~150MB for LibreTranslate + dependencies
            )
        )
    }

    // Overall state
    var overallProgress by remember { mutableFloatStateOf(0f) }
    var currentStatusMessage by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }
    var showCancelConfirmation by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }

    // Incrementing this key re-triggers the install LaunchedEffect on retry.
    // DependencyInstaller reuses any files already downloaded in a previous attempt,
    // so components that finished earlier pass through almost instantly on retry.
    var retryCount by remember { mutableIntStateOf(0) }

    // Weights used to blend per-component progress into the overall progress bar.
    // They need not match actual download sizes — they just determine pacing of the bar.
    val componentWeights = remember {
        linkedMapOf(
            ComponentId.YT_DLP to 0.08f,
            ComponentId.FFMPEG to 0.17f,
            ComponentId.WHISPER_CPP to 0.08f,
            ComponentId.WHISPER_MODEL_BASE to 0.10f,
            ComponentId.WHISPER_MODEL_SMALL to 0.10f,
            ComponentId.WHISPER_MODEL_MEDIUM to 0.10f,
            ComponentId.WHISPER_MODEL_LARGE to 0.10f,
            ComponentId.PYTHON to 0.07f,
            ComponentId.LIBRE_TRANSLATE to 0.50f,
        )
    }

    // Bookkeeping: how much of each component is done (0..1)
    val componentProgress = remember { mutableStateOf(mapOf<ComponentId, Float>()) }

    fun recomputeOverall() {
        val done = componentProgress.value.entries.sumOf {
            ((componentWeights[it.key] ?: 0f) * it.value).toDouble()
        }
        val total = componentWeights.values.sum().coerceAtLeast(0.0001f).toDouble()
        overallProgress = (done / total).toFloat().coerceIn(0f, 1f)
    }

    // Start install when component mounts and each time the user retries
    LaunchedEffect(retryCount) {
        isDownloading = true
        hasError = false
        componentProgress.value = emptyMap()
        overallProgress = 0f

        // Map a ComponentId to the corresponding UI row state. Unknown ids (e.g.
        // VC_REDIST on Windows) are silently ignored — they don't appear in the UI.
        fun updateComponentState(
            id: ComponentId,
            mutator: (ComponentDownloadState) -> ComponentDownloadState,
        ) {
            when (id) {
                ComponentId.YT_DLP -> ytDlpState = mutator(ytDlpState)
                ComponentId.FFMPEG -> ffmpegState = mutator(ffmpegState)
                ComponentId.WHISPER_CPP -> whisperCppState = mutator(whisperCppState)
                ComponentId.WHISPER_MODEL_BASE,
                ComponentId.WHISPER_MODEL_SMALL,
                ComponentId.WHISPER_MODEL_MEDIUM,
                ComponentId.WHISPER_MODEL_LARGE -> whisperModelState = mutator(whisperModelState)
                ComponentId.PYTHON -> pythonState = mutator(pythonState)
                ComponentId.LIBRE_TRANSLATE -> libreTranslateState = mutator(libreTranslateState)
                ComponentId.VC_REDIST -> Unit // no dedicated UI row; proceeds silently
            }
        }

        try {
            dependencyInstaller
                .install(
                    whisperModelId = selectedWhisperModel,
                    includeLibreTranslate = true,
                )
                .catch { e ->
                    hasError = true
                    currentStatusMessage = e.message ?: "Installation failed"
                }
                .collect { event ->
                    when (event) {
                        is InstallationProgress.Starting -> {
                            currentStatusMessage = i18n["setup.downloading.from", event.componentName, ""]
                                .ifBlank { "Installing ${event.componentName}..." }
                            updateComponentState(event.componentId) {
                                it.copy(status = DownloadStatus.DOWNLOADING, progress = 0f, errorMessage = null)
                            }
                            componentProgress.value = componentProgress.value + (event.componentId to 0f)
                            recomputeOverall()
                        }

                        is InstallationProgress.Downloading -> {
                            updateComponentState(event.componentId) {
                                it.copy(
                                    status = DownloadStatus.DOWNLOADING,
                                    progress = event.percentage,
                                    downloadedSize = event.downloadedBytes,
                                    totalSize = if (event.totalBytes > 0) event.totalBytes else it.totalSize,
                                )
                            }
                            componentProgress.value = componentProgress.value + (event.componentId to event.percentage)
                            recomputeOverall()
                        }

                        is InstallationProgress.Installing -> {
                            updateComponentState(event.componentId) {
                                it.copy(
                                    status = DownloadStatus.DOWNLOADING,
                                    progress = event.percentage,
                                    message = event.message,
                                )
                            }
                            componentProgress.value = componentProgress.value + (event.componentId to event.percentage)
                            currentStatusMessage = event.message
                            recomputeOverall()
                        }

                        is InstallationProgress.ComponentCompleted -> {
                            updateComponentState(event.componentId) {
                                it.copy(status = DownloadStatus.COMPLETE, progress = 1f, errorMessage = null)
                            }
                            componentProgress.value = componentProgress.value + (event.componentId to 1f)
                            recomputeOverall()
                        }

                        is InstallationProgress.ComponentFailed -> {
                            updateComponentState(event.componentId) {
                                it.copy(status = DownloadStatus.ERROR, errorMessage = event.error)
                            }
                            hasError = true
                            currentStatusMessage = event.error
                        }

                        is InstallationProgress.Completed -> {
                            overallProgress = 1f
                            currentStatusMessage = i18n["setup.downloading.status.complete"]
                        }

                        is InstallationProgress.Failed -> {
                            hasError = true
                            currentStatusMessage = event.error
                        }

                        is InstallationProgress.Cancelling,
                        is InstallationProgress.Cancelled -> Unit
                    }
                }
        } catch (e: Exception) {
            hasError = true
            currentStatusMessage = e.message ?: "Installation failed"
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

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // Python
                DownloadComponentItem(
                    state = pythonState,
                    i18n = i18n
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // LibreTranslate
                DownloadComponentItem(
                    state = libreTranslateState,
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
                        // Reset error states back to pending; cached downloads from the
                        // previous attempt will be reused by UpdateManager.
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
                        if (pythonState.status == DownloadStatus.ERROR) {
                            pythonState = pythonState.copy(
                                status = DownloadStatus.PENDING,
                                errorMessage = null,
                                progress = 0f
                            )
                        }
                        if (libreTranslateState.status == DownloadStatus.ERROR) {
                            libreTranslateState = libreTranslateState.copy(
                                status = DownloadStatus.PENDING,
                                errorMessage = null,
                                progress = 0f
                            )
                        }
                        overallProgress = 0f
                        // Re-fire the download LaunchedEffect
                        retryCount++
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
