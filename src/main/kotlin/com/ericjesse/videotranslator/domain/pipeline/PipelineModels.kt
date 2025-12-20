package com.ericjesse.videotranslator.domain.pipeline

import com.ericjesse.videotranslator.domain.model.*
import kotlinx.serialization.Serializable
import java.time.Instant

// ==================== Stage Result Sealed Class ====================

/**
 * Represents the result of a pipeline stage execution.
 * Enables proper error handling and recovery strategies.
 */
sealed class StageResult<out T> {
    /**
     * Stage completed successfully with a result.
     */
    data class Success<T>(
        val data: T,
        val stage: PipelineStageName,
        val durationMs: Long,
        val metrics: StageMetrics? = null
    ) : StageResult<T>()

    /**
     * Stage failed but may be recoverable.
     */
    data class Failure(
        val stage: PipelineStageName,
        val error: PipelineError,
        val recoveryStrategy: RecoveryStrategy? = null,
        val attemptNumber: Int = 1
    ) : StageResult<Nothing>()

    /**
     * Stage partially completed - some work was done before failure.
     * Useful for checkpoint/resume scenarios.
     */
    data class Partial<T>(
        val partialData: T,
        val stage: PipelineStageName,
        val completedPortion: Float,
        val error: PipelineError,
        val recoverable: Boolean = true
    ) : StageResult<T>()

    /**
     * Stage was skipped (e.g., captions already exist).
     */
    data class Skipped(
        val stage: PipelineStageName,
        val reason: String
    ) : StageResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
    val isPartial: Boolean get() = this is Partial

    /**
     * Maps the success value to a new type.
     */
    inline fun <R> map(transform: (T) -> R): StageResult<R> = when (this) {
        is Success -> Success(transform(data), stage, durationMs, metrics)
        is Failure -> this
        is Partial -> Partial(transform(partialData), stage, completedPortion, error, recoverable)
        is Skipped -> this
    }

    /**
     * Gets the value or null.
     */
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Partial -> partialData
        else -> null
    }

    /**
     * Gets the value or throws the error.
     */
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Partial -> partialData
        is Failure -> throw error.toException()
        is Skipped -> throw IllegalStateException("Stage was skipped: $reason")
    }
}

// ==================== Pipeline Stage Names ====================

/**
 * Enumeration of all pipeline stages for consistent reference.
 */
enum class PipelineStageName(val displayName: String, val order: Int) {
    DOWNLOAD("Download", 1),
    CAPTION_CHECK("Caption Check", 2),
    TRANSCRIPTION("Transcription", 3),
    TRANSLATION("Translation", 4),
    RENDERING("Rendering", 5);

    companion object {
        fun fromOrder(order: Int): PipelineStageName? = entries.find { it.order == order }
    }
}

// ==================== Pipeline Errors ====================

/**
 * Comprehensive error representation for pipeline failures.
 */
@Serializable
data class PipelineError(
    val code: ErrorCode,
    val stage: PipelineStageName,
    val message: String,
    val technicalDetails: String? = null,
    val suggestion: String? = null,
    val recoverable: Boolean = false,
    val retryable: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toException(): PipelineException = PipelineException(this)

    /**
     * Creates a user-friendly error message.
     */
    fun toUserMessage(): String = buildString {
        append("${stage.displayName} failed: $message")
        suggestion?.let { append("\n\nSuggestion: $it") }
    }
}

/**
 * Error codes for categorizing pipeline failures.
 */
@Serializable
enum class ErrorCode(val category: ErrorCategory) {
    // Network errors
    NETWORK_TIMEOUT(ErrorCategory.NETWORK),
    NETWORK_UNREACHABLE(ErrorCategory.NETWORK),
    CONNECTION_REFUSED(ErrorCategory.NETWORK),
    SSL_ERROR(ErrorCategory.NETWORK),

    // API errors
    API_KEY_INVALID(ErrorCategory.API),
    API_KEY_MISSING(ErrorCategory.API),
    RATE_LIMITED(ErrorCategory.API),
    QUOTA_EXCEEDED(ErrorCategory.API),
    API_ERROR(ErrorCategory.API),

