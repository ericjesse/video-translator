package com.ericjesse.videotranslator.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ericjesse.videotranslator.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Log level for log entries.
 */
enum class LogLevel(val color: Color, val label: String) {
    DEBUG(Color(0xFF6B7280), "DEBUG"),
    INFO(Color(0xFF3B82F6), "INFO"),
    WARNING(Color(0xFFF59E0B), "WARN"),
    ERROR(Color(0xFFEF4444), "ERROR"),
    SUCCESS(Color(0xFF22C55E), "OK")
}

/**
 * A single log entry.
 */
data class LogEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO,
    val message: String,
    val source: String? = null,
    val details: String? = null
)

/**
 * A collapsible log panel with auto-scroll, timestamps, log level coloring, and various actions.
 *
 * @param logs List of log entries to display.
 * @param modifier Modifier to be applied to the panel.
 * @param title Optional title for the panel.
 * @param collapsible Whether the panel can be collapsed.
 * @param initiallyExpanded Whether the panel starts expanded.
 * @param maxHeight Maximum height of the log area.
 * @param autoScroll Whether to automatically scroll to new entries.
 * @param showTimestamps Whether to show timestamps.
 * @param showLogLevel Whether to show log level badges.
 * @param searchable Whether to show the search field.
 * @param onClear Optional callback when clear button is clicked.
 */
