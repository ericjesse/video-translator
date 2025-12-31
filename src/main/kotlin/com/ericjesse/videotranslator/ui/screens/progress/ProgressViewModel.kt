package com.ericjesse.videotranslator.ui.screens.progress

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.domain.model.TranslationJob
import com.ericjesse.videotranslator.domain.model.TranslationResult
import com.ericjesse.videotranslator.domain.model.VideoInfo
import com.ericjesse.videotranslator.domain.pipeline.ErrorCode
import com.ericjesse.videotranslator.domain.pipeline.ErrorMapper
import com.ericjesse.videotranslator.domain.pipeline.PipelineCheckpoint
import com.ericjesse.videotranslator.domain.pipeline.PipelineError
import com.ericjesse.videotranslator.domain.pipeline.PipelineException
import com.ericjesse.videotranslator.domain.pipeline.PipelineLogEvent
import com.ericjesse.videotranslator.domain.pipeline.PipelineOrchestrator
import com.ericjesse.videotranslator.domain.pipeline.PipelineStageName
import com.ericjesse.videotranslator.domain.pipeline.PipelineStage as DomainPipelineStage
import com.ericjesse.videotranslator.domain.pipeline.LogLevel as DomainLogLevel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import java.awt.Desktop
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

// ========== State Models ==========

/**
 * Main state class for the progress screen.
 *
 * @property videoInfo Information about the video being processed.
 * @property stages List of all pipeline stages with their current state.
 * @property currentStageIndex Index of the currently executing stage (0-based).
 * @property overallProgress Overall progress from 0.0 to 1.0.
 * @property logEntries Log entries for the log panel.
 * @property status Current status of the translation process.
 * @property result Translation result on success, null otherwise.
 * @property error Error details on failure, null otherwise.
 */
data class ProgressScreenState(
    val videoInfo: VideoInfo,
    val stages: List<StageState> = emptyList(),
    val currentStageIndex: Int = 0,
    val overallProgress: Float = 0f,
    val logEntries: List<LogEntry> = emptyList(),
    val status: ProgressStatus = ProgressStatus.Processing,
    val result: TranslationResult? = null,
    val error: PipelineError? = null
) {
    /**
     * Gets the currently executing stage, if any.
     */
    val currentStage: StageState?
        get() = stages.getOrNull(currentStageIndex)

    /**
     * Whether the process can be cancelled.
     */
    val canCancel: Boolean
        get() = status == ProgressStatus.Processing

    /**
     * Whether the process has finished (success, error, or cancelled).
     */
    val isFinished: Boolean
        get() = status != ProgressStatus.Processing
}

/**
 * State for a single pipeline stage.
 *
 * @property name Display name of the stage.
 * @property pipelineStage The underlying pipeline stage name.
 * @property status Current status of the stage.
 * @property progress Progress from 0.0 to 1.0 (only relevant when InProgress).
 * @property message Current status message.
 * @property details Additional details (e.g., file sizes, counts).
 */
data class StageState(
    val name: String,
    val pipelineStage: PipelineStageName,
    val status: StageStatus = StageStatus.Pending,
    val progress: Float = 0f,
    val message: String? = null,
    val details: String? = null
)

/**
 * Status of a pipeline stage.
 */
enum class StageStatus {
    /** Stage has not started yet. */
    Pending,

    /** Stage is currently executing. */
    InProgress,

    /** Stage completed successfully. */
    Complete,

    /** Stage was skipped (e.g., captions found, no transcription needed). */
    Skipped,

    /** Stage failed with an error. */
    Error
}

/**
 * Overall status of the translation process.
 */
enum class ProgressStatus {
    /** Translation is in progress. */
    Processing,

    /** Translation completed successfully. */
    Complete,

    /** Translation failed with an error. */
    Error,

    /** Translation was cancelled by the user. */
    Cancelled
}

