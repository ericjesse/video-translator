@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.setup.steps

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.ui.components.AppButton
import com.ericjesse.videotranslator.ui.components.ButtonSize
import com.ericjesse.videotranslator.ui.components.ButtonStyle
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import com.ericjesse.videotranslator.ui.i18n.Locale
import com.ericjesse.videotranslator.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * Application version - should match build.gradle.kts
 */
private const val APP_VERSION = "1.0.0"

/**
 * Welcome step of the setup wizard.
 *
 * Displays:
 * - App icon (large, centered)
 * - App name "Video Translator"
 * - Version number
 * - Tagline from i18n
 * - Language selector with native names
 * - "Get Started" button
 *
 * All elements animate on entrance with staggered timing.
 *
 * @param i18n The i18n manager for localized strings.
 * @param onNext Callback when the user clicks "Get Started".
 * @param onClose Optional callback when the user clicks the close button.
 * @param modifier Modifier to be applied to the step.
 */
@Composable
fun WelcomeStep(
    i18n: I18nManager,
    onNext: () -> Unit,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val currentLocale by i18n.currentLocale.collectAsState()

    // Animation states for staggered entrance
    var showIcon by remember { mutableStateOf(false) }
    var showTitle by remember { mutableStateOf(false) }
    var showTagline by remember { mutableStateOf(false) }
    var showLanguageSelector by remember { mutableStateOf(false) }
    var showButton by remember { mutableStateOf(false) }

    // Trigger staggered animations
    LaunchedEffect(Unit) {
        showIcon = true
        delay(150)
        showTitle = true
        delay(150)
        showTagline = true
        delay(150)
        showLanguageSelector = true
        delay(150)
        showButton = true
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Close button in corner
        if (onClose != null) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = i18n["action.close"],
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            // App icon with entrance animation
            AnimatedIconSection(
                visible = showIcon
            )

            Spacer(modifier = Modifier.height(24.dp))

            // App name and version with entrance animation
            AnimatedTitleSection(
                visible = showTitle,
                appName = i18n["app.name"],
                version = APP_VERSION
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Tagline with entrance animation
            AnimatedTaglineSection(
                visible = showTagline,
                tagline = i18n["app.tagline"]
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Language selector with entrance animation
            AnimatedLanguageSelector(
                visible = showLanguageSelector,
                currentLocale = currentLocale,
                label = i18n["setup.welcome.selectLanguage"],
                onLocaleSelected = { i18n.setLocale(it) }
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Get Started button with entrance animation
            AnimatedGetStartedButton(
                visible = showButton,
                text = i18n["setup.welcome.getStarted"],
                onClick = onNext
            )
        }
    }
}

@Composable
private fun AnimatedIconSection(
    visible: Boolean
) {
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.5f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "iconScale"
    )

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400),
        label = "iconAlpha"
    )

    Surface(
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
            .alpha(alpha),
        shape = RoundedCornerShape(28.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // Video/translate icon composition
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(80.dp)
                )
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
    }
}

@Composable
private fun AnimatedTitleSection(
    visible: Boolean,
    appName: String,
    version: String
) {
    val offsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 30f,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "titleOffset"
    )

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400),
        label = "titleAlpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .offset(y = offsetY.dp)
            .alpha(alpha)
    ) {
        Text(
            text = appName,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                text = "v$version",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun AnimatedTaglineSection(
    visible: Boolean,
    tagline: String
) {
    val offsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 20f,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "taglineOffset"
    )

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400),
        label = "taglineAlpha"
    )

    Text(
        text = tagline,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .offset(y = offsetY.dp)
            .alpha(alpha)
            .padding(horizontal = 32.dp)
    )
}

@Composable
private fun AnimatedLanguageSelector(
    visible: Boolean,
    currentLocale: Locale,
    label: String,
    onLocaleSelected: (Locale) -> Unit
) {
    val offsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 20f,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "selectorOffset"
    )

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400),
        label = "selectorAlpha"
    )

    var expanded by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .offset(y = offsetY.dp)
            .alpha(alpha)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = currentLocale.nativeName,
                onValueChange = {},
                readOnly = true,
                leadingIcon = {
                    LanguageFlag(locale = currentLocale)
                },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                modifier = Modifier
                    .menuAnchor()
                    .width(220.dp),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                Locale.entries.forEach { locale ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                LanguageFlag(locale = locale)
                                Column {
                                    Text(
                                        text = locale.nativeName,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (locale.nativeName != locale.displayName) {
                                        Text(
                                            text = locale.displayName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        },
                        onClick = {
                            onLocaleSelected(locale)
                            expanded = false
                        },
                        trailingIcon = if (locale == currentLocale) {
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

/**
 * Displays a flag indicator for the given locale.
 * Uses emoji flags for simplicity, could be replaced with actual flag icons.
 */
@Composable
private fun LanguageFlag(
    locale: Locale,
    modifier: Modifier = Modifier
) {
    val flag = when (locale) {
        Locale.ENGLISH -> "\uD83C\uDDEC\uD83C\uDDE7" // 🇬🇧
        Locale.GERMAN -> "\uD83C\uDDE9\uD83C\uDDEA" // 🇩🇪
        Locale.FRENCH -> "\uD83C\uDDEB\uD83C\uDDF7" // 🇫🇷
    }

    Surface(
        modifier = modifier.size(28.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = flag,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun AnimatedGetStartedButton(
    visible: Boolean,
    text: String,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "buttonScale"
    )

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400),
        label = "buttonAlpha"
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .alpha(alpha)
    ) {
        AppButton(
            text = text,
            onClick = onClick,
            style = ButtonStyle.Primary,
            size = ButtonSize.Large,
            leadingIcon = Icons.AutoMirrored.Filled.ArrowForward
        )
    }
}
