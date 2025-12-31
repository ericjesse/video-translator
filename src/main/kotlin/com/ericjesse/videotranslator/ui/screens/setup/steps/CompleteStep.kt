package com.ericjesse.videotranslator.ui.screens.setup.steps

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.ui.components.*
import com.ericjesse.videotranslator.ui.components.CardElevation as AppCardElevation
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import com.ericjesse.videotranslator.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * Complete step of the setup wizard.
 *
 * Displays:
 * - Animated success icon (large green checkmark)
 * - "Setup Complete!" heading
 * - Summary card with installed components and versions
 * - "Start Translating" button
 *
 * All elements animate on entrance with staggered timing.
 *
 * @param appModule Application module for accessing services.
 * @param selectedWhisperModel The selected Whisper model name.
 * @param selectedService The selected translation service ID.
 * @param onStart Callback when the user clicks "Start Translating".
 * @param modifier Modifier to be applied to the step.
 */
@Composable
fun CompleteStep(
    appModule: AppModule,
    selectedWhisperModel: String,
    selectedService: String,
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val i18n = appModule.i18nManager
    val configManager = appModule.configManager

    // Get installed versions
    val installedVersions = remember { configManager.getInstalledVersions() }

    // Animation states for staggered entrance
    var showIcon by remember { mutableStateOf(false) }
    var showTitle by remember { mutableStateOf(false) }
    var showDescription by remember { mutableStateOf(false) }
    var showCard by remember { mutableStateOf(false) }
    var showButton by remember { mutableStateOf(false) }

    // Trigger staggered animations
    LaunchedEffect(Unit) {
        showIcon = true
        delay(200)
        showTitle = true
        delay(150)
        showDescription = true
        delay(150)
        showCard = true
        delay(200)
        showButton = true
    }

    val scrollState = rememberScrollState()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(24.dp)
                .padding(end = 12.dp) // Extra padding for scrollbar
        ) {
            // Animated success icon
            AnimatedSuccessIcon(visible = showIcon)

            Spacer(modifier = Modifier.height(24.dp))

            // Title with entrance animation
            AnimatedTitle(
                visible = showTitle,
                text = i18n["setup.complete.title"]
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Description with entrance animation
            AnimatedDescription(
                visible = showDescription,
                text = i18n["setup.complete.description"]
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Summary card with entrance animation
            AnimatedSummaryCard(
                visible = showCard,
                i18n = i18n,
                ytDlpVersion = installedVersions.ytDlp,
                ffmpegVersion = installedVersions.ffmpeg,
                whisperModel = selectedWhisperModel,
                translationService = selectedService
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Start button with entrance animation
            AnimatedStartButton(
                visible = showButton,
                text = i18n["setup.complete.startTranslating"],
                onClick = onStart
            )
        }

        // Scrollbar
        VerticalScrollbar(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
            adapter = rememberScrollbarAdapter(scrollState)
        )
    }
}

@Composable
private fun AnimatedSuccessIcon(visible: Boolean) {
    // Scale animation with bounce
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "iconScale"
    )

    // Alpha animation
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400),
        label = "iconAlpha"
    )

    // Subtle pulse animation after entrance
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Combined scale: entrance + pulse
    val combinedScale = if (visible && scale >= 0.99f) pulseScale else scale

    Surface(
        color = AppColors.success.copy(alpha = 0.15f),
        shape = CircleShape,
        modifier = Modifier
            .size(120.dp)
            .scale(combinedScale)
            .alpha(alpha)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = AppColors.success,
                modifier = Modifier.size(72.dp)
            )
        }
    }
}

@Composable
private fun AnimatedTitle(
    visible: Boolean,
    text: String
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

    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .offset(y = offsetY.dp)
            .alpha(alpha)
    )
}

@Composable
private fun AnimatedDescription(
    visible: Boolean,
    text: String
) {
    val offsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 20f,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "descOffset"
    )

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400),
        label = "descAlpha"
    )

    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .offset(y = offsetY.dp)
            .alpha(alpha)
    )
}

@Composable
private fun AnimatedSummaryCard(
    visible: Boolean,
    i18n: I18nManager,
    ytDlpVersion: String?,
    ffmpegVersion: String?,
    whisperModel: String,
    translationService: String
) {
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.95f,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "cardScale"
    )

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400),
        label = "cardAlpha"
    )

    // Get display name for translation service
    val translationServiceName = when (translationService) {
        "libretranslate" -> "LibreTranslate"
        "deepl" -> "DeepL"
        "openai" -> "OpenAI"
        else -> translationService
    }

    AppCard(
        elevation = AppCardElevation.Low,
        modifier = Modifier
            .scale(scale)
            .alpha(alpha)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // yt-dlp
            SummaryItem(
                icon = Icons.Default.Check,
                text = "yt-dlp",
                detail = ytDlpVersion
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
            )

            // FFmpeg
            SummaryItem(
                icon = Icons.Default.Check,
                text = "FFmpeg",
                detail = ffmpegVersion
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
            )

            // Whisper model
            SummaryItem(
                icon = Icons.Default.Check,
                text = i18n["component.whisper.name"],
                detail = whisperModel
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
            )

            // Translation service
            SummaryItem(
                icon = Icons.Default.Check,
                text = i18n["setup.complete.configured", translationServiceName]
            )
        }
    }
}

@Composable
private fun SummaryItem(
    icon: ImageVector,
    text: String,
    detail: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Check icon in colored circle
            Surface(
                color = AppColors.success.copy(alpha = 0.15f),
                shape = CircleShape,
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = AppColors.success,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Version/detail badge
        if (detail != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun AnimatedStartButton(
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
            size = ButtonSize.Large
        )
    }
}
