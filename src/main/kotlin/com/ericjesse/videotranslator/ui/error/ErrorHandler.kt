package com.ericjesse.videotranslator.ui.error

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Categories of errors with their display characteristics and available actions.
 */
sealed class ErrorCategory {
    /**
     * Network-related errors (connection issues, timeouts, API failures).
     * Shows retry option.
     */
    data class NetworkError(
        val endpoint: String? = null,
        val statusCode: Int? = null
    ) : ErrorCategory()

    /**
     * Configuration errors (invalid settings, missing API keys).
     * Shows settings button.
     */
    data class ConfigError(
        val configKey: String? = null
    ) : ErrorCategory()

    /**
     * Process errors (FFmpeg, Whisper, yt-dlp failures).
     * Shows technical details.
     */
    data class ProcessError(
        val processName: String,
        val exitCode: Int? = null,
        val stderr: String? = null
    ) : ErrorCategory()

    /**
     * User errors (invalid input, file not found).
     * Shows corrective action.
     */
    data class UserError(
        val correctiveAction: String
    ) : ErrorCategory()

    /**
     * Unknown/general errors.
     */
    data object UnknownError : ErrorCategory()
}

/**
 * Severity level of an error.
 */
enum class ErrorSeverity {
    /**
     * Minor errors shown as snackbar (auto-dismiss after a few seconds).
     * Examples: Failed to copy, temporary network hiccup.
     */
    MINOR,

    /**
     * Major errors shown as dialog (requires user acknowledgment).
     * Examples: Translation failed, API key invalid.
     */
    MAJOR,

    /**
     * Critical errors that may require app restart.
     * Examples: Database corruption, dependency missing.
     */
    CRITICAL
}

/**
 * Represents an application error with all relevant details.
 *
 * @property id Unique identifier for this error.
 * @property title Short error title.
 * @property message User-friendly error message.
 * @property category The error category.
 * @property severity The error severity.
 * @property technicalDetails Optional technical details (stack trace, error codes).
 * @property exception The original exception, if any.
 * @property timestamp When the error occurred.
 * @property retryAction Optional action to retry the failed operation.
 */
data class AppError(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val message: String,
    val category: ErrorCategory,
    val severity: ErrorSeverity,
    val technicalDetails: String? = null,
    val exception: Throwable? = null,
    val timestamp: Instant = Instant.now(),
    val retryAction: (() -> Unit)? = null
) {
    /**
     * Generates a complete error report for copying to clipboard.
     */
    fun generateErrorReport(): String = buildString {
        appendLine("=== Video Translator Error Report ===")
        appendLine()
        appendLine("Timestamp: $timestamp")
        appendLine("Error ID: $id")
        appendLine("Title: $title")
        appendLine("Message: $message")
        appendLine("Severity: $severity")
        appendLine("Category: ${categoryDescription()}")
        appendLine()

        if (technicalDetails != null) {
            appendLine("--- Technical Details ---")
            appendLine(technicalDetails)
            appendLine()
        }

        if (exception != null) {
            appendLine("--- Stack Trace ---")
            val sw = StringWriter()
            exception.printStackTrace(PrintWriter(sw))
            appendLine(sw.toString())
        }

        appendLine("=== End of Report ===")
    }

    private fun categoryDescription(): String = when (category) {
        is ErrorCategory.NetworkError -> {
            buildString {
                append("Network Error")
                category.endpoint?.let { append(" (endpoint: $it)") }
                category.statusCode?.let { append(" [HTTP $it]") }
            }
        }
        is ErrorCategory.ConfigError -> {
            buildString {
                append("Configuration Error")
                category.configKey?.let { append(" (key: $it)") }
            }
        }
        is ErrorCategory.ProcessError -> {
            buildString {
                append("Process Error (${category.processName})")
                category.exitCode?.let { append(" [exit code: $it]") }
            }
        }
        is ErrorCategory.UserError -> "User Error"
        is ErrorCategory.UnknownError -> "Unknown Error"
    }
}

/**
 * Global error handler singleton.
 *
 * Manages a queue of errors to display and provides methods for reporting
 * and clearing errors. Thread-safe and observable by Compose.
 */
object ErrorHandler {
    /**
     * Observable list of current errors.
     * The first error in the list is the one currently being displayed.
     */
    private val _errors: SnapshotStateList<AppError> = mutableStateListOf()
    val errors: List<AppError> get() = _errors

    /**
     * The current error to display, if any.
     */
    val currentError: AppError?
        get() = _errors.firstOrNull()

    /**
     * Whether there are any errors to display.
     */
    val hasErrors: Boolean
        get() = _errors.isNotEmpty()

