package com.ericjesse.videotranslator.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.ui.theme.AppColors

/**
 * Button size variants.
 */
enum class ButtonSize {
    Small,
    Medium,
    Large
}

/**
 * Button style variants.
 */
enum class ButtonStyle {
    Primary,
    Secondary,
    Text,
    Danger
}

/**
 * A styled primary button following Material 3 guidelines.
 *
 * @param text The button text.
 * @param onClick Callback when the button is clicked.
 * @param modifier Modifier to be applied to the button.
 * @param style The button style variant.
 * @param size The button size variant.
 * @param enabled Whether the button is enabled.
 * @param loading Whether to show a loading spinner.
 * @param leadingIcon Optional icon displayed before the text.
 * @param trailingIcon Optional icon displayed after the text.
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    size: ButtonSize = ButtonSize.Medium,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val (height, horizontalPadding, iconSize, textStyle) = when (size) {
        ButtonSize.Small -> Quadruple(32.dp, 12.dp, 16.dp, MaterialTheme.typography.labelMedium)
        ButtonSize.Medium -> Quadruple(40.dp, 16.dp, 18.dp, MaterialTheme.typography.labelLarge)
        ButtonSize.Large -> Quadruple(48.dp, 24.dp, 20.dp, MaterialTheme.typography.titleSmall)
    }

    val shape = RoundedCornerShape(8.dp)
    val isClickable = enabled && !loading

    when (style) {
        ButtonStyle.Primary -> {
            val containerColor by animateColorAsState(
                targetValue = when {
                    !enabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    isPressed -> MaterialTheme.colorScheme.primaryContainer
                    isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    else -> MaterialTheme.colorScheme.primary
                },
                label = "containerColor"
            )

            Button(
                onClick = onClick,
                modifier = modifier.height(height),
                enabled = isClickable,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = containerColor,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                ),
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                interactionSource = interactionSource
            ) {
                ButtonContent(
                    text = text,
                    loading = loading,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    iconSize = iconSize,
                    textStyle = textStyle,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        ButtonStyle.Secondary -> {
            val borderColor by animateColorAsState(
                targetValue = when {
                    !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    isPressed -> MaterialTheme.colorScheme.primary
                    isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    else -> MaterialTheme.colorScheme.outline
                },
                label = "borderColor"
            )

            val contentColor by animateColorAsState(
                targetValue = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    isPressed -> MaterialTheme.colorScheme.primary
                    isHovered -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                label = "contentColor"
            )

            OutlinedButton(
                onClick = onClick,
                modifier = modifier.height(height),
                enabled = isClickable,
                shape = shape,
                border = BorderStroke(1.dp, borderColor),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = contentColor,
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                ),
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                interactionSource = interactionSource
            ) {
                ButtonContent(
                    text = text,
                    loading = loading,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    iconSize = iconSize,
                    textStyle = textStyle,
                    contentColor = contentColor
                )
            }
        }

        ButtonStyle.Text -> {
            val contentColor by animateColorAsState(
                targetValue = when {
                    !enabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    isPressed -> MaterialTheme.colorScheme.primaryContainer
                    isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    else -> MaterialTheme.colorScheme.primary
                },
                label = "contentColor"
            )

            TextButton(
                onClick = onClick,
                modifier = modifier.height(height),
                enabled = isClickable,
                shape = shape,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = contentColor,
                    disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                ),
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                interactionSource = interactionSource
            ) {
                ButtonContent(
                    text = text,
                    loading = loading,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    iconSize = iconSize,
                    textStyle = textStyle,
                    contentColor = contentColor
                )
            }
        }

        ButtonStyle.Danger -> {
            val containerColor by animateColorAsState(
                targetValue = when {
                    !enabled -> AppColors.error.copy(alpha = 0.5f)
                    isPressed -> AppColors.error.copy(alpha = 0.8f)
                    isHovered -> AppColors.error.copy(alpha = 0.9f)
                    else -> AppColors.error
                },
                label = "containerColor"
            )

            Button(
                onClick = onClick,
                modifier = modifier.height(height),
                enabled = isClickable,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = containerColor,
                    contentColor = Color.White,
                    disabledContainerColor = AppColors.error.copy(alpha = 0.5f),
                    disabledContentColor = Color.White.copy(alpha = 0.7f)
                ),
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                interactionSource = interactionSource
            ) {
                ButtonContent(
                    text = text,
                    loading = loading,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    iconSize = iconSize,
                    textStyle = textStyle,
                    contentColor = Color.White
                )
            }
        }
    }
}

@Composable
private fun ButtonContent(
    text: String,
    loading: Boolean,
    leadingIcon: ImageVector?,
    trailingIcon: ImageVector?,
    iconSize: Dp,
    textStyle: androidx.compose.ui.text.TextStyle,
    contentColor: Color
) {
    if (loading) {
        LoadingSpinner(
            size = iconSize,
            color = contentColor
        )
        Spacer(modifier = Modifier.width(8.dp))
    } else if (leadingIcon != null) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = contentColor
        )
        Spacer(modifier = Modifier.width(8.dp))
    }

    Text(
        text = text,
        style = textStyle
    )

    if (!loading && trailingIcon != null) {
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = trailingIcon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = contentColor
        )
    }
}

/**
 * An icon-only button.
 *
 * @param icon The icon to display.
 * @param onClick Callback when the button is clicked.
 * @param modifier Modifier to be applied to the button.
 * @param contentDescription Accessibility description.
 * @param style The button style variant.
 * @param size The button size variant.
 * @param enabled Whether the button is enabled.
 * @param loading Whether to show a loading spinner.
 */
