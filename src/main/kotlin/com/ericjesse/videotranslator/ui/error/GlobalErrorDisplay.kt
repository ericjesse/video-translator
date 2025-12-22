package com.ericjesse.videotranslator.ui.error

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ericjesse.videotranslator.ui.components.AppButton
import com.ericjesse.videotranslator.ui.components.ButtonSize
import com.ericjesse.videotranslator.ui.components.ButtonStyle
import com.ericjesse.videotranslator.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * Global error display composable.
 *
 * Observes the ErrorHandler's error queue and displays:
 * - Snackbar for minor errors (auto-dismiss)
 * - Dialog for major/critical errors (requires acknowledgment)
 *
 * Place this at the root of your app's composition.
 *
 * @param onNavigateToSettings Optional callback to navigate to settings screen.
 * @param content The main app content.
 */
@Composable
fun GlobalErrorDisplay(
    onNavigateToSettings: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val errors = ErrorHandler.errors
    val currentError = errors.firstOrNull()

    // Separate minor errors from major/critical
    val minorErrors = errors.filter { it.severity == ErrorSeverity.MINOR }
    val majorErrors = errors.filter { it.severity != ErrorSeverity.MINOR }

    Box(modifier = Modifier.fillMaxSize()) {
        // Main content
        content()

        // Snackbar for minor errors
        minorErrors.firstOrNull()?.let { error ->
            ErrorSnackbar(
                error = error,
                onDismiss = { ErrorHandler.clearError(error.id) },
                onRetry = error.retryAction,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Dialog for major/critical errors
        majorErrors.firstOrNull()?.let { error ->
            ErrorDisplayDialog(
                error = error,
                onDismiss = { ErrorHandler.clearError(error.id) },
                onRetry = error.retryAction,
                onSettings = when (error.category) {
                    is ErrorCategory.ConfigError -> onNavigateToSettings
                    is ErrorCategory.NetworkError -> onNavigateToSettings
                    else -> null
                }
            )
        }
    }
}

// ========== Snackbar Component ==========

/**
 * Snackbar for displaying minor errors.
 * Auto-dismisses after a delay.
 */
@Composable
private fun ErrorSnackbar(
    error: AppError,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }

    // Animate in
    LaunchedEffect(error.id) {
        visible = true
        // Auto-dismiss after 5 seconds for minor errors
        delay(5000)
        visible = false
        delay(300) // Wait for animation
        onDismiss()
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier.padding(16.dp)
    ) {
        Snackbar(
            modifier = Modifier.widthIn(max = 500.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (onRetry != null) {
                        TextButton(
                            onClick = {
                                onRetry()
                                onDismiss()
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.inversePrimary
                            )
                        ) {
                            Text("Retry")
                        }
                    }
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f)
                        )
                    ) {
                        Text("Dismiss")
                    }
                }
            },
            dismissAction = {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f)
                    )
                }
            }
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = getErrorIcon(error.category),
                    contentDescription = null,
                    tint = getSnackbarIconColor(error.category),
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = error.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ========== Dialog Component ==========

/**
 * Dialog for displaying major/critical errors.
 */
