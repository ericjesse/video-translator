@file:OptIn(ExperimentalMaterial3Api::class)

package com.ericjesse.videotranslator.ui.screens.setup

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.infrastructure.config.SetupProgress
import com.ericjesse.videotranslator.ui.components.*
import com.ericjesse.videotranslator.ui.components.CardElevation as AppCardElevation
import com.ericjesse.videotranslator.ui.components.dialogs.ConfirmDialog
import com.ericjesse.videotranslator.ui.components.dialogs.ConfirmDialogStyle
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import com.ericjesse.videotranslator.ui.i18n.Locale
import com.ericjesse.videotranslator.ui.screens.setup.steps.CompleteStep
import com.ericjesse.videotranslator.ui.screens.setup.steps.DependenciesStep
import com.ericjesse.videotranslator.ui.screens.setup.steps.DownloadingStep
import com.ericjesse.videotranslator.ui.screens.setup.steps.TranslationServiceStep
import com.ericjesse.videotranslator.ui.screens.setup.steps.WelcomeStep
import com.ericjesse.videotranslator.ui.theme.AppColors

/**
 * Setup wizard steps.
 */
enum class SetupStep(val index: Int) {
    WELCOME(0),
    DEPENDENCIES(1),
    DOWNLOADING(2),
    TRANSLATION_SERVICE(3),
    COMPLETE(4);

    companion object {
        fun fromIndex(index: Int): SetupStep = entries.find { it.index == index } ?: WELCOME

        /**
         * Steps visible in the step indicator (excludes DOWNLOADING which is a sub-step).
         */
        val visibleSteps = listOf(WELCOME, DEPENDENCIES, TRANSLATION_SERVICE, COMPLETE)
    }
}

/**
 * State holder for the setup wizard.
 */
class SetupWizardState(
    initialStep: SetupStep = SetupStep.WELCOME,
    initialWhisperModel: String = "base",
    initialTranslationService: String = "libretranslate"
) {
    var currentStep by mutableStateOf(initialStep)
    var selectedWhisperModel by mutableStateOf(initialWhisperModel)
    var selectedTranslationService by mutableStateOf(initialTranslationService)
    var dependenciesDownloaded by mutableStateOf(false)
    var showCloseConfirmation by mutableStateOf(false)

    /**
     * Navigation direction for animations.
     */
    var navigationDirection by mutableStateOf(1) // 1 = forward, -1 = backward

    fun canGoBack(): Boolean = currentStep != SetupStep.WELCOME && currentStep != SetupStep.DOWNLOADING

    fun goBack() {
        navigationDirection = -1
        currentStep = when (currentStep) {
            SetupStep.WELCOME -> SetupStep.WELCOME
            SetupStep.DEPENDENCIES -> SetupStep.WELCOME
            SetupStep.DOWNLOADING -> SetupStep.DEPENDENCIES
            SetupStep.TRANSLATION_SERVICE -> if (dependenciesDownloaded) SetupStep.DEPENDENCIES else SetupStep.DEPENDENCIES
            SetupStep.COMPLETE -> SetupStep.TRANSLATION_SERVICE
        }
    }

    fun goNext() {
        navigationDirection = 1
        currentStep = when (currentStep) {
            SetupStep.WELCOME -> SetupStep.DEPENDENCIES
            SetupStep.DEPENDENCIES -> SetupStep.DOWNLOADING
            SetupStep.DOWNLOADING -> SetupStep.TRANSLATION_SERVICE
            SetupStep.TRANSLATION_SERVICE -> SetupStep.COMPLETE
            SetupStep.COMPLETE -> SetupStep.COMPLETE
        }
    }

    fun skipToTranslationService() {
        navigationDirection = 1
        dependenciesDownloaded = true
        currentStep = SetupStep.TRANSLATION_SERVICE
    }
}

