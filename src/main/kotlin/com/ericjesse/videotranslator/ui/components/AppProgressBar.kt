package com.ericjesse.videotranslator.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.ui.theme.AppColors

/**
 * Progress bar size variants.
 */
enum class ProgressSize {
    Small,
    Medium,
    Large
}

/**
 * Progress bar color variants.
 */
enum class ProgressColor {
    Primary,
    Success,
    Warning,
    Error,
    Info
}

/**
 * A styled linear progress bar following Material 3 guidelines.
 *
 * @param progress The current progress value (0.0 to 1.0), or null for indeterminate.
 * @param modifier Modifier to be applied to the progress bar.
 * @param color The color variant of the progress bar.
 * @param size The size variant of the progress bar.
 * @param showPercentage Whether to show the percentage label.
 * @param label Optional label displayed above the progress bar.
 */
@Composable
fun AppLinearProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    color: ProgressColor = ProgressColor.Primary,
    size: ProgressSize = ProgressSize.Medium,
    showPercentage: Boolean = false,
    label: String? = null
) {
    val progressColor = getProgressColor(color)
    val trackColor = progressColor.copy(alpha = 0.2f)

    val height = when (size) {
        ProgressSize.Small -> 4.dp
        ProgressSize.Medium -> 8.dp
        ProgressSize.Large -> 12.dp
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progress?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "progress"
    )

    Column(modifier = modifier) {
        // Label and percentage row
        if (label != null || (showPercentage && progress != null)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showPercentage && progress != null) {
                    Text(
                        text = "${(animatedProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = progressColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(trackColor)
        ) {
            if (progress == null) {
                // Indeterminate progress
                IndeterminateLinearProgress(
                    color = progressColor,
                    height = height
                )
            } else {
                // Determinate progress
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedProgress)
                        .clip(RoundedCornerShape(height / 2))
                        .background(progressColor)
                )
            }
        }
    }
}

@Composable
private fun IndeterminateLinearProgress(
    color: Color,
    height: Dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "indeterminate")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "offset"
    )

    val width by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "width"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(height / 2))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(width)
                .offset(x = (offset * 300).dp - 100.dp)
                .clip(RoundedCornerShape(height / 2))
                .background(color)
        )
    }
}

/**
 * A styled circular progress indicator following Material 3 guidelines.
 *
 * @param progress The current progress value (0.0 to 1.0), or null for indeterminate.
 * @param modifier Modifier to be applied to the progress indicator.
 * @param color The color variant of the progress indicator.
 * @param size The size variant of the progress indicator.
 * @param showPercentage Whether to show the percentage in the center.
 * @param strokeWidth The width of the progress stroke.
 */
@Composable
fun AppCircularProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    color: ProgressColor = ProgressColor.Primary,
    size: ProgressSize = ProgressSize.Medium,
    showPercentage: Boolean = false,
    strokeWidth: Dp? = null
) {
    val progressColor = getProgressColor(color)
    val trackColor = progressColor.copy(alpha = 0.2f)

    val diameter = when (size) {
        ProgressSize.Small -> 32.dp
        ProgressSize.Medium -> 48.dp
        ProgressSize.Large -> 72.dp
    }

    val defaultStrokeWidth = when (size) {
        ProgressSize.Small -> 3.dp
        ProgressSize.Medium -> 4.dp
        ProgressSize.Large -> 6.dp
    }

    val actualStrokeWidth = strokeWidth ?: defaultStrokeWidth

    val animatedProgress by animateFloatAsState(
        targetValue = progress?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "progress"
    )

    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center
    ) {
        if (progress == null) {
            // Indeterminate circular progress
            IndeterminateCircularProgress(
                color = progressColor,
                diameter = diameter,
                strokeWidth = actualStrokeWidth
            )
        } else {
            // Determinate circular progress
            Canvas(modifier = Modifier.size(diameter)) {
                val strokePx = actualStrokeWidth.toPx()
                val canvasSize = this.size
                val arcDimension = minOf(canvasSize.width, canvasSize.height) - strokePx

                // Track
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(strokePx / 2, strokePx / 2),
                    size = Size(arcDimension, arcDimension),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )

                // Progress
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress,
                    useCenter = false,
                    topLeft = Offset(strokePx / 2, strokePx / 2),
                    size = Size(arcDimension, arcDimension),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }
        }

        // Percentage label in center
        if (showPercentage && progress != null) {
            Text(
                text = "${(animatedProgress * 100).toInt()}%",
                style = when (size) {
                    ProgressSize.Small -> MaterialTheme.typography.labelSmall
                    ProgressSize.Medium -> MaterialTheme.typography.labelMedium
                    ProgressSize.Large -> MaterialTheme.typography.titleMedium
                },
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun IndeterminateCircularProgress(
    color: Color,
    diameter: Dp,
    strokeWidth: Dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "circular")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 20f,
        targetValue = 280f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sweep"
    )

    Canvas(modifier = Modifier.size(diameter)) {
        val strokePx = strokeWidth.toPx()
        val canvasSize = this.size
        val arcDimension = minOf(canvasSize.width, canvasSize.height) - strokePx

        drawArc(
            color = color,
            startAngle = rotation - 90f,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(strokePx / 2, strokePx / 2),
            size = Size(arcDimension, arcDimension),
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )
    }
}

