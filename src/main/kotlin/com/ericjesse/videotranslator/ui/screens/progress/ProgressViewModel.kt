package com.ericjesse.videotranslator.ui.screens.progress

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.domain.model.TranslationJob
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Desktop
import java.io.File
import java.time.LocalTime

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the progress screen.
 *
 * Manages:
 * - Pipeline stage states
 * - Log entries
 * - Overall progress tracking
 * - Translation job execution (placeholder for actual implementation)
 *
 * @param appModule Application module for accessing services.
 * @param job The translation job to process.
 * @param scope Coroutine scope for async operations.
 */
class ProgressViewModel(
    private val appModule: AppModule,
    val job: TranslationJob,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val i18n = appModule.i18nManager

    /**
     * Current progress state.
     */
    var progressState by mutableStateOf<ProgressState>(createInitialState())
        private set

    /**
     * Log entries.
     */
    private val _logs = mutableStateListOf<LogEntry>()
    val logs: List<LogEntry> get() = _logs

    /**
     * Whether the job has been cancelled.
     */
    private var cancelled = false

    /**
     * Job handle for the translation process.
     */
    private var translationJob: Job? = null

    /**
     * Start time for processing duration calculation.
     */
    private var startTime: Long = 0L

    init {
        log(LogLevel.INFO, "Translation job created for: ${job.videoInfo.title}")
    }

    /**
     * Starts the translation process.
     */
    fun startTranslation() {
        if (translationJob?.isActive == true) {
            log(LogLevel.WARNING, "Translation already in progress")
            return
        }

        cancelled = false
        startTime = System.currentTimeMillis()

        translationJob = scope.launch {
            try {
                log(LogLevel.INFO, "Starting translation pipeline...")
                executeTranslationPipeline()
            } catch (e: CancellationException) {
                log(LogLevel.WARNING, "Translation cancelled by user")
                throw e
            } catch (e: Exception) {
                log(LogLevel.ERROR, "Translation failed: ${e.message}")
                handleError(getCurrentStage(), e)
            }
        }
    }

    /**
     * Cancels the translation process.
     */
    fun cancelTranslation() {
        cancelled = true
        translationJob?.cancel()
        log(LogLevel.WARNING, "Translation cancelled")
    }

    /**
     * Opens the output folder in the system file manager.
     */
    fun openFolder(path: String) {
        try {
            val file = File(path)
            if (file.exists()) {
                Desktop.getDesktop().open(file)
                log(LogLevel.INFO, "Opened folder: $path")
            } else {
                log(LogLevel.WARNING, "Folder does not exist: $path")
            }
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Failed to open folder: ${e.message}")
        }
    }

    /**
     * Resets the ViewModel for a retry.
     */
    fun reset() {
        cancelled = false
        _logs.clear()
        progressState = createInitialState()
    }

    /**
     * Cleans up resources.
     */
    fun dispose() {
        translationJob?.cancel()
        scope.cancel()
    }

    // ========== Pipeline Execution ==========

    private suspend fun executeTranslationPipeline() {
        // Stage 1: Downloading
        updateStage(PipelineStage.DOWNLOADING, StageStatus.InProgress(0f, "Connecting to YouTube..."))
        log(LogLevel.INFO, "Downloading video from YouTube...")

        // Simulate download progress
        for (progress in 1..100 step 5) {
            if (cancelled) throw CancellationException()
            delay(100)
            updateStage(
                PipelineStage.DOWNLOADING,
                StageStatus.InProgress(
                    progress / 100f,
                    "Downloading: ${progress}%"
                )
            )
        }

        updateStage(PipelineStage.DOWNLOADING, StageStatus.Complete("Downloaded ${formatSize(45_200_000L)} in 12 seconds"))
        log(LogLevel.INFO, "Video downloaded successfully")

        // Stage 2: Checking captions
        updateStage(PipelineStage.CHECKING_CAPTIONS, StageStatus.InProgress(message = "Checking for existing captions..."))
        log(LogLevel.INFO, "Checking for YouTube captions...")
        delay(1000)

        // Simulate: no captions found
        updateStage(PipelineStage.CHECKING_CAPTIONS, StageStatus.Complete("No captions available, will transcribe"))
        log(LogLevel.INFO, "No captions found, proceeding to transcription")

        // Stage 3: Transcribing
        updateStage(PipelineStage.TRANSCRIBING, StageStatus.InProgress(0f, "Loading Whisper model..."))
        log(LogLevel.INFO, "Starting transcription with Whisper ${appModule.configManager.getSettings().transcription.whisperModel} model")
        delay(500)

        for (progress in 1..100 step 2) {
            if (cancelled) throw CancellationException()
            delay(100)

            val timeProcessed = (job.videoInfo.duration * progress / 100).let { formatDuration(it) }
            val totalTime = formatDuration(job.videoInfo.duration)

            updateStage(
                PipelineStage.TRANSCRIBING,
                StageStatus.InProgress(
                    progress / 100f,
                    "Processing: $timeProcessed / $totalTime"
                )
            )

            if (progress % 20 == 0) {
                log(LogLevel.INFO, "Transcription progress: ${progress}%")
            }
        }

        updateStage(PipelineStage.TRANSCRIBING, StageStatus.Complete("Transcribed ${calculateSegments(job.videoInfo.duration)} segments"))
        log(LogLevel.INFO, "Transcription completed")

        // Stage 4: Translating
        updateStage(PipelineStage.TRANSLATING, StageStatus.InProgress(0f, "Connecting to translation service..."))
        log(LogLevel.INFO, "Translating to ${job.targetLanguage.displayName}...")
        delay(500)

        for (progress in 1..100 step 3) {
            if (cancelled) throw CancellationException()
            delay(80)
            updateStage(
                PipelineStage.TRANSLATING,
                StageStatus.InProgress(progress / 100f, "Translating segments...")
            )
        }

        updateStage(PipelineStage.TRANSLATING, StageStatus.Complete("Translated to ${job.targetLanguage.nativeName}"))
        log(LogLevel.INFO, "Translation completed")

        // Stage 5: Rendering
        updateStage(PipelineStage.RENDERING, StageStatus.InProgress(0f, "Preparing video output..."))
        log(LogLevel.INFO, "Rendering video with subtitles...")

        val renderMessage = if (job.outputOptions.subtitleType == com.ericjesse.videotranslator.domain.model.SubtitleType.BURNED_IN) {
            "Burning in subtitles..."
        } else {
            "Embedding subtitle track..."
        }

        for (progress in 1..100 step 2) {
            if (cancelled) throw CancellationException()
            delay(100)
            updateStage(
                PipelineStage.RENDERING,
                StageStatus.InProgress(progress / 100f, renderMessage)
            )
        }

        updateStage(PipelineStage.RENDERING, StageStatus.Complete("Video rendered successfully"))
        log(LogLevel.INFO, "Video rendering completed")

        // Complete!
        val processingTime = System.currentTimeMillis() - startTime
        val outputFiles = buildOutputFileList()

        progressState = ProgressState.Complete(
            outputFiles = outputFiles,
            outputDirectory = job.outputOptions.outputDirectory,
            processingTime = processingTime
        )

        log(LogLevel.INFO, "Translation completed successfully in ${formatProcessingTime(processingTime)}")
    }

    // ========== State Management ==========

    private fun createInitialState(): ProgressState.Processing {
        return ProgressState.Processing(
            stages = PipelineStage.entries.map { stage ->
                StageState(stage = stage, status = StageStatus.Pending)
            },
            currentStage = PipelineStage.DOWNLOADING,
            overallProgress = 0f
        )
    }

    private fun updateStage(stage: PipelineStage, status: StageStatus) {
        val currentState = progressState
        if (currentState !is ProgressState.Processing) return

        val updatedStages = currentState.stages.map { stageState ->
            if (stageState.stage == stage) {
                stageState.copy(status = status)
            } else {
                stageState
            }
        }

        val overallProgress = calculateOverallProgress(updatedStages)

        progressState = currentState.copy(
            stages = updatedStages,
            currentStage = stage,
            overallProgress = overallProgress
        )
    }

    private fun calculateOverallProgress(stages: List<StageState>): Float {
        val stageWeight = 1f / stages.size
        var totalProgress = 0f

        stages.forEach { stageState ->
            totalProgress += when (val status = stageState.status) {
                is StageStatus.Complete -> stageWeight
                is StageStatus.Skipped -> stageWeight
                is StageStatus.InProgress -> stageWeight * status.progress
                else -> 0f
            }
        }

        return totalProgress.coerceIn(0f, 1f)
    }

    private fun getCurrentStage(): PipelineStage {
        return (progressState as? ProgressState.Processing)?.currentStage ?: PipelineStage.DOWNLOADING
    }

    private fun handleError(failedStage: PipelineStage, exception: Exception) {
        val suggestions = when (failedStage) {
            PipelineStage.DOWNLOADING -> listOf(
                "Check your internet connection",
                "Verify the YouTube URL is correct",
                "Try again in a few minutes"
            )
            PipelineStage.CHECKING_CAPTIONS -> listOf(
                "This is usually not critical, the transcription will be used instead"
            )
            PipelineStage.TRANSCRIBING -> listOf(
                "Re-download the Whisper model in Settings",
                "Try a different Whisper model",
                "Ensure you have enough disk space"
            )
            PipelineStage.TRANSLATING -> listOf(
                "Check your translation service configuration in Settings",
                "Verify your API key is valid",
                "Try a different translation service"
            )
            PipelineStage.RENDERING -> listOf(
                "Ensure FFmpeg is installed correctly",
                "Check you have enough disk space",
                "Verify the output directory is writable"
            )
        }

        progressState = ProgressState.Error(
            failedStage = failedStage,
            errorMessage = exception.message ?: "Unknown error occurred",
            errorDetails = exception.stackTraceToString().take(500),
            suggestions = suggestions
        )
    }

    // ========== Logging ==========

    private fun log(level: LogLevel, message: String) {
        _logs.add(
            LogEntry(
                timestamp = LocalTime.now(),
                message = message,
                level = level
            )
        )

        when (level) {
            LogLevel.DEBUG -> logger.debug { message }
            LogLevel.INFO -> logger.info { message }
            LogLevel.WARNING -> logger.warn { message }
            LogLevel.ERROR -> logger.error { message }
        }
    }

    // ========== Helper Functions ==========

    private fun buildOutputFileList(): List<OutputFile> {
        val videoTitle = sanitizeFileName(job.videoInfo.title)
        val langSuffix = job.targetLanguage.code.uppercase()
        val extension = if (job.outputOptions.subtitleType == com.ericjesse.videotranslator.domain.model.SubtitleType.SOFT) "mkv" else "mp4"

        val files = mutableListOf<OutputFile>()

        // Video file
        files.add(
            OutputFile(
                name = "${videoTitle}_$langSuffix.$extension",
                path = "${job.outputOptions.outputDirectory}/${videoTitle}_$langSuffix.$extension",
                size = 142_500_000L, // Simulated size
                type = OutputFileType.VIDEO
            )
        )

        // SRT file (if exporting)
        if (job.outputOptions.exportSrt) {
            files.add(
                OutputFile(
                    name = "${videoTitle}_$langSuffix.srt",
                    path = "${job.outputOptions.outputDirectory}/${videoTitle}_$langSuffix.srt",
                    size = 4_200L, // Simulated size
                    type = OutputFileType.SUBTITLE
                )
            )
        }

        return files
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9\\s_-]"), "")
            .replace(Regex("\\s+"), "_")
            .take(50)
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return String.format("%.1f MB", mb)
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun formatProcessingTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "$minutes min $seconds sec" else "$seconds seconds"
    }

    private fun calculateSegments(durationMillis: Long): Int {
        // Rough estimate: ~5 second segments
        return ((durationMillis / 1000) / 5).toInt().coerceAtLeast(1)
    }
}
