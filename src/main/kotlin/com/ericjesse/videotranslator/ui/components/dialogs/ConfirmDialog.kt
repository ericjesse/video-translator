package com.ericjesse.videotranslator.ui.components.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ericjesse.videotranslator.ui.components.AppButton
import com.ericjesse.videotranslator.ui.components.ButtonSize
import com.ericjesse.videotranslator.ui.components.ButtonStyle
import com.ericjesse.videotranslator.ui.theme.AppColors

/**
 * Result of a confirmation dialog.
 */
data class ConfirmDialogResult(
    val confirmed: Boolean,
    val dontShowAgain: Boolean = false
)

/**
 * Style variants for confirmation dialogs.
 */
enum class ConfirmDialogStyle {
    Default,
    Warning,
    Danger
}

/**
 * A generic confirmation dialog with title, message, and optional "Don't show again" checkbox.
 *
 * @param title The dialog title.
 * @param message The dialog message.
 * @param onConfirm Callback when the confirm button is clicked with the result.
 * @param onDismiss Callback when the dialog is dismissed.
 * @param confirmText Text for the confirm button.
 * @param cancelText Text for the cancel button.
 * @param style The dialog style variant.
 * @param icon Optional icon to display.
 * @param showDontShowAgain Whether to show the "Don't show again" checkbox.
 * @param confirmEnabled Whether the confirm button is enabled.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: (ConfirmDialogResult) -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "Confirm",
    cancelText: String = "Cancel",
    style: ConfirmDialogStyle = ConfirmDialogStyle.Default,
    icon: ImageVector? = null,
    showDontShowAgain: Boolean = false,
    confirmEnabled: Boolean = true
) {
    var dontShowAgain by remember { mutableStateOf(false) }

    val iconToShow = icon ?: when (style) {
        ConfirmDialogStyle.Warning -> Icons.Default.Warning
        ConfirmDialogStyle.Danger -> Icons.Default.Warning
        else -> null
    }

    val iconColor = when (style) {
        ConfirmDialogStyle.Warning -> AppColors.warning
        ConfirmDialogStyle.Danger -> AppColors.error
        else -> MaterialTheme.colorScheme.primary
    }

    val confirmButtonStyle = when (style) {
        ConfirmDialogStyle.Danger -> ButtonStyle.Danger
        else -> ButtonStyle.Primary
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
                .widthIn(min = 320.dp, max = 480.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Icon
                if (iconToShow != null) {
                    Icon(
                        imageVector = iconToShow,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Title
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Message
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                // Don't show again checkbox
                if (showDontShowAgain) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = dontShowAgain,
                            onCheckedChange = { dontShowAgain = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Don't show this again",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    AppButton(
                        text = cancelText,
                        onClick = onDismiss,
                        style = ButtonStyle.Secondary,
                        size = ButtonSize.Medium
                    )
                    AppButton(
                        text = confirmText,
                        onClick = { onConfirm(ConfirmDialogResult(confirmed = true, dontShowAgain = dontShowAgain)) },
                        style = confirmButtonStyle,
                        size = ButtonSize.Medium,
                        enabled = confirmEnabled
                    )
                }
            }
        }
    }
}

/**
 * A simple confirmation dialog without the "Don't show again" option.
 */
@Composable
fun SimpleConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "Confirm",
    cancelText: String = "Cancel",
    style: ConfirmDialogStyle = ConfirmDialogStyle.Default,
    icon: ImageVector? = null
) {
    ConfirmDialog(
        title = title,
        message = message,
        onConfirm = { onConfirm() },
        onDismiss = onDismiss,
        confirmText = confirmText,
        cancelText = cancelText,
        style = style,
        icon = icon,
        showDontShowAgain = false
    )
}

/**
 * A delete confirmation dialog with appropriate styling.
 */
@Composable
fun DeleteConfirmDialog(
    itemName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "Delete $itemName?",
    message: String = "This action cannot be undone. Are you sure you want to delete this item?"
) {
    SimpleConfirmDialog(
        title = title,
        message = message,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmText = "Delete",
        cancelText = "Cancel",
        style = ConfirmDialogStyle.Danger
    )
}
