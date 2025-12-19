package com.ericjesse.videotranslator.domain.pipeline

import com.ericjesse.videotranslator.domain.model.*
import com.ericjesse.videotranslator.domain.service.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Represents the current stage of the translation pipeline.
 */
sealed class PipelineStage {
    data object Idle : PipelineStage()
    data class Downloading(val progress: Float, val message: String) : PipelineStage()
    data class CheckingCaptions(val message: String) : PipelineStage()
    data class Transcribing(val progress: Float, val message: String) : PipelineStage()
    data class Translating(val progress: Float, val message: String) : PipelineStage()
    data class Rendering(val progress: Float, val message: String) : PipelineStage()
    data class Complete(val result: TranslationResult) : PipelineStage()
    data class Error(val stage: String, val error: String, val suggestion: String?) : PipelineStage()
    data object Cancelled : PipelineStage()
}

/**
 * Orchestrates the entire translation pipeline.
 * Coordinates the download, transcription, translation, and rendering stages.
 */
class PipelineOrchestrator(
    private val videoDownloader: VideoDownloader,
    private val transcriberService: TranscriberService,
    private val translatorService: TranslatorService,
    private val subtitleRenderer: SubtitleRenderer
) {
    
    /**
     * Executes the full translation pipeline.
     * Emits PipelineStage updates as the pipeline progresses.
     */
    fun execute(job: TranslationJob): Flow<PipelineStage> = flow {
        val startTime = System.currentTimeMillis()
        var downloadedVideoPath: String? = null
        var subtitles: Subtitles? = null
        var translatedSubtitles: Subtitles? = null
        
        try {
            // Stage 1: Download video
            emit(PipelineStage.Downloading(0f, "Starting download..."))
            logger.info { "Starting download for: ${job.videoInfo.url}" }
            
            videoDownloader.download(job.videoInfo).collect { progress ->
                emit(PipelineStage.Downloading(progress.percentage, progress.message))
            }
            
            downloadedVideoPath = videoDownloader.getDownloadedVideoPath(job.videoInfo)
            logger.info { "Download complete: $downloadedVideoPath" }
            
            // Stage 2: Check for existing captions
            emit(PipelineStage.CheckingCaptions("Checking for YouTube captions..."))
            logger.info { "Checking for captions..." }
            
            val existingCaptions = videoDownloader.extractCaptions(
                job.videoInfo, 
                job.sourceLanguage
            )
            
            if (existingCaptions != null) {
                logger.info { "Found existing captions in ${existingCaptions.language}" }
                subtitles = existingCaptions
            } else {
                // Stage 3: Transcribe audio
                logger.info { "No captions found, starting transcription..." }
                emit(PipelineStage.Transcribing(0f, "Starting transcription..."))
                
                transcriberService.transcribe(
                    downloadedVideoPath!!, 
                    job.sourceLanguage
                ).collect { progress ->
                    emit(PipelineStage.Transcribing(progress.percentage, progress.message))
                }
                
                subtitles = transcriberService.getTranscriptionResult()
                logger.info { "Transcription complete: ${subtitles?.entries?.size} entries" }
            }
            
            // Stage 4: Translate subtitles
            emit(PipelineStage.Translating(0f, "Starting translation..."))
            logger.info { "Translating from ${subtitles?.language} to ${job.targetLanguage}" }
            
            translatorService.translate(
                subtitles!!,
                job.targetLanguage
            ).collect { progress ->
                emit(PipelineStage.Translating(progress.percentage, progress.message))
            }
            
            translatedSubtitles = translatorService.getTranslationResult()
            logger.info { "Translation complete" }
            
            // Stage 5: Render output
            emit(PipelineStage.Rendering(0f, "Starting render..."))
            logger.info { "Rendering output video..." }
            
            subtitleRenderer.render(
                videoPath = downloadedVideoPath!!,
                subtitles = translatedSubtitles!!,
                outputOptions = job.outputOptions,
                videoInfo = job.videoInfo
            ).collect { progress ->
                emit(PipelineStage.Rendering(progress.percentage, progress.message))
            }
            
            val result = subtitleRenderer.getRenderResult()
            val duration = System.currentTimeMillis() - startTime
            
            logger.info { "Pipeline complete in ${duration}ms" }
            emit(PipelineStage.Complete(result.copy(duration = duration)))
            
        } catch (e: CancellationException) {
            logger.info { "Pipeline cancelled" }
            cleanup(downloadedVideoPath)
            emit(PipelineStage.Cancelled)
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Pipeline failed" }
            cleanup(downloadedVideoPath)
            emit(PipelineStage.Error(
                stage = determineFailedStage(subtitles, translatedSubtitles),
                error = e.message ?: "Unknown error",
                suggestion = getSuggestionForError(e)
            ))
        }
    }
    
    private fun determineFailedStage(
        subtitles: Subtitles?,
        translatedSubtitles: Subtitles?
    ): String = when {
        subtitles == null -> "Transcription"
        translatedSubtitles == null -> "Translation"
        else -> "Rendering"
    }
    
    private fun getSuggestionForError(e: Exception): String? {
        val message = e.message?.lowercase() ?: return null
        return when {
            "model" in message && "not found" in message -> 
                "Re-download the Whisper model in Settings"
            "network" in message || "connection" in message ->
                "Check your internet connection"
            "rate limit" in message ->
                "Wait a few minutes or try a different translation service"
            "api key" in message ->
                "Check your API key configuration in Settings"
            else -> null
        }
    }
    
    private fun cleanup(videoPath: String?) {
        videoPath?.let {
            try {
                File(it).delete()
                logger.debug { "Cleaned up temporary file: $it" }
            } catch (e: Exception) {
                logger.warn { "Failed to cleanup: ${e.message}" }
            }
        }
    }
}

/**
 * Represents progress of a pipeline stage.
 */
data class StageProgress(
    val percentage: Float, // 0.0 to 1.0
    val message: String
)
