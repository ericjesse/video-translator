package com.ericjesse.videotranslator.integration

import com.ericjesse.videotranslator.domain.model.*
import com.ericjesse.videotranslator.domain.pipeline.*
import com.ericjesse.videotranslator.domain.service.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Integration tests for the PipelineOrchestrator.
 * Tests the full pipeline execution with mocked services.
 */
class PipelineOrchestratorIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var videoDownloader: VideoDownloader
    private lateinit var transcriberService: TranscriberService
    private lateinit var translatorService: TranslatorService
    private lateinit var subtitleRenderer: SubtitleRenderer
    private lateinit var orchestrator: PipelineOrchestrator

    private val testVideoInfo = VideoInfo(
        url = "https://www.youtube.com/watch?v=test123456",
        id = "test123456",
        title = "Test Video",
        duration = 180000L, // 3 minutes
        width = 1920,
        height = 1080
    )

    private val testSubtitles = Subtitles(
        entries = listOf(
            SubtitleEntry(1, 0L, 5000L, "Hello world"),
            SubtitleEntry(2, 5000L, 10000L, "This is a test"),
            SubtitleEntry(3, 10000L, 15000L, "Goodbye")
        ),
        language = Language.ENGLISH
    )

    private val testTranslatedSubtitles = Subtitles(
        entries = listOf(
            SubtitleEntry(1, 0L, 5000L, "Hallo Welt"),
            SubtitleEntry(2, 5000L, 10000L, "Dies ist ein Test"),
            SubtitleEntry(3, 10000L, 15000L, "Auf Wiedersehen")
        ),
        language = Language.GERMAN
    )

    private val testTranslationResult = TranslationResult(
        videoFile = "output.mp4",
        subtitleFile = "output.srt",
        duration = 5000L
    )

    @BeforeEach
    fun setup() {
        // Create mocks for services
        videoDownloader = mockk(relaxed = true)
        transcriberService = mockk(relaxed = true)
        translatorService = mockk(relaxed = true)
        subtitleRenderer = mockk(relaxed = true)

        // Create orchestrator with minimal dependencies (uses defaults internally)
        val checkpointDir = tempDir.resolve("checkpoints").toFile().apply { mkdirs() }
        orchestrator = PipelineOrchestrator(
            videoDownloader = videoDownloader,
            transcriberService = transcriberService,
            translatorService = translatorService,
            subtitleRenderer = subtitleRenderer,
            checkpointDir = checkpointDir
        )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // ==================== Full Pipeline Tests ====================

    @Nested
    inner class FullPipelineTest {

        @Test
        fun `full pipeline executes all stages successfully`() = runTest {
            // Setup video file
            val videoFile = tempDir.resolve("test.mp4").toFile().apply { writeText("video") }

            // Setup mocks for successful execution
            setupSuccessfulPipelineMocks(videoFile.absolutePath)

            // Create job
            val job = createTestJob()

            // Execute pipeline
            val stages = orchestrator.execute(job).toList()

            // Verify stage progression
            assertTrue(stages.any { it is PipelineStage.Downloading })
            assertTrue(stages.any { it is PipelineStage.CheckingCaptions })
            assertTrue(stages.any { it is PipelineStage.Transcribing })
            assertTrue(stages.any { it is PipelineStage.Translating })
            assertTrue(stages.any { it is PipelineStage.Rendering })
            assertTrue(stages.any { it is PipelineStage.Complete })

            // Verify the final result
            val complete = stages.filterIsInstance<PipelineStage.Complete>().first()
            assertEquals("output.mp4", complete.result.videoFile)
        }

        @Test
        fun `pipeline skips transcription when captions exist`() = runTest {
            val videoFile = tempDir.resolve("test.mp4").toFile().apply { writeText("video") }

            // Return existing captions
            coEvery { videoDownloader.extractCaptions(any(), any<Language>()) } returns testSubtitles
            setupSuccessfulPipelineMocks(videoFile.absolutePath, skipTranscription = true)

            val job = createTestJob()
            val stages = orchestrator.execute(job).toList()

            // Should have caption check but NOT transcription progress
            assertTrue(stages.any { it is PipelineStage.CheckingCaptions })

            // Verify transcribe was NOT called
            coVerify(exactly = 0) { transcriberService.transcribe(any(), any<Language>()) }
        }

        @Test
        fun `pipeline emits correct progress percentages`() = runTest {
            val videoFile = tempDir.resolve("test.mp4").toFile().apply { writeText("video") }
            setupSuccessfulPipelineMocks(videoFile.absolutePath)

            val job = createTestJob()
            val stages = orchestrator.execute(job).toList()

            // Check download progress
            val downloadStages = stages.filterIsInstance<PipelineStage.Downloading>()
            assertTrue(downloadStages.any { it.progress == 0f })
            assertTrue(downloadStages.any { it.progress > 0f })

            // Check final completion
            assertTrue(stages.last() is PipelineStage.Complete)
        }

        @Test
        fun `pipeline generates log events`() = runTest {
            val videoFile = tempDir.resolve("test.mp4").toFile().apply { writeText("video") }
            setupSuccessfulPipelineMocks(videoFile.absolutePath)

            orchestrator.clearLogEvents()
            val job = createTestJob()
            orchestrator.execute(job).toList()

            val logEvents = orchestrator.getLogEvents()
            assertTrue(logEvents.isNotEmpty())

            // Should have stage transitions
            assertTrue(logEvents.any { it is PipelineLogEvent.StageTransition })

            // Should have info messages
            assertTrue(logEvents.any { it is PipelineLogEvent.Info })
        }
    }

    // ==================== Cancellation Tests ====================

    @Nested
    inner class CancellationTest {

        @Test
        fun `pipeline cancels during download stage`() = runTest {
            // Setup download to hang
            coEvery { videoDownloader.download(any(), any()) } returns flow {
                emit(StageProgress(0.1f, "Downloading..."))
                delay(Long.MAX_VALUE) // Hang indefinitely
            }

            val job = createTestJob()

            val stages = mutableListOf<PipelineStage>()
            val executeJob = launch {
                orchestrator.execute(job).collect { stages.add(it) }
            }

            // Wait a bit then cancel
            delay(100)
            executeJob.cancelAndJoin()

            // Should have started downloading
            assertTrue(stages.any { it is PipelineStage.Downloading })
        }

        @Test
        fun `pipeline cancels during transcription stage`() = runTest {
            val videoFile = tempDir.resolve("test.mp4").toFile().apply { writeText("video") }

            // Setup successful download
            setupDownloadMock(videoFile.absolutePath)
            coEvery { videoDownloader.extractCaptions(any(), any<Language>()) } returns null

            // Setup transcription to hang
            coEvery { transcriberService.transcribe(any(), any<Language>()) } returns flow {
                emit(StageProgress(0.1f, "Transcribing..."))
                delay(Long.MAX_VALUE)
            }

            val job = createTestJob()
            val stages = mutableListOf<PipelineStage>()

            val executeJob = launch {
                orchestrator.execute(job).collect { stages.add(it) }
            }

            delay(100)
            executeJob.cancelAndJoin()

            // Should have reached transcription
            assertTrue(stages.any { it is PipelineStage.Transcribing })
        }

        @Test
        fun `pipeline cancels during translation stage`() = runTest {
            val videoFile = tempDir.resolve("test.mp4").toFile().apply { writeText("video") }

            setupDownloadMock(videoFile.absolutePath)
            setupTranscriptionMock()
            coEvery { videoDownloader.extractCaptions(any(), any<Language>()) } returns null

            // Setup translation to hang
            coEvery { translatorService.translate(any(), any()) } returns flow {
                emit(StageProgress(0.1f, "Translating..."))
                delay(Long.MAX_VALUE)
            }

            val job = createTestJob()
            val stages = mutableListOf<PipelineStage>()

            val executeJob = launch {
                orchestrator.execute(job).collect { stages.add(it) }
            }

            delay(100)
            executeJob.cancelAndJoin()

            assertTrue(stages.any { it is PipelineStage.Translating })
        }

        @Test
        fun `pipeline cancels during rendering stage`() = runTest {
            val videoFile = tempDir.resolve("test.mp4").toFile().apply { writeText("video") }

            setupDownloadMock(videoFile.absolutePath)
            coEvery { videoDownloader.extractCaptions(any(), any<Language>()) } returns testSubtitles
            setupTranslationMock()

            // Setup rendering to hang
            coEvery { subtitleRenderer.render(any(), any(), any(), any()) } returns flow {
                emit(StageProgress(0.1f, "Rendering..."))
                delay(Long.MAX_VALUE)
            }

            val job = createTestJob()
            val stages = mutableListOf<PipelineStage>()

            val executeJob = launch {
                orchestrator.execute(job).collect { stages.add(it) }
            }

            delay(100)
            executeJob.cancelAndJoin()

            assertTrue(stages.any { it is PipelineStage.Rendering })
        }
    }

    // ==================== Error Recovery Tests ====================

    @Nested
    inner class ErrorRecoveryTest {

        @Test
        fun `download error emits error stage`() = runTest {
            coEvery { videoDownloader.download(any(), any()) } throws
                YtDlpException(
                    errorType = YtDlpErrorType.VIDEO_NOT_FOUND,
                    userMessage = "Video unavailable",
                    technicalMessage = "HTTP 404"
                )

            val job = createTestJob()
            val stages = orchestrator.execute(job).toList()

            val errorStage = stages.filterIsInstance<PipelineStage.Error>().firstOrNull()
            assertNotNull(errorStage)
            assertEquals("Download", errorStage!!.stage)
        }

        @Test
        fun `unrecoverable error stops pipeline`() = runTest {
            coEvery { videoDownloader.download(any(), any()) } throws
                YtDlpException(
                    errorType = YtDlpErrorType.PRIVATE_VIDEO,
                    userMessage = "This video is private",
                    technicalMessage = "Private video"
                )

            val job = createTestJob()
            val stages = orchestrator.execute(job).toList()

            val errorStage = stages.filterIsInstance<PipelineStage.Error>().first()
            assertNotNull(errorStage)

            // Should NOT have any stages after error
            val errorIndex = stages.indexOfFirst { it is PipelineStage.Error }
            assertTrue(errorIndex == stages.size - 1)
        }
    }

    // ==================== Progress Emission Tests ====================

    @Nested
    inner class ProgressEmissionTest {

        @Test
        fun `pipeline emits progress for each stage`() = runTest {
            val videoFile = tempDir.resolve("test.mp4").toFile().apply { writeText("video") }
            setupSuccessfulPipelineMocks(videoFile.absolutePath)

            val job = createTestJob()
            val stages = orchestrator.execute(job).toList()

            // Verify we got progress for each major stage
            val stageTypes = stages.map { it::class.simpleName }.distinct()
            assertTrue(stageTypes.contains("Downloading"))
            assertTrue(stageTypes.contains("CheckingCaptions"))
            assertTrue(stageTypes.contains("Translating"))
            assertTrue(stageTypes.contains("Rendering"))
            assertTrue(stageTypes.contains("Complete"))
        }

        @Test
        fun `download progress increases monotonically`() = runTest {
            val videoFile = tempDir.resolve("test.mp4").toFile().apply { writeText("video") }

            // Setup download with multiple progress updates
            coEvery { videoDownloader.download(any(), any()) } returns flow {
                emit(StageProgress(0.0f, "Starting..."))
                emit(StageProgress(0.25f, "25%"))
                emit(StageProgress(0.50f, "50%"))
                emit(StageProgress(0.75f, "75%"))
                emit(StageProgress(1.0f, "Complete"))
            }
            every { videoDownloader.getDownloadedVideoPath(any(), any()) } returns videoFile.absolutePath
            coEvery { videoDownloader.extractCaptions(any(), any<Language>()) } returns testSubtitles
            setupTranslationMock()
            setupRenderMock()

            val job = createTestJob()
            val stages = orchestrator.execute(job).toList()

            val downloadProgresses = stages
                .filterIsInstance<PipelineStage.Downloading>()
                .map { it.progress }

            // Verify monotonic increase (allowing duplicates)
            for (i in 1 until downloadProgresses.size) {
                assertTrue(
                    downloadProgresses[i] >= downloadProgresses[i - 1],
                    "Progress should be monotonically increasing"
                )
            }
        }

        @Test
        fun `progress messages are descriptive`() = runTest {
            val videoFile = tempDir.resolve("test.mp4").toFile().apply { writeText("video") }
            setupSuccessfulPipelineMocks(videoFile.absolutePath)

            val job = createTestJob()
            val stages = orchestrator.execute(job).toList()

            // Check that messages are not empty
            val downloadingStages = stages.filterIsInstance<PipelineStage.Downloading>()
            assertTrue(downloadingStages.all { it.message.isNotEmpty() })

            val translatingStages = stages.filterIsInstance<PipelineStage.Translating>()
            assertTrue(translatingStages.all { it.message.isNotEmpty() })
        }
    }

    // ==================== Checkpoint Tests ====================

    @Nested
    inner class CheckpointTest {

        @Test
        fun `pipeline saves checkpoint after download`() = runTest {
            val videoFile = tempDir.resolve("test.mp4").toFile().apply { writeText("video") }
            setupSuccessfulPipelineMocks(videoFile.absolutePath)

            val job = createTestJob()
            orchestrator.execute(job).toList()

            // Check for checkpoint saved log
            val logEvents = orchestrator.getLogEvents()
            assertTrue(logEvents.any { it is PipelineLogEvent.CheckpointSaved })
        }

        @Test
        fun `pipeline can resume from checkpoint`() = runTest {
            val videoFile = tempDir.resolve("test.mp4").toFile().apply { writeText("video") }

            // Create a checkpoint as if download was completed
            val checkpoint = PipelineCheckpoint(
                jobId = "test-job",
                lastCompletedStage = PipelineStageName.DOWNLOAD,
                downloadedVideoPath = videoFile.absolutePath,
                subtitles = null,
                translatedSubtitles = null,
                videoInfo = testVideoInfo,
                targetLanguage = Language.GERMAN,
                outputOptions = createTestOutputOptions()
            )

            coEvery { videoDownloader.extractCaptions(any(), any<Language>()) } returns null
            setupTranscriptionMock()
            setupTranslationMock()
            setupRenderMock()

            val job = createTestJob()
            val stages = orchestrator.execute(job, checkpoint).toList()

            // Should NOT have download stage (resumed after it)
            val downloadStages = stages.filterIsInstance<PipelineStage.Downloading>()
            assertTrue(downloadStages.isEmpty(), "Should skip download when resuming from checkpoint")

            // Should still complete
            assertTrue(stages.any { it is PipelineStage.Complete })
        }

    }

    // ==================== Helper Methods ====================

    private fun createTestJob(): TranslationJob = TranslationJob(
        videoInfo = testVideoInfo,
        sourceLanguage = Language.ENGLISH,
        targetLanguage = Language.GERMAN,
        outputOptions = createTestOutputOptions()
    )

    private fun createTestOutputOptions(): OutputOptions = OutputOptions(
        outputDirectory = tempDir.toString(),
        subtitleType = SubtitleType.SOFT,
        exportSrt = false
    )

    private fun setupSuccessfulPipelineMocks(videoPath: String, skipTranscription: Boolean = false) {
        setupDownloadMock(videoPath)

        if (!skipTranscription) {
            coEvery { videoDownloader.extractCaptions(any(), any<Language>()) } returns null
            setupTranscriptionMock()
        }

        setupTranslationMock()
        setupRenderMock()
    }

    private fun setupDownloadMock(videoPath: String) {
        coEvery { videoDownloader.download(any(), any()) } returns flow {
            emit(StageProgress(0f, "Starting..."))
            emit(StageProgress(0.5f, "Downloading..."))
            emit(StageProgress(1f, "Complete"))
        }
        every { videoDownloader.getDownloadedVideoPath(any(), any()) } returns videoPath
    }

    private fun setupTranscriptionMock() {
        coEvery { transcriberService.transcribe(any(), any<Language>()) } returns flow {
            emit(StageProgress(0f, "Starting..."))
            emit(StageProgress(0.5f, "Transcribing..."))
            emit(StageProgress(1f, "Complete"))
        }
        every { transcriberService.getTranscriptionResult() } returns testSubtitles
    }

    private fun setupTranslationMock() {
        coEvery { translatorService.translate(any(), any()) } returns flow {
            emit(StageProgress(0f, "Starting..."))
            emit(StageProgress(0.5f, "Translating..."))
            emit(StageProgress(1f, "Complete"))
        }
        every { translatorService.getTranslationResult() } returns testTranslatedSubtitles
    }

    private fun setupRenderMock() {
        coEvery { subtitleRenderer.render(any(), any(), any(), any()) } returns flow {
            emit(StageProgress(0f, "Starting..."))
            emit(StageProgress(0.5f, "Rendering..."))
            emit(StageProgress(1f, "Complete"))
        }
        every { subtitleRenderer.getRenderResult() } returns testTranslationResult
    }
}
