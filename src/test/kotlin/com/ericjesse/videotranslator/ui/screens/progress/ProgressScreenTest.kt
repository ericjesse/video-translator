package com.ericjesse.videotranslator.ui.screens.progress

import com.ericjesse.videotranslator.di.AppModule
import com.ericjesse.videotranslator.domain.model.*
import com.ericjesse.videotranslator.domain.pipeline.*
import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * Tests for ProgressScreen state management via ProgressViewModel and state models.
 *
 * Tests cover:
 * - Stage transitions
 * - Log display
 * - Completion states
 * - Cancellation
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ProgressScreen Tests")
class ProgressScreenTest {

    private lateinit var appModule: AppModule
    private lateinit var pipelineOrchestrator: PipelineOrchestrator
    private lateinit var configManager: ConfigManager
    private lateinit var i18nManager: I18nManager
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    private val testVideoInfo = VideoInfo(
        url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        id = "dQw4w9WgXcQ",
        title = "Test Video",
        duration = 213L
    )

    private val testJob = TranslationJob(
        videoInfo = testVideoInfo,
        sourceLanguage = null,
        targetLanguage = Language.GERMAN,
        outputOptions = OutputOptions(
            outputDirectory = "/tmp/output",
            subtitleType = SubtitleType.SOFT
        )
    )

    @BeforeEach
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        Dispatchers.setMain(testDispatcher)

        appModule = mockk(relaxed = true)
        pipelineOrchestrator = mockk(relaxed = true)
        configManager = mockk(relaxed = true)
        i18nManager = mockk(relaxed = true)

        every { appModule.pipelineOrchestrator } returns pipelineOrchestrator
        every { appModule.configManager } returns configManager
        every { appModule.i18nManager } returns i18nManager
        every { i18nManager[any()] } returns "Test String"
        every { pipelineOrchestrator.getLogEvents() } returns emptyList()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Nested
    @DisplayName("ProgressScreenState")
    inner class ProgressScreenStateTest {

        @Test
        @DisplayName("Initial state should have stages empty by default")
        fun initialStateHasEmptyStages() {
            val state = ProgressScreenState(videoInfo = testVideoInfo)
            assertTrue(state.stages.isEmpty())
        }

        @Test
        @DisplayName("currentStage should return null when stages are empty")
        fun currentStageNullWhenEmpty() {
            val state = ProgressScreenState(videoInfo = testVideoInfo)
            assertNull(state.currentStage)
        }

        @Test
        @DisplayName("currentStage should return correct stage based on currentStageIndex")
        fun currentStageReturnsCorrectStage() {
            val stages = listOf(
                StageState("Download", PipelineStageName.DOWNLOAD),
                StageState("Transcribe", PipelineStageName.TRANSCRIPTION)
            )
            val state = ProgressScreenState(
                videoInfo = testVideoInfo,
                stages = stages,
                currentStageIndex = 1
            )
            assertEquals(stages[1], state.currentStage)
        }

        @Test
        @DisplayName("canCancel should be true when status is Processing")
        fun canCancelWhenProcessing() {
            val state = ProgressScreenState(
                videoInfo = testVideoInfo,
                status = ProgressStatus.Processing
            )
            assertTrue(state.canCancel)
        }

        @Test
        @DisplayName("canCancel should be false when status is Complete")
        fun cannotCancelWhenComplete() {
            val state = ProgressScreenState(
                videoInfo = testVideoInfo,
                status = ProgressStatus.Complete
            )
            assertFalse(state.canCancel)
        }

        @Test
        @DisplayName("canCancel should be false when status is Error")
        fun cannotCancelWhenError() {
            val state = ProgressScreenState(
                videoInfo = testVideoInfo,
                status = ProgressStatus.Error
            )
            assertFalse(state.canCancel)
        }

        @Test
        @DisplayName("canCancel should be false when status is Cancelled")
        fun cannotCancelWhenCancelled() {
            val state = ProgressScreenState(
                videoInfo = testVideoInfo,
                status = ProgressStatus.Cancelled
            )
            assertFalse(state.canCancel)
        }

        @Test
        @DisplayName("isFinished should be false when status is Processing")
        fun notFinishedWhenProcessing() {
            val state = ProgressScreenState(
                videoInfo = testVideoInfo,
                status = ProgressStatus.Processing
            )
            assertFalse(state.isFinished)
        }

        @Test
        @DisplayName("isFinished should be true when status is Complete")
        fun finishedWhenComplete() {
            val state = ProgressScreenState(
                videoInfo = testVideoInfo,
                status = ProgressStatus.Complete
            )
            assertTrue(state.isFinished)
        }

        @Test
        @DisplayName("isFinished should be true when status is Error")
        fun finishedWhenError() {
            val state = ProgressScreenState(
                videoInfo = testVideoInfo,
                status = ProgressStatus.Error
            )
            assertTrue(state.isFinished)
        }

        @Test
        @DisplayName("isFinished should be true when status is Cancelled")
        fun finishedWhenCancelled() {
            val state = ProgressScreenState(
                videoInfo = testVideoInfo,
                status = ProgressStatus.Cancelled
            )
            assertTrue(state.isFinished)
        }
    }

