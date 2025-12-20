package com.ericjesse.videotranslator.domain.pipeline

import com.ericjesse.videotranslator.domain.model.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class PipelineModelsTest {

    // ==================== StageResult Tests ====================

    @Nested
    inner class StageResultTest {

        @Test
        fun `Success contains data and metadata`() {
            val result = StageResult.Success(
                data = "test-data",
                stage = PipelineStageName.DOWNLOAD,
                durationMs = 1000L,
                metrics = StageMetrics(durationMs = 1000L, bytesProcessed = 1024L)
            )

            assertEquals("test-data", result.data)
            assertEquals(PipelineStageName.DOWNLOAD, result.stage)
            assertEquals(1000L, result.durationMs)
            assertNotNull(result.metrics)
            assertEquals(1024L, result.metrics?.bytesProcessed)
        }

        @Test
        fun `Success isSuccess returns true`() {
            val result = StageResult.Success(
                data = "data",
                stage = PipelineStageName.DOWNLOAD,
                durationMs = 100L
            )

            assertTrue(result.isSuccess)
            assertFalse(result.isFailure)
            assertFalse(result.isPartial)
        }

        @Test
        fun `Failure contains error and recovery strategy`() {
            val error = PipelineError(
                code = ErrorCode.NETWORK_TIMEOUT,
                stage = PipelineStageName.DOWNLOAD,
                message = "Connection timed out"
            )
            val strategy = RecoveryStrategy.Retry(maxAttempts = 3)

            val result = StageResult.Failure(
                stage = PipelineStageName.DOWNLOAD,
                error = error,
                recoveryStrategy = strategy,
                attemptNumber = 2
            )

            assertEquals(PipelineStageName.DOWNLOAD, result.stage)
            assertEquals(error, result.error)
            assertEquals(strategy, result.recoveryStrategy)
            assertEquals(2, result.attemptNumber)
        }

        @Test
        fun `Failure isFailure returns true`() {
            val result = StageResult.Failure(
                stage = PipelineStageName.DOWNLOAD,
                error = PipelineError(
                    code = ErrorCode.UNKNOWN,
                    stage = PipelineStageName.DOWNLOAD,
                    message = "Error"
                )
            )

            assertFalse(result.isSuccess)
            assertTrue(result.isFailure)
            assertFalse(result.isPartial)
        }

        @Test
        fun `Partial contains partial data and completion info`() {
            val error = PipelineError(
                code = ErrorCode.NETWORK_TIMEOUT,
                stage = PipelineStageName.TRANSLATION,
                message = "Partial translation"
            )

            val result = StageResult.Partial(
                partialData = listOf("entry1", "entry2"),
                stage = PipelineStageName.TRANSLATION,
                completedPortion = 0.5f,
                error = error,
                recoverable = true
            )

            assertEquals(listOf("entry1", "entry2"), result.partialData)
            assertEquals(0.5f, result.completedPortion)
            assertTrue(result.recoverable)
        }

        @Test
        fun `Partial isPartial returns true`() {
            val result = StageResult.Partial(
                partialData = "data",
                stage = PipelineStageName.TRANSLATION,
                completedPortion = 0.5f,
                error = PipelineError(
                    code = ErrorCode.UNKNOWN,
                    stage = PipelineStageName.TRANSLATION,
                    message = "Partial"
                )
            )

            assertFalse(result.isSuccess)
            assertFalse(result.isFailure)
            assertTrue(result.isPartial)
        }

        @Test
        fun `Skipped contains stage and reason`() {
            val result = StageResult.Skipped(
                stage = PipelineStageName.TRANSCRIPTION,
                reason = "Captions already exist"
            )

            assertEquals(PipelineStageName.TRANSCRIPTION, result.stage)
            assertEquals("Captions already exist", result.reason)
        }

        @Test
        fun `map transforms Success data`() {
            val result = StageResult.Success(
                data = 10,
                stage = PipelineStageName.DOWNLOAD,
                durationMs = 100L
            )

            val mapped = result.map { it * 2 }

            assertTrue(mapped is StageResult.Success)
            assertEquals(20, mapped.getOrNull())
        }

        @Test
        fun `map transforms Partial data`() {
            val result = StageResult.Partial(
                partialData = 5,
                stage = PipelineStageName.TRANSLATION,
                completedPortion = 0.5f,
                error = PipelineError(
                    code = ErrorCode.UNKNOWN,
                    stage = PipelineStageName.TRANSLATION,
                    message = "Error"
                )
            )

            val mapped = result.map { it * 3 }

            assertTrue(mapped is StageResult.Partial)
            assertEquals(15, mapped.getOrNull())
        }

        @Test
        fun `map preserves Failure`() {
            val error = PipelineError(
                code = ErrorCode.NETWORK_TIMEOUT,
                stage = PipelineStageName.DOWNLOAD,
                message = "Timeout"
            )
            val result: StageResult<Int> = StageResult.Failure(
                stage = PipelineStageName.DOWNLOAD,
                error = error
            )

            val mapped = result.map { it * 2 }

            assertTrue(mapped is StageResult.Failure)
        }

        @Test
        fun `map preserves Skipped`() {
            val result: StageResult<Int> = StageResult.Skipped(
                stage = PipelineStageName.TRANSCRIPTION,
                reason = "Not needed"
            )

            val mapped = result.map { it * 2 }

            assertTrue(mapped is StageResult.Skipped)
        }

        @Test
        fun `getOrNull returns data for Success`() {
            val result = StageResult.Success(
                data = "test",
                stage = PipelineStageName.DOWNLOAD,
                durationMs = 100L
            )

            assertEquals("test", result.getOrNull())
        }

        @Test
        fun `getOrNull returns partialData for Partial`() {
            val result = StageResult.Partial(
                partialData = "partial",
                stage = PipelineStageName.TRANSLATION,
                completedPortion = 0.5f,
                error = PipelineError(
                    code = ErrorCode.UNKNOWN,
                    stage = PipelineStageName.TRANSLATION,
                    message = "Error"
                )
            )

            assertEquals("partial", result.getOrNull())
        }

        @Test
        fun `getOrNull returns null for Failure`() {
            val result = StageResult.Failure(
                stage = PipelineStageName.DOWNLOAD,
                error = PipelineError(
                    code = ErrorCode.UNKNOWN,
                    stage = PipelineStageName.DOWNLOAD,
                    message = "Error"
                )
            )

            assertNull(result.getOrNull())
        }

        @Test
        fun `getOrNull returns null for Skipped`() {
            val result = StageResult.Skipped(
                stage = PipelineStageName.TRANSCRIPTION,
                reason = "Not needed"
            )

            assertNull(result.getOrNull())
        }

        @Test
        fun `getOrThrow returns data for Success`() {
            val result = StageResult.Success(
                data = "success",
                stage = PipelineStageName.DOWNLOAD,
                durationMs = 100L
            )

            assertEquals("success", result.getOrThrow())
        }

        @Test
        fun `getOrThrow throws PipelineException for Failure`() {
            val result = StageResult.Failure(
                stage = PipelineStageName.DOWNLOAD,
                error = PipelineError(
                    code = ErrorCode.NETWORK_TIMEOUT,
                    stage = PipelineStageName.DOWNLOAD,
                    message = "Timeout occurred"
                )
            )

            val exception = assertFailsWith<PipelineException> {
                result.getOrThrow()
            }

            assertEquals(ErrorCode.NETWORK_TIMEOUT, exception.error.code)
        }

        @Test
        fun `getOrThrow throws IllegalStateException for Skipped`() {
            val result = StageResult.Skipped(
                stage = PipelineStageName.TRANSCRIPTION,
                reason = "Already done"
            )

            val exception = assertFailsWith<IllegalStateException> {
                result.getOrThrow()
            }

            assertTrue(exception.message!!.contains("skipped"))
        }
    }

    // ==================== PipelineStageName Tests ====================

    @Nested
    inner class PipelineStageNameTest {

        @Test
        fun `stages have correct order`() {
            assertEquals(1, PipelineStageName.DOWNLOAD.order)
            assertEquals(2, PipelineStageName.CAPTION_CHECK.order)
            assertEquals(3, PipelineStageName.TRANSCRIPTION.order)
            assertEquals(4, PipelineStageName.TRANSLATION.order)
            assertEquals(5, PipelineStageName.RENDERING.order)
        }

        @Test
        fun `stages have display names`() {
            PipelineStageName.entries.forEach { stage ->
                assertTrue(stage.displayName.isNotBlank())
            }
        }

        @Test
        fun `fromOrder returns correct stage`() {
            assertEquals(PipelineStageName.DOWNLOAD, PipelineStageName.fromOrder(1))
            assertEquals(PipelineStageName.CAPTION_CHECK, PipelineStageName.fromOrder(2))
            assertEquals(PipelineStageName.TRANSCRIPTION, PipelineStageName.fromOrder(3))
            assertEquals(PipelineStageName.TRANSLATION, PipelineStageName.fromOrder(4))
            assertEquals(PipelineStageName.RENDERING, PipelineStageName.fromOrder(5))
        }

        @Test
        fun `fromOrder returns null for invalid order`() {
            assertNull(PipelineStageName.fromOrder(0))
            assertNull(PipelineStageName.fromOrder(6))
            assertNull(PipelineStageName.fromOrder(-1))
        }
    }

    // ==================== PipelineError Tests ====================

    @Nested
    inner class PipelineErrorTest {

        @Test
        fun `error contains all fields`() {
            val error = PipelineError(
                code = ErrorCode.NETWORK_TIMEOUT,
                stage = PipelineStageName.DOWNLOAD,
                message = "Connection timed out",
                technicalDetails = "java.net.SocketTimeoutException",
                suggestion = "Check your internet connection",
                recoverable = true,
                retryable = true
            )

            assertEquals(ErrorCode.NETWORK_TIMEOUT, error.code)
            assertEquals(PipelineStageName.DOWNLOAD, error.stage)
            assertEquals("Connection timed out", error.message)
            assertEquals("java.net.SocketTimeoutException", error.technicalDetails)
            assertEquals("Check your internet connection", error.suggestion)
            assertTrue(error.recoverable)
            assertTrue(error.retryable)
            assertTrue(error.timestamp > 0)
        }

        @Test
        fun `toException creates PipelineException`() {
            val error = PipelineError(
                code = ErrorCode.API_KEY_INVALID,
                stage = PipelineStageName.TRANSLATION,
                message = "Invalid API key"
            )

            val exception = error.toException()

            assertIs<PipelineException>(exception)
            assertEquals(error, exception.error)
        }

        @Test
        fun `toUserMessage includes stage and message`() {
            val error = PipelineError(
                code = ErrorCode.NETWORK_TIMEOUT,
                stage = PipelineStageName.DOWNLOAD,
                message = "Connection timed out"
            )

            val userMessage = error.toUserMessage()

            assertTrue(userMessage.contains("Download"))
            assertTrue(userMessage.contains("Connection timed out"))
        }

        @Test
        fun `toUserMessage includes suggestion when present`() {
            val error = PipelineError(
                code = ErrorCode.NETWORK_TIMEOUT,
                stage = PipelineStageName.DOWNLOAD,
                message = "Connection timed out",
                suggestion = "Check your network"
            )

            val userMessage = error.toUserMessage()

            assertTrue(userMessage.contains("Check your network"))
            assertTrue(userMessage.contains("Suggestion"))
        }
    }

    // ==================== ErrorCode Tests ====================

    @Nested
    inner class ErrorCodeTest {

        @Test
        fun `network errors have NETWORK category`() {
            assertEquals(ErrorCategory.NETWORK, ErrorCode.NETWORK_TIMEOUT.category)
            assertEquals(ErrorCategory.NETWORK, ErrorCode.NETWORK_UNREACHABLE.category)
            assertEquals(ErrorCategory.NETWORK, ErrorCode.CONNECTION_REFUSED.category)
            assertEquals(ErrorCategory.NETWORK, ErrorCode.SSL_ERROR.category)
        }

        @Test
        fun `API errors have API category`() {
            assertEquals(ErrorCategory.API, ErrorCode.API_KEY_INVALID.category)
            assertEquals(ErrorCategory.API, ErrorCode.API_KEY_MISSING.category)
            assertEquals(ErrorCategory.API, ErrorCode.RATE_LIMITED.category)
            assertEquals(ErrorCategory.API, ErrorCode.QUOTA_EXCEEDED.category)
            assertEquals(ErrorCategory.API, ErrorCode.API_ERROR.category)
        }

        @Test
        fun `resource errors have RESOURCE category`() {
            assertEquals(ErrorCategory.RESOURCE, ErrorCode.MODEL_NOT_FOUND.category)
            assertEquals(ErrorCategory.RESOURCE, ErrorCode.BINARY_NOT_FOUND.category)
            assertEquals(ErrorCategory.RESOURCE, ErrorCode.FILE_NOT_FOUND.category)
            assertEquals(ErrorCategory.RESOURCE, ErrorCode.DISK_FULL.category)
            assertEquals(ErrorCategory.RESOURCE, ErrorCode.INSUFFICIENT_MEMORY.category)
        }

        @Test
        fun `input errors have INPUT category`() {
            assertEquals(ErrorCategory.INPUT, ErrorCode.INVALID_URL.category)
            assertEquals(ErrorCategory.INPUT, ErrorCode.UNSUPPORTED_FORMAT.category)
            assertEquals(ErrorCategory.INPUT, ErrorCode.VIDEO_UNAVAILABLE.category)
            assertEquals(ErrorCategory.INPUT, ErrorCode.AGE_RESTRICTED.category)
            assertEquals(ErrorCategory.INPUT, ErrorCode.PRIVATE_VIDEO.category)
            assertEquals(ErrorCategory.INPUT, ErrorCode.REGION_BLOCKED.category)
        }

        @Test
        fun `processing errors have PROCESSING category`() {
            assertEquals(ErrorCategory.PROCESSING, ErrorCode.TRANSCRIPTION_FAILED.category)
            assertEquals(ErrorCategory.PROCESSING, ErrorCode.TRANSLATION_FAILED.category)
            assertEquals(ErrorCategory.PROCESSING, ErrorCode.ENCODING_FAILED.category)
            assertEquals(ErrorCategory.PROCESSING, ErrorCode.SUBTITLE_PARSE_ERROR.category)
        }

        @Test
        fun `system errors have SYSTEM category`() {
            assertEquals(ErrorCategory.SYSTEM, ErrorCode.PROCESS_CRASHED.category)
            assertEquals(ErrorCategory.SYSTEM, ErrorCode.HARDWARE_ERROR.category)
            assertEquals(ErrorCategory.SYSTEM, ErrorCode.PERMISSION_DENIED.category)
        }

        @Test
        fun `cancellation has CANCELLATION category`() {
            assertEquals(ErrorCategory.CANCELLATION, ErrorCode.CANCELLED.category)
        }

        @Test
        fun `unknown has UNKNOWN category`() {
            assertEquals(ErrorCategory.UNKNOWN, ErrorCode.UNKNOWN.category)
        }
    }

    // ==================== RecoveryStrategy Tests ====================

    @Nested
    inner class RecoveryStrategyTest {

        @Test
        fun `Retry has default values`() {
            val retry = RecoveryStrategy.Retry()

            assertEquals(3, retry.maxAttempts)
            assertEquals(1000L, retry.delayMs)
            assertEquals(2f, retry.backoffMultiplier)
        }

        @Test
        fun `Retry can be customized`() {
            val retry = RecoveryStrategy.Retry(
                maxAttempts = 5,
                delayMs = 2000L,
                backoffMultiplier = 1.5f
            )

            assertEquals(5, retry.maxAttempts)
            assertEquals(2000L, retry.delayMs)
            assertEquals(1.5f, retry.backoffMultiplier)
        }

        @Test
        fun `RetryWithFallback hasMoreFallbacks is true when more options available`() {
            val fallback = RecoveryStrategy.RetryWithFallback(
                fallbackOptions = listOf("a", "b", "c"),
                currentIndex = 0
            )

            assertTrue(fallback.hasMoreFallbacks)
        }

        @Test
        fun `RetryWithFallback hasMoreFallbacks is false at last option`() {
            val fallback = RecoveryStrategy.RetryWithFallback(
                fallbackOptions = listOf("a", "b", "c"),
                currentIndex = 2
            )

            assertFalse(fallback.hasMoreFallbacks)
        }

        @Test
        fun `RetryWithFallback nextFallback returns current option`() {
            val fallback = RecoveryStrategy.RetryWithFallback(
                fallbackOptions = listOf("first", "second", "third"),
                currentIndex = 1
            )

            assertEquals("second", fallback.nextFallback())
        }

        @Test
        fun `RetryWithFallback nextFallback returns null when exhausted`() {
            val fallback = RecoveryStrategy.RetryWithFallback(
                fallbackOptions = listOf("a"),
                currentIndex = 5
            )

            assertNull(fallback.nextFallback())
        }

        @Test
        fun `Skip contains reason`() {
            val skip = RecoveryStrategy.Skip("Not required")

            assertEquals("Not required", skip.reason)
        }

        @Test
        fun `Abort is singleton`() {
            assertSame(RecoveryStrategy.Abort, RecoveryStrategy.Abort)
        }
    }

    // ==================== VideoFormat Tests ====================

    @Nested
    inner class VideoFormatTest {

        @Test
        fun `all formats have ytDlp format strings`() {
            VideoFormat.entries.forEach { format ->
                assertTrue(format.ytDlpFormat.isNotBlank())
            }
        }

        @Test
        fun `all formats have display names`() {
            VideoFormat.entries.forEach { format ->
                assertTrue(format.displayName.isNotBlank())
            }
        }

        @Test
        fun `BEST format uses best quality selector`() {
            assertTrue(VideoFormat.BEST.ytDlpFormat.contains("bestvideo"))
            assertTrue(VideoFormat.BEST.ytDlpFormat.contains("bestaudio"))
        }

        @Test
        fun `resolution formats have height limits`() {
            assertTrue(VideoFormat.MP4_1080P.ytDlpFormat.contains("1080"))
            assertTrue(VideoFormat.MP4_720P.ytDlpFormat.contains("720"))
            assertTrue(VideoFormat.MP4_480P.ytDlpFormat.contains("480"))
        }

        @Test
        fun `AUDIO_ONLY uses audio format`() {
            assertTrue(VideoFormat.AUDIO_ONLY.ytDlpFormat.contains("bestaudio"))
            assertFalse(VideoFormat.AUDIO_ONLY.ytDlpFormat.contains("bestvideo"))
        }
    }

    // ==================== PipelineCheckpoint Tests ====================

    @Nested
    inner class PipelineCheckpointTest {

        private val testVideoInfo = VideoInfo(
            url = "https://youtube.com/watch?v=test",
            id = "test-id",
            title = "Test Video",
            duration = 60000L
        )

        private val testOutputOptions = OutputOptions(
            outputDirectory = "/tmp/output",
            subtitleType = SubtitleType.SOFT
        )

        @Test
        fun `checkpoint contains all fields`() {
            val checkpoint = PipelineCheckpoint(
                jobId = "job-123",
                lastCompletedStage = PipelineStageName.DOWNLOAD,
                downloadedVideoPath = "/tmp/video.mp4",
                subtitles = null,
                translatedSubtitles = null,
                videoInfo = testVideoInfo,
                targetLanguage = Language.GERMAN,
                outputOptions = testOutputOptions
            )

            assertEquals("job-123", checkpoint.jobId)
            assertEquals(PipelineStageName.DOWNLOAD, checkpoint.lastCompletedStage)
            assertEquals("/tmp/video.mp4", checkpoint.downloadedVideoPath)
            assertEquals(Language.GERMAN, checkpoint.targetLanguage)
            assertTrue(checkpoint.timestamp > 0)
        }

        @Test
        fun `getNextStage returns correct next stage`() {
            val checkpointDownload = PipelineCheckpoint(
                jobId = "job-1",
                lastCompletedStage = PipelineStageName.DOWNLOAD,
                videoInfo = testVideoInfo,
                targetLanguage = Language.GERMAN,
                outputOptions = testOutputOptions
            )

            assertEquals(PipelineStageName.CAPTION_CHECK, checkpointDownload.getNextStage())
        }

        @Test
        fun `getNextStage returns null after last stage`() {
            val checkpointRendering = PipelineCheckpoint(
                jobId = "job-1",
                lastCompletedStage = PipelineStageName.RENDERING,
                videoInfo = testVideoInfo,
                targetLanguage = Language.GERMAN,
                outputOptions = testOutputOptions
            )

            assertNull(checkpointRendering.getNextStage())
        }

        @Test
        fun `isValid returns false for old checkpoint`() {
            val oldTimestamp = System.currentTimeMillis() - (25 * 60 * 60 * 1000L) // 25 hours ago
            val checkpoint = PipelineCheckpoint(
                jobId = "job-1",
                timestamp = oldTimestamp,
                lastCompletedStage = PipelineStageName.DOWNLOAD,
                videoInfo = testVideoInfo,
                targetLanguage = Language.GERMAN,
                outputOptions = testOutputOptions
            )

            assertFalse(checkpoint.isValid())
        }

        @Test
        fun `isValid returns true for recent checkpoint without video path`() {
            val checkpoint = PipelineCheckpoint(
                jobId = "job-1",
                lastCompletedStage = PipelineStageName.DOWNLOAD,
                downloadedVideoPath = null,
                videoInfo = testVideoInfo,
                targetLanguage = Language.GERMAN,
                outputOptions = testOutputOptions
            )

            assertTrue(checkpoint.isValid())
        }

        @Test
        fun `isValid returns false when video file is missing`(@TempDir tempDir: Path) {
            val nonExistentPath = tempDir.resolve("missing.mp4").toString()
            val checkpoint = PipelineCheckpoint(
                jobId = "job-1",
                lastCompletedStage = PipelineStageName.DOWNLOAD,
                downloadedVideoPath = nonExistentPath,
                videoInfo = testVideoInfo,
                targetLanguage = Language.GERMAN,
                outputOptions = testOutputOptions
            )

            assertFalse(checkpoint.isValid())
        }

        @Test
        fun `isValid returns true when video file exists`(@TempDir tempDir: Path) {
            val videoFile = tempDir.resolve("video.mp4").toFile()
            videoFile.writeText("video content")

            val checkpoint = PipelineCheckpoint(
                jobId = "job-1",
                lastCompletedStage = PipelineStageName.DOWNLOAD,
                downloadedVideoPath = videoFile.absolutePath,
                videoInfo = testVideoInfo,
                targetLanguage = Language.GERMAN,
                outputOptions = testOutputOptions
            )

            assertTrue(checkpoint.isValid())
        }
    }

    // ==================== StageMetrics Tests ====================

    @Nested
    inner class StageMetricsTest {

        @Test
        fun `metrics contain all optional fields`() {
            val metrics = StageMetrics(
                durationMs = 5000L,
                bytesProcessed = 1024L * 1024L,
                itemsProcessed = 100,
                retryCount = 2,
                cacheHits = 10,
                cacheMisses = 5,
                memoryPeakMb = 512L
            )

            assertEquals(5000L, metrics.durationMs)
            assertEquals(1024L * 1024L, metrics.bytesProcessed)
            assertEquals(100, metrics.itemsProcessed)
            assertEquals(2, metrics.retryCount)
            assertEquals(10, metrics.cacheHits)
            assertEquals(5, metrics.cacheMisses)
            assertEquals(512L, metrics.memoryPeakMb)
        }

        @Test
        fun `metrics have sensible defaults`() {
            val metrics = StageMetrics(durationMs = 1000L)

            assertNull(metrics.bytesProcessed)
            assertNull(metrics.itemsProcessed)
            assertEquals(0, metrics.retryCount)
            assertEquals(0, metrics.cacheHits)
            assertEquals(0, metrics.cacheMisses)
            assertNull(metrics.memoryPeakMb)
        }
    }

    // ==================== PipelineLogEvent Tests ====================

    @Nested
    inner class PipelineLogEventTest {

        @Test
        fun `Info event has INFO level`() {
            val event = PipelineLogEvent.Info(
                stage = PipelineStageName.DOWNLOAD,
                message = "Download started"
            )

            assertEquals(LogLevel.INFO, event.level)
        }

        @Test
        fun `Warning event has WARNING level`() {
            val event = PipelineLogEvent.Warning(
                stage = PipelineStageName.TRANSCRIPTION,
                message = "Slow connection detected"
            )

            assertEquals(LogLevel.WARNING, event.level)
        }

        @Test
        fun `Error event has ERROR level`() {
            val event = PipelineLogEvent.Error(
                stage = PipelineStageName.TRANSLATION,
                message = "API error occurred"
            )

            assertEquals(LogLevel.ERROR, event.level)
        }

        @Test
        fun `Debug event has DEBUG level`() {
            val event = PipelineLogEvent.Debug(
                stage = PipelineStageName.RENDERING,
                message = "FFmpeg command built"
            )

            assertEquals(LogLevel.DEBUG, event.level)
        }

        @Test
        fun `StageTransition event has INFO level`() {
            val event = PipelineLogEvent.StageTransition(
                stage = PipelineStageName.TRANSLATION,
                message = "Moving to translation",
                fromStage = PipelineStageName.TRANSCRIPTION
            )

            assertEquals(LogLevel.INFO, event.level)
        }

        @Test
        fun `RecoveryAttempt event has WARNING level`() {
            val event = PipelineLogEvent.RecoveryAttempt(
                stage = PipelineStageName.DOWNLOAD,
                message = "Retrying download",
                attemptNumber = 2,
                maxAttempts = 3,
                strategy = "retry"
            )

            assertEquals(LogLevel.WARNING, event.level)
        }

        @Test
        fun `CheckpointSaved event has DEBUG level`() {
            val event = PipelineLogEvent.CheckpointSaved(
                stage = PipelineStageName.TRANSLATION,
                checkpointPath = "/tmp/checkpoint.json"
            )

            assertEquals(LogLevel.DEBUG, event.level)
        }

        @Test
        fun `Metric event has DEBUG level`() {
            val event = PipelineLogEvent.Metric(
                stage = PipelineStageName.DOWNLOAD,
                message = "Download speed",
                name = "download_speed",
                value = 10.5,
                unit = "MB/s"
            )

            assertEquals(LogLevel.DEBUG, event.level)
        }

        @Test
        fun `all events have timestamp`() {
            val before = System.currentTimeMillis()
            val event = PipelineLogEvent.Info(
                stage = PipelineStageName.DOWNLOAD,
                message = "Test"
            )
            val after = System.currentTimeMillis()

            assertTrue(event.timestamp >= before)
            assertTrue(event.timestamp <= after)
        }
    }

    // ==================== LogLevel Tests ====================

    @Nested
    inner class LogLevelTest {

        @Test
        fun `log levels have correct priority order`() {
            assertTrue(LogLevel.DEBUG.priority < LogLevel.INFO.priority)
            assertTrue(LogLevel.INFO.priority < LogLevel.WARNING.priority)
            assertTrue(LogLevel.WARNING.priority < LogLevel.ERROR.priority)
        }

        @Test
        fun `DEBUG has lowest priority`() {
            assertEquals(0, LogLevel.DEBUG.priority)
        }

        @Test
        fun `ERROR has highest priority`() {
            assertEquals(3, LogLevel.ERROR.priority)
        }
    }

    // ==================== ErrorMapper Tests ====================

    @Nested
    inner class ErrorMapperTest {

        @Test
        fun `maps timeout exception to NETWORK_TIMEOUT`() {
            val exception = Exception("Connection timed out")
            val error = ErrorMapper.mapException(exception, PipelineStageName.DOWNLOAD)

            assertEquals(ErrorCode.NETWORK_TIMEOUT, error.code)
            assertTrue(error.recoverable)
            assertTrue(error.retryable)
        }

        @Test
        fun `maps connection refused to CONNECTION_REFUSED`() {
            val exception = Exception("Connection refused by server")
            val error = ErrorMapper.mapException(exception, PipelineStageName.TRANSLATION)

            assertEquals(ErrorCode.CONNECTION_REFUSED, error.code)
        }

        @Test
        fun `maps rate limit to RATE_LIMITED`() {
            val exception = Exception("Rate limit exceeded (429)")
            val error = ErrorMapper.mapException(exception, PipelineStageName.TRANSLATION)

            assertEquals(ErrorCode.RATE_LIMITED, error.code)
            assertTrue(error.retryable)
        }

        @Test
        fun `maps too many requests to RATE_LIMITED`() {
            val exception = Exception("Too many requests, please slow down")
            val error = ErrorMapper.mapException(exception, PipelineStageName.TRANSLATION)

            assertEquals(ErrorCode.RATE_LIMITED, error.code)
        }

        @Test
        fun `maps invalid API key to API_KEY_INVALID`() {
            val exception = Exception("API key is invalid")
            val error = ErrorMapper.mapException(exception, PipelineStageName.TRANSLATION)

            assertEquals(ErrorCode.API_KEY_INVALID, error.code)
            assertFalse(error.recoverable)
            assertFalse(error.retryable)
        }

        @Test
        fun `maps missing API key to API_KEY_MISSING`() {
            val exception = Exception("API key is required but missing")
            val error = ErrorMapper.mapException(exception, PipelineStageName.TRANSLATION)

            assertEquals(ErrorCode.API_KEY_MISSING, error.code)
        }

        @Test
        fun `maps quota exceeded to QUOTA_EXCEEDED`() {
            val exception = Exception("Quota limit exceeded")
            val error = ErrorMapper.mapException(exception, PipelineStageName.TRANSLATION)

            assertEquals(ErrorCode.QUOTA_EXCEEDED, error.code)
        }

        @Test
        fun `maps model not found to MODEL_NOT_FOUND`() {
            val exception = Exception("Model small doesn't exist")
            val error = ErrorMapper.mapException(exception, PipelineStageName.TRANSCRIPTION)

            assertEquals(ErrorCode.MODEL_NOT_FOUND, error.code)
        }

        @Test
        fun `maps out of memory to INSUFFICIENT_MEMORY`() {
            val exception = Exception("Out of memory error")
            val error = ErrorMapper.mapException(exception, PipelineStageName.TRANSCRIPTION)

            assertEquals(ErrorCode.INSUFFICIENT_MEMORY, error.code)
            assertTrue(error.recoverable)
        }

        @Test
        fun `maps disk full to DISK_FULL`() {
            val exception = Exception("Disk full, no space left on device")
            val error = ErrorMapper.mapException(exception, PipelineStageName.DOWNLOAD)

            assertEquals(ErrorCode.DISK_FULL, error.code)
            assertFalse(error.recoverable)
        }

        @Test
        fun `maps private video to PRIVATE_VIDEO`() {
            val exception = Exception("Video is private")
            val error = ErrorMapper.mapException(exception, PipelineStageName.DOWNLOAD)

            assertEquals(ErrorCode.PRIVATE_VIDEO, error.code)
            assertFalse(error.recoverable)
        }

        @Test
        fun `maps age restricted to AGE_RESTRICTED`() {
            val exception = Exception("Sign in to confirm your age")
            val error = ErrorMapper.mapException(exception, PipelineStageName.DOWNLOAD)

            assertEquals(ErrorCode.AGE_RESTRICTED, error.code)
        }

        @Test
        fun `maps unavailable video to VIDEO_UNAVAILABLE`() {
            val exception = Exception("Video is not available")
            val error = ErrorMapper.mapException(exception, PipelineStageName.DOWNLOAD)

            assertEquals(ErrorCode.VIDEO_UNAVAILABLE, error.code)
        }

        @Test
        fun `maps region blocked to REGION_BLOCKED`() {
            val exception = Exception("This video is blocked in your country")
            val error = ErrorMapper.mapException(exception, PipelineStageName.DOWNLOAD)

            assertEquals(ErrorCode.REGION_BLOCKED, error.code)
        }

        @Test
        fun `maps encoding error to ENCODING_FAILED`() {
            val exception = Exception("FFmpeg encoding failed")
            val error = ErrorMapper.mapException(exception, PipelineStageName.RENDERING)

            assertEquals(ErrorCode.ENCODING_FAILED, error.code)
            assertTrue(error.retryable)
        }

        @Test
        fun `maps unknown exception to UNKNOWN`() {
            val exception = Exception("Something weird happened")
            val error = ErrorMapper.mapException(exception, PipelineStageName.DOWNLOAD)

            assertEquals(ErrorCode.UNKNOWN, error.code)
            assertFalse(error.recoverable)
        }

        @Test
        fun `includes suggestion in error`() {
            val exception = Exception("Connection timed out")
            val error = ErrorMapper.mapException(exception, PipelineStageName.DOWNLOAD)

            assertNotNull(error.suggestion)
            assertTrue(error.suggestion!!.isNotBlank())
        }

        @Test
        fun `includes stage in error`() {
            val exception = Exception("Error")
            val error = ErrorMapper.mapException(exception, PipelineStageName.TRANSLATION)

            assertEquals(PipelineStageName.TRANSLATION, error.stage)
        }

        @Test
        fun `getRecoveryStrategy returns Retry for retryable errors`() {
            val error = PipelineError(
                code = ErrorCode.NETWORK_TIMEOUT,
                stage = PipelineStageName.DOWNLOAD,
                message = "Timeout",
                retryable = true
            )

            val strategy = ErrorMapper.getRecoveryStrategy(error)

            assertIs<RecoveryStrategy.Retry>(strategy)
        }

        @Test
        fun `getRecoveryStrategy uses longer delay for rate limiting`() {
            val error = PipelineError(
                code = ErrorCode.RATE_LIMITED,
                stage = PipelineStageName.TRANSLATION,
                message = "Rate limited",
                retryable = true
            )

            val strategy = ErrorMapper.getRecoveryStrategy(error)

            assertIs<RecoveryStrategy.Retry>(strategy)
            assertEquals(30_000L, (strategy as RecoveryStrategy.Retry).delayMs)
        }

        @Test
        fun `getRecoveryStrategy returns format fallback for download encoding error`() {
            val error = PipelineError(
                code = ErrorCode.ENCODING_FAILED,
                stage = PipelineStageName.DOWNLOAD,
                message = "Encoding failed",
                retryable = false
            )

            val strategy = ErrorMapper.getRecoveryStrategy(error)

            assertIs<RecoveryStrategy.RetryWithFallback<*>>(strategy)
        }

        @Test
        fun `getRecoveryStrategy returns model fallback for transcription memory error`() {
            val error = PipelineError(
                code = ErrorCode.INSUFFICIENT_MEMORY,
                stage = PipelineStageName.TRANSCRIPTION,
                message = "Out of memory",
                retryable = false
            )

            val strategy = ErrorMapper.getRecoveryStrategy(error)

            assertIs<RecoveryStrategy.RetryWithFallback<*>>(strategy)
        }

        @Test
        fun `getRecoveryStrategy returns encoder fallback for render encoding error`() {
            val error = PipelineError(
                code = ErrorCode.ENCODING_FAILED,
                stage = PipelineStageName.RENDERING,
                message = "Encoder failed",
                retryable = false
            )

            val strategy = ErrorMapper.getRecoveryStrategy(error)

            assertIs<RecoveryStrategy.RetryWithFallback<*>>(strategy)
        }

        @Test
        fun `getRecoveryStrategy returns Abort for non-recoverable errors`() {
            val error = PipelineError(
                code = ErrorCode.API_KEY_INVALID,
                stage = PipelineStageName.TRANSLATION,
                message = "Invalid API key",
                recoverable = false,
                retryable = false
            )

            val strategy = ErrorMapper.getRecoveryStrategy(error)

            assertEquals(RecoveryStrategy.Abort, strategy)
        }
    }

    // ==================== PipelineException Tests ====================

    @Nested
    inner class PipelineExceptionTest {

        @Test
        fun `exception wraps error`() {
            val error = PipelineError(
                code = ErrorCode.NETWORK_TIMEOUT,
                stage = PipelineStageName.DOWNLOAD,
                message = "Timeout"
            )

            val exception = PipelineException(error)

            assertEquals(error, exception.error)
        }

        @Test
        fun `exception message uses user message format`() {
            val error = PipelineError(
                code = ErrorCode.NETWORK_TIMEOUT,
                stage = PipelineStageName.DOWNLOAD,
                message = "Connection timed out",
                suggestion = "Check network"
            )

            val exception = PipelineException(error)

            assertTrue(exception.message!!.contains("Download"))
            assertTrue(exception.message!!.contains("Connection timed out"))
        }
    }
}
