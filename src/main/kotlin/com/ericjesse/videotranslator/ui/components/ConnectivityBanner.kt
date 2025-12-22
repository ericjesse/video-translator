package com.ericjesse.videotranslator.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ericjesse.videotranslator.infrastructure.network.ConnectivityChecker
import com.ericjesse.videotranslator.infrastructure.network.ConnectivityState
import com.ericjesse.videotranslator.infrastructure.network.ConnectivityStatus
import com.ericjesse.videotranslator.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Displays a banner when the device is offline or has connectivity issues.
 *
 * The banner shows:
 * - Disconnected state: Red banner with offline message and retry button
 * - Slow connection: Yellow warning banner
 * - Partial connectivity: Orange banner listing unavailable services
 *
 * @param connectivityChecker The connectivity checker to observe.
 * @param modifier Modifier for the banner.
 * @param onRetry Callback when the user clicks retry.
 */
@Composable
fun ConnectivityBanner(
    connectivityChecker: ConnectivityChecker,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    val status = connectivityChecker.currentStatus
    val scope = rememberCoroutineScope()

    // Only show banner for non-connected states
    val shouldShow = status.state != ConnectivityState.CONNECTED &&
            status.state != ConnectivityState.UNKNOWN &&
            status.state != ConnectivityState.CHECKING

    AnimatedVisibility(
        visible = shouldShow,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier.fillMaxWidth()
    ) {
        ConnectivityBannerContent(
            status = status,
            onRetry = {
                scope.launch {
                    connectivityChecker.checkConnectivity()
                }
                onRetry?.invoke()
            }
        )
    }
}

/**
 * Content of the connectivity banner based on the current status.
 */
@Composable
private fun ConnectivityBannerContent(
    status: ConnectivityStatus,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (backgroundColor, icon, iconColor) = when (status.state) {
        ConnectivityState.DISCONNECTED -> Triple(
            AppColors.error.copy(alpha = 0.15f),
            Icons.Default.WifiOff,
            AppColors.error
        )
        ConnectivityState.SLOW_CONNECTION -> Triple(
            AppColors.warning.copy(alpha = 0.15f),
            Icons.Default.SignalWifiStatusbarConnectedNoInternet4,
            AppColors.warning
        )
        ConnectivityState.PARTIAL -> Triple(
            Color(0xFFF97316).copy(alpha = 0.15f), // Orange
            Icons.Default.CloudOff,
            Color(0xFFF97316)
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            Icons.Default.Info,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Surface(
        color = backgroundColor,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Animated icon
            AnimatedConnectivityIcon(
                icon = icon,
                tint = iconColor,
                isDisconnected = status.state == ConnectivityState.DISCONNECTED
            )

            // Message
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = getTitle(status.state),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = iconColor
                )
                Text(
                    text = status.getMessage(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }

            // Retry button
            TextButton(
                onClick = onRetry,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = iconColor
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Retry")
            }
        }
    }
}

@Composable
private fun AnimatedConnectivityIcon(
    icon: ImageVector,
    tint: Color,
    isDisconnected: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "connectivity_icon")

    val alpha by if (isDisconnected) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint.copy(alpha = alpha),
        modifier = modifier.size(24.dp)
    )
}

private fun getTitle(state: ConnectivityState): String = when (state) {
    ConnectivityState.DISCONNECTED -> "No Internet Connection"
    ConnectivityState.SLOW_CONNECTION -> "Slow Connection"
    ConnectivityState.PARTIAL -> "Limited Connectivity"
    else -> "Connection Issue"
}

// ========== Compact Indicator ==========

/**
 * A compact connectivity indicator that shows a small icon/dot.
 * Useful for showing in headers or toolbars.
 */
@Composable
fun ConnectivityIndicator(
    connectivityChecker: ConnectivityChecker,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val status = connectivityChecker.currentStatus

    val (icon, color, show) = when (status.state) {
        ConnectivityState.CONNECTED -> Triple(Icons.Default.Wifi, AppColors.success, false)
        ConnectivityState.SLOW_CONNECTION -> Triple(Icons.Default.NetworkCheck, AppColors.warning, true)
        ConnectivityState.PARTIAL -> Triple(Icons.Default.CloudOff, Color(0xFFF97316), true)
        ConnectivityState.DISCONNECTED -> Triple(Icons.Default.WifiOff, AppColors.error, true)
        else -> Triple(Icons.Default.Wifi, MaterialTheme.colorScheme.onSurfaceVariant, false)
    }

    AnimatedVisibility(
        visible = show,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut()
    ) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(4.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = status.getMessage(),
                tint = color,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ========== Wrapper Component ==========

/**
 * Wraps content with a connectivity banner at the top.
 * Also provides a way to disable actions when offline.
 */
@Composable
fun ConnectivityAwareContent(
    connectivityChecker: ConnectivityChecker,
    modifier: Modifier = Modifier,
    requiresNetwork: Boolean = true,
    disableWhenOffline: Boolean = true,
    onRetry: (() -> Unit)? = null,
    content: @Composable (isOnline: Boolean) -> Unit
) {
    val status = connectivityChecker.currentStatus
    val isOnline = status.internetAvailable

    Column(modifier = modifier) {
        // Connectivity banner
        ConnectivityBanner(
            connectivityChecker = connectivityChecker,
            onRetry = onRetry
        )

        // Content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!isOnline && disableWhenOffline && requiresNetwork) {
                        Modifier.then(DisabledOverlay())
                    } else {
                        Modifier
                    }
                )
        ) {
            content(isOnline)
        }
    }
}