    @Nested
    @DisplayName("StageState")
    inner class StageStateTest {

        @Test
        @DisplayName("Default status should be Pending")
        fun defaultStatusIsPending() {
            val stage = StageState("Download", PipelineStageName.DOWNLOAD)
            assertEquals(StageStatus.Pending, stage.status)
        }

        @Test
        @DisplayName("Default progress should be 0")
        fun defaultProgressIsZero() {
            val stage = StageState("Download", PipelineStageName.DOWNLOAD)
            assertEquals(0f, stage.progress)
        }

        @Test
        @DisplayName("Message and details should be null by default")
        fun messageAndDetailsNullByDefault() {
            val stage = StageState("Download", PipelineStageName.DOWNLOAD)
            assertNull(stage.message)
            assertNull(stage.details)
        }

        @Test
        @DisplayName("Stage should be copyable with new values")
        fun stageCopyable() {
            val stage = StageState("Download", PipelineStageName.DOWNLOAD)
            val updated = stage.copy(
                status = StageStatus.InProgress,
                progress = 0.5f,
                message = "Downloading..."
            )

            assertEquals(StageStatus.InProgress, updated.status)
            assertEquals(0.5f, updated.progress)
            assertEquals("Downloading...", updated.message)
        }
    }

    @Nested
    @DisplayName("StageStatus Enum")
    inner class StageStatusTest {

        @Test
        @DisplayName("StageStatus should have all expected values")
        fun stageStatusValues() {
            val values = StageStatus.entries
            assertTrue(values.contains(StageStatus.Pending))
            assertTrue(values.contains(StageStatus.InProgress))
            assertTrue(values.contains(StageStatus.Complete))
            assertTrue(values.contains(StageStatus.Skipped))
            assertTrue(values.contains(StageStatus.Error))
            assertEquals(5, values.size)
        }
    }

    @Nested
    @DisplayName("ProgressStatus Enum")
    inner class ProgressStatusTest {

        @Test
        @DisplayName("ProgressStatus should have all expected values")
        fun progressStatusValues() {
            val values = ProgressStatus.entries
            assertTrue(values.contains(ProgressStatus.Processing))
            assertTrue(values.contains(ProgressStatus.Complete))
            assertTrue(values.contains(ProgressStatus.Error))
            assertTrue(values.contains(ProgressStatus.Cancelled))
            assertEquals(4, values.size)
        }
    }