    // Resource errors
    MODEL_NOT_FOUND(ErrorCategory.RESOURCE),
    BINARY_NOT_FOUND(ErrorCategory.RESOURCE),
    FILE_NOT_FOUND(ErrorCategory.RESOURCE),
    DISK_FULL(ErrorCategory.RESOURCE),
    INSUFFICIENT_MEMORY(ErrorCategory.RESOURCE),

    // Input errors
    INVALID_URL(ErrorCategory.INPUT),
    UNSUPPORTED_FORMAT(ErrorCategory.INPUT),
    VIDEO_UNAVAILABLE(ErrorCategory.INPUT),
    AGE_RESTRICTED(ErrorCategory.INPUT),
    PRIVATE_VIDEO(ErrorCategory.INPUT),
    REGION_BLOCKED(ErrorCategory.INPUT),

    // Processing errors
    TRANSCRIPTION_FAILED(ErrorCategory.PROCESSING),
    TRANSLATION_FAILED(ErrorCategory.PROCESSING),
    ENCODING_FAILED(ErrorCategory.PROCESSING),
    SUBTITLE_PARSE_ERROR(ErrorCategory.PROCESSING),

    // System errors
    PROCESS_CRASHED(ErrorCategory.SYSTEM),
    HARDWARE_ERROR(ErrorCategory.SYSTEM),
    PERMISSION_DENIED(ErrorCategory.SYSTEM),

    // Generic
    UNKNOWN(ErrorCategory.UNKNOWN),
    CANCELLED(ErrorCategory.CANCELLATION)
}

/**
 * Categories for grouping error types.
 */
@Serializable
enum class ErrorCategory {
    NETWORK,
    API,
    RESOURCE,
    INPUT,
    PROCESSING,
    SYSTEM,
    CANCELLATION,
    UNKNOWN
}

/**
 * Exception wrapper for pipeline errors.
 */
class PipelineException(val error: PipelineError) : Exception(error.message) {
    override val message: String get() = error.toUserMessage()
}

// ==================== Recovery Strategies ====================

/**
 * Represents a recovery strategy for a failed stage.
 */
sealed class RecoveryStrategy {
    /**
     * Retry the same operation with the same parameters.
     */
    data class Retry(
        val maxAttempts: Int = 3,
        val delayMs: Long = 1000,
        val backoffMultiplier: Float = 2f
    ) : RecoveryStrategy()

    /**
     * Retry with modified parameters (e.g., different format, smaller model).
     */
    data class RetryWithFallback<T>(
        val fallbackOptions: List<T>,
        val currentIndex: Int = 0
    ) : RecoveryStrategy() {
        val hasMoreFallbacks: Boolean get() = currentIndex < fallbackOptions.size - 1
        fun nextFallback(): T? = fallbackOptions.getOrNull(currentIndex)
    }

    /**
     * Skip this stage and continue with defaults.
     */
    data class Skip(val reason: String) : RecoveryStrategy()

    /**
     * Resume from a checkpoint.
     */
    data class Resume(val checkpoint: PipelineCheckpoint) : RecoveryStrategy()

    /**
     * No recovery possible - fail the pipeline.
     */
    data object Abort : RecoveryStrategy()
}

/**
 * Fallback options for download recovery.
 */
data class DownloadFallbackOptions(
    val formats: List<VideoFormat> = listOf(
        VideoFormat.BEST,
        VideoFormat.MP4_720P,
        VideoFormat.MP4_480P,
        VideoFormat.AUDIO_ONLY
    ),
    val currentIndex: Int = 0
)

/**
 * Video format options for fallback.
 */
@Serializable
enum class VideoFormat(val ytDlpFormat: String, val displayName: String) {
    BEST("bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best", "Best quality"),
    MP4_1080P("bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080]", "1080p"),
    MP4_720P("bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720]", "720p"),
    MP4_480P("bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/best[height<=480]", "480p"),
    AUDIO_ONLY("bestaudio[ext=m4a]/bestaudio", "Audio only")
}

