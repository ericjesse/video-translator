package com.ericjesse.videotranslator.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.ui.theme.AppColors

/**
 * Data class representing a pipeline stage with its current status.
 */
data class StageInfo(
    val name: String,
    val state: StageState,
    val progress: Float? = null,
    val statusText: String? = null,
    val details: String? = null,
    val icon: ImageVector? = null,
    val duration: String? = null,
    val errorMessage: String? = null
)

/**
 * A full stage progress row with indicator, name, progress bar, status, and expandable details.
 *
 * @param stage The stage information to display.
 * @param modifier Modifier to be applied to the row.
 * @param expandable Whether the row can be expanded to show details.
 * @param initiallyExpanded Whether the row starts expanded.
 * @param onRetry Optional callback for retry action on error state.
 */
@Composable
fun StageProgressRow(
    stage: StageInfo,
    modifier: Modifier = Modifier,
    expandable: Boolean = true,
    initiallyExpanded: Boolean = false,
    onRetry: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val hasExpandableContent = expandable && (stage.details != null || stage.errorMessage != null)
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val backgroundColor by animateColorAsState(
        targetValue = when {
            stage.state == StageState.Error -> AppColors.error.copy(alpha = 0.05f)
            isHovered && hasExpandableContent -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        label = "backgroundColor"
    )

    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "rotation"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .then(
                if (hasExpandableContent) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { expanded = !expanded }
                } else {
                    Modifier
                }
            )
            .padding(12.dp)
    ) {
        // Main row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Stage indicator
            StageIndicator(
                state = stage.state,
                size = IndicatorSize.Medium,
                showPulse = true
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Stage name and status
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (stage.icon != null) {
                        Icon(
                            imageVector = stage.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Text(
                        text = stage.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (stage.state == StageState.InProgress) FontWeight.Medium else FontWeight.Normal,
                        color = when (stage.state) {
                            StageState.Complete -> AppColors.statusComplete
                            StageState.Error -> AppColors.statusFailed
                            StageState.InProgress -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                // Status text
                if (stage.statusText != null) {
                    Text(
                        text = stage.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Duration badge
            if (stage.duration != null && stage.state == StageState.Complete) {
                DurationBadge(duration = stage.duration)
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Progress percentage or expand icon
            if (stage.state == StageState.InProgress && stage.progress != null) {
                Text(
                    text = "${(stage.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            } else if (hasExpandableContent) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotationAngle)
                )
            }
        }

        // Progress bar (for in-progress state)
        AnimatedVisibility(
            visible = stage.state == StageState.InProgress && stage.progress != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AppLinearProgressBar(
                progress = stage.progress,
                color = ProgressColor.Primary,
                size = ProgressSize.Small,
                modifier = Modifier.padding(top = 8.dp, start = 40.dp)
            )
        }

        // Indeterminate progress bar (for in-progress without percentage)
        AnimatedVisibility(
            visible = stage.state == StageState.InProgress && stage.progress == null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AppLinearProgressBar(
                progress = null,
                color = ProgressColor.Primary,
                size = ProgressSize.Small,
                modifier = Modifier.padding(top = 8.dp, start = 40.dp)
            )
        }

        // Expandable details
        AnimatedVisibility(
            visible = expanded && hasExpandableContent,
            enter = fadeIn() + expandVertically(
                animationSpec = spring(stiffness = Spring.StiffnessMedium)
            ),
            exit = fadeOut() + shrinkVertically(
                animationSpec = spring(stiffness = Spring.StiffnessMedium)
            )
        ) {
            Column(
                modifier = Modifier.padding(top = 12.dp, start = 40.dp)
            ) {
                // Error message
                if (stage.errorMessage != null) {
                    ErrorDetails(
                        message = stage.errorMessage,
                        onRetry = onRetry
                    )
                }

                // General details
                if (stage.details != null) {
                    Text(
                        text = stage.details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = if (stage.errorMessage != null) 8.dp else 0.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DurationBadge(duration: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = duration,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ErrorDetails(
    message: String,
    onRetry: (() -> Unit)?
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = AppColors.error.copy(alpha = 0.1f)
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.error
            )

            if (onRetry != null) {
                Spacer(modifier = Modifier.height(8.dp))
                AppButton(
                    text = "Retry",
                    onClick = onRetry,
                    style = ButtonStyle.Secondary,
                    size = ButtonSize.Small
                )
            }
        }
    }
}

/**
 * A list of stage progress rows for the entire pipeline.
 *
 * @param stages List of stage information.
 * @param modifier Modifier to be applied to the list.
 * @param expandedByDefault Whether stages start expanded.
 * @param onRetry Callback for retry action, receives the stage index.
 */
@Composable
fun StageProgressList(
    stages: List<StageInfo>,
    modifier: Modifier = Modifier,
    expandedByDefault: Boolean = false,
    onRetry: ((Int) -> Unit)? = null
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        stages.forEachIndexed { index, stage ->
            StageProgressRow(
                stage = stage,
                expandable = true,
                initiallyExpanded = expandedByDefault && (stage.details != null || stage.errorMessage != null),
                onRetry = onRetry?.let { { it(index) } }
            )

            // Connector line between stages
            if (index < stages.size - 1) {
                Box(
                    modifier = Modifier
                        .padding(start = 25.dp) // Align with center of indicator
                        .width(2.dp)
                        .height(8.dp)
                        .background(
                            when {
                                stage.state == StageState.Complete -> AppColors.statusComplete.copy(alpha = 0.3f)
                                stage.state == StageState.Error -> AppColors.statusFailed.copy(alpha = 0.3f)
                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            }
                        )
                )
            }
        }
    }
}

/**
 * A compact stage progress summary showing only the current stage.
 *
 * @param currentStage The currently active stage.
 * @param totalStages Total number of stages.
 * @param stageName Name of the current stage.
 * @param progress Optional progress percentage.
 * @param statusText Optional status message.
 * @param modifier Modifier to be applied.
 */
@Composable
fun CompactStageProgress(
    currentStage: Int,
    totalStages: Int,
    stageName: String,
    progress: Float? = null,
    statusText: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StageIndicator(
                    state = StageState.InProgress,
                    size = IndicatorSize.Small
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = stageName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                text = "Step $currentStage of $totalStages",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Overall progress bar
        AppLinearProgressBar(
            progress = progress ?: ((currentStage - 1).toFloat() / totalStages),
            color = ProgressColor.Primary,
            size = ProgressSize.Medium,
            showPercentage = progress != null
        )
    }
}

/**
 * A stage card with full details for a single stage.
 *
 * @param stage The stage information.
 * @param modifier Modifier to be applied.
 * @param onRetry Optional retry callback.
 */
@Composable
fun StageCard(
    stage: StageInfo,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    AppCard(
        modifier = modifier,
        elevation = CardElevation.Low,
        contentPadding = PaddingValues(0.dp)
    ) {
        StageProgressRow(
            stage = stage,
            expandable = true,
            initiallyExpanded = stage.state == StageState.Error,
            onRetry = onRetry
        )
    }
}

/**
 * An animated transition between stages.
 */
@Composable
fun AnimatedStageTransition(
    fromStage: String,
    toStage: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "transition")

    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = fromStage,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.statusComplete
        )

        // Animated arrow
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        Text(
            text = toStage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}