    @Nested
    @DisplayName("LogEntry")
    inner class LogEntryTest {

        @Test
        @DisplayName("LogEntry should be created with correct values")
        fun logEntryCreation() {
            val timestamp = LocalTime.of(10, 30, 0)
            val entry = LogEntry(
                timestamp = timestamp,
                level = LogEntryLevel.INFO,
                stage = PipelineStageName.DOWNLOAD,
                message = "Test message",
                details = "Some details"
            )

            assertEquals(timestamp, entry.timestamp)
            assertEquals(LogEntryLevel.INFO, entry.level)
            assertEquals(PipelineStageName.DOWNLOAD, entry.stage)
            assertEquals("Test message", entry.message)
            assertEquals("Some details", entry.details)
        }

        @Test
        @DisplayName("LogEntry stage can be null")
        fun logEntryStageNullable() {
            val entry = LogEntry(
                timestamp = LocalTime.now(),
                level = LogEntryLevel.INFO,
                stage = null,
                message = "General message"
            )
            assertNull(entry.stage)
        }

        @Test
        @DisplayName("LogEntry details can be null")
        fun logEntryDetailsNullable() {
            val entry = LogEntry(
                timestamp = LocalTime.now(),
                level = LogEntryLevel.INFO,
                stage = null,
                message = "Message"
            )
            assertNull(entry.details)
        }
    }

    @Nested
    @DisplayName("LogEntryLevel Enum")
    inner class LogEntryLevelTest {

        @Test
        @DisplayName("LogEntryLevel should have all expected values")
        fun logEntryLevelValues() {
            val values = LogEntryLevel.entries
            assertTrue(values.contains(LogEntryLevel.DEBUG))
            assertTrue(values.contains(LogEntryLevel.INFO))
            assertTrue(values.contains(LogEntryLevel.WARNING))
            assertTrue(values.contains(LogEntryLevel.ERROR))
            assertEquals(4, values.size)
        }
    }

    @Nested
    @DisplayName("ProgressViewModel Initialization")
    inner class ViewModelInitializationTest {

        @Test
        @DisplayName("ViewModel should create initial state with stages")
        fun viewModelCreatesInitialState() {
            val viewModel = ProgressViewModel(appModule, testJob, testScope)

            assertNotNull(viewModel.state)
            assertEquals(testVideoInfo, viewModel.state.videoInfo)
            assertEquals(5, viewModel.state.stages.size) // 5 pipeline stages
            assertEquals(ProgressStatus.Processing, viewModel.state.status)
        }

        @Test
        @DisplayName("Initial state should have all stages in Pending status")
        fun initialStagesArePending() {
            val viewModel = ProgressViewModel(appModule, testJob, testScope)

            viewModel.state.stages.forEach { stage ->
                assertEquals(StageStatus.Pending, stage.status)
            }
        }

        @Test
        @DisplayName("ViewModel should log job creation")
        fun viewModelLogsCreation() {
            val viewModel = ProgressViewModel(appModule, testJob, testScope)

            assertTrue(viewModel.state.logEntries.isNotEmpty())
            assertTrue(viewModel.state.logEntries.any {
                it.message.contains("Translation job created")
            })
        }
    }

