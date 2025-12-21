package com.ericjesse.videotranslator.ui.screens.progress.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ericjesse.videotranslator.domain.pipeline.PipelineError
import com.ericjesse.videotranslator.domain.pipeline.PipelineStageName
import com.ericjesse.videotranslator.ui.components.AppButton
import com.ericjesse.videotranslator.ui.components.AppCard
import com.ericjesse.videotranslator.ui.components.ButtonSize
import com.ericjesse.videotranslator.ui.components.ButtonStyle
import com.ericjesse.videotranslator.ui.components.CardElevation as AppCardElevation
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import com.ericjesse.videotranslator.ui.screens.progress.OutputFile
import com.ericjesse.videotranslator.ui.screens.progress.OutputFileType
import com.ericjesse.videotranslator.ui.theme.AppColors

// ========== Completion Card Container ==========

/**
 * A container card that displays either success or error state with smooth transitions.
 *
 * @param isSuccess Whether the completion was successful.
 * @param isVisible Whether the card should be visible (for transition animations).
 * @param modifier Modifier for the card.
 * @param content The content to display inside the card.
 */
@Composable
fun CompletionCard(
    isSuccess: Boolean,
    isVisible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(
            animationSpec = tween(durationMillis = 400, easing = EaseOutCubic)
        ) + slideInVertically(
            animationSpec = tween(durationMillis = 400, easing = EaseOutCubic),
            initialOffsetY = { it / 4 }
        ),
        exit = fadeOut(
            animationSpec = tween(durationMillis = 200)
        ),
        modifier = modifier
    ) {
        AppCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = AppCardElevation.Low
        ) {
            content()
        }
    }
}

// ========== Success State ==========

/**
 * Success completion card showing animated checkmark, output files, and actions.
 *
 * @param outputFiles List of generated output files.
 * @param outputDirectory The directory where files are saved.
 * @param processingTime Total processing time in milliseconds.
 * @param i18n Internationalization manager.
 * @param onOpenFolder Callback when "Open in Folder" is clicked.
 * @param modifier Modifier for the card.
 */
@Composable
fun SuccessCompletionCard(
    outputFiles: List<OutputFile>,
    outputDirectory: String,
    processingTime: Long,
    i18n: I18nManager,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isVisible by remember { mutableStateOf(false) }

    // Trigger visibility animation on first composition
    LaunchedEffect(Unit) {
        isVisible = true
    }

    CompletionCard(
        isSuccess = true,
        isVisible = isVisible,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Animated success header
            SuccessHeader(
                title = i18n["progress.complete.message"],
                subtitle = i18n["progress.complete.time", formatProcessingTime(processingTime)]
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // Output files section
            OutputFilesSection(
                outputFiles = outputFiles,
                i18n = i18n
            )

            // Output location
            OutputLocationRow(
                directory = outputDirectory,
                onClick = onOpenFolder
            )
        }
    }
}

/**
 * Success header with animated checkmark icon.
 */
@Composable
private fun SuccessHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Animated checkmark icon
            AnimatedCheckmark(size = 64.dp)

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Animated checkmark with scale and rotation effects.
 */
@Composable
fun AnimatedCheckmark(
    size: androidx.compose.ui.unit.Dp = 64.dp,
    modifier: Modifier = Modifier
) {
    var isAnimating by remember { mutableStateOf(false) }

    // Start animation after a short delay
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        isAnimating = true
    }

    // Scale animation (pop-in effect)
    val scale by animateFloatAsState(
        targetValue = if (isAnimating) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "checkmark_scale"
    )

    // Rotation animation (subtle spin)
    val rotation by animateFloatAsState(
        targetValue = if (isAnimating) 0f else -30f,
        animationSpec = tween(
            durationMillis = 400,
            easing = EaseOutBack
        ),
        label = "checkmark_rotation"
    )

    // Alpha animation
    val alpha by animateFloatAsState(
        targetValue = if (isAnimating) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "checkmark_alpha"
    )

    Surface(
        color = AppColors.success.copy(alpha = 0.15f),
        shape = CircleShape,
        modifier = modifier
            .size(size)
            .scale(scale)
            .graphicsLayer {
                rotationZ = rotation
                this.alpha = alpha
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = AppColors.success,
                modifier = Modifier.size(size * 0.6f)
            )
        }
    }
}

/**
 * Section displaying output files with icons and sizes.
 */
@Composable
private fun OutputFilesSection(
    outputFiles: List<OutputFile>,
    i18n: I18nManager,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = i18n["progress.complete.outputFiles"],
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )

        outputFiles.forEachIndexed { index, file ->
            // Staggered animation for each file row
            var isVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(200L + (index * 100L))
                isVisible = true
            }

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(300)) + slideInHorizontally(
                    animationSpec = tween(300),
                    initialOffsetX = { -it / 4 }
                )
            ) {
                OutputFileRow(file = file)
            }
        }
    }
}