@Composable
fun LogPanel(
    logs: List<LogEntry>,
    modifier: Modifier = Modifier,
    title: String = "Logs",
    collapsible: Boolean = true,
    initiallyExpanded: Boolean = true,
    maxHeight: Int = 300,
    autoScroll: Boolean = true,
    showTimestamps: Boolean = true,
    showLogLevel: Boolean = true,
    searchable: Boolean = true,
    onClear: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedLevel: LogLevel? by remember { mutableStateOf(null) }
    val clipboardManager = LocalClipboardManager.current
    var showCopiedToast by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Filter logs
    val filteredLogs = remember(logs, searchQuery, selectedLevel) {
        logs.filter { log ->
            val matchesSearch = searchQuery.isBlank() ||
                log.message.contains(searchQuery, ignoreCase = true) ||
                log.source?.contains(searchQuery, ignoreCase = true) == true
            val matchesLevel = selectedLevel == null || log.level == selectedLevel
            matchesSearch && matchesLevel
        }
    }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size, autoScroll) {
        if (autoScroll && logs.isNotEmpty() && expanded) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    // Hide copied toast after delay
    LaunchedEffect(showCopiedToast) {
        if (showCopiedToast) {
            kotlinx.coroutines.delay(2000)
            showCopiedToast = false
        }
    }

    AppCard(
        modifier = modifier,
        elevation = CardElevation.Low,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column {
            // Header
            LogPanelHeader(
                title = title,
                logCount = logs.size,
                filteredCount = filteredLogs.size,
                expanded = expanded,
                collapsible = collapsible,
                onToggleExpand = { expanded = !expanded },
                onCopy = {
                    val text = filteredLogs.joinToString("\n") { log ->
                        val time = formatTimestamp(log.timestamp)
                        val level = "[${log.level.label}]"
                        val source = log.source?.let { "[$it]" } ?: ""
                        "$time $level $source ${log.message}"
                    }
                    clipboardManager.setText(AnnotatedString(text))
                    showCopiedToast = true
                },
                onClear = onClear
            )

            // Content
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    // Search and filter bar
                    if (searchable || showLogLevel) {
                        LogPanelToolbar(
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            selectedLevel = selectedLevel,
                            onLevelSelected = { selectedLevel = if (selectedLevel == it) null else it },
                            searchable = searchable,
                            showLogLevel = showLogLevel
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

                    // Log entries
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxHeight.dp)
                    ) {
                        if (filteredLogs.isEmpty()) {
                            EmptyLogsPlaceholder(
                                hasLogs = logs.isNotEmpty(),
                                hasFilter = searchQuery.isNotBlank() || selectedLevel != null
                            )
                        } else {
                            LogEntryList(
                                logs = filteredLogs,
                                listState = listState,
                                showTimestamps = showTimestamps,
                                showLogLevel = showLogLevel
                            )
                        }

                        // Scroll to bottom button
                        ScrollToBottomButton(
                            listState = listState,
                            itemCount = filteredLogs.size,
                            onClick = {
                                scope.launch {
                                    listState.animateScrollToItem(filteredLogs.size - 1)
                                }
                            }
                        )

                        // Copied toast
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showCopiedToast,
                            enter = fadeIn() + slideInVertically(),
                            exit = fadeOut() + slideOutVertically(),
                            modifier = Modifier.align(Alignment.TopCenter)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.inverseSurface,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Text(
                                    text = "Copied to clipboard",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogPanelHeader(
    title: String,
    logCount: Int,
    filteredCount: Int,
    expanded: Boolean,
    collapsible: Boolean,
    onToggleExpand: () -> Unit,
    onCopy: () -> Unit,
    onClear: (() -> Unit)?
) {
    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "rotation"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (collapsible) {
                    Modifier.clickable(onClick = onToggleExpand)
                } else {
                    Modifier
                }
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Terminal,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Log count badge
        if (logCount > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = if (filteredCount != logCount) "$filteredCount / $logCount" else "$logCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action buttons
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy logs",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (onClear != null) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear logs",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (collapsible) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotationAngle)
                )
            }
        }
    }
}

@Composable
private fun LogPanelToolbar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedLevel: LogLevel?,
    onLevelSelected: (LogLevel) -> Unit,
    searchable: Boolean,
    showLogLevel: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Search field
        if (searchable) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))

                androidx.compose.foundation.text.BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        Box {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Filter logs...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier.size(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // Level filter chips
        if (showLogLevel) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LogLevel.entries.forEach { level ->
                    LogLevelChip(
                        level = level,
                        selected = selectedLevel == level,
                        onClick = { onLevelSelected(level) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LogLevelChip(
    level: LogLevel,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = when {
            selected -> level.color.copy(alpha = 0.2f)
            isHovered -> level.color.copy(alpha = 0.1f)
            else -> Color.Transparent
        },
        border = if (selected) BorderStroke(1.dp, level.color) else null,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Text(
            text = level.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) level.color else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun LogEntryList(
    logs: List<LogEntry>,
    listState: LazyListState,
    showTimestamps: Boolean,
    showLogLevel: Boolean
) {
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(logs, key = { it.id }) { log ->
                LogEntryRow(
                    log = log,
                    showTimestamp = showTimestamps,
                    showLogLevel = showLogLevel
                )
            }
        }
    }
}

@Composable
private fun LogEntryRow(
    log: LogEntry,
    showTimestamp: Boolean,
    showLogLevel: Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(
                if (isHovered) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(vertical = 2.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Timestamp
        if (showTimestamp) {
            Text(
                text = formatTimestamp(log.timestamp),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.width(70.dp)
            )
        }

        // Log level badge
        if (showLogLevel) {
            Surface(
                shape = RoundedCornerShape(2.dp),
                color = log.level.color.copy(alpha = 0.15f),
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Text(
                    text = log.level.label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    ),
                    color = log.level.color,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        // Source
        if (log.source != null) {
            Text(
                text = "[${log.source}]",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier.padding(end = 6.dp)
            )
        }

        // Message
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                color = when (log.level) {
                    LogLevel.ERROR -> AppColors.error
                    LogLevel.WARNING -> AppColors.warning
                    LogLevel.SUCCESS -> AppColors.success
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )

            // Details (expanded)
            if (log.details != null) {
                Text(
                    text = log.details,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun BoxScope.ScrollToBottomButton(
    listState: LazyListState,
    itemCount: Int,
    onClick: () -> Unit
) {
    val showButton by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            itemCount > 5 && lastVisibleItem < itemCount - 3
        }
    }

    AnimatedVisibility(
        visible = showButton,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(8.dp)
    ) {
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier.size(32.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Scroll to bottom",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun EmptyLogsPlaceholder(
    hasLogs: Boolean,
    hasFilter: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (hasFilter) Icons.Default.FilterAlt else Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    hasFilter -> "No matching logs"
                    hasLogs -> "All logs filtered out"
                    else -> "No logs yet"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Format timestamp to a readable string.
 */
private fun formatTimestamp(timestamp: Long): String {
    val dateTime = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(timestamp),
        ZoneId.systemDefault()
    )
    return dateTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
}

/**
 * A minimal inline log display for compact spaces.
 *
 * @param logs List of recent log entries.
 * @param maxLines Maximum number of lines to show.
 * @param modifier Modifier to be applied.
 */
@Composable
fun InlineLogDisplay(
    logs: List<LogEntry>,
    maxLines: Int = 3,
    modifier: Modifier = Modifier
) {
    val recentLogs = logs.takeLast(maxLines)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        recentLogs.forEach { log ->
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                ),
                color = when (log.level) {
                    LogLevel.ERROR -> AppColors.error
                    LogLevel.WARNING -> AppColors.warning
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (logs.isEmpty()) {
            Text(
                text = "Waiting for output...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}