    @Nested
    @DisplayName("Stage Transitions")
    inner class StageTransitionsTest {

        @Test
        @DisplayName("Starting translation should add log entry")
        fun startTranslationLogsEntry() = runTest {
            val emptyFlow = emptyFlow<PipelineStage>()
            coEvery { pipelineOrchestrator.execute(any()) } returns emptyFlow

            val viewModel = ProgressViewModel(appModule, testJob, this)
            viewModel.startTranslation()
            advanceUntilIdle()

            assertTrue(viewModel.state.logEntries.any {
                it.message.contains("Starting translation pipeline")
            })
        }

        @Test
        @DisplayName("Downloading stage should update state")
        fun downloadingStageUpdatesState() = runTest {
            val stageFlow = flowOf(
                PipelineStage.Downloading(0.5f, "50% complete")
            )
            coEvery { pipelineOrchestrator.execute(any()) } returns stageFlow

            val viewModel = ProgressViewModel(appModule, testJob, this)
            viewModel.startTranslation()
            advanceUntilIdle()

            val downloadStage = viewModel.state.stages.find {
                it.pipelineStage == PipelineStageName.DOWNLOAD
            }
            assertNotNull(downloadStage)
            assertEquals(StageStatus.InProgress, downloadStage?.status)
            assertEquals(0.5f, downloadStage?.progress)
        }

        @Test
        @DisplayName("Complete stage should update status to Complete")
        fun completeStageUpdatesStatus() = runTest {
            val result = TranslationResult(
                videoFile = "/tmp/output/video.mp4",
                subtitleFile = null,
                duration = 1000L
            )
            val stageFlow = flowOf(
                PipelineStage.Complete(result)
            )
            coEvery { pipelineOrchestrator.execute(any()) } returns stageFlow

            val viewModel = ProgressViewModel(appModule, testJob, this)
            viewModel.startTranslation()
            advanceUntilIdle()

            assertEquals(ProgressStatus.Complete, viewModel.state.status)
            assertNotNull(viewModel.state.result)
            assertEquals(1f, viewModel.state.overallProgress)
        }

        @Test
        @DisplayName("Error stage should update status to Error")
        fun errorStageUpdatesStatus() = runTest {
            val stageFlow = flowOf(
                PipelineStage.Error("Download", "Network error", "Check connection")
            )
            coEvery { pipelineOrchestrator.execute(any()) } returns stageFlow

            val viewModel = ProgressViewModel(appModule, testJob, this)
            viewModel.startTranslation()
            advanceUntilIdle()

            assertEquals(ProgressStatus.Error, viewModel.state.status)
            assertNotNull(viewModel.state.error)
        }
    }

    @Nested
    @DisplayName("Cancellation")
    inner class CancellationTest {

        @Test
        @DisplayName("cancelTranslation should set status to Cancelled")
        fun cancelSetsStatus() = runTest {
            // Create a flow that never completes
            val infiniteFlow = flow<PipelineStage> {
                delay(Long.MAX_VALUE)
            }
            coEvery { pipelineOrchestrator.execute(any()) } returns infiniteFlow

            val viewModel = ProgressViewModel(appModule, testJob, this)
            viewModel.startTranslation()

            // Allow the job to start
            advanceTimeBy(100)

            // Cancel
            viewModel.cancelTranslation()
            advanceUntilIdle()

            assertEquals(ProgressStatus.Cancelled, viewModel.state.status)
        }

        @Test
        @DisplayName("cancelTranslation should add warning log")
        fun cancelAddsLog() = runTest {
            val infiniteFlow = flow<PipelineStage> {
                delay(Long.MAX_VALUE)
            }
            coEvery { pipelineOrchestrator.execute(any()) } returns infiniteFlow

            val viewModel = ProgressViewModel(appModule, testJob, this)
            viewModel.startTranslation()
            advanceTimeBy(100)

            viewModel.cancelTranslation()
            advanceUntilIdle()

            assertTrue(viewModel.state.logEntries.any {
                it.level == LogEntryLevel.WARNING && it.message.contains("Cancellation")
            })
        }

        @Test
        @DisplayName("cancelTranslation should be ignored if already finished")
        fun cancelIgnoredWhenFinished() = runTest {
            val result = TranslationResult(
                videoFile = "/tmp/output/video.mp4",
                subtitleFile = null,
                duration = 1000L
            )
            val stageFlow = flowOf(PipelineStage.Complete(result))
            coEvery { pipelineOrchestrator.execute(any()) } returns stageFlow

            val viewModel = ProgressViewModel(appModule, testJob, this)
            viewModel.startTranslation()
            advanceUntilIdle()

            assertEquals(ProgressStatus.Complete, viewModel.state.status)

            // Try to cancel
            viewModel.cancelTranslation()
            advanceUntilIdle()

            // Status should still be Complete
            assertEquals(ProgressStatus.Complete, viewModel.state.status)
        }
    }