    /**
     * Reports a new error.
     *
     * @param error The error to report.
     */
    fun reportError(error: AppError) {
        logger.error(error.exception) {
            "Error reported: [${error.severity}] ${error.title} - ${error.message}"
        }

        // Add to the beginning so it's displayed first
        _errors.add(0, error)
    }

    /**
     * Reports a new error with simplified parameters.
     *
     * @param title Short error title.
     * @param message User-friendly error message.
     * @param category The error category.
     * @param severity The error severity.
     * @param exception The original exception, if any.
     * @param retryAction Optional action to retry the failed operation.
     */
    fun reportError(
        title: String,
        message: String,
        category: ErrorCategory = ErrorCategory.UnknownError,
        severity: ErrorSeverity = ErrorSeverity.MAJOR,
        exception: Throwable? = null,
        retryAction: (() -> Unit)? = null
    ) {
        val technicalDetails = buildTechnicalDetails(exception, category)

        reportError(
            AppError(
                title = title,
                message = message,
                category = category,
                severity = severity,
                technicalDetails = technicalDetails,
                exception = exception,
                retryAction = retryAction
            )
        )
    }

    /**
     * Clears a specific error.
     *
     * @param errorId The ID of the error to clear.
     */
    fun clearError(errorId: String) {
        _errors.removeAll { it.id == errorId }
    }

    /**
     * Clears the current (first) error.
     */
    fun clearCurrentError() {
        if (_errors.isNotEmpty()) {
            _errors.removeAt(0)
        }
    }

    /**
     * Clears all errors.
     */
    fun clearAllErrors() {
        _errors.clear()
    }

    /**
     * Clears all errors of a specific severity.
     */
    fun clearErrors(severity: ErrorSeverity) {
        _errors.removeAll { it.severity == severity }
    }

    /**
     * Creates a CoroutineExceptionHandler that reports errors to this handler.
     *
     * @param title Default title for errors.
     * @param category Default category for errors.
     * @param severity Default severity for errors.
     * @param onError Optional callback before error is reported.
     */
    fun createExceptionHandler(
        title: String = "Operation Failed",
        category: ErrorCategory = ErrorCategory.UnknownError,
        severity: ErrorSeverity = ErrorSeverity.MAJOR,
        onError: ((Throwable) -> Unit)? = null
    ): CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        onError?.invoke(throwable)

        val (resolvedCategory, resolvedMessage) = categorizeException(throwable, category)

        reportError(
            title = title,
            message = resolvedMessage,
            category = resolvedCategory,
            severity = severity,
            exception = throwable
        )
    }

    /**
     * Creates a supervised CoroutineScope with error handling.
     *
     * @param title Default title for errors.
     * @param category Default category for errors.
     * @param severity Default severity for errors.
     */
    fun createHandledScope(
        title: String = "Operation Failed",
        category: ErrorCategory = ErrorCategory.UnknownError,
        severity: ErrorSeverity = ErrorSeverity.MAJOR
    ): CoroutineScope = CoroutineScope(
        SupervisorJob() +
        Dispatchers.Default +
        createExceptionHandler(title, category, severity)
    )

    /**
     * Wraps a suspend function with error handling.
     *
     * @param title Title for any errors.
     * @param category Category for any errors.
     * @param severity Severity for any errors.
     * @param retryAction Optional retry action.
     * @param block The suspend function to execute.
     * @return The result of the block, or null if an error occurred.
     */
    suspend fun <T> withErrorHandling(
        title: String = "Operation Failed",
        category: ErrorCategory = ErrorCategory.UnknownError,
        severity: ErrorSeverity = ErrorSeverity.MAJOR,
        retryAction: (() -> Unit)? = null,
        block: suspend () -> T
    ): T? {
        return try {
            block()
        } catch (e: Exception) {
            val (resolvedCategory, resolvedMessage) = categorizeException(e, category)

            reportError(
                title = title,
                message = resolvedMessage,
                category = resolvedCategory,
                severity = severity,
                exception = e,
                retryAction = retryAction
            )
            null
        }
    }

    /**
     * Categorizes an exception and generates an appropriate message.
     */
    private fun categorizeException(
        throwable: Throwable,
        defaultCategory: ErrorCategory
    ): Pair<ErrorCategory, String> {
        val message = throwable.message ?: "An unexpected error occurred"

        // Try to detect network errors
        if (isNetworkException(throwable)) {
            return ErrorCategory.NetworkError() to
                "Unable to connect. Please check your internet connection."
        }

        // Try to detect process errors
        if (throwable.message?.contains("exit code", ignoreCase = true) == true) {
            return defaultCategory to message
        }

        return defaultCategory to message
    }

    /**
     * Checks if an exception is network-related.
     */
    private fun isNetworkException(throwable: Throwable): Boolean {
        val className = throwable::class.simpleName ?: ""
        val message = throwable.message?.lowercase() ?: ""

        return className.contains("connect", ignoreCase = true) ||
                className.contains("socket", ignoreCase = true) ||
                className.contains("timeout", ignoreCase = true) ||
                className.contains("network", ignoreCase = true) ||
                message.contains("connection refused") ||
                message.contains("connection reset") ||
                message.contains("timed out") ||
                message.contains("no route to host") ||
                message.contains("network is unreachable")
    }

    /**
     * Builds technical details string from exception and category.
     */
    private fun buildTechnicalDetails(
        exception: Throwable?,
        category: ErrorCategory
    ): String? {
        val parts = mutableListOf<String>()

        // Add category-specific details
        when (category) {
            is ErrorCategory.NetworkError -> {
                category.endpoint?.let { parts.add("Endpoint: $it") }
                category.statusCode?.let { parts.add("HTTP Status: $it") }
            }
            is ErrorCategory.ProcessError -> {
                parts.add("Process: ${category.processName}")
                category.exitCode?.let { parts.add("Exit Code: $it") }
                category.stderr?.let {
                    parts.add("Output:")
                    parts.add(it.take(2000)) // Limit stderr length
                }
            }
            is ErrorCategory.ConfigError -> {
                category.configKey?.let { parts.add("Configuration Key: $it") }
            }
            is ErrorCategory.UserError -> {
                parts.add("Corrective Action: ${category.correctiveAction}")
            }
            is ErrorCategory.UnknownError -> {}
        }

        // Add exception details
        exception?.let {
            parts.add("Exception: ${it::class.simpleName}")
            it.message?.let { msg -> parts.add("Message: $msg") }
        }

        return if (parts.isEmpty()) null else parts.joinToString("\n")
    }
}

