package com.ericjesse.videotranslator.infrastructure.logging

import io.github.oshai.kotlinlogging.KLogger

/**
 * Extension functions for structured logging with context.
 * Allows logging key-value pairs in a consistent format.
 *
 * Example usage:
 * ```kotlin
 * logger.infoWithContext("Pipeline stage completed") {
 *     "stage" with stage.name
 *     "duration_ms" with duration.inWholeMilliseconds
 *     "video_id" with job.videoId
 * }
 * ```
 */

/**
 * Builder for structured log context.
 */
class LogContextBuilder {
    private val pairs = mutableListOf<Pair<String, Any?>>()

    /**
     * Adds a key-value pair to the context.
     * Use this instead of the standard `to` to avoid conflicts.
     */
    fun put(key: String, value: Any?) {
        pairs.add(key to value)
    }

    /**
     * Infix function for adding key-value pairs.
     * Usage: "key" with "value"
     */
    infix fun String.with(value: Any?) {
        pairs.add(this to value)
    }

    fun build(): String {
        if (pairs.isEmpty()) return ""
        return pairs.joinToString(", ") { (key, value) ->
            "$key=$value"
        }
    }

    fun toMap(): Map<String, Any?> = pairs.toMap()
}

/**
 * Logs an info message with structured context.
 *
 * @param message The log message.
 * @param contextBuilder Builder function for adding context key-value pairs.
 */
inline fun KLogger.infoWithContext(message: String, contextBuilder: LogContextBuilder.() -> Unit) {
    val context = LogContextBuilder().apply(contextBuilder).build()
    if (context.isNotEmpty()) {
        info { "$message | $context" }
    } else {
        info { message }
    }
}

/**
 * Logs a debug message with structured context.
 *
 * @param message The log message.
 * @param contextBuilder Builder function for adding context key-value pairs.
 */
inline fun KLogger.debugWithContext(message: String, contextBuilder: LogContextBuilder.() -> Unit) {
    val context = LogContextBuilder().apply(contextBuilder).build()
    if (context.isNotEmpty()) {
        debug { "$message | $context" }
    } else {
        debug { message }
    }
}

/**
 * Logs a warning message with structured context.
 *
 * @param message The log message.
 * @param contextBuilder Builder function for adding context key-value pairs.
 */
inline fun KLogger.warnWithContext(message: String, contextBuilder: LogContextBuilder.() -> Unit) {
    val context = LogContextBuilder().apply(contextBuilder).build()
    if (context.isNotEmpty()) {
        warn { "$message | $context" }
    } else {
        warn { message }
    }
}

/**
 * Logs an error message with structured context.
 *
 * @param message The log message.
 * @param contextBuilder Builder function for adding context key-value pairs.
 */
inline fun KLogger.errorWithContext(message: String, contextBuilder: LogContextBuilder.() -> Unit) {
    val context = LogContextBuilder().apply(contextBuilder).build()
    if (context.isNotEmpty()) {
        error { "$message | $context" }
    } else {
        error { message }
    }
}

/**
 * Logs an error message with structured context and an exception.
 *
 * @param throwable The exception to log.
 * @param message The log message.
 * @param contextBuilder Builder function for adding context key-value pairs.
 */
inline fun KLogger.errorWithContext(
    throwable: Throwable,
    message: String,
    contextBuilder: LogContextBuilder.() -> Unit,
) {
    val context = LogContextBuilder().apply(contextBuilder).build()
    if (context.isNotEmpty()) {
        error(throwable) { "$message | $context" }
    } else {
        error(throwable) { message }
    }
}

/**
 * Creates a log context builder for use with standard logger methods.
 *
 * Example usage:
 * ```kotlin
 * logger.info { "Event occurred" + logContext {
 *     "event" to "download_complete"
 *     "video_id" to videoId
 * }}
 * ```
 */
inline fun logContext(builder: LogContextBuilder.() -> Unit): String {
    val context = LogContextBuilder().apply(builder).build()
    return if (context.isNotEmpty()) " | $context" else ""
}