    @Nested
    @DisplayName("Retry Functionality")
    inner class RetryTest {

        @Test
        @DisplayName("retryTranslation should reset state and restart")
        fun retryResetsAndRestarts() = runTest {
            // First run fails
            val errorFlow = flowOf(
                PipelineStage.Error("Download", "Network error", null)
            )
            coEvery { pipelineOrchestrator.execute(any()) } returns errorFlow

            val viewModel = ProgressViewModel(appModule, testJob, this)
            viewModel.startTranslation()
            advanceUntilIdle()

            assertEquals(ProgressStatus.Error, viewModel.state.status)

            // Now retry with success
            val result = TranslationResult(
                videoFile = "/tmp/output/video.mp4",
                subtitleFile = null,
                duration = 1000L
            )
            val successFlow = flowOf(PipelineStage.Complete(result))
            coEvery { pipelineOrchestrator.execute(any()) } returns successFlow

            viewModel.retryTranslation()
            advanceUntilIdle()

            assertEquals(ProgressStatus.Complete, viewModel.state.status)
        }
    }

    @Nested
    @DisplayName("Progress Calculation")
    inner class ProgressCalculationTest {

        @Test
        @DisplayName("Overall progress should be 0 when all stages are Pending")
        fun progressZeroWhenAllPending() {
            val viewModel = ProgressViewModel(appModule, testJob, testScope)
            assertEquals(0f, viewModel.state.overallProgress)
        }

        @Test
        @DisplayName("Overall progress should be 1 when Complete")
        fun progressOneWhenComplete() = runTest {
            val result = TranslationResult(
                videoFile = "/tmp/output/video.mp4",
                subtitleFile = null,
                duration = 1000L
            )
            val stageFlow = flowOf(PipelineStage.Complete(result))
            coEvery { pipelineOrchestrator.execute(any()) } returns stageFlow

            val viewModel = ProgressViewModel(appModule, testJob, this)
            viewModel.startTranslation()
            advanceUntilIdle()

            assertEquals(1f, viewModel.state.overallProgress)
        }
    }

    @Nested
    @DisplayName("Log Collection")
    inner class LogCollectionTest {

        @Test
        @DisplayName("Logs should be collected chronologically")
        fun logsCollectedChronologically() {
            val viewModel = ProgressViewModel(appModule, testJob, testScope)

            // Initial log should be present
            assertTrue(viewModel.state.logEntries.isNotEmpty())

            // Logs should have timestamps
            viewModel.state.logEntries.forEach { entry ->
                assertNotNull(entry.timestamp)
            }
        }

        @Test
        @DisplayName("Multiple log entries should be added as pipeline progresses")
        fun multipleLogsAdded() = runTest {
            val stageFlow = flowOf(
                PipelineStage.Downloading(0.5f, "Downloading..."),
                PipelineStage.Complete(
                    TranslationResult(
                        videoFile = "/tmp/output/video.mp4",
                        subtitleFile = null,
                        duration = 1000L
                    )
                )
            )
            coEvery { pipelineOrchestrator.execute(any()) } returns stageFlow

            val viewModel = ProgressViewModel(appModule, testJob, this)
            val initialLogCount = viewModel.state.logEntries.size

            viewModel.startTranslation()
            advanceUntilIdle()

            assertTrue(viewModel.state.logEntries.size > initialLogCount)
        }
    }

    @Nested
    @DisplayName("Cleanup")
    inner class CleanupTest {

        @Test
        @DisplayName("cancelTranslation should cancel pipeline job and set status")
        fun cancelTranslationSetsStatus() = runTest {
            val infiniteFlow = flow<PipelineStage> {
                delay(Long.MAX_VALUE)
            }
            coEvery { pipelineOrchestrator.execute(any()) } returns infiniteFlow

            val viewModel = ProgressViewModel(appModule, testJob, this)
            viewModel.startTranslation()
            advanceTimeBy(100)

            // Cancel the translation (not dispose)
            viewModel.cancelTranslation()
            advanceUntilIdle()

            // After cancel, the status should be Cancelled
            assertEquals(ProgressStatus.Cancelled, viewModel.state.status)
        }
    }
}
