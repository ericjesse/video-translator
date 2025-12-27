package com.ericjesse.videotranslator.domain.pipeline

import com.ericjesse.videotranslator.domain.model.*
import com.ericjesse.videotranslator.domain.service.*
import com.ericjesse.videotranslator.domain.validation.*
import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.UUID

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
 * Orchestrates the entire translation pipeline with robust error handling.
 *
 * Features:
 * - Stage-specific error recovery with fallback strategies
 * - Checkpoint/resume capability for intermediate results
 * - Proper resource cleanup on cancellation
 * - Detailed error context for user-friendly messages
 * - Structured log events for the log panel
 *
 * @property videoDownloader Service for downloading videos.
 * @property transcriberService Service for transcribing audio.
 * @property translatorService Service for translating subtitles.
 * @property subtitleRenderer Service for rendering subtitles to video.
 * @property configManager Configuration manager for settings.
 * @property resourceManager Resource manager for memory tracking.
 * @property tempFileManager Temp file manager for cleanup.
 * @property diskSpaceChecker Disk space checker.
 * @property checkpointDir Directory for saving checkpoints.
 */
class PipelineOrchestrator(
    private val videoDownloader: VideoDownloader,
    private val transcriberService: TranscriberService,
    private val translatorService: TranslatorService,
    private val subtitleRenderer: SubtitleRenderer,
    private val configManager: ConfigManager? = null,
    private val resourceManager: ResourceManager? = null,
    private val tempFileManager: TempFileManager? = null,
    private val diskSpaceChecker: DiskSpaceChecker? = null,
    private val checkpointDir: File = File(System.getProperty("user.home"), ".video-translator/checkpoints")
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val logEvents = mutableListOf<PipelineLogEvent>()
    private val tempFiles = mutableListOf<File>()
    private var currentCheckpoint: PipelineCheckpoint? = null

    // Validators
    private val videoValidator = VideoValidator()
    private val captionValidator = CaptionValidator()
    private val translationValidator = TranslationValidator()
    private val outputValidator = OutputValidator()

    init {
        checkpointDir.mkdirs()
    }

    /**
     * Gets all log events from the current or last pipeline run.
     */
    fun getLogEvents(): List<PipelineLogEvent> = logEvents.toList()

    /**
     * Clears log events.
     */
    fun clearLogEvents() {
        logEvents.clear()
    }

    /**
     * Executes the full translation pipeline.
     * Emits PipelineStage updates as the pipeline progresses.
     *
     * @param job The translation job to execute.
     * @param checkpoint Optional checkpoint to resume from.
     * @return Flow of pipeline stage updates.
     */
    fun execute(job: TranslationJob, checkpoint: PipelineCheckpoint? = null): Flow<PipelineStage> = channelFlow {
        val jobId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        // Start resource tracking
        resourceManager?.startOperationTracking(jobId)
        resourceManager?.startMonitoring(
            onHighMemory = { state ->
                emitLog(PipelineLogEvent.Warning(
                    stage = null,
                    message = "High memory usage: ${state.getSummary()}",
                    suggestion = "Consider using a smaller Whisper model"
                ))
            },
            onCriticalMemory = { state ->
                emitLog(PipelineLogEvent.Warning(
                    stage = null,
                    message = "Critical memory usage: ${state.getSummary()}",
                    suggestion = "Pipeline may be cancelled to prevent system instability"
                ))
            }
        )

        // Pipeline state
        var downloadedVideoPath: String? = checkpoint?.downloadedVideoPath
        var subtitles: Subtitles? = checkpoint?.subtitles
        var translatedSubtitles: Subtitles? = checkpoint?.translatedSubtitles

        // Determine starting stage
        val startStage = checkpoint?.getNextStage() ?: PipelineStageName.DOWNLOAD

        logEvents.clear()
        emitLog(PipelineLogEvent.Info(
            stage = null,
            message = "Starting pipeline",
            details = mapOf(
                "jobId" to jobId,
                "videoId" to job.videoInfo.id,
                "resuming" to (checkpoint != null).toString(),
                "startStage" to startStage.displayName
            )
        ))

        try {
            // Pre-flight resource checks
            val resourceCheckResult = performPreflightResourceChecks(job)
            if (resourceCheckResult != null) {
                send(resourceCheckResult)
                return@channelFlow
            }
            // Stage 1: Download video (if not resuming past this stage)
            if (startStage.order <= PipelineStageName.DOWNLOAD.order) {
                val downloadResult = executeDownloadStage(job) { send(it) }
                when (downloadResult) {
                    is StageResult.Success -> {
                        downloadedVideoPath = downloadResult.data
                        saveCheckpoint(jobId, PipelineStageName.DOWNLOAD, downloadedVideoPath, null, null, job)
                    }
                    is StageResult.Failure -> {
                        emitError(downloadResult.error)
                        send(createErrorStage(downloadResult.error))
                        return@channelFlow
                    }
                    else -> {}
                }
            }

            // Stage 2: Check for existing captions
            if (startStage.order <= PipelineStageName.CAPTION_CHECK.order) {
                send(PipelineStage.CheckingCaptions("Checking for YouTube captions..."))
                emitLog(PipelineLogEvent.StageTransition(
                    stage = PipelineStageName.CAPTION_CHECK,
                    message = "Checking for existing captions",
                    fromStage = PipelineStageName.DOWNLOAD
                ))

                val existingCaptions = try {
                    videoDownloader.extractCaptions(job.videoInfo, job.sourceLanguage)
                } catch (e: Exception) {
                    emitLog(PipelineLogEvent.Warning(
                        stage = PipelineStageName.CAPTION_CHECK,
                        message = "Failed to extract captions, will use transcription",
                        suggestion = "This is normal - the video may not have captions"
                    ))
                    null
                }

                if (existingCaptions != null) {
                    subtitles = existingCaptions
                    emitLog(PipelineLogEvent.Info(
                        stage = PipelineStageName.CAPTION_CHECK,
                        message = "Found existing captions",
                        details = mapOf(
                            "language" to existingCaptions.language.displayName,
                            "entries" to existingCaptions.entries.size.toString()
                        )
                    ))
                    saveCheckpoint(jobId, PipelineStageName.CAPTION_CHECK, downloadedVideoPath, subtitles, null, job)
                }
            }

            // Stage 3: Transcribe audio (if no captions found and not resuming past this)
            if (subtitles == null && startStage.order <= PipelineStageName.TRANSCRIPTION.order) {
                val transcribeResult = executeTranscriptionStage(downloadedVideoPath!!, job) { send(it) }
                when (transcribeResult) {
                    is StageResult.Success -> {
                        subtitles = transcribeResult.data
                        saveCheckpoint(jobId, PipelineStageName.TRANSCRIPTION, downloadedVideoPath, subtitles, null, job)
                    }
                    is StageResult.Failure -> {
                        emitError(transcribeResult.error)
                        send(createErrorStage(transcribeResult.error))
                        return@channelFlow
                    }
                    is StageResult.Skipped -> {
                        emitLog(PipelineLogEvent.Info(
                            stage = PipelineStageName.TRANSCRIPTION,
                            message = "Transcription skipped: ${transcribeResult.reason}"
                        ))
                    }
                    else -> {}
                }
            }

            // Stage 4: Translate subtitles
            if (startStage.order <= PipelineStageName.TRANSLATION.order && translatedSubtitles == null) {
                val translateResult = executeTranslationStage(subtitles!!, job) { send(it) }
                when (translateResult) {
                    is StageResult.Success -> {
                        translatedSubtitles = translateResult.data
                        saveCheckpoint(jobId, PipelineStageName.TRANSLATION, downloadedVideoPath, subtitles, translatedSubtitles, job)
                    }
                    is StageResult.Failure -> {
                        emitError(translateResult.error)
                        send(createErrorStage(translateResult.error))
                        return@channelFlow
                    }
                    else -> {}
                }
            }

            // Stage 5: Render output
            val renderResult = executeRenderStage(downloadedVideoPath!!, translatedSubtitles!!, job) { send(it) }
            when (renderResult) {
                is StageResult.Success -> {
                    val duration = System.currentTimeMillis() - startTime
                    val result = renderResult.data.copy(duration = duration)

                    emitLog(PipelineLogEvent.Info(
                        stage = PipelineStageName.RENDERING,
                        message = "Pipeline complete",
                        details = mapOf(
                            "duration" to "${duration}ms",
                            "outputFile" to result.videoFile
                        )
                    ))

                    // Cleanup checkpoint on success
                    deleteCheckpoint(jobId)

                    send(PipelineStage.Complete(result))
                }
                is StageResult.Failure -> {
                    emitError(renderResult.error)
                    send(createErrorStage(renderResult.error))
                }
                else -> {}
            }

        } catch (e: CancellationException) {
            emitLog(PipelineLogEvent.Warning(
                stage = null,
                message = "Pipeline cancelled by user"
            ))
            cleanupResources(jobId)
            send(PipelineStage.Cancelled)
            throw e
        } catch (e: Exception) {
            val error = ErrorMapper.mapException(e, determineCurrentStage(subtitles, translatedSubtitles))
            emitError(error)
            cleanupResources(jobId)
            send(createErrorStage(error))
        } finally {
            resourceManager?.stopMonitoring()
            resourceManager?.stopOperationTracking(jobId)
        }
    }

    /**
     * Performs pre-flight validation and resource checks before starting the pipeline.
     * Returns an error stage if validation fails or resources are insufficient, null otherwise.
     */
    private suspend fun performPreflightResourceChecks(job: TranslationJob): PipelineStage.Error? {
        // 1. Validate video duration and properties
        val videoValidation = videoValidator.validate(job.videoInfo)
        when (videoValidation) {
            is VideoValidationResult.Invalid -> {
                val error = videoValidation.error
                emitLog(PipelineLogEvent.Error(
                    stage = null,
                    message = "Video validation failed: ${error.message}",
                    error = PipelineError(
                        code = when (error) {
                            is VideoError.TooShort -> ErrorCode.INVALID_URL
                            is VideoError.TooLong -> ErrorCode.INVALID_URL
                            is VideoError.LiveStream -> ErrorCode.VIDEO_UNAVAILABLE
                            is VideoError.PrivateVideo -> ErrorCode.PRIVATE_VIDEO
                            is VideoError.AgeRestricted -> ErrorCode.AGE_RESTRICTED
                            is VideoError.GeoRestricted -> ErrorCode.REGION_BLOCKED
                            else -> ErrorCode.VIDEO_UNAVAILABLE
                        },
                        stage = PipelineStageName.DOWNLOAD,
                        message = error.message,
                        suggestion = error.suggestion,
                        retryable = error.retryable
                    )
                ))
                return PipelineStage.Error(
                    stage = "Pre-flight",
                    error = error.message,
                    suggestion = error.suggestion
                )
            }
            is VideoValidationResult.ValidWithWarning -> {
                val warning = videoValidation.warning
                emitLog(PipelineLogEvent.Warning(
                    stage = null,
                    message = warning.message,
                    suggestion = warning.suggestion
                ))
            }
            is VideoValidationResult.Valid -> {
                emitLog(PipelineLogEvent.Debug(
                    stage = null,
                    message = "Video validation passed"
                ))
            }
        }

        // 2. Validate output path
        val outputValidation = outputValidator.validateOutput(
            directory = job.outputOptions.outputDirectory,
            filename = OutputValidator.generateTranslatedFilename(
                job.videoInfo.title,
                job.targetLanguage.code
            ),
            fileExistsAction = FileExistsAction.RENAME, // Auto-rename for pipeline
            estimatedSizeMB = DiskSpaceRequirements.estimateForTranslation(
                job.videoInfo.duration / 1000,
                includeDownload = true,
                includeRender = job.outputOptions.subtitleType == SubtitleType.BURNED_IN
            )
        )
        when (outputValidation) {
            is OutputValidationResult.Invalid -> {
                val error = outputValidation.error
                emitLog(PipelineLogEvent.Error(
                    stage = null,
                    message = "Output validation failed: ${error.message}",
                    error = PipelineError(
                        code = when (error) {
                            is OutputError.PermissionDenied -> ErrorCode.FILE_NOT_FOUND
                            is OutputError.DiskFull -> ErrorCode.DISK_FULL
                            else -> ErrorCode.FILE_NOT_FOUND
                        },
                        stage = PipelineStageName.RENDERING,
                        message = error.message,
                        suggestion = error.suggestion,
                        retryable = false
                    )
                ))
                return PipelineStage.Error(
                    stage = "Pre-flight",
                    error = error.message,
                    suggestion = error.suggestion
                )
            }
            is OutputValidationResult.RequiresAction -> {
                val action = outputValidation.action
                when (action) {
                    is OutputAction.DirectoryMissing -> {
                        // Try to create directory
                        val createResult = outputValidator.ensureDirectoryExists(action.directory)
                        if (createResult is OutputValidationResult.Invalid) {
                            return PipelineStage.Error(
                                stage = "Pre-flight",
                                error = "Cannot create output directory: ${action.directory}",
                                suggestion = "Check permissions or choose a different location"
                            )
                        }
                    }
                    is OutputAction.FileExists -> {
                        // Logged as info - we'll use renamed file
                        emitLog(PipelineLogEvent.Info(
                            stage = null,
                            message = "Output file exists, will use alternative name"
                        ))
                    }
                }
            }
            is OutputValidationResult.ValidWithWarnings -> {
                outputValidation.warnings.forEach { warning ->
                    emitLog(PipelineLogEvent.Warning(
                        stage = null,
                        message = warning.message,
                        suggestion = warning.suggestion
                    ))
                }
            }
            is OutputValidationResult.Valid -> {
                emitLog(PipelineLogEvent.Debug(
                    stage = null,
                    message = "Output path validation passed"
                ))
            }
        }

        // 3. Validate translation (check if source = target)
        val translationValidation = translationValidator.validateBeforeTranslation(
            subtitles = Subtitles(emptyList(), job.sourceLanguage ?: Language.ENGLISH),
            sourceLanguage = job.sourceLanguage,
            targetLanguage = job.targetLanguage
        )
        when (translationValidation) {
            is TranslationValidationResult.Skip -> {
                emitLog(PipelineLogEvent.Warning(
                    stage = PipelineStageName.TRANSLATION,
                    message = translationValidation.reason.message,
                    suggestion = "Translation will be skipped"
                ))
                // Don't block - just warn. The translation stage will handle skipping.
            }
            is TranslationValidationResult.ValidWithWarnings -> {
                translationValidation.warnings.forEach { warning ->
                    emitLog(PipelineLogEvent.Warning(
                        stage = PipelineStageName.TRANSLATION,
                        message = warning.message,
                        suggestion = warning.suggestion
                    ))
                }
            }
            else -> {}
        }

        // 4. Check disk space before download
        diskSpaceChecker?.let { checker ->
            val videoDuration = job.videoInfo.duration
            val includeRender = job.outputOptions.subtitleType == SubtitleType.BURNED_IN

            val spaceCheck = checker.checkSpaceForTranslation(
                videoDurationSeconds = videoDuration,
                includeDownload = true,
                includeRender = includeRender
            )

            when (spaceCheck) {
                is DiskSpaceCheckResult.InsufficientSpace -> {
                    emitLog(PipelineLogEvent.Error(
                        stage = null,
                        message = "Insufficient disk space",
                        error = PipelineError(
                            code = ErrorCode.DISK_FULL,
                            stage = PipelineStageName.DOWNLOAD,
                            message = spaceCheck.getMessage(),
                            suggestion = spaceCheck.suggestions.firstOrNull(),
                            retryable = false
                        )
                    ))
                    return PipelineStage.Error(
                        stage = "Pre-flight",
                        error = spaceCheck.getMessage(),
                        suggestion = spaceCheck.suggestions.firstOrNull()
                    )
                }
                is DiskSpaceCheckResult.LowSpace -> {
                    emitLog(PipelineLogEvent.Warning(
                        stage = null,
                        message = spaceCheck.warningMessage,
                        suggestion = "Consider freeing up disk space before proceeding"
                    ))
                    // Proceed with warning - don't block
                }
                is DiskSpaceCheckResult.Sufficient -> {
                    emitLog(PipelineLogEvent.Debug(
                        stage = null,
                        message = "Disk space check passed"
                    ))
                }
            }
        }

        // Check memory for Whisper transcription
        resourceManager?.let { rm ->
            // Get configured Whisper model or default
            val preferredModel = configManager?.getSettings()?.transcription?.whisperModel ?: "small"
            val bestModel = rm.getBestAvailableWhisperModel(preferredModel)

            if (bestModel != preferredModel) {
                emitLog(PipelineLogEvent.Warning(
                    stage = PipelineStageName.TRANSCRIPTION,
                    message = "Degrading Whisper model from $preferredModel to $bestModel due to memory constraints",
                    suggestion = "Close other applications to use higher quality model"
                ))
            }

            val memoryCheck = rm.checkResourcesForWhisperModel(bestModel)
            when (memoryCheck) {
                is ResourceCheckResult.MemoryLimitExceeded -> {
                    emitLog(PipelineLogEvent.Error(
                        stage = PipelineStageName.TRANSCRIPTION,
                        message = "Insufficient memory for transcription",
                        error = PipelineError(
                            code = ErrorCode.INSUFFICIENT_MEMORY,
                            stage = PipelineStageName.TRANSCRIPTION,
                            message = memoryCheck.getMessage(),
                            suggestion = "Close other applications or use a smaller Whisper model",
                            retryable = false
                        )
                    ))
                    return PipelineStage.Error(
                        stage = "Pre-flight",
                        error = memoryCheck.getMessage(),
                        suggestion = "Close other applications or use a smaller Whisper model"
                    )
                }
                is ResourceCheckResult.LowMemory -> {
                    emitLog(PipelineLogEvent.Warning(
                        stage = PipelineStageName.TRANSCRIPTION,
                        message = memoryCheck.getMessage()
                    ))
                }
                else -> {}
            }
        }

        return null
    }

    // ==================== Stage Execution Methods ====================

    private suspend fun executeDownloadStage(
        job: TranslationJob,
        emit: suspend (PipelineStage) -> Unit
    ): StageResult<String> {
        val stage = PipelineStageName.DOWNLOAD
        val startTime = System.currentTimeMillis()
        var lastError: PipelineError? = null
        var attemptNumber = 0

        val fallbackFormats = listOf(
            VideoFormat.BEST,
            VideoFormat.MP4_720P,
            VideoFormat.MP4_480P
        )

        for ((index, format) in fallbackFormats.withIndex()) {
            attemptNumber++

            try {
                emit(PipelineStage.Downloading(0f, "Starting download${if (index > 0) " (format: ${format.displayName})" else ""}..."))
                emitLog(PipelineLogEvent.StageTransition(
                    stage = stage,
                    message = "Starting download",
                    fromStage = null,
                    progress = 0f
                ))

                if (index > 0) {
                    emitLog(PipelineLogEvent.RecoveryAttempt(
                        stage = stage,
                        message = "Retrying with ${format.displayName}",
                        attemptNumber = attemptNumber,
                        maxAttempts = fallbackFormats.size,
                        strategy = "format_fallback"
                    ))
                }

                videoDownloader.download(job.videoInfo).collect { progress ->
                    emit(PipelineStage.Downloading(progress.percentage, progress.message))
                }

                val downloadedPath = videoDownloader.getDownloadedVideoPath(job.videoInfo)
                trackTempFile(downloadedPath)

                val duration = System.currentTimeMillis() - startTime
                emitLog(PipelineLogEvent.Metric(
                    stage = stage,
                    message = "Download completed",
                    name = "download_duration",
                    value = duration.toDouble(),
                    unit = "ms"
                ))

                return StageResult.Success(
                    data = downloadedPath,
                    stage = stage,
                    durationMs = duration,
                    metrics = StageMetrics(durationMs = duration, retryCount = attemptNumber - 1)
                )

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = ErrorMapper.mapException(e, stage)
                emitLog(PipelineLogEvent.Warning(
                    stage = stage,
                    message = "Download attempt ${attemptNumber} failed: ${e.message}",
                    suggestion = if (index < fallbackFormats.size - 1) "Trying fallback format..." else null
                ))

                // Only continue to fallback for certain error types
                if (!lastError.retryable && lastError.code != ErrorCode.ENCODING_FAILED) {
                    break
                }
            }
        }

        return StageResult.Failure(
            stage = stage,
            error = lastError!!,
            recoveryStrategy = RecoveryStrategy.Abort,
            attemptNumber = attemptNumber
        )
    }

    private suspend fun executeTranscriptionStage(
        videoPath: String,
        job: TranslationJob,
        emit: suspend (PipelineStage) -> Unit
    ): StageResult<Subtitles> {
        val stage = PipelineStageName.TRANSCRIPTION
        val startTime = System.currentTimeMillis()
        var lastError: PipelineError? = null
        var attemptNumber = 0

        // Fallback models in order of decreasing size
        val fallbackModels = listOf(
            null, // Use configured model first
            WhisperModel.SMALL,
            WhisperModel.BASE,
            WhisperModel.TINY
        )

        for ((index, fallbackModel) in fallbackModels.withIndex()) {
            attemptNumber++

            try {
                emit(PipelineStage.Transcribing(0f, "Starting transcription..."))
                emitLog(PipelineLogEvent.StageTransition(
                    stage = stage,
                    message = "Starting transcription${fallbackModel?.let { " with ${it.displayName} model" } ?: ""}",
                    fromStage = PipelineStageName.CAPTION_CHECK
                ))

                if (index > 0) {
                    emitLog(PipelineLogEvent.RecoveryAttempt(
                        stage = stage,
                        message = "Retrying with ${fallbackModel?.displayName ?: "default"} model",
                        attemptNumber = attemptNumber,
                        maxAttempts = fallbackModels.size,
                        strategy = "model_fallback"
                    ))
                }

                transcriberService.transcribe(videoPath, job.sourceLanguage).collect { progress ->
                    emit(PipelineStage.Transcribing(progress.percentage, progress.message))
                }

                val subtitles = transcriberService.getTranscriptionResult()
                val duration = System.currentTimeMillis() - startTime

                emitLog(PipelineLogEvent.Info(
                    stage = stage,
                    message = "Transcription complete",
                    details = mapOf(
                        "entries" to subtitles.entries.size.toString(),
                        "language" to subtitles.language.displayName,
                        "duration" to "${duration}ms"
                    )
                ))

                return StageResult.Success(
                    data = subtitles,
                    stage = stage,
                    durationMs = duration,
                    metrics = StageMetrics(
                        durationMs = duration,
                        itemsProcessed = subtitles.entries.size,
                        retryCount = attemptNumber - 1
                    )
                )

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = ErrorMapper.mapException(e, stage)
                emitLog(PipelineLogEvent.Warning(
                    stage = stage,
                    message = "Transcription attempt ${attemptNumber} failed: ${e.message}",
                    suggestion = if (index < fallbackModels.size - 1) "Trying smaller model..." else null
                ))

                // Only continue to fallback for memory errors
                if (lastError.code != ErrorCode.INSUFFICIENT_MEMORY) {
                    break
                }
            }
        }

        return StageResult.Failure(
            stage = stage,
            error = lastError!!,
            recoveryStrategy = RecoveryStrategy.Abort,
            attemptNumber = attemptNumber
        )
    }

    private suspend fun executeTranslationStage(
        subtitles: Subtitles,
        job: TranslationJob,
        emit: suspend (PipelineStage) -> Unit
    ): StageResult<Subtitles> {
        val stage = PipelineStageName.TRANSLATION
        val startTime = System.currentTimeMillis()
        var lastError: PipelineError? = null
        val maxRetries = 3

        for (attempt in 1..maxRetries) {
            try {
                emit(PipelineStage.Translating(0f, "Starting translation..."))
                emitLog(PipelineLogEvent.StageTransition(
                    stage = stage,
                    message = "Translating to ${job.targetLanguage.displayName}",
                    fromStage = PipelineStageName.TRANSCRIPTION
                ))

                if (attempt > 1) {
                    emitLog(PipelineLogEvent.RecoveryAttempt(
                        stage = stage,
                        message = "Retry attempt $attempt",
                        attemptNumber = attempt,
                        maxAttempts = maxRetries,
                        strategy = "retry"
                    ))
                    delay(2000L * attempt) // Exponential backoff
                }

                translatorService.translate(subtitles, job.targetLanguage).collect { progress ->
                    emit(PipelineStage.Translating(progress.percentage, progress.message))
                }

                val translated = translatorService.getTranslationResult()
                val duration = System.currentTimeMillis() - startTime

                emitLog(PipelineLogEvent.Info(
                    stage = stage,
                    message = "Translation complete",
                    details = mapOf(
                        "entries" to translated.entries.size.toString(),
                        "targetLanguage" to job.targetLanguage.displayName,
                        "duration" to "${duration}ms"
                    )
                ))

                return StageResult.Success(
                    data = translated,
                    stage = stage,
                    durationMs = duration,
                    metrics = StageMetrics(
                        durationMs = duration,
                        itemsProcessed = translated.entries.size,
                        retryCount = attempt - 1
                    )
                )

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = ErrorMapper.mapException(e, stage)
                emitLog(PipelineLogEvent.Warning(
                    stage = stage,
                    message = "Translation attempt $attempt failed: ${e.message}"
                ))

                // Only retry for retryable errors
                if (!lastError.retryable) {
                    break
                }
            }
        }

        return StageResult.Failure(
            stage = stage,
            error = lastError!!,
            recoveryStrategy = RecoveryStrategy.Abort,
            attemptNumber = maxRetries
        )
    }

    private suspend fun executeRenderStage(
        videoPath: String,
        subtitles: Subtitles,
        job: TranslationJob,
        emit: suspend (PipelineStage) -> Unit
    ): StageResult<TranslationResult> {
        val stage = PipelineStageName.RENDERING
        val startTime = System.currentTimeMillis()
        var lastError: PipelineError? = null
        var attemptNumber = 0

        // Fallback encoders: try configured, then software
        val encoderOptions = listOf(
            null, // Use configured encoder
            HardwareEncoder.NONE // Software fallback
        )

        for ((index, encoder) in encoderOptions.withIndex()) {
            attemptNumber++

            try {
                emit(PipelineStage.Rendering(0f, "Starting render..."))
                emitLog(PipelineLogEvent.StageTransition(
                    stage = stage,
                    message = "Rendering output video${encoder?.let { " with ${it.displayName}" } ?: ""}",
                    fromStage = PipelineStageName.TRANSLATION
                ))

                if (index > 0) {
                    emitLog(PipelineLogEvent.RecoveryAttempt(
                        stage = stage,
                        message = "Falling back to software encoding",
                        attemptNumber = attemptNumber,
                        maxAttempts = encoderOptions.size,
                        strategy = "encoder_fallback"
                    ))
                }

                // Apply encoder override if falling back
                val outputOptions = if (encoder != null && job.outputOptions.renderOptions != null) {
                    job.outputOptions.copy(
                        renderOptions = job.outputOptions.renderOptions.copy(
                            encoding = job.outputOptions.renderOptions.encoding.copy(encoder = encoder)
                        )
                    )
                } else {
                    job.outputOptions
                }

                subtitleRenderer.render(
                    videoPath = videoPath,
                    subtitles = subtitles,
                    outputOptions = outputOptions,
                    videoInfo = job.videoInfo
                ).collect { progress ->
                    emit(PipelineStage.Rendering(progress.percentage, progress.message))
                }

                val result = subtitleRenderer.getRenderResult()
                val duration = System.currentTimeMillis() - startTime

                return StageResult.Success(
                    data = result,
                    stage = stage,
                    durationMs = duration,
                    metrics = StageMetrics(durationMs = duration, retryCount = attemptNumber - 1)
                )

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = ErrorMapper.mapException(e, stage)
                emitLog(PipelineLogEvent.Warning(
                    stage = stage,
                    message = "Render attempt ${attemptNumber} failed: ${e.message}",
                    suggestion = if (index < encoderOptions.size - 1) "Trying software encoding..." else null
                ))

                // Only continue to fallback for encoding errors
                if (lastError.code != ErrorCode.ENCODING_FAILED) {
                    break
                }
            }
        }

        return StageResult.Failure(
            stage = stage,
            error = lastError!!,
            recoveryStrategy = RecoveryStrategy.Abort,
            attemptNumber = attemptNumber
        )
    }

    // ==================== Checkpoint Management ====================

    private fun saveCheckpoint(
        jobId: String,
        stage: PipelineStageName,
        videoPath: String?,
        subtitles: Subtitles?,
        translatedSubtitles: Subtitles?,
        job: TranslationJob
    ) {
        try {
            val checkpoint = PipelineCheckpoint(
                jobId = jobId,
                lastCompletedStage = stage,
                downloadedVideoPath = videoPath,
                subtitles = subtitles,
                translatedSubtitles = translatedSubtitles,
                videoInfo = job.videoInfo,
                targetLanguage = job.targetLanguage,
                outputOptions = job.outputOptions
            )

            val checkpointFile = File(checkpointDir, "$jobId.json")
            checkpointFile.writeText(json.encodeToString(checkpoint))
            currentCheckpoint = checkpoint

            emitLog(PipelineLogEvent.CheckpointSaved(
                stage = stage,
                checkpointPath = checkpointFile.absolutePath
            ))

            logger.debug { "Checkpoint saved for stage: $stage" }
        } catch (e: Exception) {
            logger.warn { "Failed to save checkpoint: ${e.message}" }
        }
    }

    private fun deleteCheckpoint(jobId: String) {
        try {
            val checkpointFile = File(checkpointDir, "$jobId.json")
            if (checkpointFile.exists()) {
                checkpointFile.delete()
                logger.debug { "Checkpoint deleted: $jobId" }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to delete checkpoint: ${e.message}" }
        }
    }

    /**
     * Loads a checkpoint for resuming a pipeline.
     *
     * @param jobId The job ID to load.
     * @return The checkpoint if found and valid, null otherwise.
     */
    fun loadCheckpoint(jobId: String): PipelineCheckpoint? {
        return try {
            val checkpointFile = File(checkpointDir, "$jobId.json")
            if (!checkpointFile.exists()) return null

            val checkpoint: PipelineCheckpoint = json.decodeFromString(checkpointFile.readText())
            if (checkpoint.isValid()) checkpoint else null
        } catch (e: Exception) {
            logger.warn { "Failed to load checkpoint: ${e.message}" }
            null
        }
    }

    /**
     * Lists all available checkpoints.
     *
     * @return List of valid checkpoint job IDs.
     */
    fun listCheckpoints(): List<String> {
        return checkpointDir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val checkpoint: PipelineCheckpoint = json.decodeFromString(file.readText())
                    if (checkpoint.isValid()) checkpoint.jobId else null
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()
    }

    // ==================== Cleanup ====================

    private fun trackTempFile(path: String, jobId: String = "unknown") {
        val file = File(path)
        tempFiles.add(file)

        // Also track with TempFileManager if available
        tempFileManager?.trackFile(
            path = file.toPath(),
            operationId = jobId,
            isDirectory = file.isDirectory,
            description = "Pipeline temp file"
        )
    }

    private suspend fun cleanupResources(jobId: String) {
        logger.debug { "Cleaning up resources for job: $jobId" }

        // Use TempFileManager if available for managed cleanup
        tempFileManager?.let { tfm ->
            val deleted = tfm.cleanupOperation(jobId, "pipeline cleanup")
            logger.info { "TempFileManager cleaned up $deleted files for job $jobId" }
        }

        // Also clean up local tracking (for backward compatibility)
        cleanup()
    }

    private fun cleanup() {
        logger.debug { "Cleaning up ${tempFiles.size} temporary files" }

        for (file in tempFiles) {
            try {
                if (file.exists()) {
                    if (file.isDirectory) {
                        file.deleteRecursively()
                    } else {
                        file.delete()
                    }
                    logger.debug { "Cleaned up: ${file.absolutePath}" }
                }
            } catch (e: Exception) {
                logger.warn { "Failed to cleanup ${file.absolutePath}: ${e.message}" }
            }
        }

        tempFiles.clear()
    }

    // ==================== Logging Helpers ====================

    private fun emitLog(event: PipelineLogEvent) {
        logEvents.add(event)
        when (event) {
            is PipelineLogEvent.Error -> logger.error { "[${event.stage?.displayName ?: "Pipeline"}] ${event.message}" }
            is PipelineLogEvent.Warning -> logger.warn { "[${event.stage?.displayName ?: "Pipeline"}] ${event.message}" }
            is PipelineLogEvent.Info -> logger.info { "[${event.stage?.displayName ?: "Pipeline"}] ${event.message}" }
            is PipelineLogEvent.Debug -> logger.debug { "[${event.stage?.displayName ?: "Pipeline"}] ${event.message}" }
            is PipelineLogEvent.StageTransition -> logger.info { "[${event.stage.displayName}] ${event.message}" }
            is PipelineLogEvent.RecoveryAttempt -> logger.warn { "[${event.stage.displayName}] ${event.message} (attempt ${event.attemptNumber}/${event.maxAttempts})" }
            is PipelineLogEvent.CheckpointSaved -> logger.debug { "[${event.stage.displayName}] Checkpoint saved" }
            is PipelineLogEvent.Metric -> logger.debug { "[${event.stage?.displayName ?: "Pipeline"}] ${event.name}: ${event.value} ${event.unit}" }
        }
    }

    private fun emitError(error: PipelineError) {
        emitLog(PipelineLogEvent.Error(
            stage = error.stage,
            message = error.message,
            error = error,
            stackTrace = error.technicalDetails
        ))
    }

    private fun createErrorStage(error: PipelineError): PipelineStage.Error {
        return PipelineStage.Error(
            stage = error.stage.displayName,
            error = error.message,
            suggestion = error.suggestion
        )
    }

    private fun determineCurrentStage(
        subtitles: Subtitles?,
        translatedSubtitles: Subtitles?
    ): PipelineStageName = when {
        subtitles == null -> PipelineStageName.TRANSCRIPTION
        translatedSubtitles == null -> PipelineStageName.TRANSLATION
        else -> PipelineStageName.RENDERING
    }
}

/**
 * Represents progress of a pipeline stage.
 */
data class StageProgress(
    val percentage: Float, // 0.0 to 1.0
    val message: String
)
