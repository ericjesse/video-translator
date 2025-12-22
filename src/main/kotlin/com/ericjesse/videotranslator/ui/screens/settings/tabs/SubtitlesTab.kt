@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.settings.tabs

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.domain.model.BackgroundColor
import com.ericjesse.videotranslator.infrastructure.config.AppSettings
import com.ericjesse.videotranslator.infrastructure.config.BurnedInSettings
import com.ericjesse.videotranslator.ui.i18n.I18nManager

/**
 * Available font colors for subtitles.
 */
enum class SubtitleFontColor(val hex: String, val displayName: String) {
    WHITE("#FFFFFF", "White"),
    YELLOW("#FFFF00", "Yellow"),
    CYAN("#00FFFF", "Cyan"),
    GREEN("#00FF00", "Green"),
    MAGENTA("#FF00FF", "Magenta"),
    RED("#FF0000", "Red"),
    BLUE("#0000FF", "Blue");

    companion object {
        fun fromHex(hex: String): SubtitleFontColor? =
            entries.find { it.hex.equals(hex, ignoreCase = true) }
    }
}

/**
 * Available font sizes for subtitles.
 */
val FONT_SIZES = listOf(18, 20, 22, 24, 26, 28, 32)

/**
 * Subtitles settings tab content.
 *
 * Allows configuration of:
 * - Default output mode (soft/burned-in)
 * - Always export SRT option
 * - Burned-in subtitle styling (font size, colors, opacity)
 * - Real-time preview
 *
 * @param appModule Application module for accessing services.
 * @param settings Current application settings.
 * @param onUpdateSettings Callback to update settings.
 * @param modifier Modifier for the content.
 */
@Composable
fun SubtitlesTabContent(
    appModule: AppModule,
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    val i18n = appModule.i18nManager
    val subtitleSettings = settings.subtitle
    val burnedInSettings = subtitleSettings.burnedIn

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // Default Output Mode Section
        OutputModeSection(
            i18n = i18n,
            currentMode = subtitleSettings.defaultOutputMode,
            onModeChange = { mode ->
                onUpdateSettings {
                    it.copy(subtitle = it.subtitle.copy(defaultOutputMode = mode))
                }
            }
        )

        // Always Export SRT Section
        ExportSrtSection(
            i18n = i18n,
            alwaysExportSrt = subtitleSettings.alwaysExportSrt,
            onExportSrtChange = { checked ->
                onUpdateSettings {
                    it.copy(subtitle = it.subtitle.copy(alwaysExportSrt = checked))
                }
            }
        )

        // Burned-in Subtitle Style Section
        BurnedInStyleSection(
            i18n = i18n,
            burnedInSettings = burnedInSettings,
            onUpdateBurnedIn = { newSettings ->
                onUpdateSettings {
                    it.copy(subtitle = it.subtitle.copy(burnedIn = newSettings))
                }
            }
        )
    }
}

// ========== Output Mode Section ==========

