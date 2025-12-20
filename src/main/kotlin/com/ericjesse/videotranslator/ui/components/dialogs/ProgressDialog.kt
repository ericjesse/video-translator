package com.ericjesse.videotranslator.ui.components.dialogs

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ericjesse.videotranslator.ui.components.*
import com.ericjesse.videotranslator.ui.theme.AppColors

/**
 * State for the progress dialog.
 */
sealed class ProgressDialogState {
    data object InProgress : ProgressDialogState()
    data object Success : ProgressDialogState()
    data class Error(val message: String) : ProgressDialogState()
    data object Cancelled : ProgressDialogState()
}

/**
 * A modal progress dialog with title, progress bar, status message, and optional cancel button.
 *
 * @param title The dialog title.
 * @param statusMessage The current status message.
 * @param progress The current progress value (0.0 to 1.0), or null for indeterminate.
 * @param onCancel Optional callback when cancel is clicked.
 * @param onDismiss Callback when the dialog should be dismissed.
 * @param showCancelButton Whether to show the cancel button.
 * @param cancelEnabled Whether the cancel button is enabled.
 * @param icon Optional icon to display.
 * @param subStatus Optional secondary status message.
 * @param state The current state of the progress dialog.
 */
@Composable
fun ProgressDialog(
    title: String,
    statusMessage: String,
    progress: Float? = null,
    onCancel: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    showCancelButton: Boolean = onCancel != null,
    cancelEnabled: Boolean = true,
    icon: ImageVector? = null,
    subStatus: String? = null,
    state: ProgressDialogState = ProgressDialogState.InProgress
) {
    val canDismiss = state != ProgressDialogState.InProgress

    Dialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = canDismiss,
            dismissOnClickOutside = canDismiss,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 480.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // State-dependent icon/spinner
                AnimatedContent(
                    targetState = state,
                    transitionSpec = {
                        fadeIn() + scaleIn() togetherWith fadeOut() + scaleOut()
                    },
                    label = "stateIcon"
                ) { currentState ->
                    when (currentState) {
                        is ProgressDialogState.InProgress -> {
                            if (icon != null) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            } else {
                                AppCircularProgressBar(
                                    progress = progress,
                                    size = ProgressSize.Large,
                                    color = ProgressColor.Primary,
                                    showPercentage = progress != null
                                )
                            }
                        }
                        is ProgressDialogState.Success -> {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(AppColors.success.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Success",
                                    tint = AppColors.success,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        is ProgressDialogState.Error -> {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(AppColors.error.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = "Error",
                                    tint = AppColors.error,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        is ProgressDialogState.Cancelled -> {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(AppColors.warning.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cancel,
                                    contentDescription = "Cancelled",
                                    tint = AppColors.warning,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Title
                Text(
                    text = when (state) {
                        is ProgressDialogState.Success -> "Complete"
                        is ProgressDialogState.Error -> "Error"
                        is ProgressDialogState.Cancelled -> "Cancelled"
                        else -> title
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = when (state) {
                        is ProgressDialogState.Success -> AppColors.success
                        is ProgressDialogState.Error -> AppColors.error
                        is ProgressDialogState.Cancelled -> AppColors.warning
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Status message
                AnimatedContent(
                    targetState = when (state) {
                        is ProgressDialogState.Error -> state.message
                        else -> statusMessage
                    },
                    transitionSpec = {
                        fadeIn() togetherWith fadeOut()
                    },
                    label = "statusMessage"
                ) { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Sub-status
                if (subStatus != null && state == ProgressDialogState.InProgress) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }

                // Progress bar (only show when in progress and we have an icon)
                if (state == ProgressDialogState.InProgress && icon != null) {
                    Spacer(modifier = Modifier.height(20.dp))
                    AppLinearProgressBar(
                        progress = progress,
                        color = ProgressColor.Primary,
                        size = ProgressSize.Medium,
                        showPercentage = progress != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Buttons
                when (state) {
                    is ProgressDialogState.InProgress -> {
                        if (showCancelButton && onCancel != null) {
                            AppButton(
                                text = "Cancel",
                                onClick = onCancel,
                                style = ButtonStyle.Secondary,
                                size = ButtonSize.Medium,
                                enabled = cancelEnabled
                            )
                        }
                    }
                    else -> {
                        AppButton(
                            text = "Close",
                            onClick = onDismiss,
                            style = ButtonStyle.Primary,
                            size = ButtonSize.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * A simple indeterminate progress dialog.
 */
@Composable
fun LoadingDialog(
    title: String = "Loading",
    message: String = "Please wait...",
    onCancel: (() -> Unit)? = null
) {
    ProgressDialog(
        title = title,
        statusMessage = message,
        progress = null,
        onCancel = onCancel,
        onDismiss = { /* Cannot dismiss while loading */ },
        showCancelButton = onCancel != null
    )
}

/**
 * A download progress dialog with specific styling.
 */
@Composable
fun DownloadProgressDialog(
    title: String = "Downloading",
    fileName: String,
    progress: Float?,
    bytesDownloaded: String? = null,
    totalBytes: String? = null,
    onCancel: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    state: ProgressDialogState = ProgressDialogState.InProgress
) {
    val subStatus = if (bytesDownloaded != null && totalBytes != null) {
        "$bytesDownloaded / $totalBytes"
    } else if (bytesDownloaded != null) {
        bytesDownloaded
    } else {
        null
    }

    ProgressDialog(
        title = title,
        statusMessage = fileName,
        progress = progress,
        onCancel = onCancel,
        onDismiss = onDismiss,
        icon = Icons.Default.Download,
        subStatus = subStatus,
        state = state
    )
}

/**
 * A processing progress dialog for background tasks.
 */
@Composable
fun ProcessingDialog(
    title: String = "Processing",
    currentStep: String,
    progress: Float? = null,
    stepInfo: String? = null,
    onCancel: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    state: ProgressDialogState = ProgressDialogState.InProgress
) {
    ProgressDialog(
        title = title,
        statusMessage = currentStep,
        progress = progress,
        onCancel = onCancel,
        onDismiss = onDismiss,
        icon = Icons.Default.Settings,
        subStatus = stepInfo,
        state = state
    )
}

/**
 * A multi-step progress dialog showing current step and overall progress.
 */
@Composable
fun MultiStepProgressDialog(
    title: String,
    steps: List<String>,
    currentStepIndex: Int,
    stepProgress: Float? = null,
    onCancel: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    state: ProgressDialogState = ProgressDialogState.InProgress
) {
    val overallProgress = if (steps.isNotEmpty()) {
        val stepWeight = 1f / steps.size
        val completedStepsProgress = currentStepIndex * stepWeight
        val currentStepContribution = (stepProgress ?: 0f) * stepWeight
        completedStepsProgress + currentStepContribution
    } else {
        null
    }

    val currentStepName = steps.getOrNull(currentStepIndex) ?: "Processing..."

    Dialog(
        onDismissRequest = { if (state != ProgressDialogState.InProgress) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = state != ProgressDialogState.InProgress,
            dismissOnClickOutside = state != ProgressDialogState.InProgress,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 520.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Overall progress
                if (state == ProgressDialogState.InProgress) {
                    StepProgressBar(
                        currentStep = currentStepIndex + 1,
                        totalSteps = steps.size,
                        color = ProgressColor.Primary,
                        stepLabels = steps.map { it.take(12) }
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Current step info
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppCircularProgressBar(
                            progress = stepProgress,
                            size = ProgressSize.Small,
                            color = ProgressColor.Primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = currentStepName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Step ${currentStepIndex + 1} of ${steps.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // Show final state
                    when (state) {
                        is ProgressDialogState.Success -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Success",
                                tint = AppColors.success,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "All steps completed successfully",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.success
                            )
                        }
                        is ProgressDialogState.Error -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Error",
                                tint = AppColors.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.error,
                                textAlign = TextAlign.Center
                            )
                        }
                        is ProgressDialogState.Cancelled -> {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "Cancelled",
                                tint = AppColors.warning,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Operation cancelled",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.warning
                            )
                        }
                        else -> {}
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    when (state) {
                        is ProgressDialogState.InProgress -> {
                            if (onCancel != null) {
                                AppButton(
                                    text = "Cancel",
                                    onClick = onCancel,
                                    style = ButtonStyle.Secondary,
                                    size = ButtonSize.Medium
                                )
                            }
                        }
                        else -> {
                            AppButton(
                                text = "Close",
                                onClick = onDismiss,
                                style = ButtonStyle.Primary,
                                size = ButtonSize.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