/**
 * A semi-transparent overlay for disabled content.
 */
@Composable
private fun DisabledOverlay(): Modifier = Modifier
    .background(Color.Black.copy(alpha = 0.1f))

// ========== Auto-Retry Component ==========

/**
 * A component that automatically retries an action when connectivity is restored.
 */
@Composable
fun AutoRetryOnConnectivity(
    connectivityChecker: ConnectivityChecker,
    enabled: Boolean = true,
    onConnectivityRestored: () -> Unit
) {
    val status = connectivityChecker.currentStatus
    var wasDisconnected by remember { mutableStateOf(false) }

    LaunchedEffect(status.state) {
        if (status.state == ConnectivityState.DISCONNECTED) {
            wasDisconnected = true
        } else if (wasDisconnected && status.internetAvailable && enabled) {
            // Connectivity was restored
            wasDisconnected = false
            // Small delay to ensure connection is stable
            delay(1000)
            onConnectivityRestored()
        }
    }
}

// ========== Service Status Display ==========

/**
 * Displays the status of individual services.
 */
@Composable
fun ServiceStatusList(
    connectivityChecker: ConnectivityChecker,
    modifier: Modifier = Modifier
) {
    val status = connectivityChecker.currentStatus

    if (status.services.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Service Status",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        status.services.forEach { (name, serviceStatus) ->
            ServiceStatusRow(name = name, status = serviceStatus)
        }
    }
}

@Composable
private fun ServiceStatusRow(
    name: String,
    status: com.ericjesse.videotranslator.infrastructure.network.ServiceStatus,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (status.isAvailable) AppColors.success else AppColors.error
                    )
            )

            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Response time
            status.responseTimeMs?.let { ms ->
                Text(
                    text = "${ms}ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        ms < 1000 -> AppColors.success
                        ms < 3000 -> AppColors.warning
                        else -> AppColors.error
                    }
                )
            }

            // Status icon
            Icon(
                imageVector = if (status.isAvailable) {
                    Icons.Default.CheckCircle
                } else {
                    Icons.Default.Cancel
                },
                contentDescription = if (status.isAvailable) "Available" else "Unavailable",
                tint = if (status.isAvailable) AppColors.success else AppColors.error,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ========== Pre-Translation Check Dialog ==========

/**
 * Dialog shown when connectivity issues are detected before translation.
 */
@Composable
fun ConnectivityCheckDialog(
    status: ConnectivityStatus,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onProceedAnyway: (() -> Unit)? = null
) {
    val canProceed = status.state == ConnectivityState.SLOW_CONNECTION ||
            status.state == ConnectivityState.PARTIAL

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = when (status.state) {
                    ConnectivityState.DISCONNECTED -> Icons.Default.WifiOff
                    ConnectivityState.SLOW_CONNECTION -> Icons.Default.Speed
                    else -> Icons.Default.CloudOff
                },
                contentDescription = null,
                tint = when (status.state) {
                    ConnectivityState.DISCONNECTED -> AppColors.error
                    ConnectivityState.SLOW_CONNECTION -> AppColors.warning
                    else -> Color(0xFFF97316)
                },
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = when (status.state) {
                    ConnectivityState.DISCONNECTED -> "No Internet Connection"
                    ConnectivityState.SLOW_CONNECTION -> "Slow Connection Detected"
                    ConnectivityState.PARTIAL -> "Service Unavailable"
                    else -> "Connection Issue"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status.getMessage())

                if (status.state == ConnectivityState.SLOW_CONNECTION) {
                    Text(
                        text = "Average latency: ${status.averageLatencyMs}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (canProceed) {
                    Text(
                        text = "You can still proceed, but the translation may be slower or incomplete.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (canProceed && onProceedAnyway != null) {
                TextButton(onClick = onProceedAnyway) {
                    Text("Proceed Anyway")
                }
            }
            Button(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Retry")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
