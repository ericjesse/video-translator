@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.main.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ericjesse.videotranslator.domain.model.BackgroundColor
import com.ericjesse.videotranslator.ui.i18n.I18nManager

/**
 * Burned-in subtitle options panel.
 *
 * Displays when burned-in subtitles are selected, showing:
 * - Background color dropdown (None, Black, Dark Gray, White)
 * - Background opacity slider (0-100%, enabled only when color != None)
 * - Real-time preview panel with applied styling
 *
 * Features smooth expand/collapse animation when visibility changes.
 *
 * @param i18n Internationalization manager.
 * @param visible Whether the options panel should be visible.
 * @param backgroundColor Currently selected background color.
 * @param onBackgroundColorChange Callback when background color changes.
 * @param backgroundOpacity Current background opacity (0.0 to 1.0).
 * @param onBackgroundOpacityChange Callback when opacity changes.
 * @param modifier Modifier to be applied to the container.
 */
@Composable
fun BurnedInOptions(
    i18n: I18nManager,
    visible: Boolean,
    backgroundColor: BackgroundColor,
    onBackgroundColorChange: (BackgroundColor) -> Unit,
    backgroundOpacity: Float,
    onBackgroundOpacityChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeIn(animationSpec = tween(200)),
        exit = shrinkVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeOut(animationSpec = tween(150)),
        modifier = modifier
    ) {
        BurnedInOptionsContent(
            i18n = i18n,
            backgroundColor = backgroundColor,
            onBackgroundColorChange = onBackgroundColorChange,
            backgroundOpacity = backgroundOpacity,
            onBackgroundOpacityChange = onBackgroundOpacityChange
        )
    }
}

/**
 * Internal content of the burned-in options panel.
 */
@Composable
private fun BurnedInOptionsContent(
    i18n: I18nManager,
    backgroundColor: BackgroundColor,
    onBackgroundColorChange: (BackgroundColor) -> Unit,
    backgroundOpacity: Float,
    onBackgroundOpacityChange: (Float) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(start = 48.dp, top = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Background Color dropdown
            BackgroundColorDropdown(
                i18n = i18n,
                selectedColor = backgroundColor,
                onColorSelected = onBackgroundColorChange
            )

            // Background Opacity slider (only when color is selected)
            BackgroundOpacitySlider(
                i18n = i18n,
                enabled = backgroundColor != BackgroundColor.NONE,
                opacity = backgroundOpacity,
                onOpacityChange = onBackgroundOpacityChange
            )

            // Preview panel
            SubtitlePreview(
                i18n = i18n,
                backgroundColor = backgroundColor,
                backgroundOpacity = backgroundOpacity
            )
        }
    }
}

/**
 * Background color dropdown selector.
 */
@Composable
private fun BackgroundColorDropdown(
    i18n: I18nManager,
    selectedColor: BackgroundColor,
    onColorSelected: (BackgroundColor) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = i18n["burnedIn.backgroundColor"],
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = getBackgroundColorDisplayName(selectedColor, i18n),
                onValueChange = {},
                readOnly = true,
                leadingIcon = {
                    ColorSwatch(
                        color = selectedColor,
                        size = 16
                    )
                },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                BackgroundColor.entries.forEach { color ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                ColorSwatch(color = color, size = 20)
                                Text(
                                    text = getBackgroundColorDisplayName(color, i18n),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        },
                        onClick = {
                            onColorSelected(color)
                            expanded = false
                        },
                        leadingIcon = if (color == selectedColor) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else null
                    )
                }
            }
        }
    }
}

/**
 * Color swatch indicator for the dropdown.
 */