/**
 * Log entry for the log panel.
 *
 * @property timestamp Time the log entry was created.
 * @property level Severity level of the log entry.
 * @property stage Pipeline stage that generated the log, if any.
 * @property message Log message text.
 * @property details Additional details, if any.
 */
data class LogEntry(
    val timestamp: LocalTime,
    val level: LogEntryLevel,
    val stage: PipelineStageName?,
    val message: String,
    val details: String? = null
)

/**
 * Log entry severity levels.
 */
enum class LogEntryLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR
}

// ========== ViewModel ==========

/**
 * ViewModel for the progress screen.
 *
 * Connects to [PipelineOrchestrator.execute] flow and maps emissions to UI state.
 * Collects log messages for the log panel and handles cancellation.
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
    private val pipelineOrchestrator = appModule.pipelineOrchestrator

    /**
     * Current screen state.
     */
    var state by mutableStateOf(createInitialState())
        private set

    /**
     * Whether cancellation has been requested.
     */
    private var cancellationRequested = false

    /**
     * Handle to the pipeline execution job.
     */
    private var pipelineJob: Job? = null

    /**
     * Start time for calculating processing duration.
     */
    private var startTime: Long = 0L

    init {
        addLog(LogEntryLevel.INFO, null, "Translation job created for: ${job.videoInfo.title}")
    }

    /**
     * Starts the translation pipeline.
     * If already running, this is a no-op.
     */
    fun startTranslation() {
        if (pipelineJob?.isActive == true) {
            addLog(LogEntryLevel.WARNING, null, "Translation already in progress")
            return
        }

        cancellationRequested = false
        startTime = System.currentTimeMillis()

        pipelineJob = scope.launch {
            try {
                addLog(LogEntryLevel.INFO, null, "Starting translation pipeline...")

                pipelineOrchestrator.execute(job)
                    .catch { e ->
                        if (e !is CancellationException) {
                            addLog(LogEntryLevel.ERROR, null, "Pipeline error: ${e.message}")
                            handlePipelineError(e)
                        }
                    }
                    .onCompletion { cause ->
                        if (cause is CancellationException) {
                            handleCancellation()
                        }
                        // Collect final logs from orchestrator
                        collectOrchestratorLogs()
                    }
                    .collect { pipelineStage ->
                        mapPipelineStageToState(pipelineStage)
                        // Collect logs from orchestrator on each stage update
                        collectOrchestratorLogs()
                    }

            } catch (e: CancellationException) {
                handleCancellation()
                throw e
            }
        }
    }

    /**
     * Requests cancellation of the translation.
     * The actual cancellation happens asynchronously.
     */
    fun cancelTranslation() {
        if (!state.canCancel) return

        cancellationRequested = true
        addLog(LogEntryLevel.WARNING, null, "Cancellation requested...")

        pipelineJob?.cancel()
    }

    /**
     * Retries the translation after an error.
     */
    fun retryTranslation() {
        // Reset state
        state = createInitialState()
        cancellationRequested = false

        // Start fresh
        startTranslation()
    }

    /**
     * Opens the output folder in the system file manager.
     */
    fun openOutputFolder() {
        val directory = job.outputOptions.outputDirectory
        try {
            val file = File(directory)
            if (file.exists() && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file)
                addLog(LogEntryLevel.INFO, null, "Opened folder: $directory")
            } else {
                addLog(LogEntryLevel.WARNING, null, "Folder does not exist: $directory")
            }
        } catch (e: Exception) {
            addLog(LogEntryLevel.ERROR, null, "Failed to open folder: ${e.message}")
        }
    }

    /**
     * Cleans up resources.
     */
    fun dispose() {
        pipelineJob?.cancel()
        scope.cancel()
    }

    // ========== State Mapping ==========

    /**
     * Maps a PipelineStage emission to UI state.
     */
    private fun mapPipelineStageToState(pipelineStage: DomainPipelineStage) {
        when (pipelineStage) {
            is DomainPipelineStage.Idle -> {
                // Initial state, no action needed
            }

            is DomainPipelineStage.Downloading -> {
                updateStage(
                    pipelineStage = PipelineStageName.DOWNLOAD,
                    status = StageStatus.InProgress,
                    progress = pipelineStage.progress,
                    message = pipelineStage.message
                )
            }

            is DomainPipelineStage.CheckingCaptions -> {
                updateStage(
                    pipelineStage = PipelineStageName.CAPTION_CHECK,
                    status = StageStatus.InProgress,
                    progress = 0f,
                    message = pipelineStage.message
                )
            }

            is DomainPipelineStage.Transcribing -> {
                updateStage(
                    pipelineStage = PipelineStageName.TRANSCRIPTION,
                    status = StageStatus.InProgress,
                    progress = pipelineStage.progress,
                    message = pipelineStage.message
                )
            }

            is DomainPipelineStage.Translating -> {
                updateStage(
                    pipelineStage = PipelineStageName.TRANSLATION,
                    status = StageStatus.InProgress,
                    progress = pipelineStage.progress,
                    message = pipelineStage.message
                )
            }

            is DomainPipelineStage.Rendering -> {
                updateStage(
                    pipelineStage = PipelineStageName.RENDERING,
                    status = StageStatus.InProgress,
                    progress = pipelineStage.progress,
                    message = pipelineStage.message
                )
            }

            is DomainPipelineStage.Complete -> {
                handleCompletion(pipelineStage.result)
            }

            is DomainPipelineStage.Error -> {
                handleError(
                    stageName = pipelineStage.stage,
                    errorMessage = pipelineStage.error,
                    suggestion = pipelineStage.suggestion
                )
            }

            is DomainPipelineStage.Cancelled -> {
                handleCancellation()
            }
        }
    }

    /**
     * Updates a specific stage's state.
     */
    private fun updateStage(
        pipelineStage: PipelineStageName,
        status: StageStatus,
        progress: Float,
        message: String?,
        details: String? = null
    ) {
        val stageIndex = state.stages.indexOfFirst { it.pipelineStage == pipelineStage }
        if (stageIndex < 0) return

        // Mark all previous stages as complete if they're still pending or in progress
        val updatedStages = state.stages.mapIndexed { index, stageState ->
            when {
                index < stageIndex && (stageState.status == StageStatus.Pending || stageState.status == StageStatus.InProgress) -> {
                    stageState.copy(status = StageStatus.Complete, progress = 1f)
                }
                index == stageIndex -> {
                    stageState.copy(
                        status = status,
                        progress = progress,
                        message = message,
                        details = details
                    )
                }
                else -> stageState
            }
        }

        state = state.copy(
            stages = updatedStages,
            currentStageIndex = stageIndex,
            overallProgress = calculateOverallProgress(updatedStages)
        )
    }

    /**
     * Marks a stage as complete.
     */
    private fun markStageComplete(pipelineStage: PipelineStageName, details: String? = null) {
        updateStage(
            pipelineStage = pipelineStage,
            status = StageStatus.Complete,
            progress = 1f,
            message = null,
            details = details
        )
    }

    /**
     * Marks a stage as skipped.
     */
    private fun markStageSkipped(pipelineStage: PipelineStageName, reason: String) {
        val stageIndex = state.stages.indexOfFirst { it.pipelineStage == pipelineStage }
        if (stageIndex < 0) return

        val updatedStages = state.stages.mapIndexed { index, stageState ->
            if (index == stageIndex) {
                stageState.copy(
                    status = StageStatus.Skipped,
                    message = reason
                )
            } else stageState
        }

        state = state.copy(
            stages = updatedStages,
            overallProgress = calculateOverallProgress(updatedStages)
        )
    }

    /**
     * Handles successful completion.
     */
    private fun handleCompletion(result: TranslationResult) {
        val processingTime = System.currentTimeMillis() - startTime
        val updatedResult = result.copy(duration = processingTime)

        // Mark all stages as complete
        val updatedStages = state.stages.map { stage ->
            if (stage.status == StageStatus.Pending || stage.status == StageStatus.InProgress) {
                stage.copy(status = StageStatus.Complete, progress = 1f)
            } else stage
        }

        state = state.copy(
            stages = updatedStages,
            overallProgress = 1f,
            status = ProgressStatus.Complete,
            result = updatedResult
        )

        addLog(LogEntryLevel.INFO, null, "Translation completed successfully in ${formatDuration(processingTime)}")
    }

    /**
     * Handles pipeline error.
     */
    private fun handleError(stageName: String, errorMessage: String, suggestion: String?) {
        val pipelineStageName = PipelineStageName.entries.find { it.displayName == stageName }

        // Create a PipelineError for consistency
        val error = PipelineError(
            code = ErrorCode.UNKNOWN,
            stage = pipelineStageName ?: PipelineStageName.DOWNLOAD,
            message = errorMessage,
            suggestion = suggestion
        )

        // Mark the failed stage
        if (pipelineStageName != null) {
            val stageIndex = state.stages.indexOfFirst { it.pipelineStage == pipelineStageName }
            if (stageIndex >= 0) {
                val updatedStages = state.stages.mapIndexed { index, stageState ->
                    if (index == stageIndex) {
                        stageState.copy(
                            status = StageStatus.Error,
                            message = errorMessage
                        )
                    } else stageState
                }
                state = state.copy(stages = updatedStages)
            }
        }

        state = state.copy(
            status = ProgressStatus.Error,
            error = error
        )

        // Log detailed error info
        logger.error { "[${pipelineStageName?.displayName ?: "Pipeline"}] Error: $errorMessage" }
        suggestion?.let { logger.error { "Suggestion: $it" } }
        logger.error { "Error code: ${error.code}" }
        error.technicalDetails?.let { logger.error { "Technical details: $it" } }

        addLog(LogEntryLevel.ERROR, pipelineStageName, "Error: $errorMessage")
        suggestion?.let { addLog(LogEntryLevel.INFO, pipelineStageName, "Suggestion: $it") }
    }

    /**
     * Handles pipeline error from exception.
     */
    private fun handlePipelineError(exception: Throwable) {
        val currentStage = state.currentStage?.pipelineStage ?: PipelineStageName.DOWNLOAD

        // Log detailed error information
        logger.error(exception) { "Pipeline error occurred during ${currentStage.displayName}" }
        logger.error { "Error type: ${exception::class.simpleName}" }
        logger.error { "Error message: ${exception.message}" }
        logger.error { "Video: ${job.videoInfo.title} (${job.videoInfo.id})" }
        logger.error { "Video URL: ${job.videoInfo.url}" }

        val error = if (exception is PipelineException) {
            logger.error { "Pipeline error code: ${exception.error.code}" }
            logger.error { "Technical details: ${exception.error.technicalDetails}" }
            exception.error
        } else {
            ErrorMapper.mapException(exception as Exception, currentStage)
        }

        // Log the full stack trace at debug level
        logger.debug { "Full stack trace:\n${exception.stackTraceToString()}" }

        handleError(
            stageName = error.stage.displayName,
            errorMessage = error.message,
            suggestion = error.suggestion
        )
    }

    /**
     * Handles cancellation.
     */
    private fun handleCancellation() {
        state = state.copy(status = ProgressStatus.Cancelled)
        addLog(LogEntryLevel.WARNING, null, "Translation cancelled by user")
    }

    /**
     * Calculates overall progress based on stage states.
     */
    private fun calculateOverallProgress(stages: List<StageState>): Float {
        if (stages.isEmpty()) return 0f

        val stageWeight = 1f / stages.size
        var totalProgress = 0f

        stages.forEach { stage ->
            totalProgress += when (stage.status) {
                StageStatus.Complete, StageStatus.Skipped -> stageWeight
                StageStatus.InProgress -> stageWeight * stage.progress
                else -> 0f
            }
        }

        return totalProgress.coerceIn(0f, 1f)
    }

    // ========== Logging ==========

    /**
     * Adds a log entry.
     */
    private fun addLog(level: LogEntryLevel, stage: PipelineStageName?, message: String, details: String? = null) {
        val entry = LogEntry(
            timestamp = LocalTime.now(),
            level = level,
            stage = stage,
            message = message,
            details = details
        )

        state = state.copy(
            logEntries = state.logEntries + entry
        )

        // Also log to the application logger
        when (level) {
            LogEntryLevel.DEBUG -> logger.debug { "[${stage?.displayName ?: "Pipeline"}] $message" }
            LogEntryLevel.INFO -> logger.info { "[${stage?.displayName ?: "Pipeline"}] $message" }
            LogEntryLevel.WARNING -> logger.warn { "[${stage?.displayName ?: "Pipeline"}] $message" }
            LogEntryLevel.ERROR -> logger.error { "[${stage?.displayName ?: "Pipeline"}] $message" }
        }
    }

    /**
     * Collects log events from the PipelineOrchestrator and adds them to the log panel.
     */
    private fun collectOrchestratorLogs() {
        val orchestratorLogs = pipelineOrchestrator.getLogEvents()

        orchestratorLogs.forEach { event ->
            val level = when (event.level) {
                DomainLogLevel.DEBUG -> LogEntryLevel.DEBUG
                DomainLogLevel.INFO -> LogEntryLevel.INFO
                DomainLogLevel.WARNING -> LogEntryLevel.WARNING
                DomainLogLevel.ERROR -> LogEntryLevel.ERROR
            }

            val details = when (event) {
                is PipelineLogEvent.Info -> event.details.entries.joinToString(", ") { "${it.key}: ${it.value}" }.takeIf { it.isNotEmpty() }
                is PipelineLogEvent.Error -> event.stackTrace
                is PipelineLogEvent.RecoveryAttempt -> "Attempt ${event.attemptNumber}/${event.maxAttempts}, strategy: ${event.strategy}"
                is PipelineLogEvent.Metric -> "${event.name}: ${event.value} ${event.unit}"
                else -> null
            }

            // Convert timestamp
            val localTime = LocalTime.ofInstant(
                Instant.ofEpochMilli(event.timestamp),
                ZoneId.systemDefault()
            )

            val entry = LogEntry(
                timestamp = localTime,
                level = level,
                stage = event.stage,
                message = event.message,
                details = details
            )

            // Avoid duplicates by checking if we already have this log
            if (state.logEntries.none { it.timestamp == entry.timestamp && it.message == entry.message }) {
                state = state.copy(
                    logEntries = state.logEntries + entry
                )
            }
        }
    }

    // ========== Helpers ==========

    /**
     * Creates the initial state with all stages in Pending status.
     */
    private fun createInitialState(): ProgressScreenState {
        val stages = listOf(
            StageState(
                name = i18n["progress.stage.downloading"],
                pipelineStage = PipelineStageName.DOWNLOAD
            ),
            StageState(
                name = i18n["progress.stage.checkingCaptions"],
                pipelineStage = PipelineStageName.CAPTION_CHECK
            ),
            StageState(
                name = i18n["progress.stage.transcribing"],
                pipelineStage = PipelineStageName.TRANSCRIPTION
            ),
            StageState(
                name = i18n["progress.stage.translating"],
                pipelineStage = PipelineStageName.TRANSLATION
            ),
            StageState(
                name = i18n["progress.stage.rendering"],
                pipelineStage = PipelineStageName.RENDERING
            )
        )

        return ProgressScreenState(
            videoInfo = job.videoInfo,
            stages = stages
        )
    }

    /**
     * Formats duration in milliseconds to a human-readable string.
     */
    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        return if (minutes > 0) {
            "$minutes min $seconds sec"
        } else {
            "$seconds seconds"
        }
    }
}
