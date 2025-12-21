package com.ericjesse.videotranslator.ui.screens.progress.components

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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ericjesse.videotranslator.ui.components.AppButton
import com.ericjesse.videotranslator.ui.components.ButtonSize
import com.ericjesse.videotranslator.ui.components.ButtonStyle
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import com.ericjesse.videotranslator.ui.theme.AppColors

/**
 * Information about a partial file that will be deleted on cancellation.
 *
 * @property name The file name.
 * @property path The full file path.
 * @property type The type of file (video, subtitle, temp).
 */
data class PartialFile(
    val name: String,
    val path: String,
    val type: PartialFileType
)

/**
 * Types of partial files that may be created during translation.
 */
enum class PartialFileType {
    VIDEO,
    AUDIO,
    SUBTITLE,
    TEMP
}

/**
 * Cancel confirmation dialog for the progress screen.
 *
 * Shows a warning about losing progress and lists partial files that will be deleted.
 * Supports both button clicks and window close (X button) for dismissal.
 *
 * @param visible Whether the dialog is visible.
 * @param partialFiles List of partial files that will be deleted.
 * @param currentStageName Name of the current processing stage.
 * @param i18n Internationalization manager.
 * @param onContinue Callback when user chooses to continue processing.
 * @param onCancel Callback when user confirms cancellation.
 */
@Composable
fun CancelConfirmationDialog(
    visible: Boolean,
    partialFiles: List<PartialFile> = emptyList(),
    currentStageName: String? = null,
    i18n: I18nManager,
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    // Track internal visibility for exit animation
    var showDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Sync visibility with prop
    LaunchedEffect(visible) {
        if (visible) {
            showDialog = true
            pendingAction = null
        }
    }

    // Handle exit animation completion
    LaunchedEffect(showDialog, pendingAction) {
        if (!showDialog && pendingAction != null) {
            kotlinx.coroutines.delay(200) // Wait for exit animation
            pendingAction?.invoke()
            pendingAction = null
        }
    }

    if (visible || showDialog) {
        Dialog(
            onDismissRequest = {
                // Handle window close (X button) - treat as "Continue"
                showDialog = false
                pendingAction = onContinue
            },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false, // Prevent accidental dismissal
                usePlatformDefaultWidth = false
            )
        ) {
            AnimatedDialogContent(
                visible = showDialog,
                partialFiles = partialFiles,
                currentStageName = currentStageName,
                i18n = i18n,
                onContinue = {
                    showDialog = false
                    pendingAction = onContinue
                },
                onCancel = {
                    showDialog = false
                    pendingAction = onCancel
                }
            )
        }
    }
}

/**
 * Animated dialog content with scale and fade animations.
 */
@Composable
private fun AnimatedDialogContent(
    visible: Boolean,
    partialFiles: List<PartialFile>,
    currentStageName: String?,
    i18n: I18nManager,
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    // Scale animation
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.85f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "dialog_scale"
    )

    // Alpha animation
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (visible) 300 else 200,
            easing = if (visible) EaseOutCubic else LinearEasing
        ),
        label = "dialog_alpha"
    )

    Surface(
        modifier = Modifier
            .widthIn(min = 360.dp, max = 480.dp)
            .padding(16.dp)
            .scale(scale)
            .graphicsLayer { this.alpha = alpha },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Warning icon with animation
            AnimatedWarningIcon()

            Spacer(modifier = Modifier.height(16.dp))

            // Title
            Text(
                text = i18n["progress.cancel.title"],
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Main warning message
            Text(
                text = i18n["progress.cancel.description"],
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Current stage info
            currentStageName?.let { stageName ->
                Spacer(modifier = Modifier.height(8.dp))
                CurrentStageInfo(stageName = stageName, i18n = i18n)
            }

            // Partial files section
            if (partialFiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                PartialFilesSection(files = partialFiles, i18n = i18n)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            ActionButtons(
                i18n = i18n,
                onContinue = onContinue,
                onCancel = onCancel
            )
        }
    }
}

/**
 * Animated warning icon with pulse effect.
 */
@Composable
private fun AnimatedWarningIcon(
    modifier: Modifier = Modifier
) {
    var isAnimating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        isAnimating = true
    }

    // Scale animation with bounce
    val scale by animateFloatAsState(
        targetValue = if (isAnimating) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "warning_scale"
    )

    // Pulse animation for attention
    val infiniteTransition = rememberInfiniteTransition(label = "warning_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Surface(
        color = AppColors.warning.copy(alpha = pulseAlpha),
        shape = CircleShape,
        modifier = modifier
            .size(56.dp)
            .scale(scale)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = AppColors.warning,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

/**
 * Shows the current processing stage that will be interrupted.
 */
@Composable
private fun CurrentStageInfo(
    stageName: String,
    i18n: I18nManager,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.PlayCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = i18n["progress.cancel.currentStage", stageName],
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Section showing partial files that will be deleted.
 */
@Composable
private fun PartialFilesSection(
    files: List<PartialFile>,
    i18n: I18nManager,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Section header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DeleteForever,
                contentDescription = null,
                tint = AppColors.error,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = i18n["progress.cancel.filesDeleted"],
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.error,
                fontWeight = FontWeight.Medium
            )
        }

        // File list
        Surface(
            color = AppColors.error.copy(alpha = 0.08f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                files.forEach { file ->
                    PartialFileRow(file = file)
                }
            }
        }
    }
}

/**
 * Row displaying a single partial file.
 */
@Composable
private fun PartialFileRow(
    file: PartialFile,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // File type icon
        Icon(
            imageVector = when (file.type) {
                PartialFileType.VIDEO -> Icons.Default.VideoFile
                PartialFileType.AUDIO -> Icons.Default.AudioFile
                PartialFileType.SUBTITLE -> Icons.Default.Subtitles
                PartialFileType.TEMP -> Icons.Default.Description
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )

        // File name
        Text(
            text = file.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Action buttons: Continue Processing (primary) and Cancel Translation (destructive).
 */
@Composable
private fun ActionButtons(
    i18n: I18nManager,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Cancel Translation (destructive action)
        AppButton(
            text = i18n["progress.cancel.confirm"],
            onClick = onCancel,
            style = ButtonStyle.Danger,
            size = ButtonSize.Medium,
            modifier = Modifier.weight(1f)
        )

        // Continue Processing (primary action - recommended)
        AppButton(
            text = i18n["progress.cancel.continue"],
            onClick = onContinue,
            style = ButtonStyle.Primary,
            size = ButtonSize.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Simplified cancel confirmation dialog without partial files list.
 * Used when partial file information is not available.
 *
 * @param visible Whether the dialog is visible.
 * @param i18n Internationalization manager.
 * @param onContinue Callback when user chooses to continue processing.
 * @param onCancel Callback when user confirms cancellation.
 */
@Composable
fun SimpleCancelConfirmationDialog(
    visible: Boolean,
    i18n: I18nManager,
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    CancelConfirmationDialog(
        visible = visible,
        partialFiles = emptyList(),
        currentStageName = null,
        i18n = i18n,
        onContinue = onContinue,
        onCancel = onCancel
    )
}

// Custom easing functions
private val EaseOutCubic: Easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
private val EaseInOutCubic: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
