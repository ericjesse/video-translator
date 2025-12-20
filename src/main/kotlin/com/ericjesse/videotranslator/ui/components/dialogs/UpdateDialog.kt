package com.ericjesse.videotranslator.ui.components.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ericjesse.videotranslator.ui.components.AppButton
import com.ericjesse.videotranslator.ui.components.ButtonSize
import com.ericjesse.videotranslator.ui.components.ButtonStyle
import com.ericjesse.videotranslator.ui.theme.AppColors

/**
 * Result of the update dialog action.
 */
sealed class UpdateDialogResult {
    data object DownloadNow : UpdateDialogResult()
    data object RemindLater : UpdateDialogResult()
    data class SkipVersion(val version: String) : UpdateDialogResult()
}

/**
 * An update notification dialog showing version comparison, release notes, and action buttons.
 *
 * @param currentVersion The currently installed version.
 * @param newVersion The new available version.
 * @param releaseNotes The release notes for the new version.
 * @param onResult Callback with the user's choice.
 * @param onDismiss Callback when the dialog is dismissed.
 * @param downloadUrl Optional download URL to display.
 * @param releaseDate Optional release date string.
 * @param isRequired Whether this update is required (hides skip options).
 */
@Composable
fun UpdateDialog(
    currentVersion: String,
    newVersion: String,
    releaseNotes: String,
    onResult: (UpdateDialogResult) -> Unit,
    onDismiss: () -> Unit,
    downloadUrl: String? = null,
    releaseDate: String? = null,
    isRequired: Boolean = false
) {
    var skipThisVersion by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (!isRequired) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isRequired,
            dismissOnClickOutside = !isRequired,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 400.dp, max = 560.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                // Header with icon
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppColors.info.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = AppColors.info,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Text(
                            text = "Update Available",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (releaseDate != null) {
                            Text(
                                text = "Released $releaseDate",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Version comparison
                VersionComparison(
                    currentVersion = currentVersion,
                    newVersion = newVersion,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Release notes
                Text(
                    text = "What's New",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    )
                }

                // Skip this version checkbox (only if not required)
                if (!isRequired) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = skipThisVersion,
                            onCheckedChange = { skipThisVersion = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Don't remind me for this version",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    if (!isRequired) {
                        AppButton(
                            text = "Remind Later",
                            onClick = {
                                if (skipThisVersion) {
                                    onResult(UpdateDialogResult.SkipVersion(newVersion))
                                } else {
                                    onResult(UpdateDialogResult.RemindLater)
                                }
                                onDismiss()
                            },
                            style = ButtonStyle.Secondary,
                            size = ButtonSize.Medium
                        )
                    }

                    AppButton(
                        text = "Download Now",
                        onClick = {
                            onResult(UpdateDialogResult.DownloadNow)
                            onDismiss()
                        },
                        style = ButtonStyle.Primary,
                        size = ButtonSize.Medium,
                        leadingIcon = Icons.Default.Download
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionComparison(
    currentVersion: String,
    newVersion: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Current version
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Current",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            ) {
                Text(
                    text = "v$currentVersion",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(24.dp))

        // Arrow
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = AppColors.success,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(24.dp))

        // New version
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "New",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.success
            )
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = AppColors.success.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "v$newVersion",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.success,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * A required update dialog that cannot be dismissed.
 */
@Composable
fun RequiredUpdateDialog(
    currentVersion: String,
    newVersion: String,
    releaseNotes: String,
    onDownload: () -> Unit,
    releaseDate: String? = null,
    reason: String = "This update contains critical fixes and is required to continue using the application."
) {
    Dialog(
        onDismissRequest = { /* Cannot dismiss */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 400.dp, max = 560.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Warning icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppColors.warning.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = AppColors.warning,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Required Update",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Version comparison
                VersionComparison(
                    currentVersion = currentVersion,
                    newVersion = newVersion,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Release notes (shorter height for required updates)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Download button only
                AppButton(
                    text = "Download Update",
                    onClick = onDownload,
                    style = ButtonStyle.Primary,
                    size = ButtonSize.Large,
                    leadingIcon = Icons.Default.Download,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