@Composable
fun AppIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    style: ButtonStyle = ButtonStyle.Primary,
    size: ButtonSize = ButtonSize.Medium,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val buttonSize = when (size) {
        ButtonSize.Small -> 32.dp
        ButtonSize.Medium -> 40.dp
        ButtonSize.Large -> 48.dp
    }

    val iconSize = when (size) {
        ButtonSize.Small -> 16.dp
        ButtonSize.Medium -> 20.dp
        ButtonSize.Large -> 24.dp
    }

    val shape = RoundedCornerShape(8.dp)
    val isClickable = enabled && !loading

    when (style) {
        ButtonStyle.Primary -> {
            val containerColor by animateColorAsState(
                targetValue = when {
                    !enabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    isPressed -> MaterialTheme.colorScheme.primaryContainer
                    isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    else -> MaterialTheme.colorScheme.primary
                },
                label = "containerColor"
            )

            FilledIconButton(
                onClick = onClick,
                modifier = modifier.size(buttonSize),
                enabled = isClickable,
                shape = shape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = containerColor,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                ),
                interactionSource = interactionSource
            ) {
                IconButtonContent(
                    icon = icon,
                    loading = loading,
                    iconSize = iconSize,
                    contentDescription = contentDescription,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        ButtonStyle.Secondary -> {
            val borderColor by animateColorAsState(
                targetValue = when {
                    !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    isPressed -> MaterialTheme.colorScheme.primary
                    isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    else -> MaterialTheme.colorScheme.outline
                },
                label = "borderColor"
            )

            val contentColor by animateColorAsState(
                targetValue = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    isPressed -> MaterialTheme.colorScheme.primary
                    isHovered -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                label = "contentColor"
            )

            OutlinedIconButton(
                onClick = onClick,
                modifier = modifier.size(buttonSize),
                enabled = isClickable,
                shape = shape,
                border = BorderStroke(1.dp, borderColor),
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    contentColor = contentColor,
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                ),
                interactionSource = interactionSource
            ) {
                IconButtonContent(
                    icon = icon,
                    loading = loading,
                    iconSize = iconSize,
                    contentDescription = contentDescription,
                    contentColor = contentColor
                )
            }
        }

        ButtonStyle.Text, ButtonStyle.Danger -> {
            val contentColor by animateColorAsState(
                targetValue = when {
                    !enabled -> (if (style == ButtonStyle.Danger) AppColors.error else MaterialTheme.colorScheme.primary).copy(alpha = 0.5f)
                    isPressed -> (if (style == ButtonStyle.Danger) AppColors.error else MaterialTheme.colorScheme.primary).copy(alpha = 0.7f)
                    isHovered -> (if (style == ButtonStyle.Danger) AppColors.error else MaterialTheme.colorScheme.primary).copy(alpha = 0.8f)
                    else -> if (style == ButtonStyle.Danger) AppColors.error else MaterialTheme.colorScheme.primary
                },
                label = "contentColor"
            )

            IconButton(
                onClick = onClick,
                modifier = modifier.size(buttonSize),
                enabled = isClickable,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = contentColor,
                    disabledContentColor = contentColor.copy(alpha = 0.5f)
                ),
                interactionSource = interactionSource
            ) {
                IconButtonContent(
                    icon = icon,
                    loading = loading,
                    iconSize = iconSize,
                    contentDescription = contentDescription,
                    contentColor = contentColor
                )
            }
        }
    }
}

@Composable
private fun IconButtonContent(
    icon: ImageVector,
    loading: Boolean,
    iconSize: Dp,
    contentDescription: String?,
    contentColor: Color
) {
    if (loading) {
        LoadingSpinner(
            size = iconSize,
            color = contentColor
        )
    } else {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = contentColor
        )
    }
}

/**
 * A small loading spinner for buttons.
 */
@Composable
fun LoadingSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 2.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "spinner")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    CircularProgressIndicator(
        modifier = modifier
            .size(size)
            .rotate(rotation),
        color = color,
        strokeWidth = strokeWidth,
        trackColor = color.copy(alpha = 0.2f)
    )
}

/**
 * Helper data class for button dimensions.
 */
private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
