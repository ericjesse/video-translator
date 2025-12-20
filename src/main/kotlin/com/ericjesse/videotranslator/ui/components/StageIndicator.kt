package com.ericjesse.videotranslator.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.ui.theme.AppColors

/**
 * Represents the state of a pipeline stage.
 */
enum class StageState {
    Pending,
    InProgress,
    Complete,
    Error,
    Skipped
}

/**
 * Size variants for the stage indicator.
 */
enum class IndicatorSize {
    Small,
    Medium,
    Large
}

/**
 * A visual indicator for pipeline stage status with smooth animations.
 *
 * @param state The current state of the stage.
 * @param modifier Modifier to be applied to the indicator.
 * @param size The size variant of the indicator.
 * @param showPulse Whether to show pulse animation for in-progress state.
 */
@Composable
fun StageIndicator(
    state: StageState,
    modifier: Modifier = Modifier,
    size: IndicatorSize = IndicatorSize.Medium,
    showPulse: Boolean = true
) {
    val (indicatorSize, iconSize, strokeWidth) = when (size) {
        IndicatorSize.Small -> Triple(20.dp, 12.dp, 2.dp)
        IndicatorSize.Medium -> Triple(28.dp, 16.dp, 3.dp)
        IndicatorSize.Large -> Triple(36.dp, 20.dp, 4.dp)
    }

    val backgroundColor by animateColorAsState(
        targetValue = when (state) {
            StageState.Pending -> AppColors.statusPending.copy(alpha = 0.15f)
            StageState.InProgress -> AppColors.statusInProgress.copy(alpha = 0.15f)
            StageState.Complete -> AppColors.statusComplete.copy(alpha = 0.15f)
            StageState.Error -> AppColors.statusFailed.copy(alpha = 0.15f)
            StageState.Skipped -> AppColors.statusPending.copy(alpha = 0.1f)
        },
        animationSpec = tween(300),
        label = "backgroundColor"
    )

    val foregroundColor by animateColorAsState(
        targetValue = when (state) {
            StageState.Pending -> AppColors.statusPending
            StageState.InProgress -> AppColors.statusInProgress
            StageState.Complete -> AppColors.statusComplete
            StageState.Error -> AppColors.statusFailed
            StageState.Skipped -> AppColors.statusPending.copy(alpha = 0.5f)
        },
        animationSpec = tween(300),
        label = "foregroundColor"
    )

    Box(
        modifier = modifier.size(indicatorSize),
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            StageState.Pending -> {
                PendingIndicator(
                    size = indicatorSize,
                    color = foregroundColor,
                    backgroundColor = backgroundColor
                )
            }
            StageState.InProgress -> {
                InProgressIndicator(
                    size = indicatorSize,
                    color = foregroundColor,
                    backgroundColor = backgroundColor,
                    strokeWidth = strokeWidth,
                    showPulse = showPulse
                )
            }
            StageState.Complete -> {
                CompleteIndicator(
                    size = indicatorSize,
                    iconSize = iconSize,
                    color = foregroundColor,
                    backgroundColor = backgroundColor
                )
            }
            StageState.Error -> {
                ErrorIndicator(
                    size = indicatorSize,
                    iconSize = iconSize,
                    color = foregroundColor,
                    backgroundColor = backgroundColor
                )
            }
            StageState.Skipped -> {
                SkippedIndicator(
                    size = indicatorSize,
                    color = foregroundColor,
                    backgroundColor = backgroundColor
                )
            }
        }
    }
}

@Composable
private fun PendingIndicator(
    size: Dp,
    color: Color,
    backgroundColor: Color
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.35f)
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
private fun InProgressIndicator(
    size: Dp,
    color: Color,
    backgroundColor: Color,
    strokeWidth: Dp,
    showPulse: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "inProgress")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .size(size)
            .then(if (showPulse) Modifier.scale(pulseScale) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        // Background circle
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(backgroundColor)
        )

        // Spinning arc
        Canvas(
            modifier = Modifier
                .size(size)
                .rotate(rotation)
        ) {
            val strokePx = strokeWidth.toPx()
            val arcDimension = this.size.width - strokePx

            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(strokePx / 2, strokePx / 2),
                size = Size(arcDimension, arcDimension),
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun CompleteIndicator(
    size: Dp,
    iconSize: Dp,
    color: Color,
    backgroundColor: Color
) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = "Complete",
            tint = color,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun ErrorIndicator(
    size: Dp,
    iconSize: Dp,
    color: Color,
    backgroundColor: Color
) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Error",
            tint = color,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun SkippedIndicator(
    size: Dp,
    color: Color,
    backgroundColor: Color
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        // Dash line
        Box(
            modifier = Modifier
                .width(size * 0.4f)
                .height(2.dp)
                .background(color)
        )
    }
}

