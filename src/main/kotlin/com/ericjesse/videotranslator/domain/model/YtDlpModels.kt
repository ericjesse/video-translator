package com.ericjesse.videotranslator.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Complete video information returned by yt-dlp --dump-json.
 *
 * @property id Unique video identifier.
 * @property title Video title.
 * @property description Full video description.
 * @property uploadDate Upload date in YYYYMMDD format.
 * @property uploader Channel/uploader name.
 * @property uploaderId Channel/uploader ID.
 * @property duration Video duration in seconds.
 * @property viewCount Number of views.
 * @property likeCount Number of likes.
 * @property thumbnail Primary thumbnail URL.
 * @property thumbnails List of all available thumbnails.
 * @property categories Video categories.
 * @property tags Video tags.
 * @property formats Available download formats.
 * @property subtitles Manually added subtitles/captions.
 * @property automaticCaptions Auto-generated captions.
 * @property isLive Whether the video is currently live.
 * @property wasLive Whether the video was a live stream.
 * @property ageLimit Age restriction (0 = none, 18 = adult content).
 * @property webpageUrl Canonical URL of the video page.
 * @property originalUrl Original URL that was provided.
 * @property extractor Name of the extractor used (e.g., "youtube").
 * @property extractorKey Key of the extractor (e.g., "Youtube").
 */
@Serializable
data class YtDlpVideoInfo(
    val id: String,
    val title: String,
    val description: String? = null,
    @SerialName("upload_date")
    val uploadDate: String? = null,
    val uploader: String? = null,
    @SerialName("uploader_id")
    val uploaderId: String? = null,
    @SerialName("uploader_url")
    val uploaderUrl: String? = null,
    @SerialName("channel")
    val channel: String? = null,
    @SerialName("channel_id")
    val channelId: String? = null,
    @SerialName("channel_url")
    val channelUrl: String? = null,
    val duration: Long? = null,
    @SerialName("view_count")
    val viewCount: Long? = null,
    @SerialName("like_count")
    val likeCount: Long? = null,
    @SerialName("comment_count")
    val commentCount: Long? = null,
    val thumbnail: String? = null,
    val thumbnails: List<YtDlpThumbnail>? = null,
    val categories: List<String>? = null,
    val tags: List<String>? = null,
    val formats: List<YtDlpFormat>? = null,
    val subtitles: Map<String, List<YtDlpSubtitle>>? = null,
    @SerialName("automatic_captions")
    val automaticCaptions: Map<String, List<YtDlpSubtitle>>? = null,
    @SerialName("is_live")
    val isLive: Boolean? = null,
    @SerialName("was_live")
    val wasLive: Boolean? = null,
    @SerialName("age_limit")
    val ageLimit: Int? = null,
    @SerialName("webpage_url")
    val webpageUrl: String? = null,
    @SerialName("original_url")
    val originalUrl: String? = null,
    val extractor: String? = null,
    @SerialName("extractor_key")
    val extractorKey: String? = null,
    @SerialName("playlist")
    val playlist: String? = null,
    @SerialName("playlist_index")
    val playlistIndex: Int? = null,
    @SerialName("availability")
    val availability: String? = null,
    @SerialName("live_status")
    val liveStatus: String? = null
) {
    /**
     * Whether captions are available (manual or automatic).
     */
    val hasCaptions: Boolean
        get() = !subtitles.isNullOrEmpty() || !automaticCaptions.isNullOrEmpty()

    /**
     * Whether manual (human-created) subtitles are available.
     */
    val hasManualCaptions: Boolean
        get() = !subtitles.isNullOrEmpty()

    /**
     * Whether the video is age-restricted.
     */
    val isAgeRestricted: Boolean
        get() = (ageLimit ?: 0) > 0

    /**
     * Gets all available caption languages.
     * @param preferManual If true, returns manual captions when available, otherwise automatic.
     */
    fun getAvailableCaptionLanguages(preferManual: Boolean = true): List<String> {
        val manual = subtitles?.keys?.toList() ?: emptyList()
        val automatic = automaticCaptions?.keys?.toList() ?: emptyList()

        return if (preferManual && manual.isNotEmpty()) {
            manual
        } else {
            automatic.ifEmpty { manual }
        }
    }

    /**
     * Checks if captions are available for a specific language.
     */
    fun hasCaptionsForLanguage(langCode: String): Boolean {
        return subtitles?.containsKey(langCode) == true ||
                automaticCaptions?.containsKey(langCode) == true
    }

    /**
     * Converts to the simpler VideoInfo domain model.
     */
    fun toVideoInfo(): VideoInfo {
        return VideoInfo(
            url = webpageUrl ?: originalUrl ?: "",
            id = id,
            title = title,
            duration = duration ?: 0L,
            thumbnailUrl = thumbnail
        )
    }
}

