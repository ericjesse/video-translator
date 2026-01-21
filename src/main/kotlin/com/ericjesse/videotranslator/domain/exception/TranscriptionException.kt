package com.ericjesse.videotranslator.domain.exception

import com.ericjesse.videotranslator.domain.model.WhisperErrorType
import com.ericjesse.videotranslator.domain.model.WhisperException
import com.ericjesse.videotranslator.domain.model.WhisperModel

/**
 * Exception hierarchy for transcription errors.
 * Provides structured error handling for Whisper operations.
 */
sealed class TranscriptionException(
    message: String,
    cause: Throwable? = null,
    userMessage: String,
    suggestion: String? = null,
    isRetryable: Boolean = false,
) : DomainException(message, cause, userMessage, suggestion, isRetryable) {

    /**
     * Whisper model file was not found.
     */
    class ModelNotFound(
        val model: WhisperModel,
        cause: Throwable? = null,
    ) : TranscriptionException(
        message = "Whisper model not found: ${model.modelName}",
        cause = cause,
        userMessage = "Whisper model '${model.displayName}' not found.",
        suggestion = "Please download the model in Settings.",
        isRetryable = false
    )

    /**
     * Audio file was not found or could not be read.
     */
    class AudioNotFound(
        val path: String,
        cause: Throwable? = null,
    ) : TranscriptionException(
        message = "Audio file not found: $path",
        cause = cause,
        userMessage = "Could not read the audio file.",
        suggestion = "The file may be corrupted or in an unsupported format.",
        isRetryable = false
    )

    /**
     * Not enough memory to run transcription.
     */
    class InsufficientMemory(
        val required: Long,
        val available: Long,
        cause: Throwable? = null,
    ) : TranscriptionException(
        message = "Insufficient memory: required ${required}MB, available ${available}MB",
        cause = cause,
        userMessage = "Out of memory. The selected model requires more memory than available.",
        suggestion = "Try using a smaller model or closing other applications to free memory.",
        isRetryable = false
    )

    /**
     * GPU acceleration requested but not available.
     */
    class GpuNotAvailable(
        cause: Throwable? = null,
    ) : TranscriptionException(
        message = "GPU acceleration not available",
        cause = cause,
        userMessage = "GPU acceleration is not available on this system.",
        suggestion = "The transcription will fall back to CPU, which may be slower.",
        isRetryable = true
    )

    /**
     * Whisper binary not found or not installed.
     */
    class WhisperNotFound(
        cause: Throwable? = null,
    ) : TranscriptionException(
        message = "Whisper binary not found",
        cause = cause,
        userMessage = "Whisper is not installed.",
        suggestion = "Please install Whisper in Settings.",
        isRetryable = false
    )

    /**
     * FFmpeg not found (required for audio extraction).
     */
    class FfmpegNotFound(
        cause: Throwable? = null,
    ) : TranscriptionException(
        message = "FFmpeg not found",
        cause = cause,
        userMessage = "FFmpeg is required for audio extraction.",
        suggestion = "Please install FFmpeg in Settings.",
        isRetryable = false
    )

    /**
     * Invalid audio format.
     */
    class InvalidAudioFormat(
        val path: String,
        cause: Throwable? = null,
    ) : TranscriptionException(
        message = "Invalid audio format: $path",
        cause = cause,
        userMessage = "Invalid audio format. The file may be corrupted.",
        suggestion = "Try downloading the video again.",
        isRetryable = false
    )

    /**
     * Transcription was cancelled.
     */
    class Cancelled(
        cause: Throwable? = null,
    ) : TranscriptionException(
        message = "Transcription was cancelled",
        cause = cause,
        userMessage = "Transcription was cancelled.",
        suggestion = null,
        isRetryable = false
    )

    /**
     * Unknown transcription error.
     */
    class Unknown(
        message: String,
        cause: Throwable? = null,
    ) : TranscriptionException(
        message = message,
        cause = cause,
        userMessage = "Transcription failed. Please try again.",
        suggestion = "If the problem persists, check the logs for details.",
        isRetryable = true
    )

    companion object {
        /**
         * Creates a TranscriptionException from a WhisperException.
         */
        fun fromWhisperException(e: WhisperException): TranscriptionException = when (e.errorType) {
            WhisperErrorType.WHISPER_NOT_FOUND -> WhisperNotFound(e)
            WhisperErrorType.MODEL_NOT_FOUND -> Unknown(e.technicalMessage, e)
            WhisperErrorType.AUDIO_NOT_FOUND -> AudioNotFound(e.technicalMessage, e)
            WhisperErrorType.FFMPEG_NOT_FOUND -> FfmpegNotFound(e)
            WhisperErrorType.GPU_NOT_AVAILABLE -> GpuNotAvailable(e)
            WhisperErrorType.OUT_OF_MEMORY -> InsufficientMemory(0, 0, e)
            WhisperErrorType.INVALID_AUDIO -> InvalidAudioFormat(e.technicalMessage, e)
            WhisperErrorType.CANCELLED -> Cancelled(e)
            WhisperErrorType.UNKNOWN -> Unknown(e.technicalMessage, e)
        }
    }
}