// ==================== Checkpoint/Resume ====================

/**
 * Checkpoint for resuming a pipeline from a saved state.
 */
@Serializable
data class PipelineCheckpoint(
    val jobId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val lastCompletedStage: PipelineStageName,
    val downloadedVideoPath: String? = null,
    val subtitles: Subtitles? = null,
    val translatedSubtitles: Subtitles? = null,
    val videoInfo: VideoInfo,
    val targetLanguage: Language,
    val outputOptions: OutputOptions
) {
    /**
     * Gets the next stage to execute.
     */
    fun getNextStage(): PipelineStageName? {
        val nextOrder = lastCompletedStage.order + 1
        return PipelineStageName.fromOrder(nextOrder)
    }

    /**
     * Checks if the checkpoint is still valid (files exist, not too old).
     */
    fun isValid(maxAgeMs: Long = 24 * 60 * 60 * 1000L): Boolean {
        val age = System.currentTimeMillis() - timestamp
        if (age > maxAgeMs) return false

        // Check if downloaded video still exists
        downloadedVideoPath?.let {
            if (!java.io.File(it).exists()) return false
        }

        return true
    }
}

// ==================== Stage Metrics ====================

/**
 * Metrics collected during stage execution.
 */
@Serializable
data class StageMetrics(
    val durationMs: Long,
    val bytesProcessed: Long? = null,
    val itemsProcessed: Int? = null,
    val retryCount: Int = 0,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,
    val memoryPeakMb: Long? = null
)

// ==================== Structured Log Events ====================

/**
 * Structured log event for the log panel.
 */
@Serializable
sealed class PipelineLogEvent {
    abstract val timestamp: Long
    abstract val level: LogLevel
    abstract val stage: PipelineStageName?
    abstract val message: String

    /**
     * Informational message.
     */
    @Serializable
    data class Info(
        override val timestamp: Long = System.currentTimeMillis(),
        override val stage: PipelineStageName?,
        override val message: String,
        val details: Map<String, String> = emptyMap()
    ) : PipelineLogEvent() {
        override val level: LogLevel = LogLevel.INFO
    }

    /**
     * Warning message.
     */
    @Serializable
    data class Warning(
        override val timestamp: Long = System.currentTimeMillis(),
        override val stage: PipelineStageName?,
        override val message: String,
        val suggestion: String? = null
    ) : PipelineLogEvent() {
        override val level: LogLevel = LogLevel.WARNING
    }

    /**
     * Error message.
     */
    @Serializable
    data class Error(
        override val timestamp: Long = System.currentTimeMillis(),
        override val stage: PipelineStageName?,
        override val message: String,
        val error: PipelineError? = null,
        val stackTrace: String? = null
    ) : PipelineLogEvent() {
        override val level: LogLevel = LogLevel.ERROR
    }

    /**
     * Debug message.
     */
    @Serializable
    data class Debug(
        override val timestamp: Long = System.currentTimeMillis(),
        override val stage: PipelineStageName?,
        override val message: String,
        val data: Map<String, String> = emptyMap()
    ) : PipelineLogEvent() {
        override val level: LogLevel = LogLevel.DEBUG
    }

    /**
     * Stage transition event.
     */
    @Serializable
    data class StageTransition(
        override val timestamp: Long = System.currentTimeMillis(),
        override val stage: PipelineStageName,
        override val message: String,
        val fromStage: PipelineStageName?,
        val progress: Float = 0f
    ) : PipelineLogEvent() {
        override val level: LogLevel = LogLevel.INFO
    }

    /**
     * Recovery attempt event.
     */
    @Serializable
    data class RecoveryAttempt(
        override val timestamp: Long = System.currentTimeMillis(),
        override val stage: PipelineStageName,
        override val message: String,
        val attemptNumber: Int,
        val maxAttempts: Int,
        val strategy: String
    ) : PipelineLogEvent() {
        override val level: LogLevel = LogLevel.WARNING
    }