/**
 * Thumbnail information from yt-dlp.
 *
 * @property url URL of the thumbnail image.
 * @property id Thumbnail identifier.
 * @property width Thumbnail width in pixels.
 * @property height Thumbnail height in pixels.
 * @property resolution Resolution string (e.g., "1280x720").
 */
@Serializable
data class YtDlpThumbnail(
    val url: String,
    val id: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val resolution: String? = null,
    val preference: Int? = null
)

/**
 * Available download format from yt-dlp.
 *
 * @property formatId Unique format identifier.
 * @property formatNote Human-readable format description (e.g., "720p", "medium").
 * @property ext File extension (e.g., "mp4", "webm", "m4a").
 * @property resolution Resolution string (e.g., "1920x1080").
 * @property fps Frames per second.
 * @property vcodec Video codec (e.g., "avc1", "vp9", "none").
 * @property acodec Audio codec (e.g., "mp4a", "opus", "none").
 * @property filesize File size in bytes.
 * @property filesizeApprox Approximate file size in bytes.
 * @property tbr Total bitrate in kbps.
 * @property vbr Video bitrate in kbps.
 * @property abr Audio bitrate in kbps.
 * @property asr Audio sample rate in Hz.
 */
@Serializable
data class YtDlpFormat(
    @SerialName("format_id")
    val formatId: String,
    @SerialName("format_note")
    val formatNote: String? = null,
    @SerialName("format")
    val format: String? = null,
    val ext: String? = null,
    val resolution: String? = null,
    val fps: Float? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val filesize: Long? = null,
    @SerialName("filesize_approx")
    val filesizeApprox: Long? = null,
    val tbr: Float? = null,
    val vbr: Float? = null,
    val abr: Float? = null,
    val asr: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val quality: Float? = null,
    val protocol: String? = null,
    @SerialName("audio_ext")
    val audioExt: String? = null,
    @SerialName("video_ext")
    val videoExt: String? = null
) {
    /**
     * Whether this format contains video.
     */
    val hasVideo: Boolean
        get() = vcodec != null && vcodec != "none"

    /**
     * Whether this format contains audio.
     */
    val hasAudio: Boolean
        get() = acodec != null && acodec != "none"

    /**
     * Whether this is an audio-only format.
     */
    val isAudioOnly: Boolean
        get() = hasAudio && !hasVideo
}

/**
 * Subtitle/caption information from yt-dlp.
 *
 * @property ext Subtitle format extension (e.g., "vtt", "srt", "json3").
 * @property url URL to download the subtitle.
 * @property name Human-readable name of the subtitle track.
 */
@Serializable
data class YtDlpSubtitle(
    val ext: String? = null,
    val url: String? = null,
    val name: String? = null,
    val protocol: String? = null
)

/**
 * Options for downloading videos with yt-dlp.
 *
 * @property audioOnly Download only the audio track (faster when captions are available).
 * @property preferredFormat Preferred video format (e.g., "mp4", "webm").
 * @property maxHeight Maximum video height (e.g., 720, 1080).
 * @property rateLimitKbps Download speed limit in kilobytes per second (null = unlimited).
 * @property cookiesFile Path to a cookies file for age-restricted content.
 * @property cookiesFromBrowser Browser to extract cookies from (e.g., "firefox", "chrome").
 * @property writeSubtitles Download subtitles if available.
 * @property subtitleLanguages List of subtitle languages to download (e.g., ["en", "de"]).
 * @property embedSubtitles Embed subtitles in the video file.
 * @property noPlaylist Download only the video, not the entire playlist.
 */
@Serializable
data class YtDlpDownloadOptions(
    val audioOnly: Boolean = false,
    val preferredFormat: String = "mp4",
    val maxHeight: Int? = null,
    val rateLimitKbps: Int? = null,
    val cookiesFile: String? = null,
    val cookiesFromBrowser: String? = null,
    val writeSubtitles: Boolean = false,
    val subtitleLanguages: List<String> = emptyList(),
    val embedSubtitles: Boolean = false,
    val noPlaylist: Boolean = true
)

/**
 * Result of a caption extraction operation.
 *
 * @property language Language code of the captions.
 * @property isAutoGenerated Whether the captions are auto-generated.
 * @property format Format of the captions (e.g., "vtt", "srt").
 * @property filePath Path to the downloaded caption file.
 */