@Composable
private fun ErrorDisplayDialog(
    error: AppError,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)?,
    onSettings: (() -> Unit)?
) {
    var detailsExpanded by remember { mutableStateOf(false) }
    var showCopiedToast by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    val rotationAngle by animateFloatAsState(
        targetValue = if (detailsExpanded) 180f else 0f,
        label = "rotation"
    )

    // Hide copied toast after delay
    LaunchedEffect(showCopiedToast) {
        if (showCopiedToast) {
            delay(2000)
            showCopiedToast = false
        }
    }

    val isCritical = error.severity == ErrorSeverity.CRITICAL
    val iconColor = if (isCritical) AppColors.error else AppColors.warning
    val errorIcon = getErrorIcon(error.category)

    Dialog(
        onDismissRequest = if (isCritical) { {} } else onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = !isCritical,
            dismissOnClickOutside = !isCritical,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 560.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Error icon with animation
                AnimatedErrorIcon(
                    icon = errorIcon,
                    color = iconColor,
                    isCritical = isCritical
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Severity badge for critical errors
                if (isCritical) {
                    Surface(
                        color = AppColors.error.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "CRITICAL ERROR",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.error,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Title
                Text(
                    text = error.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Message
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                // Corrective action for user errors
                if (error.category is ErrorCategory.UserError) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lightbulb,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = error.category.correctiveAction,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Technical details (collapsible)
                if (error.technicalDetails != null || error.exception != null) {
                    Spacer(modifier = Modifier.height(16.dp))

                    // Details header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { detailsExpanded = !detailsExpanded }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Technical Details",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (detailsExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(rotationAngle)
                        )
                    }

                    // Expandable details
                    AnimatedVisibility(
                        visible = detailsExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = error.technicalDetails
                                            ?: error.exception?.stackTraceToString()?.take(2000)
                                            ?: "",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .verticalScroll(rememberScrollState())
                                            .padding(12.dp)
                                    )
                                }
                            }

                            // Copy button
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Box {
                                    TextButton(
                                        onClick = {
                                            clipboardManager.setText(
                                                AnnotatedString(error.generateErrorReport())
                                            )
                                            showCopiedToast = true
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Copy Error Details")
                                    }

                                    // Copied toast
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = showCopiedToast,
                                        enter = fadeIn(),
                                        exit = fadeOut(),
                                        modifier = Modifier.align(Alignment.CenterStart)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.inverseSurface
                                        ) {
                                            Text(
                                                text = "Copied!",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    // Settings button (for config/network errors)
                    if (onSettings != null) {
                        AppButton(
                            text = "Settings",
                            onClick = {
                                onSettings()
                                onDismiss()
                            },
                            style = ButtonStyle.Text,
                            size = ButtonSize.Medium,
                            leadingIcon = Icons.Default.Settings
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Close button
                    AppButton(
                        text = if (isCritical) "Close App" else "Close",
                        onClick = onDismiss,
                        style = ButtonStyle.Secondary,
                        size = ButtonSize.Medium
                    )

                    // Retry button
                    if (onRetry != null) {
                        AppButton(
                            text = "Retry",
                            onClick = {
                                onRetry()
                                onDismiss()
                            },
                            style = ButtonStyle.Primary,
                            size = ButtonSize.Medium,
                            leadingIcon = Icons.Default.Refresh
                        )
                    }
                }
            }
        }
    }
}

// ========== Animated Error Icon ==========

@Composable
private fun AnimatedErrorIcon(
    icon: ImageVector,
    color: Color,
    isCritical: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "error_icon")

    // Pulse animation for critical errors
    val scale by if (isCritical) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    Box(
        modifier = modifier
            .size((64 * scale).dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Error",
            tint = color,
            modifier = Modifier.size((36 * scale).dp)
        )
    }
}

// ========== Helper Functions ==========

/**
 * Gets the appropriate icon for an error category.
 */
private fun getErrorIcon(category: ErrorCategory): ImageVector = when (category) {
    is ErrorCategory.NetworkError -> Icons.Default.WifiOff
    is ErrorCategory.ConfigError -> Icons.Default.Settings
    is ErrorCategory.ProcessError -> Icons.Default.Terminal
    is ErrorCategory.UserError -> Icons.Default.Info
    is ErrorCategory.UnknownError -> Icons.Default.Error
}

/**
 * Gets the appropriate snackbar icon color for an error category.
 */
@Composable
private fun getSnackbarIconColor(category: ErrorCategory): Color = when (category) {
    is ErrorCategory.NetworkError -> AppColors.warning
    is ErrorCategory.ConfigError -> MaterialTheme.colorScheme.inversePrimary
    is ErrorCategory.ProcessError -> AppColors.error
    is ErrorCategory.UserError -> MaterialTheme.colorScheme.inversePrimary
    is ErrorCategory.UnknownError -> AppColors.error
}

// ========== Provider Composable ==========

/**
 * Provides error handling context for the entire app.
 * This is an alternative way to integrate error handling.
 *
 * @param onNavigateToSettings Callback to navigate to settings.
 * @param content The app content.
 */
@Composable
fun ErrorHandlingProvider(
    onNavigateToSettings: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    GlobalErrorDisplay(
        onNavigateToSettings = onNavigateToSettings,
        content = content
    )
}

/**
 * A simple error boundary that catches errors in its content and displays them.
 * Note: This only catches errors reported through ErrorHandler, not runtime crashes.
 *
 * @param fallback Optional composable to show when there's an error.
 * @param content The content to wrap.
 */
@Composable
fun ErrorBoundary(
    fallback: (@Composable (AppError) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val currentError = ErrorHandler.currentError

    if (currentError != null && currentError.severity == ErrorSeverity.CRITICAL && fallback != null) {
        fallback(currentError)
    } else {
        content()
    }
}