@Composable
private fun OutputModeSection(
    i18n: I18nManager,
    currentMode: String,
    onModeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsSectionCard(
        title = i18n["settings.subtitles.defaultMode"],
        description = i18n["settings.subtitles.defaultMode.description"],
        modifier = modifier
    ) {
        OutputModeDropdown(
            i18n = i18n,
            currentMode = currentMode,
            onModeChange = onModeChange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun OutputModeDropdown(
    i18n: I18nManager,
    currentMode: String,
    onModeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val modes = listOf(
        "soft" to i18n["main.subtitleType.soft"],
        "hard" to i18n["main.subtitleType.burnedIn"]
    )
    val currentDisplayName = modes.find { it.first == currentMode }?.second ?: currentMode

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = currentDisplayName,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            leadingIcon = {
                Icon(
                    imageVector = if (currentMode == "soft") Icons.Default.Subtitles else Icons.Default.TextFields,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            modes.forEach { (mode, displayName) ->
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (mode == "soft") Icons.Default.Subtitles else Icons.Default.TextFields,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = if (mode == "soft") {
                                        i18n["settings.subtitles.soft.description"]
                                    } else {
                                        i18n["settings.subtitles.burnedIn.description"]
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = {
                        onModeChange(mode)
                        expanded = false
                    },
                    leadingIcon = if (mode == currentMode) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null
                )
            }
        }
    }
}

// ========== Export SRT Section ==========

@Composable
private fun ExportSrtSection(
    i18n: I18nManager,
    alwaysExportSrt: Boolean,
    onExportSrtChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExportSrtChange(!alwaysExportSrt) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Checkbox(
                checked = alwaysExportSrt,
                onCheckedChange = onExportSrtChange
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = i18n["settings.subtitles.alwaysExportSrt"],
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = i18n["settings.subtitles.alwaysExportSrt.description"],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// ========== Burned-in Style Section ==========

@Composable
private fun BurnedInStyleSection(
    i18n: I18nManager,
    burnedInSettings: BurnedInSettings,
    onUpdateBurnedIn: (BurnedInSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section header
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = i18n["settings.subtitles.burnedInStyle"],
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = i18n["settings.subtitles.burnedInStyle.description"],
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Font Size
                FontSizeSelector(
                    i18n = i18n,
                    currentSize = burnedInSettings.fontSize,
                    onSizeChange = { size ->
                        onUpdateBurnedIn(burnedInSettings.copy(fontSize = size))
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                // Font Color
                FontColorSelector(
                    i18n = i18n,
                    currentColor = burnedInSettings.fontColor,
                    onColorChange = { color ->
                        onUpdateBurnedIn(burnedInSettings.copy(fontColor = color))
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                // Background Color
                BackgroundColorSelector(
                    i18n = i18n,
                    currentColor = BackgroundColor.entries.find { it.hex == burnedInSettings.backgroundColor }
                        ?: if (burnedInSettings.backgroundColor == "none") BackgroundColor.NONE else BackgroundColor.BLACK,
                    onColorChange = { color ->
                        onUpdateBurnedIn(burnedInSettings.copy(
                            backgroundColor = color.hex ?: "none"
                        ))
                    }
                )

                // Background Opacity
                val currentBgColor = BackgroundColor.entries.find { it.hex == burnedInSettings.backgroundColor }
                    ?: BackgroundColor.NONE

                BackgroundOpacitySlider(
                    i18n = i18n,
                    enabled = currentBgColor != BackgroundColor.NONE,
                    opacity = burnedInSettings.backgroundOpacity,
                    onOpacityChange = { opacity ->
                        onUpdateBurnedIn(burnedInSettings.copy(backgroundOpacity = opacity))
                    }
                )
            }
        }

        // Preview Panel
        SubtitlePreviewPanel(
            i18n = i18n,
            fontSize = burnedInSettings.fontSize,
            fontColor = burnedInSettings.fontColor,
            backgroundColor = burnedInSettings.backgroundColor,
            backgroundOpacity = burnedInSettings.backgroundOpacity
        )
    }
}

// ========== Font Size Selector ==========

@Composable
private fun FontSizeSelector(
    i18n: I18nManager,
    currentSize: Int,
    onSizeChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = i18n["settings.subtitles.fontSize"],
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = "${currentSize}px",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.FormatSize,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                FONT_SIZES.forEach { size ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "${size}px",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = (12 + (size - 18) / 2).sp // Scale preview
                            )
                        },
                        onClick = {
                            onSizeChange(size)
                            expanded = false
                        },
                        leadingIcon = if (size == currentSize) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null
                    )
                }
            }
        }
    }
}

// ========== Font Color Selector ==========

@Composable
private fun FontColorSelector(
    i18n: I18nManager,
    currentColor: String,
    onColorChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedFontColor = SubtitleFontColor.fromHex(currentColor) ?: SubtitleFontColor.WHITE

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = i18n["settings.subtitles.fontColor"],
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Color picker grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SubtitleFontColor.entries.forEach { fontColor ->
                val isSelected = fontColor == selectedFontColor
                ColorPickerSwatch(
                    color = parseHexColor(fontColor.hex),
                    isSelected = isSelected,
                    onClick = { onColorChange(fontColor.hex) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Selected color name
        Text(
            text = selectedFontColor.displayName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ColorPickerSwatch(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        },
        animationSpec = tween(150),
        label = "swatch_border"
    )

    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 3.dp else 1.dp,
        animationSpec = tween(150),
        label = "swatch_border_width"
    )

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Surface(
                color = if (color == Color.White || color == Color.Yellow) {
                    Color.Black.copy(alpha = 0.5f)
                } else {
                    Color.White.copy(alpha = 0.8f)
                },
                shape = CircleShape,
                modifier = Modifier.size(20.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = if (color == Color.White || color == Color.Yellow) {
                            Color.White
                        } else {
                            Color.Black
                        },
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

// ========== Background Color Selector ==========

@Composable
private fun BackgroundColorSelector(
    i18n: I18nManager,
    currentColor: BackgroundColor,
    onColorChange: (BackgroundColor) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = i18n["burnedIn.backgroundColor"],
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = getBackgroundColorDisplayName(currentColor, i18n),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                leadingIcon = {
                    BackgroundColorSwatch(color = currentColor, size = 20)
                },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
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
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BackgroundColorSwatch(color = color, size = 24)
                                Text(
                                    text = getBackgroundColorDisplayName(color, i18n),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        },
                        onClick = {
                            onColorChange(color)
                            expanded = false
                        },
                        leadingIcon = if (color == currentColor) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun BackgroundColorSwatch(
    color: BackgroundColor,
    size: Int
) {
    val swatchColor = when {
        color.hex != null -> parseHexColor(color.hex)
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (color == BackgroundColor.NONE) {
                    Modifier.background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.LightGray,
                                Color.White,
                                Color.LightGray,
                                Color.White
                            )
                        )
                    )
                } else {
                    Modifier.background(swatchColor)
                }
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                RoundedCornerShape(4.dp)
            )
    )
}

// ========== Background Opacity Slider ==========

@Composable
private fun BackgroundOpacitySlider(
    i18n: I18nManager,
    enabled: Boolean,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.4f,
        animationSpec = tween(200),
        label = "slider_alpha"
    )

    Column(
        modifier = modifier.alpha(alpha),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = i18n["burnedIn.backgroundOpacity"],
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

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
            steps = 9,
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

// ========== Preview Panel ==========

@Composable
private fun SubtitlePreviewPanel(
    i18n: I18nManager,
    fontSize: Int,
    fontColor: String,
    backgroundColor: String,
    backgroundOpacity: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Preview,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = i18n["burnedIn.preview"],
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
        }

        // Video preview container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1A1A2E),
                            Color(0xFF16213E),
                            Color(0xFF0F0F1A)
                        )
                    )
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Subtle overlay
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

            // Sample subtitle text
            Box(
                modifier = Modifier.padding(bottom = 20.dp)
            ) {
                StyledSubtitleText(
                    text = i18n["burnedIn.sampleText"],
                    fontSize = fontSize,
                    fontColor = fontColor,
                    backgroundColor = backgroundColor,
                    backgroundOpacity = backgroundOpacity
                )
            }
        }
    }
}

@Composable
private fun StyledSubtitleText(
    text: String,
    fontSize: Int,
    fontColor: String,
    backgroundColor: String,
    backgroundOpacity: Float
) {
    // Calculate colors
    val textColor = parseHexColor(fontColor)
    val bgColor = when {
        backgroundColor == "none" -> Color.Transparent
        else -> parseHexColor(backgroundColor).copy(alpha = backgroundOpacity)
    }

    // Animate color changes
    val animatedBgColor by animateColorAsState(
        targetValue = bgColor,
        animationSpec = tween(200),
        label = "subtitle_bg"
    )

    val animatedTextColor by animateColorAsState(
        targetValue = textColor,
        animationSpec = tween(200),
        label = "subtitle_text"
    )

    Surface(
        color = animatedBgColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = fontSize.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
            ),
            color = animatedTextColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

// ========== Helper Functions ==========

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
 * Parses a hex color string to a Compose Color.
 */
private fun parseHexColor(hex: String): Color {
    val colorString = hex.removePrefix("#")
    return try {
        val colorLong = colorString.toLong(16)
        when (colorString.length) {
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
    } catch (e: Exception) {
        Color.White
    }
}