/**
 * Setup wizard - guides first-time users through initial configuration.
 *
 * Implements the following steps:
 * 1. Welcome - Language selection
 * 2. Dependencies - Show required components and Whisper model selection
 * 3. Downloading - Progress for downloading components
 * 4. Translation Service - Configure translation provider
 * 5. Complete - Success message and start button
 *
 * Features:
 * - Step indicator with animated transitions
 * - Progress persistence for resume functionality
 * - Window close confirmation dialog
 * - Smooth AnimatedContent transitions
 */
@Composable
fun SetupWizard(
    appModule: AppModule,
    onComplete: () -> Unit,
    onRequestClose: (() -> Unit)? = null
) {
    val i18n = appModule.i18nManager
    val configManager = appModule.configManager

    // Always start from scratch - don't resume incomplete setups
    val state = remember {
        SetupWizardState(
            initialStep = SetupStep.WELCOME,
            initialWhisperModel = "base",
            initialTranslationService = "libretranslate"
        )
    }

    // Save progress when step changes
    LaunchedEffect(state.currentStep, state.selectedWhisperModel, state.selectedTranslationService, state.dependenciesDownloaded) {
        if (state.currentStep != SetupStep.COMPLETE) {
            configManager.updateSettings { settings ->
                settings.copy(
                    setupProgress = SetupProgress(
                        completed = false,
                        currentStep = state.currentStep.index,
                        selectedWhisperModel = state.selectedWhisperModel,
                        selectedTranslationService = state.selectedTranslationService,
                        dependenciesDownloaded = state.dependenciesDownloaded
                    )
                )
            }
        }
    }

    // Close confirmation dialog
    if (state.showCloseConfirmation) {
        ConfirmDialog(
            title = i18n["setup.close.title"],
            message = i18n["setup.close.description"],
            onConfirm = { result ->
                state.showCloseConfirmation = false
                onRequestClose?.invoke()
            },
            onDismiss = { state.showCloseConfirmation = false },
            confirmText = i18n["setup.close.exit"],
            cancelText = i18n["setup.close.continue"],
            style = ConfirmDialogStyle.Warning
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header with step indicator (not shown on Welcome and Complete)
            if (state.currentStep != SetupStep.WELCOME && state.currentStep != SetupStep.COMPLETE) {
                SetupHeader(
                    state = state,
                    i18n = i18n,
                    onBack = { state.goBack() },
                    onClose = {
                        if (onRequestClose != null) {
                            state.showCloseConfirmation = true
                        }
                    },
                    showCloseButton = onRequestClose != null
                )
            }

            // Step indicator (not shown during downloading)
            if (state.currentStep != SetupStep.DOWNLOADING) {
                WizardStepIndicator(
                    currentStep = state.currentStep,
                    i18n = i18n,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                )
            }

            // Step content with animations
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AnimatedContent(
                    targetState = state.currentStep,
                    transitionSpec = {
                        val direction = state.navigationDirection
                        val slideIn = slideInHorizontally(
                            animationSpec = tween(300),
                            initialOffsetX = { fullWidth -> direction * fullWidth }
                        ) + fadeIn(animationSpec = tween(300))

                        val slideOut = slideOutHorizontally(
                            animationSpec = tween(300),
                            targetOffsetX = { fullWidth -> -direction * fullWidth }
                        ) + fadeOut(animationSpec = tween(300))

                        slideIn togetherWith slideOut
                    },
                    label = "stepTransition"
                ) { step ->
                    when (step) {
                        SetupStep.WELCOME -> WelcomeStep(
                            i18n = i18n,
                            onNext = { state.goNext() },
                            onClose = onRequestClose
                        )
                        SetupStep.DEPENDENCIES -> DependenciesStep(
                            i18n = i18n,
                            selectedModel = state.selectedWhisperModel,
                            onModelSelected = { state.selectedWhisperModel = it },
                            onDownload = { state.goNext() },
                            isDownloading = false
                        )
                        SetupStep.DOWNLOADING -> DownloadingStep(
                            appModule = appModule,
                            selectedWhisperModel = state.selectedWhisperModel,
                            onComplete = { state.skipToTranslationService() },
                            onCancel = { state.goBack() }
                        )
                        SetupStep.TRANSLATION_SERVICE -> TranslationServiceStep(
                            appModule = appModule,
                            selectedService = state.selectedTranslationService,
                            onServiceSelected = { state.selectedTranslationService = it },
                            onNext = { state.goNext() }
                        )
                        SetupStep.COMPLETE -> CompleteStep(
                            appModule = appModule,
                            selectedWhisperModel = state.selectedWhisperModel,
                            selectedService = state.selectedTranslationService,
                            onStart = {
                                // Mark setup as complete
                                configManager.updateSettings { settings ->
                                    settings.copy(
                                        setupProgress = SetupProgress(
                                            completed = true,
                                            currentStep = SetupStep.COMPLETE.index,
                                            selectedWhisperModel = state.selectedWhisperModel,
                                            selectedTranslationService = state.selectedTranslationService,
                                            dependenciesDownloaded = true
                                        ),
                                        translation = settings.translation.copy(
                                            defaultService = state.selectedTranslationService
                                        ),
                                        transcription = settings.transcription.copy(
                                            whisperModel = state.selectedWhisperModel
                                        )
                                    )
                                }
                                onComplete()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Header with back button and optional close button.
 */
@Composable
private fun SetupHeader(
    state: SetupWizardState,
    i18n: I18nManager,
    onBack: () -> Unit,
    onClose: () -> Unit,
    showCloseButton: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button
        if (state.canGoBack()) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = i18n["action.back"],
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = i18n["setup.dependencies.title"].takeIf { state.currentStep == SetupStep.DEPENDENCIES || state.currentStep == SetupStep.DOWNLOADING }
                ?: i18n["setup.translation.title"].takeIf { state.currentStep == SetupStep.TRANSLATION_SERVICE }
                ?: "",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.weight(1f))

        // Close button
        if (showCloseButton && state.currentStep != SetupStep.DOWNLOADING) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = i18n["action.close"],
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
    }
}

/**
 * Step indicator showing progress through the wizard.
 */
@Composable
private fun WizardStepIndicator(
    currentStep: SetupStep,
    i18n: I18nManager,
    modifier: Modifier = Modifier
) {
    val steps = SetupStep.visibleSteps
    val currentIndex = steps.indexOf(currentStep).coerceAtLeast(0)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            steps.forEachIndexed { index, step ->
                val isComplete = index < currentIndex
                val isCurrent = step == currentStep

                // Step circle
                StepCircle(
                    stepNumber = index + 1,
                    isComplete = isComplete,
                    isCurrent = isCurrent
                )

                // Connector line (except after last step)
                if (index < steps.size - 1) {
                    StepConnector(
                        isComplete = index < currentIndex,
                        modifier = Modifier.width(40.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Step labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            steps.forEachIndexed { index, step ->
                val isCurrent = step == currentStep
                val isComplete = index < currentIndex

                Text(
                    text = when (step) {
                        SetupStep.WELCOME -> i18n["setup.step.welcome"]
                        SetupStep.DEPENDENCIES -> i18n["setup.step.dependencies"]
                        SetupStep.TRANSLATION_SERVICE -> i18n["setup.step.translation"]
                        SetupStep.COMPLETE -> i18n["setup.step.complete"]
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        isCurrent -> MaterialTheme.colorScheme.primary
                        isComplete -> AppColors.success
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StepCircle(
    stepNumber: Int,
    isComplete: Boolean,
    isCurrent: Boolean
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isComplete -> AppColors.success
            isCurrent -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(300),
        label = "stepBg"
    )

    val contentColor = when {
        isComplete || isCurrent -> Color.White
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        if (isComplete) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Text(
                text = stepNumber.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun StepConnector(
    isComplete: Boolean,
    modifier: Modifier = Modifier
) {
    val color by animateColorAsState(
        targetValue = if (isComplete) AppColors.success else MaterialTheme.colorScheme.outline,
        animationSpec = tween(300),
        label = "connectorColor"
    )

    Box(
        modifier = modifier
            .height(2.dp)
            .background(color)
    )
}