data class CaptionDownloadResult(
    val language: String,
    val isAutoGenerated: Boolean,
    val format: String,
    val filePath: String
)

/**
 * Error types that can occur during yt-dlp operations.
 */
enum class YtDlpErrorType {
    /** Video not found or unavailable. */
    VIDEO_NOT_FOUND,

    /** Video is private. */
    PRIVATE_VIDEO,

    /** Video is age-restricted and requires authentication. */
    AGE_RESTRICTED,

    /** Geographic restriction - video not available in region. */
    GEO_RESTRICTED,

    /** Video has been removed due to copyright. */
    COPYRIGHT_CLAIM,

    /** Live stream is not supported or still live. */
    LIVE_STREAM,

    /** Rate limiting from YouTube. */
    RATE_LIMITED,

    /** Network error. */
    NETWORK_ERROR,

    /** yt-dlp binary not found or not executable. */
    YTDLP_NOT_FOUND,

    /** Invalid URL format. */
    INVALID_URL,

    /** Unknown error. */
    UNKNOWN
}

/**
 * Exception thrown when a yt-dlp operation fails.
 *
 * @property errorType Categorized error type for handling.
 * @property userMessage User-friendly error message.
 * @property technicalMessage Technical details for debugging.
 */
class YtDlpException(
    val errorType: YtDlpErrorType,
    val userMessage: String,
    val technicalMessage: String,
    cause: Throwable? = null
) : Exception(userMessage, cause) {

    companion object {
        /**
         * Parses yt-dlp error output and creates an appropriate exception.
         */
        fun fromOutput(output: String, exitCode: Int): YtDlpException {
            val lowerOutput = output.lowercase()

            return when {
                "video unavailable" in lowerOutput || "is not available" in lowerOutput ->
                    YtDlpException(
                        YtDlpErrorType.VIDEO_NOT_FOUND,
                        "This video is unavailable. It may have been removed or made private.",
                        output
                    )

                "private video" in lowerOutput ->
                    YtDlpException(
                        YtDlpErrorType.PRIVATE_VIDEO,
                        "This is a private video. You need to be signed in with access.",
                        output
                    )

                "sign in" in lowerOutput && "age" in lowerOutput ||
                "age-restricted" in lowerOutput ||
                "confirm your age" in lowerOutput ->
                    YtDlpException(
                        YtDlpErrorType.AGE_RESTRICTED,
                        "This video is age-restricted. Please provide browser cookies or a cookies file to access it.",
                        output
                    )

                "not available in your country" in lowerOutput ||
                "geo" in lowerOutput && "blocked" in lowerOutput ->
                    YtDlpException(
                        YtDlpErrorType.GEO_RESTRICTED,
                        "This video is not available in your region.",
                        output
                    )

                "copyright" in lowerOutput || "blocked" in lowerOutput && "claim" in lowerOutput ->
                    YtDlpException(
                        YtDlpErrorType.COPYRIGHT_CLAIM,
                        "This video is unavailable due to a copyright claim.",
                        output
                    )

                "is live" in lowerOutput || "live stream" in lowerOutput ->
                    YtDlpException(
                        YtDlpErrorType.LIVE_STREAM,
                        "Live streams cannot be downloaded while still live.",
                        output
                    )

                "rate" in lowerOutput && "limit" in lowerOutput ||
                "429" in lowerOutput || "too many requests" in lowerOutput ->
                    YtDlpException(
                        YtDlpErrorType.RATE_LIMITED,
                        "YouTube is rate limiting requests. Please wait a few minutes and try again.",
                        output
                    )

                "network" in lowerOutput || "connection" in lowerOutput ||
                "unable to download" in lowerOutput && "http error" in lowerOutput ->
                    YtDlpException(
                        YtDlpErrorType.NETWORK_ERROR,
                        "Network error. Please check your internet connection.",
                        output
                    )

                "no such file" in lowerOutput || "not found" in lowerOutput && "yt-dlp" in lowerOutput ->
                    YtDlpException(
                        YtDlpErrorType.YTDLP_NOT_FOUND,
                        "yt-dlp is not installed. Please install it in Settings.",
                        output
                    )

                else ->
                    YtDlpException(
                        YtDlpErrorType.UNKNOWN,
                        "An error occurred while processing the video. Please try again.",
                        "Exit code: $exitCode, Output: $output"
                    )
            }
        }
    }
}
