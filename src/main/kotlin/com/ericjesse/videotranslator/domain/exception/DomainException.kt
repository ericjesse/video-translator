package com.ericjesse.videotranslator.domain.exception

/**
 * Base class for all domain exceptions in the application.
 * Provides a unified interface for error handling with user-friendly messages.
 *
 * @property message Technical error message for logging.
 * @property cause The underlying cause of this exception.
 * @property userMessage User-friendly error message suitable for display in the UI.
 * @property suggestion Optional suggestion for how to resolve the issue.
 * @property isRetryable Whether the operation can be retried.
 */
sealed class DomainException(
    message: String,
    cause: Throwable? = null,
    val userMessage: String,
    val suggestion: String? = null,
    val isRetryable: Boolean = false,
) : Exception(message, cause)

/**
 * Exception thrown when a required resource is not found.
 */
class ResourceNotFoundException(
    resourceName: String,
    message: String = "Resource not found: $resourceName",
    userMessage: String = "Required resource '$resourceName' was not found.",
    suggestion: String? = "Please ensure the resource is installed or available.",
) : DomainException(message, null, userMessage, suggestion, false)

/**
 * Exception thrown when an operation times out.
 */
class OperationTimeoutException(
    operation: String,
    timeoutMs: Long,
    message: String = "Operation '$operation' timed out after ${timeoutMs}ms",
    userMessage: String = "The operation took too long and was cancelled.",
    suggestion: String? = "Please try again. If the problem persists, check your internet connection.",
) : DomainException(message, null, userMessage, suggestion, true)

/**
 * Exception thrown when an operation is cancelled by the user.
 */
class OperationCancelledException(
    operation: String,
    message: String = "Operation '$operation' was cancelled",
    userMessage: String = "The operation was cancelled.",
) : DomainException(message, null, userMessage, null, false)

/**
 * Exception thrown when a configuration is invalid or missing.
 */
class ConfigurationException(
    configName: String,
    message: String = "Invalid or missing configuration: $configName",
    userMessage: String = "Configuration error: $configName",
    suggestion: String? = "Please check your settings.",
) : DomainException(message, null, userMessage, suggestion, false)