/**
 * Row displaying a single output file with icon and size.
 */
@Composable
fun OutputFileRow(
    file: OutputFile,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
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

/**
 * Row displaying the output location with click-to-open functionality.
 */
@Composable
private fun OutputLocationRow(
    directory: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
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
            text = directory,
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

// ========== Error State ==========

/**
 * Error completion card showing error details, stage, and suggestions.
 *
 * @param failedStage The pipeline stage where the error occurred.
 * @param error The pipeline error details.
 * @param i18n Internationalization manager.
 * @param modifier Modifier for the card.
 */
@Composable
fun ErrorCompletionCard(
    failedStage: PipelineStageName,
    error: PipelineError?,
    i18n: I18nManager,
    modifier: Modifier = Modifier
) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    CompletionCard(
        isSuccess = false,
        isVisible = isVisible,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Error header with animated icon
            ErrorHeader(
                title = i18n["progress.error.message"],
                subtitle = i18n["progress.error.stage", getStageDisplayName(failedStage, i18n)]
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // Error details section
            ErrorDetailsSection(
                error = error,
                i18n = i18n
            )

            // Suggestions section
            error?.suggestion?.let { suggestion ->
                SuggestionsSection(
                    suggestions = listOf(suggestion),
                    i18n = i18n
                )
            }
        }
    }
}

/**
 * Error header with animated X icon.
 */
@Composable
private fun ErrorHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Animated error icon
            AnimatedErrorIcon(size = 64.dp)

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Animated error X icon with shake effect.
 */
@Composable
fun AnimatedErrorIcon(
    size: androidx.compose.ui.unit.Dp = 64.dp,
    modifier: Modifier = Modifier
) {
    var isAnimating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        isAnimating = true
    }

    // Scale animation
    val scale by animateFloatAsState(
        targetValue = if (isAnimating) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "error_scale"
    )

    // Shake animation using infinite transition
    val infiniteTransition = rememberInfiniteTransition(label = "error_shake")
    val shakeOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 600
                0f at 0
                -4f at 100
                4f at 200
                -2f at 300
                2f at 400
                0f at 500
            },
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(500) // Start shake after pop-in
        ),
        label = "shake"
    )

    // Alpha animation
    val alpha by animateFloatAsState(
        targetValue = if (isAnimating) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "error_alpha"
    )

    Surface(
        color = AppColors.error.copy(alpha = 0.15f),
        shape = CircleShape,
        modifier = modifier
            .size(size)
            .scale(scale)
            .graphicsLayer {
                translationX = if (isAnimating) shakeOffset else 0f
                this.alpha = alpha
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = AppColors.error,
                modifier = Modifier.size(size * 0.6f)
            )
        }
    }
}

/**
 * Section displaying error details.
 */
@Composable
private fun ErrorDetailsSection(
    error: PipelineError?,
    i18n: I18nManager,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
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
                    text = error?.message ?: i18n["progress.error.unknown"],
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.error
                )

                // Show error code if available
                error?.code?.let { code ->
                    Text(
                        text = "Error code: ${code.name}",
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
}

/**
 * Section displaying possible solutions.
 */
@Composable
private fun SuggestionsSection(
    suggestions: List<String>,
    i18n: I18nManager,
    modifier: Modifier = Modifier
) {
    if (suggestions.isEmpty()) return

    Column(
        modifier = modifier,
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
                    text = "\u2022", // Bullet point
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

// ========== Action Buttons ==========

/**
 * Action buttons for success state.
 */
@Composable
fun SuccessActionButtons(
    i18n: I18nManager,
    onOpenFolder: () -> Unit,
    onTranslateAnother: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AppButton(
            text = i18n["progress.complete.openFolder"],
            onClick = onOpenFolder,
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

/**
 * Action buttons for error state.
 */
@Composable
fun ErrorActionButtons(
    i18n: I18nManager,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
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

// ========== Helper Functions ==========

/**
 * Gets the display name for a pipeline stage.
 */
private fun getStageDisplayName(stage: PipelineStageName, i18n: I18nManager): String {
    return when (stage) {
        PipelineStageName.DOWNLOAD -> i18n["progress.stage.downloading"]
        PipelineStageName.CAPTION_CHECK -> i18n["progress.stage.checkingCaptions"]
        PipelineStageName.TRANSCRIPTION -> i18n["progress.stage.transcribing"]
        PipelineStageName.TRANSLATION -> i18n["progress.stage.translating"]
        PipelineStageName.RENDERING -> i18n["progress.stage.rendering"]
    }
}

/**
 * Formats processing time to a human-readable string.
 */
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

/**
 * Formats file size to a human-readable string.
 */
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

// Custom easing functions
private val EaseOutCubic: Easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
private val EaseOutBack: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