@Composable
private fun ColorSwatch(
    color: BackgroundColor,
    size: Int
) {
    val swatchColor = when {
        color.hex != null -> parseHexColor(color.hex)
        else -> Color.Transparent
    }

    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (color == BackgroundColor.NONE) {
                    // Checkerboard pattern for transparent
                    Brush.linearGradient(
                        colors = listOf(
                            Color.LightGray,
                            Color.White,
                            Color.LightGray,
                            Color.White
                        )
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(swatchColor, swatchColor)
                    )
                }
            )
            .then(
                if (color == BackgroundColor.NONE || color == BackgroundColor.WHITE) {
                    Modifier.background(
                        borderColor,
                        RoundedCornerShape(4.dp)
                    )
                } else Modifier
            )
    ) {
        // Inner fill for colors with border
        if (color != BackgroundColor.NONE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(1.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(swatchColor)
            )
        }
    }
}

/**
 * Background opacity slider.
 */
@Composable
private fun BackgroundOpacitySlider(
    i18n: I18nManager,
    enabled: Boolean,
    opacity: Float,
    onOpacityChange: (Float) -> Unit
) {
    // Animate enabled state
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.4f,
        animationSpec = tween(200),
        label = "sliderAlpha"
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.alpha(alpha)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = i18n["burnedIn.backgroundOpacity"],
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )

            // Percentage display
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "${(opacity * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Slider(
            value = opacity,
            onValueChange = onOpacityChange,
            valueRange = 0f..1f,
            steps = 9, // 10%, 20%, ..., 90%
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                disabledActiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                disabledInactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
        )
    }
}

/**
 * Preview panel showing subtitle with applied styling.
 */
@Composable
private fun SubtitlePreview(
    i18n: I18nManager,
    backgroundColor: BackgroundColor,
    backgroundOpacity: Float
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = i18n["burnedIn.preview"],
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )

        // Video preview container with gradient
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1A1A2E), // Dark blue-gray
                            Color(0xFF16213E), // Darker blue
                            Color(0xFF0F0F1A)  // Near black
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // Add subtle noise/texture overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.03f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Subtitle text with background
            SubtitleText(
                text = i18n["burnedIn.sampleText"],
                backgroundColor = backgroundColor,
                backgroundOpacity = backgroundOpacity
            )
        }
    }
}

/**
 * Sample subtitle text with applied background styling.
 */
@Composable
private fun SubtitleText(
    text: String,
    backgroundColor: BackgroundColor,
    backgroundOpacity: Float
) {
    // Calculate background color with opacity
    val bgColor = when {
        backgroundColor == BackgroundColor.NONE -> Color.Transparent
        backgroundColor.hex != null -> parseHexColor(backgroundColor.hex)
            .copy(alpha = backgroundOpacity)
        else -> Color.Transparent
    }

    // Animate background color changes
    val animatedBgColor by animateColorAsState(
        targetValue = bgColor,
        animationSpec = tween(200),
        label = "subtitleBg"
    )

    Surface(
        color = animatedBgColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
            ),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/**
 * Returns the localized display name for a background color.
 */
private fun getBackgroundColorDisplayName(color: BackgroundColor, i18n: I18nManager): String {
    return when (color) {
        BackgroundColor.NONE -> i18n["burnedIn.color.none"]
        BackgroundColor.BLACK -> i18n["burnedIn.color.black"]
        BackgroundColor.DARK_GRAY -> i18n["burnedIn.color.darkGray"]
        BackgroundColor.WHITE -> i18n["burnedIn.color.white"]
    }
}

/**
 * Parses a hex color string (e.g., "#FFFFFF") to a Compose Color.
 */
private fun parseHexColor(hex: String): Color {
    val colorString = hex.removePrefix("#")
    val colorLong = colorString.toLong(16)
    return when (colorString.length) {
        6 -> Color(
            red = ((colorLong shr 16) and 0xFF).toInt(),
            green = ((colorLong shr 8) and 0xFF).toInt(),
            blue = (colorLong and 0xFF).toInt()
        )
        8 -> Color(
            alpha = ((colorLong shr 24) and 0xFF).toInt(),
            red = ((colorLong shr 16) and 0xFF).toInt(),
            green = ((colorLong shr 8) and 0xFF).toInt(),
            blue = (colorLong and 0xFF).toInt()
        )
        else -> Color.White
    }
}
