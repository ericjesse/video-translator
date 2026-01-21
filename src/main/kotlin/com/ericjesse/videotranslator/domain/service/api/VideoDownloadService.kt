package com.ericjesse.videotranslator.domain.service.api

import com.ericjesse.videotranslator.domain.model.Language
import com.ericjesse.videotranslator.domain.model.Subtitles
import com.ericjesse.videotranslator.domain.model.VideoInfo
import com.ericjesse.videotranslator.domain.model.YtDlpDownloadOptions
import com.ericjesse.videotranslator.domain.pipeline.StageProgress
import com.ericjesse.videotranslator.domain.validation.VideoValidationResult
import kotlinx.coroutines.flow.Flow

/**
 * Service interface for downloading YouTube videos and extracting captions.
 * Provides abstraction over the yt-dlp binary for testability.
 */
interface VideoDownloadService {

    /**
     * Validates if the given URL is a valid YouTube URL.
     *
     * @param url The URL to validate.
     * @return true if the URL is a valid YouTube video URL.
     */
    fun isValidYouTubeUrl(url: String): Boolean

    /**
     * Extracts the video ID from a YouTube URL.
     *
     * @param url The YouTube URL.
     * @return The video ID, or null if the URL is invalid.
     */
    fun extractVideoId(url: String): String?

    /**
     * Fetches simplified video metadata.
     *
     * @param url The YouTube URL.
     * @param options Optional download options.
     * @return Simplified video information.
     */
    suspend fun fetchVideoInfo(
        url: String,
        options: YtDlpDownloadOptions = YtDlpDownloadOptions(),
    ): VideoInfo

    /**
     * Downloads a YouTube video.
     * Emits progress updates as the download proceeds.
     *
     * @param videoInfo Video to download.
     * @param options Download options (format, speed limit, cookies, etc.).
     * @return Flow of progress updates.
     */
    fun download(
        videoInfo: VideoInfo,
        options: YtDlpDownloadOptions = YtDlpDownloadOptions(),
    ): Flow<StageProgress>

    /**
     * Downloads only audio from a video.
     * Useful when captions are available and video isn't needed for processing.
     *
     * @param videoInfo Video to extract audio from.
     * @param options Download options.
     * @return Flow of progress updates.
     */
    fun downloadAudioOnly(
        videoInfo: VideoInfo,
        options: YtDlpDownloadOptions = YtDlpDownloadOptions(),
    ): Flow<StageProgress>

    /**
     * Attempts to extract existing captions from the video.
     * Returns null if no captions are available.
     *
     * @param videoInfo Video to extract captions from.
     * @param preferredLanguage Preferred language for captions.
     * @return Subtitles if available, null otherwise.
     */
    suspend fun extractCaptions(videoInfo: VideoInfo, preferredLanguage: Language?): Subtitles?

    /**
     * Checks if yt-dlp is installed and available.
     *
     * @return true if yt-dlp is available.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Gets the installed yt-dlp version.
     *
     * @return Version string, or null if not available.
     */
    suspend fun getVersion(): String?

    /**
     * Validates video information for edge cases.
     *
     * @param videoInfo The video info to validate.
     * @return VideoValidationResult indicating if the video can be processed.
     */
    fun validateVideo(videoInfo: VideoInfo): VideoValidationResult

    /**
     * Gets the path where a video would be downloaded.
     *
     * @param videoInfo Video information.
     * @param audioOnly Whether audio-only format is used.
     * @return Absolute path to the downloaded file.
     */
    fun getDownloadedVideoPath(videoInfo: VideoInfo, audioOnly: Boolean = false): String
}