    /**
     * Checkpoint saved event.
     */
    @Serializable
    data class CheckpointSaved(
        override val timestamp: Long = System.currentTimeMillis(),
        override val stage: PipelineStageName,
        override val message: String = "Checkpoint saved",
        val checkpointPath: String
    ) : PipelineLogEvent() {
        override val level: LogLevel = LogLevel.DEBUG
    }

    /**
     * Performance metric event.
     */
    @Serializable
    data class Metric(
        override val timestamp: Long = System.currentTimeMillis(),
        override val stage: PipelineStageName?,
        override val message: String,
        val name: String,
        val value: Double,
        val unit: String
    ) : PipelineLogEvent() {
        override val level: LogLevel = LogLevel.DEBUG
    }
}

/**
 * Log levels for filtering.
 */
@Serializable
enum class LogLevel(val priority: Int) {
    DEBUG(0),
    INFO(1),
    WARNING(2),
    ERROR(3)
}

// ==================== Error Mapping Utilities ====================

/**
 * Utility object for mapping exceptions to pipeline errors.
 */
object ErrorMapper {

    /**
     * Maps an exception to a PipelineError with appropriate error code.
     */
    fun mapException(e: Exception, stage: PipelineStageName): PipelineError {
        val message = e.message?.lowercase() ?: ""

        return when {
            // Network errors
            "timeout" in message || "timed out" in message ->
                PipelineError(
                    code = ErrorCode.NETWORK_TIMEOUT,
                    stage = stage,
                    message = "Connection timed out",
                    technicalDetails = e.message,
                    suggestion = "Check your internet connection and try again",
                    recoverable = true,
                    retryable = true
                )

            "connection refused" in message ->
                PipelineError(
                    code = ErrorCode.CONNECTION_REFUSED,
                    stage = stage,
                    message = "Could not connect to server",
                    technicalDetails = e.message,
                    suggestion = "The service may be down. Try again later.",
                    recoverable = true,
                    retryable = true
                )

            "unreachable" in message || "no route" in message ->
                PipelineError(
                    code = ErrorCode.NETWORK_UNREACHABLE,
                    stage = stage,
                    message = "Network unreachable",
                    technicalDetails = e.message,
                    suggestion = "Check your internet connection",
                    recoverable = true,
                    retryable = true
                )

            // API errors
            "rate limit" in message || "429" in message || "too many requests" in message ->
                PipelineError(
                    code = ErrorCode.RATE_LIMITED,
                    stage = stage,
                    message = "Rate limit exceeded",
                    technicalDetails = e.message,
                    suggestion = "Wait a few minutes before trying again",
                    recoverable = true,
                    retryable = true
                )

            "api key" in message && ("invalid" in message || "wrong" in message) ->
                PipelineError(
                    code = ErrorCode.API_KEY_INVALID,
                    stage = stage,
                    message = "Invalid API key",
                    technicalDetails = e.message,
                    suggestion = "Check your API key in Settings",
                    recoverable = false,
                    retryable = false
                )

            "api key" in message && ("missing" in message || "required" in message) ->
                PipelineError(
                    code = ErrorCode.API_KEY_MISSING,
                    stage = stage,
                    message = "API key not configured",
                    technicalDetails = e.message,
                    suggestion = "Add your API key in Settings",
                    recoverable = false,
                    retryable = false
                )

            "quota" in message || "limit exceeded" in message ->
                PipelineError(
                    code = ErrorCode.QUOTA_EXCEEDED,
                    stage = stage,
                    message = "API quota exceeded",
                    technicalDetails = e.message,
                    suggestion = "Try a different translation service or wait until quota resets",
                    recoverable = true,
                    retryable = false
                )

            // Resource errors
            "model" in message && ("not found" in message || "doesn't exist" in message) ->
                PipelineError(
                    code = ErrorCode.MODEL_NOT_FOUND,
                    stage = stage,
                    message = "Whisper model not found",
                    technicalDetails = e.message,
                    suggestion = "Download the model in Settings > Whisper Models",
                    recoverable = true,
                    retryable = false
                )

            "out of memory" in message || "oom" in message ->
                PipelineError(
                    code = ErrorCode.INSUFFICIENT_MEMORY,
                    stage = stage,
                    message = "Insufficient memory",
                    technicalDetails = e.message,
                    suggestion = "Try a smaller model or close other applications",
                    recoverable = true,
                    retryable = false
                )

            "disk full" in message || "no space left" in message ->
                PipelineError(
                    code = ErrorCode.DISK_FULL,
                    stage = stage,
                    message = "Disk full",
                    technicalDetails = e.message,
                    suggestion = "Free up disk space and try again",
                    recoverable = false,
                    retryable = false
                )

            // Video errors
            "private" in message ->
                PipelineError(
                    code = ErrorCode.PRIVATE_VIDEO,
                    stage = stage,
                    message = "Video is private",
                    technicalDetails = e.message,
                    suggestion = "This video is not publicly accessible",
                    recoverable = false,
                    retryable = false
                )

            "age" in message && ("restricted" in message || "confirm" in message) ->
                PipelineError(
                    code = ErrorCode.AGE_RESTRICTED,
                    stage = stage,
                    message = "Video is age-restricted",
                    technicalDetails = e.message,
                    suggestion = "Configure browser cookies in Settings to access age-restricted content",
                    recoverable = true,
                    retryable = false
                )

            "unavailable" in message || "video is not available" in message ->
                PipelineError(
                    code = ErrorCode.VIDEO_UNAVAILABLE,
                    stage = stage,
                    message = "Video is unavailable",
                    technicalDetails = e.message,
                    suggestion = "This video may have been removed or made private",
                    recoverable = false,
                    retryable = false
                )

            "blocked" in message && "country" in message ->
                PipelineError(
                    code = ErrorCode.REGION_BLOCKED,
                    stage = stage,
                    message = "Video is blocked in your region",
                    technicalDetails = e.message,
                    suggestion = "This video is not available in your country",
                    recoverable = false,
                    retryable = false
                )

            // Processing errors
            "encoding" in message || "encoder" in message || "ffmpeg" in message ->
                PipelineError(
                    code = ErrorCode.ENCODING_FAILED,
                    stage = stage,
                    message = "Video encoding failed",
                    technicalDetails = e.message,
                    suggestion = "Try software encoding instead of hardware acceleration",
                    recoverable = true,
                    retryable = true
                )

            // Default
            else ->
                PipelineError(
                    code = ErrorCode.UNKNOWN,
                    stage = stage,
                    message = e.message ?: "Unknown error",
                    technicalDetails = e.stackTraceToString().take(500),
                    recoverable = false,
                    retryable = false
                )
        }
    }