/**
 * A stage indicator with label and optional subtitle.
 *
 * @param state The current state of the stage.
 * @param label The main label text.
 * @param modifier Modifier to be applied to the component.
 * @param subtitle Optional subtitle text.
 * @param size The size variant of the indicator.
 * @param showPulse Whether to show pulse animation for in-progress state.
 */
@Composable
fun LabeledStageIndicator(
    state: StageState,
    label: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    size: IndicatorSize = IndicatorSize.Medium,
    showPulse: Boolean = true
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StageIndicator(
            state = state,
            size = size,
            showPulse = showPulse
        )

        Column {
            Text(
                text = label,
                style = when (size) {
                    IndicatorSize.Small -> MaterialTheme.typography.labelMedium
                    IndicatorSize.Medium -> MaterialTheme.typography.bodyMedium
                    IndicatorSize.Large -> MaterialTheme.typography.titleSmall
                },
                color = when (state) {
                    StageState.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
                    StageState.InProgress -> MaterialTheme.colorScheme.onSurface
                    StageState.Complete -> AppColors.statusComplete
                    StageState.Error -> AppColors.statusFailed
                    StageState.Skipped -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
                fontWeight = if (state == StageState.InProgress) FontWeight.Medium else FontWeight.Normal
            )

            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = when (size) {
                        IndicatorSize.Small -> MaterialTheme.typography.labelSmall
                        IndicatorSize.Medium -> MaterialTheme.typography.bodySmall
                        IndicatorSize.Large -> MaterialTheme.typography.bodyMedium
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * A horizontal sequence of stage indicators connected by lines.
 *
 * @param stages List of stages with their states.
 * @param modifier Modifier to be applied to the component.
 * @param size The size variant of the indicators.
 */
@Composable
fun StageIndicatorSequence(
    stages: List<Pair<String, StageState>>,
    modifier: Modifier = Modifier,
    size: IndicatorSize = IndicatorSize.Small
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        stages.forEachIndexed { index, (label, state) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                StageIndicator(
                    state = state,
                    size = size,
                    showPulse = false
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (state) {
                        StageState.InProgress -> MaterialTheme.colorScheme.primary
                        StageState.Complete -> AppColors.statusComplete
                        StageState.Error -> AppColors.statusFailed
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            // Connector line
            if (index < stages.size - 1) {
                val nextState = stages[index + 1].second
                val lineColor = when {
                    state == StageState.Complete && nextState != StageState.Pending -> AppColors.statusComplete
                    state == StageState.Error -> AppColors.statusFailed
                    else -> MaterialTheme.colorScheme.outline
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .padding(horizontal = 4.dp)
                        .background(lineColor)
                )
            }
        }
    }
}

/**
 * A vertical list of stage indicators connected by lines.
 *
 * @param stages List of stages with their states.
 * @param modifier Modifier to be applied to the component.
 * @param size The size variant of the indicators.
 * @param showSubtitles Whether to show subtitles for each stage.
 * @param subtitles Optional list of subtitles for each stage.
 */
@Composable
fun VerticalStageIndicatorList(
    stages: List<Pair<String, StageState>>,
    modifier: Modifier = Modifier,
    size: IndicatorSize = IndicatorSize.Medium,
    showSubtitles: Boolean = false,
    subtitles: List<String?>? = null
) {
    val indicatorSize = when (size) {
        IndicatorSize.Small -> 20.dp
        IndicatorSize.Medium -> 28.dp
        IndicatorSize.Large -> 36.dp
    }

    Column(modifier = modifier) {
        stages.forEachIndexed { index, (label, state) ->
            Row(
                verticalAlignment = Alignment.Top
            ) {
                // Indicator and connector line column
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    StageIndicator(
                        state = state,
                        size = size,
                        showPulse = true
                    )

                    // Connector line
                    if (index < stages.size - 1) {
                        val nextState = stages[index + 1].second
                        val lineColor = when {
                            state == StageState.Complete -> AppColors.statusComplete.copy(alpha = 0.5f)
                            state == StageState.Error -> AppColors.statusFailed.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        }

                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(24.dp)
                                .background(lineColor)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Label column
                Column(
                    modifier = Modifier.padding(top = (indicatorSize - 20.dp) / 2)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (state) {
                            StageState.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
                            StageState.InProgress -> MaterialTheme.colorScheme.onSurface
                            StageState.Complete -> AppColors.statusComplete
                            StageState.Error -> AppColors.statusFailed
                            StageState.Skipped -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        fontWeight = if (state == StageState.InProgress) FontWeight.Medium else FontWeight.Normal
                    )

                    if (showSubtitles && subtitles != null && index < subtitles.size) {
                        subtitles[index]?.let { subtitle ->
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (index < stages.size - 1) {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}
