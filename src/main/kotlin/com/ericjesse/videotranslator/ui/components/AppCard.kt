package com.ericjesse.videotranslator.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Card elevation variants.
 */
enum class CardElevation {
    None,
    Low,
    Medium,
    High
}

/**
 * A styled card component following Material 3 guidelines.
 *
 * @param modifier Modifier to be applied to the card.
 * @param onClick Optional click handler. If null, the card is not clickable.
 * @param enabled Whether the card is enabled (only applies when onClick is set).
 * @param elevation The elevation level of the card.
 * @param shape The shape of the card.
 * @param showBorder Whether to show a border around the card.
 * @param contentPadding Padding inside the card.
 * @param content The content of the card.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    elevation: CardElevation = CardElevation.Low,
    shape: Shape = RoundedCornerShape(12.dp),
    showBorder: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val baseElevation = when (elevation) {
        CardElevation.None -> 0.dp
        CardElevation.Low -> 1.dp
        CardElevation.Medium -> 4.dp
        CardElevation.High -> 8.dp
    }

    val animatedElevation by animateDpAsState(
        targetValue = when {
            onClick == null -> baseElevation
            !enabled -> baseElevation
            isPressed -> baseElevation
            isHovered -> baseElevation + 2.dp
            else -> baseElevation
        },
        label = "elevation"
    )

    val backgroundColor by animateColorAsState(
        targetValue = when {
            onClick == null -> MaterialTheme.colorScheme.surface
            !enabled -> MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            isPressed -> MaterialTheme.colorScheme.surfaceVariant
            isHovered -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.surface
        },
        label = "backgroundColor"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            onClick == null -> MaterialTheme.colorScheme.outline
            !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            isPressed -> MaterialTheme.colorScheme.primary
            isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.outline
        },
        label = "borderColor"
    )

    Surface(
        modifier = modifier
            .shadow(animatedElevation, shape)
            .clip(shape)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            ),
        shape = shape,
        color = backgroundColor,
        border = if (showBorder) BorderStroke(1.dp, borderColor) else null
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

/**
 * A card variant with a header section.
 *
 * @param title The card title.
 * @param modifier Modifier to be applied to the card.
 * @param subtitle Optional subtitle text.
 * @param icon Optional icon displayed before the title.
 * @param actions Optional trailing actions (e.g., icon buttons).
 * @param onClick Optional click handler.
 * @param enabled Whether the card is enabled.
 * @param elevation The elevation level.
 * @param content The card content below the header.
 */
@Composable
fun AppCardWithHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    elevation: CardElevation = CardElevation.Low,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        elevation = elevation,
        contentPadding = PaddingValues(0.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (actions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions
                )
            }
        }

        // Divider
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 1.dp
        )

        // Content
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

/**
 * A selectable card that can be toggled on/off.
 *
 * @param selected Whether the card is currently selected.
 * @param onSelectedChange Callback when selection changes.
 * @param modifier Modifier to be applied to the card.
 * @param enabled Whether the card is enabled.
 * @param content The card content.
 */
@Composable
fun SelectableCard(
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            selected -> MaterialTheme.colorScheme.primary
            isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.outline
        },
        label = "borderColor"
    )

    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            isHovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.surface
        },
        label = "backgroundColor"
    )

    val borderWidth by animateDpAsState(
        targetValue = if (selected) 2.dp else 1.dp,
        label = "borderWidth"
    )

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = { onSelectedChange(!selected) }
            ),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        border = BorderStroke(borderWidth, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

/**
 * A compact card variant for list items.
 *
 * @param modifier Modifier to be applied to the card.
 * @param onClick Optional click handler.
 * @param enabled Whether the card is enabled.
 * @param leading Optional leading content (e.g., icon, avatar).
 * @param trailing Optional trailing content (e.g., chevron, badge).
 * @param content The main content of the card.
 */
@Composable
fun CompactCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        elevation = CardElevation.None,
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(
                modifier = Modifier.weight(1f),
                content = content
            )

            if (trailing != null) {
                Spacer(modifier = Modifier.width(12.dp))
                trailing()
            }
        }
    }
}