// ========== Extension Functions ==========

/**
 * Reports a network error.
 */
fun ErrorHandler.reportNetworkError(
    title: String = "Connection Error",
    message: String = "Unable to connect. Please check your internet connection.",
    endpoint: String? = null,
    statusCode: Int? = null,
    exception: Throwable? = null,
    severity: ErrorSeverity = ErrorSeverity.MAJOR,
    retryAction: (() -> Unit)? = null
) {
    reportError(
        title = title,
        message = message,
        category = ErrorCategory.NetworkError(endpoint, statusCode),
        severity = severity,
        exception = exception,
        retryAction = retryAction
    )
}

/**
 * Reports a configuration error.
 */
fun ErrorHandler.reportConfigError(
    title: String = "Configuration Error",
    message: String,
    configKey: String? = null,
    exception: Throwable? = null,
    severity: ErrorSeverity = ErrorSeverity.MAJOR
) {
    reportError(
        title = title,
        message = message,
        category = ErrorCategory.ConfigError(configKey),
        severity = severity,
        exception = exception
    )
}

/**
 * Reports a process error.
 */
fun ErrorHandler.reportProcessError(
    title: String,
    message: String,
    processName: String,
    exitCode: Int? = null,
    stderr: String? = null,
    exception: Throwable? = null,
    severity: ErrorSeverity = ErrorSeverity.MAJOR,
    retryAction: (() -> Unit)? = null
) {
    reportError(
        title = title,
        message = message,
        category = ErrorCategory.ProcessError(processName, exitCode, stderr),
        severity = severity,
        exception = exception,
        retryAction = retryAction
    )
}

/**
 * Reports a user error with corrective action.
 */
fun ErrorHandler.reportUserError(
    title: String,
    message: String,
    correctiveAction: String,
    severity: ErrorSeverity = ErrorSeverity.MINOR
) {
    reportError(
        title = title,
        message = message,
        category = ErrorCategory.UserError(correctiveAction),
        severity = severity
    )
}

/**
 * Reports a minor error (shown as snackbar).
 */
fun ErrorHandler.reportMinorError(
    title: String,
    message: String,
    category: ErrorCategory = ErrorCategory.UnknownError,
    exception: Throwable? = null
) {
    reportError(
        title = title,
        message = message,
        category = category,
        severity = ErrorSeverity.MINOR,
        exception = exception
    )
}

/**
 * Reports a critical error.
 */
fun ErrorHandler.reportCriticalError(
    title: String,
    message: String,
    category: ErrorCategory = ErrorCategory.UnknownError,
    exception: Throwable? = null
) {
    reportError(
        title = title,
        message = message,
        category = category,
        severity = ErrorSeverity.CRITICAL,
        exception = exception
    )
}
