package com.ericjesse.videotranslator.ui.components.dialogs

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ericjesse.videotranslator.ui.components.AppButton
import com.ericjesse.videotranslator.ui.components.ButtonSize
import com.ericjesse.videotranslator.ui.components.ButtonStyle
import com.ericjesse.videotranslator.ui.theme.AppColors

/**
 * Action types for the error dialog.
 */
enum class ErrorDialogAction {
    Close,
    Retry,
    Settings,
    CopyDetails
}

/**
 * An error display dialog with icon, message, collapsible technical details, and action buttons.
 *
 * @param title The error title.
 * @param message The user-friendly error message.
 * @param onDismiss Callback when the dialog is dismissed.
 * @param technicalDetails Optional technical details (stack trace, error code, etc.).
 * @param onRetry Optional callback for the retry action.
 * @param onSettings Optional callback to open settings.
 * @param showRetryButton Whether to show the retry button.
 * @param showSettingsButton Whether to show the settings button.
 * @param retryButtonText Text for the retry button.
 */
@Composable
fun ErrorDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    technicalDetails: String? = null,
    onRetry: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    showRetryButton: Boolean = onRetry != null,
    showSettingsButton: Boolean = onSettings != null,
    retryButtonText: String = "Retry"
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
            kotlinx.coroutines.delay(2000)
            showCopiedToast = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
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
                // Error icon
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(AppColors.error.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Error",
                        tint = AppColors.error,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Title
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Message
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                // Technical details (collapsible)
                if (technicalDetails != null) {
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
                                        text = technicalDetails,
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
                                            clipboardManager.setText(AnnotatedString(technicalDetails))
                                            showCopiedToast = true
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Copy Details")
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
                    // Settings button
                    if (showSettingsButton && onSettings != null) {
                        AppButton(
                            text = "Settings",
                            onClick = onSettings,
                            style = ButtonStyle.Text,
                            size = ButtonSize.Medium,
                            leadingIcon = Icons.Default.Settings
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Close button
                    AppButton(
                        text = "Close",
                        onClick = onDismiss,
                        style = ButtonStyle.Secondary,
                        size = ButtonSize.Medium
                    )

                    // Retry button
                    if (showRetryButton && onRetry != null) {
                        AppButton(
                            text = retryButtonText,
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

/**
 * A simple error dialog without technical details.
 */
@Composable
fun SimpleErrorDialog(
    title: String = "Error",
    message: String,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    ErrorDialog(
        title = title,
        message = message,
        onDismiss = onDismiss,
        onRetry = onRetry
    )
}

/**
 * A network error dialog with appropriate messaging.
 */
@Composable
fun NetworkErrorDialog(
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    technicalDetails: String? = null
) {
    ErrorDialog(
        title = "Connection Error",
        message = "Unable to connect to the server. Please check your internet connection and try again.",
        onDismiss = onDismiss,
        technicalDetails = technicalDetails,
        onRetry = onRetry,
        onSettings = onSettings,
        retryButtonText = "Try Again"
    )
}

/**
 * An API error dialog for service-related errors.
 */
@Composable
fun ApiErrorDialog(
    serviceName: String,
    errorMessage: String,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    technicalDetails: String? = null
) {
    ErrorDialog(
        title = "$serviceName Error",
        message = errorMessage,
        onDismiss = onDismiss,
        technicalDetails = technicalDetails,
        onRetry = onRetry,
        onSettings = onSettings
    )
}
