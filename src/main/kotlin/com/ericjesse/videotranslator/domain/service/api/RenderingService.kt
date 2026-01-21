package com.ericjesse.videotranslator.domain.service.api

import com.ericjesse.videotranslator.domain.model.HardwareEncoder
import com.ericjesse.videotranslator.domain.model.OutputOptions
import com.ericjesse.videotranslator.domain.model.Subtitles
import com.ericjesse.videotranslator.domain.model.TranslationResult
import com.ericjesse.videotranslator.domain.model.VideoInfo
import com.ericjesse.videotranslator.domain.pipeline.StageProgress
import kotlinx.coroutines.flow.Flow

/**
 * Service interface for rendering subtitles into video.
 * Supports both soft subtitles (embedded) and burned-in subtitles.
 */
interface RenderingService {

    /**
     * Renders subtitles into the video with full styling and encoding options.
     *
     * @param videoPath Path to the source video file.
     * @param subtitles Translated subtitles to render.
     * @param outputOptions Output configuration (directory, subtitle type, etc.).
     * @param videoInfo Video metadata for filename generation.
     * @return Flow of progress updates.
     */
    fun render(
        videoPath: String,
        subtitles: Subtitles,
        outputOptions: OutputOptions,
        videoInfo: VideoInfo,
    ): Flow<StageProgress>

    /**
     * Returns the result of the last render.
     *
     * @throws IllegalStateException if no render has been performed.
     */
    fun getRenderResult(): TranslationResult

    /**
     * Gets list of available hardware encoders for this system.
     */
    suspend fun getAvailableEncoders(): List<HardwareEncoder>

    /**
     * Checks if a hardware encoder is available on this system.
     */
    suspend fun isEncoderAvailable(encoder: HardwareEncoder): Boolean

    /**
     * Gets video bitrate for original quality encoding.
     *
     * @param videoPath Path to the video file.
     * @return Bitrate in kbps, or null if it couldn't be determined.
     */
    suspend fun getVideoBitrate(videoPath: String): Int?
}
