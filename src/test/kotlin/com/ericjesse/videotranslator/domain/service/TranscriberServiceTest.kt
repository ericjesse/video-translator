package com.ericjesse.videotranslator.domain.service

import com.ericjesse.videotranslator.domain.model.Language
import com.ericjesse.videotranslator.domain.model.WhisperErrorType
import com.ericjesse.videotranslator.domain.model.WhisperException
import com.ericjesse.videotranslator.domain.model.WhisperModel
import com.ericjesse.videotranslator.domain.model.WhisperOptions
import com.ericjesse.videotranslator.domain.pipeline.StageProgress
import com.ericjesse.videotranslator.infrastructure.config.AppSettings
import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import com.ericjesse.videotranslator.infrastructure.config.TranscriptionSettings
import com.ericjesse.videotranslator.infrastructure.process.ProcessException
import com.ericjesse.videotranslator.infrastructure.process.ProcessExecutor
import com.ericjesse.videotranslator.infrastructure.process.ProcessResult
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

// Helper to check if command list contains a binary
private fun List<String>.containsBinary(name: String) = firstOrNull()?.contains(name) == true

class TranscriberServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var processExecutor: ProcessExecutor
    private lateinit var platformPaths: PlatformPaths
    private lateinit var configManager: ConfigManager
    private lateinit var transcriberService: TranscriberService

    @BeforeEach
    fun setup() {
        processExecutor = mockk()
        platformPaths = mockk()
        configManager = mockk()

        every { platformPaths.getBinaryPath("whisper") } returns "/usr/local/bin/whisper"
        every { platformPaths.getBinaryPath("ffmpeg") } returns "/usr/local/bin/ffmpeg"
        every { platformPaths.getBinaryPath("ffprobe") } returns "/usr/local/bin/ffprobe"
        every { platformPaths.cacheDir } returns tempDir.resolve("cache").toString().also {
            File(it).mkdirs()
        }
        every { platformPaths.modelsDir } returns tempDir.resolve("models").toString().also {
            File(it, "whisper").mkdirs()
        }

        // ConfigManager mocks
        every { configManager.getBinaryPath("whisper") } returns "/usr/local/bin/whisper"
        every { configManager.getBinaryPath("ffmpeg") } returns "/usr/local/bin/ffmpeg"
        every { configManager.getBinaryPath("ffprobe") } returns "/usr/local/bin/ffprobe"

        val settings = AppSettings(
            transcription = TranscriptionSettings(whisperModel = "base")
        )
        every { configManager.getSettings() } returns settings

        transcriberService = TranscriberService(processExecutor, platformPaths, configManager)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // ==================== Availability Tests ====================

    @Nested
    inner class AvailabilityTest {

        @Test
        fun `isAvailable delegates to processExecutor`() = runTest {
            coEvery { processExecutor.isAvailable(any()) } returns true

            val available = transcriberService.isAvailable()

            assertTrue(available)
            coVerify { processExecutor.isAvailable("/usr/local/bin/whisper") }
        }

        @Test
        fun `isAvailable returns false when whisper not found`() = runTest {
            coEvery { processExecutor.isAvailable(any()) } returns false

            val available = transcriberService.isAvailable()

            assertFalse(available)
        }

        @Test
        fun `getVersion delegates to processExecutor`() = runTest {
            coEvery { processExecutor.getVersion(any()) } returns "whisper.cpp v1.5.4"

            val version = transcriberService.getVersion()

            assertEquals("whisper.cpp v1.5.4", version)
            coVerify { processExecutor.getVersion("/usr/local/bin/whisper") }
        }

        @Test
        fun `isGpuAvailable checks whisper help output`() = runTest {
            coEvery {
                processExecutor.executeAndCapture(any(), any())
            } returns ProcessResult(0, "Options: --gpu, --cuda, --metal", "")

            val gpuAvailable = transcriberService.isGpuAvailable()

            assertTrue(gpuAvailable)
        }

        @Test
        fun `isGpuAvailable returns false when no GPU flags`() = runTest {
            coEvery {
                processExecutor.executeAndCapture(any(), any())
            } returns ProcessResult(0, "Options: --model, --language", "")

            val gpuAvailable = transcriberService.isGpuAvailable()

            assertFalse(gpuAvailable)
        }

        @Test
        fun `isGpuAvailable returns false on exception`() = runTest {
            coEvery {
                processExecutor.executeAndCapture(any(), any())
            } throws RuntimeException("Command failed")

            val gpuAvailable = transcriberService.isGpuAvailable()

            assertFalse(gpuAvailable)
        }
    }

    // ==================== Model Management Tests ====================

    @Nested
    inner class ModelManagementTest {

        @Test
        fun `getModelPath returns path when model exists`() {
            val modelFile = File(tempDir.toFile(), "models/whisper/ggml-base.bin")
            modelFile.parentFile.mkdirs()
            modelFile.writeText("model data")

            val path = transcriberService.getModelPath(WhisperModel.BASE)

            assertNotNull(path)
            assertTrue(path.endsWith("ggml-base.bin"))
        }

        @Test
        fun `getModelPath returns null when model does not exist`() {
            val path = transcriberService.getModelPath(WhisperModel.LARGE_V3)

            assertNull(path)
        }

        @Test
        fun `getAvailableModels returns downloaded models`() {
            val modelsDir = File(tempDir.toFile(), "models/whisper")
            modelsDir.mkdirs()
            File(modelsDir, "ggml-tiny.bin").writeText("tiny")
            File(modelsDir, "ggml-base.bin").writeText("base")
            File(modelsDir, "ggml-small.bin").writeText("small")

            val models = transcriberService.getAvailableModels()

            assertEquals(3, models.size)
            assertTrue(models.contains(WhisperModel.TINY))
            assertTrue(models.contains(WhisperModel.BASE))
            assertTrue(models.contains(WhisperModel.SMALL))
        }

        @Test
        fun `getAvailableModels returns empty list when no models`() {
            val models = transcriberService.getAvailableModels()

            assertTrue(models.isEmpty())
        }

        @Test
        fun `getAvailableModels ignores non-model files`() {
            val modelsDir = File(tempDir.toFile(), "models/whisper")
            modelsDir.mkdirs()
            File(modelsDir, "ggml-base.bin").writeText("base")
            File(modelsDir, "readme.txt").writeText("readme")
            File(modelsDir, "config.json").writeText("{}")

            val models = transcriberService.getAvailableModels()

            assertEquals(1, models.size)
            assertTrue(models.contains(WhisperModel.BASE))
        }
    }

    // ==================== Transcription Tests ====================

    @Nested
    inner class TranscriptionTest {

        @BeforeEach
        fun setupModel() {
            // Create model file
            val modelFile = File(tempDir.toFile(), "models/whisper/ggml-base.bin")
            modelFile.parentFile.mkdirs()
            modelFile.writeText("model data")
        }

        @Test
        fun `transcribe emits progress updates`() = runTest {
            val inputFile = File(tempDir.toFile(), "test.mp4")
            inputFile.writeText("video content")

            // Mock FFmpeg audio extraction
            coEvery {
                processExecutor.execute(match { it.containsBinary("ffmpeg") }, any(), any(), any())
            } coAnswers {
                // Create the output audio file
                val outputPath = File(tempDir.toFile(), "cache/transcription/test_audio.wav")
                outputPath.parentFile.mkdirs()
                outputPath.writeText("audio data")
                0
            }

            // Mock FFprobe duration
            coEvery {
                processExecutor.executeAndCapture(match { it.containsBinary("ffprobe") }, any())
            } returns ProcessResult(0, "60.0", "")

            // Mock Whisper transcription
            coEvery {
                processExecutor.execute(match { it.containsBinary("whisper") }, any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback("whisper_print_progress_callback: progress = 25%")
                callback("whisper_print_progress_callback: progress = 50%")
                callback("whisper_print_progress_callback: progress = 75%")
                callback("whisper_print_progress_callback: progress = 100%")

                // Create SRT output
                val outputDir = File(tempDir.toFile(), "cache/transcription")
                File(outputDir, "output_0.srt").writeText("""
                    1
                    00:00:00,000 --> 00:00:05,000
                    Hello world

                    2
                    00:00:05,000 --> 00:00:10,000
                    This is a test
                """.trimIndent())
                0
            }

            val progressList = transcriberService.transcribe(
                inputFile.absolutePath,
                WhisperOptions()
            ).toList()

            assertTrue(progressList.isNotEmpty())
            assertEquals(0f, progressList.first().percentage)
            assertEquals(1f, progressList.last().percentage)
        }

        @Test
        fun `transcribe throws on missing input file`() = runTest {
            val exception = assertFailsWith<WhisperException> {
                transcriberService.transcribe(
                    "/nonexistent/file.mp4",
                    WhisperOptions()
                ).toList()
            }

            assertEquals(WhisperErrorType.AUDIO_NOT_FOUND, exception.errorType)
        }

        @Test
        fun `transcribe throws on missing model`() = runTest {
            // Delete the model file
            File(tempDir.toFile(), "models/whisper/ggml-base.bin").delete()

            val inputFile = File(tempDir.toFile(), "test.mp4")
            inputFile.writeText("video content")

            // Mock FFmpeg
            coEvery {
                processExecutor.execute(match { it.containsBinary("ffmpeg") }, any(), any(), any())
            } coAnswers {
                val outputPath = File(tempDir.toFile(), "cache/transcription/test_audio.wav")
                outputPath.parentFile.mkdirs()
                outputPath.writeText("audio data")
                0
            }

            // Mock FFprobe
            coEvery {
                processExecutor.executeAndCapture(match { it.containsBinary("ffprobe") }, any())
            } returns ProcessResult(0, "60.0", "")

            val exception = assertFailsWith<WhisperException> {
                transcriberService.transcribe(
                    inputFile.absolutePath,
                    WhisperOptions()
                ).toList()
            }

            assertEquals(WhisperErrorType.MODEL_NOT_FOUND, exception.errorType)
        }

        @Test
        fun `transcribe with Language uses correct options`() = runTest {
            val inputFile = File(tempDir.toFile(), "test.mp4")
            inputFile.writeText("video content")

            val capturedCommands = mutableListOf<List<String>>()

            // Mock FFmpeg
            coEvery {
                processExecutor.execute(match { it.containsBinary("ffmpeg") }, any(), any(), any())
            } coAnswers {
                val outputPath = File(tempDir.toFile(), "cache/transcription/test_audio.wav")
                outputPath.parentFile.mkdirs()
                outputPath.writeText("audio data")
                0
            }

            // Mock FFprobe
            coEvery {
                processExecutor.executeAndCapture(match { it.containsBinary("ffprobe") }, any())
            } returns ProcessResult(0, "60.0", "")

            // Mock Whisper
            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } coAnswers {
                if (capturedCommands.last().containsBinary("whisper")) {
                    val outputDir = File(tempDir.toFile(), "cache/transcription")
                    File(outputDir, "output_0.srt").writeText("1\n00:00:00,000 --> 00:00:05,000\nTest")
                }
                0
            }

            transcriberService.transcribe(inputFile.absolutePath, Language.GERMAN).toList()

            // Find the whisper command
            val whisperCommand = capturedCommands.find { it.containsBinary("whisper") }
            assertNotNull(whisperCommand)
            assertTrue(whisperCommand.contains("--language"))
            assertTrue(whisperCommand.contains("de"))
        }
    }

    // ==================== Result Retrieval Tests ====================

    @Nested
    inner class ResultRetrievalTest {

        @Test
        fun `getTranscriptionResult throws when no result available`() {
            val exception = assertFailsWith<IllegalStateException> {
                transcriberService.getTranscriptionResult()
            }

            assertTrue(exception.message!!.contains("No transcription result"))
        }

        @Test
        fun `getFullResult throws when no result available`() {
            val exception = assertFailsWith<IllegalStateException> {
                transcriberService.getFullResult()
            }

            assertTrue(exception.message!!.contains("No transcription result"))
        }

        @Test
        fun `getWordLevelSubtitles throws when no result available`() {
            val exception = assertFailsWith<IllegalStateException> {
                transcriberService.getWordLevelSubtitles()
            }

            assertTrue(exception.message!!.contains("No transcription result"))
        }
    }

    // ==================== Progress Parsing Tests ====================

    @Nested
    inner class ProgressParsingTest {

        @BeforeEach
        fun setupModel() {
            val modelFile = File(tempDir.toFile(), "models/whisper/ggml-base.bin")
            modelFile.parentFile.mkdirs()
            modelFile.writeText("model data")
        }

        @Test
        fun `parses percentage-based progress`() = runTest {
            val inputFile = File(tempDir.toFile(), "test.mp4")
            inputFile.writeText("video content")

            val progressMessages = mutableListOf<StageProgress>()

            // Mock FFmpeg
            coEvery {
                processExecutor.execute(match { it.containsBinary("ffmpeg") }, any(), any(), any())
            } coAnswers {
                val outputPath = File(tempDir.toFile(), "cache/transcription/test_audio.wav")
                outputPath.parentFile.mkdirs()
                outputPath.writeText("audio data")
                0
            }

            // Mock FFprobe
            coEvery {
                processExecutor.executeAndCapture(match { it.containsBinary("ffprobe") }, any())
            } returns ProcessResult(0, "60.0", "")

            // Mock Whisper with percentage progress
            coEvery {
                processExecutor.execute(match { it.containsBinary("whisper") }, any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback("whisper_print_progress_callback: progress = 50%")

                val outputDir = File(tempDir.toFile(), "cache/transcription")
                File(outputDir, "output_0.srt").writeText("1\n00:00:00,000 --> 00:00:05,000\nTest")
                0
            }

            transcriberService.transcribe(inputFile.absolutePath, WhisperOptions()).collect {
                progressMessages.add(it)
            }

            // Check that we got progress between 0 and 1
            val midProgress = progressMessages.find { it.percentage > 0.1f && it.percentage < 0.95f }
            assertNotNull(midProgress, "Should have intermediate progress")
            assertTrue(midProgress.message.contains("Transcribing") || midProgress.message.contains("%"))
        }

        @Test
        fun `parses timestamp-based progress`() = runTest {
            val inputFile = File(tempDir.toFile(), "test.mp4")
            inputFile.writeText("video content")

            // Mock FFmpeg
            coEvery {
                processExecutor.execute(match { it.containsBinary("ffmpeg") }, any(), any(), any())
            } coAnswers {
                val outputPath = File(tempDir.toFile(), "cache/transcription/test_audio.wav")
                outputPath.parentFile.mkdirs()
                outputPath.writeText("audio data")
                0
            }

            // Mock FFprobe - 2 minute audio
            coEvery {
                processExecutor.executeAndCapture(match { it.containsBinary("ffprobe") }, any())
            } returns ProcessResult(0, "120.0", "")

            // Mock Whisper with timestamp progress
            coEvery {
                processExecutor.execute(match { it.containsBinary("whisper") }, any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback("[00:01:00.000 --> 00:01:05.000] Some text")

                val outputDir = File(tempDir.toFile(), "cache/transcription")
                File(outputDir, "output_0.srt").writeText("1\n00:00:00,000 --> 00:00:05,000\nTest")
                0
            }

            val progressList = transcriberService.transcribe(inputFile.absolutePath, WhisperOptions()).toList()

            // Should have progress updates
            assertTrue(progressList.size > 2)
        }

        @Test
        fun `detects language from output`() = runTest {
            val inputFile = File(tempDir.toFile(), "test.mp4")
            inputFile.writeText("video content")

            // Mock FFmpeg
            coEvery {
                processExecutor.execute(match { it.containsBinary("ffmpeg") }, any(), any(), any())
            } coAnswers {
                val outputPath = File(tempDir.toFile(), "cache/transcription/test_audio.wav")
                outputPath.parentFile.mkdirs()
                outputPath.writeText("audio data")
                0
            }

            // Mock FFprobe
            coEvery {
                processExecutor.executeAndCapture(match { it.containsBinary("ffprobe") }, any())
            } returns ProcessResult(0, "60.0", "")

            // Mock Whisper with language detection
            coEvery {
                processExecutor.execute(match { it.containsBinary("whisper") }, any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback("auto-detected language: German")

                val outputDir = File(tempDir.toFile(), "cache/transcription")
                File(outputDir, "output_0.srt").writeText("1\n00:00:00,000 --> 00:00:05,000\nHallo Welt")
                0
            }

            transcriberService.transcribe(inputFile.absolutePath, WhisperOptions()).toList()

            val result = transcriberService.getFullResult()
            assertEquals("de", result.detectedLanguage)
        }
    }

    // ==================== Audio Segmentation Tests ====================

    @Nested
    inner class AudioSegmentationTest {

        @BeforeEach
        fun setupModel() {
            val modelFile = File(tempDir.toFile(), "models/whisper/ggml-base.bin")
            modelFile.parentFile.mkdirs()
            modelFile.writeText("model data")
        }

        @Test
        fun `detects audio duration for potential segmentation`() = runTest {
            val inputFile = File(tempDir.toFile(), "test.mp4")
            inputFile.writeText("video content")

            // Mock FFmpeg - create audio file
            coEvery {
                processExecutor.execute(match { it.containsBinary("ffmpeg") }, any(), any(), any())
            } coAnswers {
                val outputPath = File(tempDir.toFile(), "cache/transcription/test_audio.wav")
                outputPath.parentFile.mkdirs()
                outputPath.writeText("audio data")
                0
            }

            // Mock FFprobe - returns audio duration
            coEvery {
                processExecutor.executeAndCapture(match { it.containsBinary("ffprobe") }, any())
            } returns ProcessResult(0, "300.0", "") // 5 minutes

            // Mock Whisper
            coEvery {
                processExecutor.execute(match { it.containsBinary("whisper") }, any(), any(), any())
            } coAnswers {
                val outputDir = File(tempDir.toFile(), "cache/transcription")
                File(outputDir, "output_0.srt").writeText("1\n00:00:00,000 --> 00:00:05,000\nTest")
                0
            }

            transcriberService.transcribe(inputFile.absolutePath, WhisperOptions()).toList()

            // Verify FFprobe was called to get duration
            coVerify { processExecutor.executeAndCapture(match { it.containsBinary("ffprobe") }, any()) }
        }

        @Test
        fun `does not segment short audio`() = runTest {
            val inputFile = File(tempDir.toFile(), "short_video.mp4")
            inputFile.writeText("video content")

            val ffmpegCalls = mutableListOf<List<String>>()

            // Mock FFmpeg
            coEvery {
                processExecutor.execute(match { it.containsBinary("ffmpeg") }, any(), any(), any())
            } coAnswers {
                val command = firstArg<List<String>>()
                ffmpegCalls.add(command)

                val outputIndex = command.indexOf("-y") + 1
                if (outputIndex > 0 && outputIndex < command.size) {
                    val outputPath = File(command[outputIndex])
                    outputPath.parentFile?.mkdirs()
                    outputPath.writeText("audio data")
                }
                0
            }

            // Mock FFprobe - 5 minute audio
            coEvery {
                processExecutor.executeAndCapture(match { it.containsBinary("ffprobe") }, any())
            } returns ProcessResult(0, "300.0", "") // 5 minutes

            // Mock Whisper
            coEvery {
                processExecutor.execute(match { it.containsBinary("whisper") }, any(), any(), any())
            } coAnswers {
                val outputDir = File(tempDir.toFile(), "cache/transcription")
                File(outputDir, "output_0.srt").writeText("1\n00:00:00,000 --> 00:00:05,000\nTest")
                0
            }

            transcriberService.transcribe(inputFile.absolutePath, WhisperOptions()).toList()

            // Should have 2 FFmpeg calls (MP3 extraction + WAV conversion)
            assertEquals(2, ffmpegCalls.size, "Should have MP3 extraction + WAV conversion for short files")
        }
    }

    // ==================== Error Handling Tests ====================

    @Nested
    inner class ErrorHandlingTest {

        @BeforeEach
        fun setupModel() {
            val modelFile = File(tempDir.toFile(), "models/whisper/ggml-base.bin")
            modelFile.parentFile.mkdirs()
            modelFile.writeText("model data")
        }

        @Test
        fun `converts FFmpeg error to WhisperException`() = runTest {
            val inputFile = File(tempDir.toFile(), "test.mp4")
            inputFile.writeText("video content")

            coEvery {
                processExecutor.execute(match { it.containsBinary("ffmpeg") }, any(), any(), any())
            } throws ProcessException("ffmpeg", 1, "Invalid input file")

            val exception = assertFailsWith<WhisperException> {
                transcriberService.transcribe(inputFile.absolutePath, WhisperOptions()).toList()
            }

            assertNotNull(exception)
        }

        @Test
        fun `converts Whisper error to WhisperException`() = runTest {
            val inputFile = File(tempDir.toFile(), "test.mp4")
            inputFile.writeText("video content")

            // Mock FFmpeg success
            coEvery {
                processExecutor.execute(match { it.containsBinary("ffmpeg") }, any(), any(), any())
            } coAnswers {
                val outputPath = File(tempDir.toFile(), "cache/transcription/test_audio.wav")
                outputPath.parentFile.mkdirs()
                outputPath.writeText("audio data")
                0
            }

            // Mock FFprobe
            coEvery {
                processExecutor.executeAndCapture(match { it.containsBinary("ffprobe") }, any())
            } returns ProcessResult(0, "60.0", "")

            // Mock Whisper failure
            coEvery {
                processExecutor.execute(match { it.containsBinary("whisper") }, any(), any(), any())
            } throws ProcessException("whisper", 1, "out of memory")

            val exception = assertFailsWith<WhisperException> {
                transcriberService.transcribe(inputFile.absolutePath, WhisperOptions()).toList()
            }

            assertEquals(WhisperErrorType.OUT_OF_MEMORY, exception.errorType)
        }

        @Test
        fun `temp files are kept for deferred cleanup on error`() = runTest {
            // Note: Temp files are now kept until app shutdown via TempFileManager
            // This test verifies that the error is properly thrown even with temp files present
            val inputFile = File(tempDir.toFile(), "test.mp4")
            inputFile.writeText("video content")

            val createdFiles = mutableListOf<File>()

            // Mock FFmpeg - create audio file
            coEvery {
                processExecutor.execute(match { it.containsBinary("ffmpeg") }, any(), any(), any())
            } coAnswers {
                val outputPath = File(tempDir.toFile(), "cache/transcription/test_audio.wav")
                outputPath.parentFile.mkdirs()
                outputPath.writeText("audio data")
                createdFiles.add(outputPath)
                0
            }

            // Mock FFprobe
            coEvery {
                processExecutor.executeAndCapture(match { it.containsBinary("ffprobe") }, any())
            } returns ProcessResult(0, "60.0", "")

            // Mock Whisper failure
            coEvery {
                processExecutor.execute(match { it.containsBinary("whisper") }, any(), any(), any())
            } throws ProcessException("whisper", 1, "Failed")

            val exception = assertFailsWith<WhisperException> {
                transcriberService.transcribe(inputFile.absolutePath, WhisperOptions()).toList()
            }

            // Verify error is properly thrown
            assertNotNull(exception)

            // Temp files are now kept for deferred cleanup via TempFileManager
            // They will be cleaned up at app shutdown, not immediately
        }
    }

    // ==================== Command Building Tests ====================

    @Nested
    inner class CommandBuildingTest {

        @BeforeEach
        fun setupModel() {
            val modelFile = File(tempDir.toFile(), "models/whisper/ggml-base.bin")
            modelFile.parentFile.mkdirs()
            modelFile.writeText("model data")
        }

        @Test
        fun `includes GPU flag when useGpu is true`() = runTest {
            val inputFile = File(tempDir.toFile(), "test.mp4")
            inputFile.writeText("video content")

            val capturedCommands = mutableListOf<List<String>>()

            // Mock FFmpeg
            coEvery {
                processExecutor.execute(match { it.containsBinary("ffmpeg") }, any(), any(), any())
            } coAnswers {
                val outputPath = File(tempDir.toFile(), "cache/transcription/test_audio.wav")
                outputPath.parentFile.mkdirs()
                outputPath.writeText("audio data")
                0
            }

            // Mock FFprobe
            coEvery {
                processExecutor.executeAndCapture(match { it.containsBinary("ffprobe") }, any())
            } returns ProcessResult(0, "60.0", "")

            // Mock Whisper
            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } coAnswers {
                if (capturedCommands.last().containsBinary("whisper")) {
                    val outputDir = File(tempDir.toFile(), "cache/transcription")
                    File(outputDir, "output_0.srt").writeText("1\n00:00:00,000 --> 00:00:05,000\nTest")
                }
                0
            }

            transcriberService.transcribe(
                inputFile.absolutePath,
                WhisperOptions(useGpu = true)
            ).toList()

            val whisperCommand = capturedCommands.find { it.containsBinary("whisper") }
            assertNotNull(whisperCommand)
            // GPU is enabled by default in whisper-cpp, so no flag is added when useGpu=true
            // We only add --no-gpu when useGpu=false
            assertFalse(whisperCommand.contains("--no-gpu"), "Should NOT contain --no-gpu when GPU is enabled")
        }

        @Test
        fun `includes translate flag when translate is true`() = runTest {
            val inputFile = File(tempDir.toFile(), "test.mp4")
            inputFile.writeText("video content")

            val capturedCommands = mutableListOf<List<String>>()

            // Mock all processes
            coEvery {
                processExecutor.execute(match { it.containsBinary("ffmpeg") }, any(), any(), any())
            } coAnswers {
                val outputPath = File(tempDir.toFile(), "cache/transcription/test_audio.wav")
                outputPath.parentFile.mkdirs()
                outputPath.writeText("audio data")
                0
            }

            coEvery {
                processExecutor.executeAndCapture(match { it.containsBinary("ffprobe") }, any())
            } returns ProcessResult(0, "60.0", "")

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } coAnswers {
                if (capturedCommands.last().containsBinary("whisper")) {
                    val outputDir = File(tempDir.toFile(), "cache/transcription")
                    File(outputDir, "output_0.srt").writeText("1\n00:00:00,000 --> 00:00:05,000\nTest")
                }
                0
            }

            transcriberService.transcribe(
                inputFile.absolutePath,
                WhisperOptions(translate = true)
            ).toList()

            val whisperCommand = capturedCommands.find { it.containsBinary("whisper") }
            assertNotNull(whisperCommand)
            assertTrue(whisperCommand.contains("--translate"))
        }

        @Test
        fun `includes thread count when specified`() = runTest {
            val inputFile = File(tempDir.toFile(), "test.mp4")
            inputFile.writeText("video content")

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(match { it.containsBinary("ffmpeg") }, any(), any(), any())
            } coAnswers {
                val outputPath = File(tempDir.toFile(), "cache/transcription/test_audio.wav")
                outputPath.parentFile.mkdirs()
                outputPath.writeText("audio data")
                0
            }

            coEvery {
                processExecutor.executeAndCapture(match { it.containsBinary("ffprobe") }, any())
            } returns ProcessResult(0, "60.0", "")

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } coAnswers {
                if (capturedCommands.last().containsBinary("whisper")) {
                    val outputDir = File(tempDir.toFile(), "cache/transcription")
                    File(outputDir, "output_0.srt").writeText("1\n00:00:00,000 --> 00:00:05,000\nTest")
                }
                0
            }

            transcriberService.transcribe(
                inputFile.absolutePath,
                WhisperOptions(threads = 8)
            ).toList()

            val whisperCommand = capturedCommands.find { it.containsBinary("whisper") }
            assertNotNull(whisperCommand)
            assertTrue(whisperCommand.contains("--threads"))
            assertTrue(whisperCommand.contains("8"))
        }

        @Test
        fun `includes prompt when specified`() = runTest {
            val inputFile = File(tempDir.toFile(), "test.mp4")
            inputFile.writeText("video content")

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(match { it.containsBinary("ffmpeg") }, any(), any(), any())
            } coAnswers {
                val outputPath = File(tempDir.toFile(), "cache/transcription/test_audio.wav")
                outputPath.parentFile.mkdirs()
                outputPath.writeText("audio data")
                0
            }

            coEvery {
                processExecutor.executeAndCapture(match { it.containsBinary("ffprobe") }, any())
            } returns ProcessResult(0, "60.0", "")

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } coAnswers {
                if (capturedCommands.last().containsBinary("whisper")) {
                    val outputDir = File(tempDir.toFile(), "cache/transcription")
                    File(outputDir, "output_0.srt").writeText("1\n00:00:00,000 --> 00:00:05,000\nTest")
                }
                0
            }

            transcriberService.transcribe(
                inputFile.absolutePath,
                WhisperOptions(prompt = "Technical discussion about software")
            ).toList()

            val whisperCommand = capturedCommands.find { it.containsBinary("whisper") }
            assertNotNull(whisperCommand)
            assertTrue(whisperCommand.contains("--prompt"))
            assertTrue(whisperCommand.contains("Technical discussion about software"))
        }
    }
}
