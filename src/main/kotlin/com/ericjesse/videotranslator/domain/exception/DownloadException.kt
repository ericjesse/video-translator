package com.ericjesse.videotranslator.domain.exception

import com.ericjesse.videotranslator.domain.model.YtDlpErrorType
import com.ericjesse.videotranslator.domain.model.YtDlpException

/**
 * Exception hierarchy for video download errors.
 * Provides structured error handling for yt-dlp operations.
 */
sealed class DownloadException(
    message: String,
    cause: Throwable? = null,
    userMessage: String,
    suggestion: String? = null,
    isRetryable: Boolean = false,
) : DomainException(message, cause, userMessage, suggestion, isRetryable) {

    /**
     * Video was not found or is unavailable.
     */
    class VideoNotFound(
        url: String,
        cause: Throwable? = null,
    ) : DownloadException(
        message = "Video not found: $url",
        cause = cause,
        userMessage = "This video is unavailable. It may have been removed or made private.",
        suggestion = "Please check the URL and try again.",
        isRetryable = false
    )

    /**
     * Video is private and requires authentication.
     */
    class VideoPrivate(
        url: String,
        cause: Throwable? = null,
    ) : DownloadException(
        message = "Video is private: $url",
        cause = cause,
        userMessage = "This is a private video. You need to be signed in with access.",
        suggestion = "If you have access, provide browser cookies in settings.",
        isRetryable = false
    )

    /**
     * Video is age-restricted and requires authentication.
     */
    class AgeRestricted(
        url: String,
        cause: Throwable? = null,
    ) : DownloadException(
        message = "Video is age-restricted: $url",
        cause = cause,
        userMessage = "This video is age-restricted and requires authentication.",
        suggestion = "Please provide browser cookies or a cookies file to access age-restricted content.",
        isRetryable = false
    )

    /**
     * Video is geo-restricted and not available in the current region.
     */
    class GeoRestricted(
        url: String,
        cause: Throwable? = null,
    ) : DownloadException(
        message = "Video is geo-restricted: $url",
        cause = cause,
        userMessage = "This video is not available in your region.",
        suggestion = "Try using a VPN to access content from another region.",
        isRetryable = false
    )

    /**
     * Network error during download.
     */
    class NetworkError(
        cause: Throwable? = null,
    ) : DownloadException(
        message = "Network error during download: ${cause?.message}",
        cause = cause,
        userMessage = "Network error. Please check your internet connection.",
        suggestion = "Check your internet connection and try again.",
        isRetryable = true
    )

    /**
     * Rate limited by the service.
     */
    class RateLimited(
        val retryAfterSeconds: Int? = null,
        cause: Throwable? = null,
    ) : DownloadException(
        message = "Rate limited by service",
        cause = cause,
        userMessage = "Too many requests. Please wait a few minutes and try again.",
        suggestion = retryAfterSeconds?.let { "Try again in $it seconds." }
            ?: "Wait a few minutes before trying again.",
        isRetryable = true
    )

    /**
     * Video is a live stream that cannot be downloaded.
     */
    class LiveStreamNotSupported(
        url: String,
        cause: Throwable? = null,
    ) : DownloadException(
        message = "Live stream not supported: $url",
        cause = cause,
        userMessage = "Live streams cannot be downloaded while still live.",
        suggestion = "Wait until the stream ends and try again.",
        isRetryable = false
    )

    /**
     * yt-dlp binary is not installed or not found.
     */
    class DownloaderNotFound(
        cause: Throwable? = null,
    ) : DownloadException(
        message = "yt-dlp not found",
        cause = cause,
        userMessage = "yt-dlp is not installed.",
        suggestion = "Please install yt-dlp in Settings.",
        isRetryable = false
    )

    /**
     * Invalid URL provided.
     */
    class InvalidUrl(
        url: String,
        cause: Throwable? = null,
    ) : DownloadException(
        message = "Invalid URL: $url",
        cause = cause,
        userMessage = "Invalid YouTube URL. Please provide a valid youtube.com or youtu.be link.",
        suggestion = "Check the URL format and try again.",
        isRetryable = false
    )

    /**
     * Unknown download error.
     */
    class Unknown(
        message: String,
        cause: Throwable? = null,
    ) : DownloadException(
        message = message,
        cause = cause,
        userMessage = "An error occurred while downloading the video.",
        suggestion = "Please try again. If the problem persists, check the logs for details.",
        isRetryable = true
    )

    companion object {
        /**
         * Creates a DownloadException from a YtDlpException.
         */
        fun fromYtDlpException(e: YtDlpException): DownloadException = when (e.errorType) {
            YtDlpErrorType.VIDEO_NOT_FOUND -> VideoNotFound(e.technicalMessage, e)
            YtDlpErrorType.PRIVATE_VIDEO -> VideoPrivate(e.technicalMessage, e)
            YtDlpErrorType.AGE_RESTRICTED -> AgeRestricted(e.technicalMessage, e)
            YtDlpErrorType.GEO_RESTRICTED -> GeoRestricted(e.technicalMessage, e)
            YtDlpErrorType.RATE_LIMITED -> RateLimited(cause = e)
            YtDlpErrorType.LIVE_STREAM -> LiveStreamNotSupported(e.technicalMessage, e)
            YtDlpErrorType.YTDLP_NOT_FOUND -> DownloaderNotFound(e)
            YtDlpErrorType.INVALID_URL -> InvalidUrl(e.technicalMessage, e)
            YtDlpErrorType.NETWORK_ERROR -> NetworkError(e)
            YtDlpErrorType.COPYRIGHT_CLAIM -> VideoNotFound(e.technicalMessage, e)
            YtDlpErrorType.UNKNOWN -> Unknown(e.technicalMessage, e)
        }
    }
}