/**
 * A labeled progress indicator with detailed status information.
 *
 * @param progress The current progress value (0.0 to 1.0), or null for indeterminate.
 * @param label The main label text.
 * @param modifier Modifier to be applied to the component.
 * @param sublabel Optional secondary label (e.g., "2 of 5 items").
 * @param color The color variant.
 * @param showPercentage Whether to show the percentage.
 */
@Composable
fun LabeledProgressBar(
    progress: Float?,
    label: String,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
    color: ProgressColor = ProgressColor.Primary,
    showPercentage: Boolean = true
) {
    val progressColor = getProgressColor(color)

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (sublabel != null) {
                    Text(
                        text = sublabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (showPercentage && progress != null) {
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = progressColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        AppLinearProgressBar(
            progress = progress,
            color = color,
            size = ProgressSize.Medium
        )
    }
}

/**
 * A progress indicator with steps/stages.
 *
 * @param currentStep The current step (1-indexed).
 * @param totalSteps The total number of steps.
 * @param modifier Modifier to be applied to the component.
 * @param color The color variant.
 * @param stepLabels Optional labels for each step.
 */
@Composable
fun StepProgressBar(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier,
    color: ProgressColor = ProgressColor.Primary,
    stepLabels: List<String>? = null
) {
    val progressColor = getProgressColor(color)
    val inactiveColor = MaterialTheme.colorScheme.outline

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (step in 1..totalSteps) {
                val isCompleted = step < currentStep
                val isCurrent = step == currentStep
                val stepColor = when {
                    isCompleted -> progressColor
                    isCurrent -> progressColor
                    else -> inactiveColor
                }

                // Step circle
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isCompleted || isCurrent) stepColor
                            else stepColor.copy(alpha = 0.2f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = step.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isCompleted || isCurrent) Color.White
                               else stepColor,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Connector line
                if (step < totalSteps) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .padding(horizontal = 4.dp)
                            .background(
                                if (step < currentStep) progressColor
                                else inactiveColor.copy(alpha = 0.3f)
                            )
                    )
                }
            }
        }

        // Step labels
        if (stepLabels != null && stepLabels.size == totalSteps) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                stepLabels.forEachIndexed { index, label ->
                    val step = index + 1
                    val isCompleted = step < currentStep
                    val isCurrent = step == currentStep

                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            isCompleted -> progressColor
                            isCurrent -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun getProgressColor(color: ProgressColor): Color {
    return when (color) {
        ProgressColor.Primary -> MaterialTheme.colorScheme.primary
        ProgressColor.Success -> AppColors.success
        ProgressColor.Warning -> AppColors.warning
        ProgressColor.Error -> AppColors.error
        ProgressColor.Info -> AppColors.info
    }
}
