package com.ericjesse.videotranslator.domain.service.api

import com.ericjesse.videotranslator.domain.model.Language
import com.ericjesse.videotranslator.domain.model.Subtitles
import com.ericjesse.videotranslator.domain.model.WhisperModel
import com.ericjesse.videotranslator.domain.pipeline.StageProgress
import kotlinx.coroutines.flow.Flow

/**
 * Service interface for transcribing audio to text using Whisper.
 * Provides abstraction over whisper.cpp for testability.
 */
interface TranscriptionService {

    /**
     * Transcribes audio with simplified interface.
     * Uses settings from ConfigManager.
     *
     * @param videoPath Path to the video file.
     * @param sourceLanguage Source language, or null for auto-detection.
     * @return Flow of progress updates.
     */
    fun transcribe(videoPath: String, sourceLanguage: Language?): Flow<StageProgress>

    /**
     * Returns the result of the last transcription.
     *
     * @throws IllegalStateException if no transcription has been performed.
     */
    fun getTranscriptionResult(): Subtitles

    /**
     * Checks if Whisper is available.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Gets the installed Whisper version.
     */
    suspend fun getVersion(): String?

    /**
     * Checks if GPU acceleration is available.
     */
    suspend fun isGpuAvailable(): Boolean

    /**
     * Gets all available (downloaded) models.
     */
    fun getAvailableModels(): List<WhisperModel>

    /**
     * Gets the path to a Whisper model file.
     *
     * @param model The model to get the path for.
     * @return Path to the model file, or null if not found.
     */
    fun getModelPath(model: WhisperModel): String?

    /**
     * Sets the current operation ID for temp file tracking.
     */
    fun setOperationId(operationId: String)
}