    /**
     * Gets a recovery strategy for an error.
     */
    fun getRecoveryStrategy(error: PipelineError): RecoveryStrategy {
        return when {
            // Retryable errors
            error.retryable -> RecoveryStrategy.Retry(
                maxAttempts = 3,
                delayMs = when (error.code) {
                    ErrorCode.RATE_LIMITED -> 30_000L
                    ErrorCode.NETWORK_TIMEOUT -> 5_000L
                    else -> 2_000L
                }
            )

            // Stage-specific fallbacks
            error.stage == PipelineStageName.DOWNLOAD && error.code == ErrorCode.ENCODING_FAILED ->
                RecoveryStrategy.RetryWithFallback(
                    fallbackOptions = VideoFormat.entries.toList(),
                    currentIndex = 0
                )

            error.stage == PipelineStageName.TRANSCRIPTION && error.code == ErrorCode.INSUFFICIENT_MEMORY ->
                RecoveryStrategy.RetryWithFallback(
                    fallbackOptions = listOf(
                        WhisperModel.SMALL,
                        WhisperModel.BASE,
                        WhisperModel.TINY
                    ),
                    currentIndex = 0
                )

            error.stage == PipelineStageName.RENDERING && error.code == ErrorCode.ENCODING_FAILED ->
                RecoveryStrategy.RetryWithFallback(
                    fallbackOptions = listOf(
                        HardwareEncoder.NONE  // Software encoder fallback
                    ),
                    currentIndex = 0
                )

            // Non-recoverable
            else -> RecoveryStrategy.Abort
        }
    }
}
