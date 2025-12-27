package com.ericjesse.videotranslator.domain.service

import com.ericjesse.videotranslator.domain.model.*
import com.ericjesse.videotranslator.domain.pipeline.StageProgress
import com.ericjesse.videotranslator.infrastructure.config.OperatingSystem
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import com.ericjesse.videotranslator.infrastructure.process.ProcessExecutor
import com.ericjesse.videotranslator.infrastructure.process.ProcessException
import io.mockk.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class VideoDownloaderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var processExecutor: ProcessExecutor
    private lateinit var platformPaths: PlatformPaths
    private lateinit var downloader: VideoDownloader

    @BeforeEach
    fun setup() {
        processExecutor = mockk()
        platformPaths = mockk()

        every { platformPaths.getBinaryPath("yt-dlp") } returns "/usr/local/bin/yt-dlp"
        every { platformPaths.cacheDir } returns tempDir.resolve("cache").toString().also {
            File(it).mkdirs()
        }
        every { platformPaths.operatingSystem } returns OperatingSystem.MACOS

        downloader = VideoDownloader(processExecutor, platformPaths)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // ==================== URL Validation Tests ====================

    @Nested
    inner class UrlValidationTest {

        @Test
        fun `isValidYouTubeUrl accepts standard watch URL`() {
            assertTrue(downloader.isValidYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
            assertTrue(downloader.isValidYouTubeUrl("http://www.youtube.com/watch?v=dQw4w9WgXcQ"))
            assertTrue(downloader.isValidYouTubeUrl("https://youtube.com/watch?v=dQw4w9WgXcQ"))
            assertTrue(downloader.isValidYouTubeUrl("www.youtube.com/watch?v=dQw4w9WgXcQ"))
            assertTrue(downloader.isValidYouTubeUrl("youtube.com/watch?v=dQw4w9WgXcQ"))
        }

        @Test
        fun `isValidYouTubeUrl accepts URL with additional parameters`() {
            assertTrue(downloader.isValidYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=120"))
            assertTrue(downloader.isValidYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLxxxx"))
            assertTrue(downloader.isValidYouTubeUrl("https://www.youtube.com/watch?t=30&v=dQw4w9WgXcQ"))
        }

        @Test
        fun `isValidYouTubeUrl accepts short URL`() {
            assertTrue(downloader.isValidYouTubeUrl("https://youtu.be/dQw4w9WgXcQ"))
            assertTrue(downloader.isValidYouTubeUrl("http://youtu.be/dQw4w9WgXcQ"))
            assertTrue(downloader.isValidYouTubeUrl("youtu.be/dQw4w9WgXcQ"))
            assertTrue(downloader.isValidYouTubeUrl("https://youtu.be/dQw4w9WgXcQ?t=30"))
        }

        @Test
        fun `isValidYouTubeUrl accepts shorts URL`() {
            assertTrue(downloader.isValidYouTubeUrl("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
            assertTrue(downloader.isValidYouTubeUrl("https://youtube.com/shorts/dQw4w9WgXcQ"))
            assertTrue(downloader.isValidYouTubeUrl("youtube.com/shorts/dQw4w9WgXcQ"))
        }

        @Test
        fun `isValidYouTubeUrl accepts embed URL`() {
            assertTrue(downloader.isValidYouTubeUrl("https://www.youtube.com/embed/dQw4w9WgXcQ"))
            assertTrue(downloader.isValidYouTubeUrl("https://youtube.com/embed/dQw4w9WgXcQ"))
        }

        @Test
        fun `isValidYouTubeUrl accepts live URL`() {
            assertTrue(downloader.isValidYouTubeUrl("https://www.youtube.com/live/dQw4w9WgXcQ"))
            assertTrue(downloader.isValidYouTubeUrl("https://youtube.com/live/dQw4w9WgXcQ"))
        }

        @Test
        fun `isValidYouTubeUrl accepts music URL`() {
            assertTrue(downloader.isValidYouTubeUrl("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        }

        @Test
        fun `isValidYouTubeUrl rejects invalid URLs`() {
            assertFalse(downloader.isValidYouTubeUrl("https://vimeo.com/12345"))
            assertFalse(downloader.isValidYouTubeUrl("https://example.com"))
            assertFalse(downloader.isValidYouTubeUrl("not a url"))
            assertFalse(downloader.isValidYouTubeUrl(""))
            assertFalse(downloader.isValidYouTubeUrl("https://youtube.com/"))
            assertFalse(downloader.isValidYouTubeUrl("https://youtube.com/watch"))
            assertFalse(downloader.isValidYouTubeUrl("https://youtube.com/watch?v=short")) // Too short
            assertFalse(downloader.isValidYouTubeUrl("https://youtube.com/watch?v=toolongvideoidhere")) // Too long
        }

        @Test
        fun `isValidYouTubeUrl handles whitespace`() {
            assertTrue(downloader.isValidYouTubeUrl("  https://www.youtube.com/watch?v=dQw4w9WgXcQ  "))
            assertTrue(downloader.isValidYouTubeUrl("\thttps://youtu.be/dQw4w9WgXcQ\n"))
        }

        @Test
        fun `extractVideoId extracts ID from various URL formats`() {
            assertEquals("dQw4w9WgXcQ", downloader.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
            assertEquals("dQw4w9WgXcQ", downloader.extractVideoId("https://youtu.be/dQw4w9WgXcQ"))
            assertEquals("dQw4w9WgXcQ", downloader.extractVideoId("https://youtube.com/shorts/dQw4w9WgXcQ"))
            assertEquals("dQw4w9WgXcQ", downloader.extractVideoId("https://youtube.com/embed/dQw4w9WgXcQ"))
            assertEquals("dQw4w9WgXcQ", downloader.extractVideoId("https://youtube.com/live/dQw4w9WgXcQ"))
        }

        @Test
        fun `extractVideoId returns null for invalid URLs`() {
            assertNull(downloader.extractVideoId("https://vimeo.com/12345"))
            assertNull(downloader.extractVideoId("not a url"))
            assertNull(downloader.extractVideoId(""))
        }

        @Test
        fun `validateUrl throws YtDlpException for invalid URL`() {
            val exception = assertFailsWith<YtDlpException> {
                downloader.validateUrl("https://vimeo.com/12345")
            }

            assertEquals(YtDlpErrorType.INVALID_URL, exception.errorType)
            assertTrue(exception.userMessage.contains("Invalid"))
        }

        @Test
        fun `validateUrl does not throw for valid URL`() {
            // Should not throw any exception
            downloader.validateUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        }
    }

    // ==================== Video Info Tests ====================

    @Nested
    inner class VideoInfoTest {

        @Test
        fun `fetchVideoInfoFull parses yt-dlp JSON output`() = runTest {
            // yt-dlp outputs JSON as a single line (video ID must be 11 chars)
            val jsonOutput = """{"id": "dQw4w9WgXcQ", "title": "Test Video Title", "duration": 300, "thumbnail": "https://example.com/thumb.jpg", "webpage_url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "uploader": "Test Channel", "view_count": 1000000}"""

            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback(jsonOutput)
                0
            }

            val info = downloader.fetchVideoInfoFull("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

            assertEquals("dQw4w9WgXcQ", info.id)
            assertEquals("Test Video Title", info.title)
            assertEquals(300.0, info.duration)
            assertEquals("https://example.com/thumb.jpg", info.thumbnail)
            assertEquals("Test Channel", info.uploader)
            assertEquals(1000000L, info.viewCount)
        }

        @Test
        fun `fetchVideoInfoFull throws on invalid URL`() = runTest {
            val exception = assertFailsWith<YtDlpException> {
                downloader.fetchVideoInfoFull("https://invalid.url/video")
            }

            assertEquals(YtDlpErrorType.INVALID_URL, exception.errorType)
        }

        @Test
        fun `fetchVideoInfoFull throws on process error`() = runTest {
            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } throws ProcessException("yt-dlp", 1, "Video unavailable")

            val exception = assertFailsWith<YtDlpException> {
                downloader.fetchVideoInfoFull("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            }

            assertNotEquals(YtDlpErrorType.INVALID_URL, exception.errorType)
        }

        @Test
        fun `fetchVideoInfo returns simplified VideoInfo`() = runTest {
            // yt-dlp outputs JSON as a single line (video ID must be 11 chars)
            val jsonOutput = """{"id": "xVkU8dDSC3Y", "title": "Simple Video", "duration": 180, "thumbnail": "https://example.com/thumb.jpg", "webpage_url": "https://www.youtube.com/watch?v=xVkU8dDSC3Y"}"""

            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback(jsonOutput)
                0
            }

            val info = downloader.fetchVideoInfo("https://www.youtube.com/watch?v=xVkU8dDSC3Y")

            assertEquals("xVkU8dDSC3Y", info.id)
            assertEquals("Simple Video", info.title)
            assertEquals(180000L, info.duration) // 180 seconds = 180000 milliseconds
            assertEquals("https://example.com/thumb.jpg", info.thumbnailUrl)
        }

        @Test
        fun `fetchVideoInfoFull includes cookie options when provided`() = runTest {
            val jsonOutput = """{"id": "test", "title": "Test"}"""
            val capturedCommand = slot<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommand), any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback(jsonOutput)
                0
            }

            val cookieFile = File(tempDir.toFile(), "cookies.txt").apply {
                writeText("# Cookie file")
            }

            downloader.fetchVideoInfoFull(
                url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                options = YtDlpDownloadOptions(cookiesFile = cookieFile.absolutePath)
            )

            assertTrue(capturedCommand.captured.contains("--cookies"))
            assertTrue(capturedCommand.captured.contains(cookieFile.absolutePath))
        }

        @Test
        fun `fetchVideoInfoFull uses browser cookies when specified`() = runTest {
            val jsonOutput = """{"id": "test", "title": "Test"}"""
            val capturedCommand = slot<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommand), any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback(jsonOutput)
                0
            }

            downloader.fetchVideoInfoFull(
                url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                options = YtDlpDownloadOptions(cookiesFromBrowser = "firefox")
            )

            assertTrue(capturedCommand.captured.contains("--cookies-from-browser"))
            assertTrue(capturedCommand.captured.contains("firefox"))
        }
    }

    // ==================== Download Tests ====================

    @Nested
    inner class DownloadTest {

        private val testVideoInfo = VideoInfo(
            url = "https://www.youtube.com/watch?v=test123",
            id = "test123",
            title = "Test Video",
            duration = 300
        )

        @Test
        fun `download emits progress updates`() = runTest {
            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback("[download]  25.0% of 50.00MiB at 5.00MiB/s ETA 00:08")
                callback("[download]  50.0% of 50.00MiB at 5.00MiB/s ETA 00:05")
                callback("[download] 100.0% of 50.00MiB at 5.00MiB/s ETA 00:00")
                0
            }

            val progressList = downloader.download(testVideoInfo).toList()

            assertTrue(progressList.size >= 2) // Initial + updates + complete
            assertTrue(progressList.first().percentage == 0f)
            assertTrue(progressList.last().percentage == 1f)
        }

        @Test
        fun `download uses correct format options`() = runTest {
            val capturedCommand = slot<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommand), any(), any(), any())
            } returns 0

            downloader.download(testVideoInfo).toList()

            val command = capturedCommand.captured
            assertTrue(command.contains("--no-playlist"))
            assertTrue(command.contains("--format"))
            assertTrue(command.contains("--merge-output-format"))
            assertTrue(command.contains("--progress"))
            assertTrue(command.contains("--newline"))
        }

        @Test
        fun `download applies rate limit when specified`() = runTest {
            val capturedCommand = slot<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommand), any(), any(), any())
            } returns 0

            downloader.download(
                testVideoInfo,
                YtDlpDownloadOptions(rateLimitKbps = 5000)
            ).toList()

            val command = capturedCommand.captured
            assertTrue(command.contains("--rate-limit"))
            assertTrue(command.contains("5000K"))
        }

        @Test
        fun `download clamps rate limit to valid range`() = runTest {
            val capturedCommand = slot<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommand), any(), any(), any())
            } returns 0

            // Test minimum clamping
            downloader.download(
                testVideoInfo,
                YtDlpDownloadOptions(rateLimitKbps = 10) // Below minimum
            ).toList()

            var command = capturedCommand.captured
            assertTrue(command.contains("${VideoDownloader.MIN_RATE_LIMIT_KBPS}K"))

            // Test maximum clamping
            downloader.download(
                testVideoInfo,
                YtDlpDownloadOptions(rateLimitKbps = 999999) // Above maximum
            ).toList()

            command = capturedCommand.captured
            assertTrue(command.contains("${VideoDownloader.MAX_RATE_LIMIT_KBPS}K"))
        }

        @Test
        fun `download uses audio-only format when specified`() = runTest {
            val capturedCommand = slot<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommand), any(), any(), any())
            } returns 0

            downloader.download(
                testVideoInfo,
                YtDlpDownloadOptions(audioOnly = true)
            ).toList()

            val command = capturedCommand.captured
            assertTrue(command.contains("--extract-audio"))
            assertTrue(command.contains("--audio-format"))
            assertTrue(command.any { it.contains("bestaudio") })
        }

        @Test
        fun `download applies max height filter`() = runTest {
            val capturedCommand = slot<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommand), any(), any(), any())
            } returns 0

            downloader.download(
                testVideoInfo,
                YtDlpDownloadOptions(maxHeight = 720)
            ).toList()

            val command = capturedCommand.captured
            val formatArg = command[command.indexOf("--format") + 1]
            assertTrue(formatArg.contains("[height<=720]"))
        }

        @Test
        fun `download includes subtitle options when specified`() = runTest {
            val capturedCommand = slot<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommand), any(), any(), any())
            } returns 0

            downloader.download(
                testVideoInfo,
                YtDlpDownloadOptions(
                    writeSubtitles = true,
                    subtitleLanguages = listOf("en", "de"),
                    embedSubtitles = true
                )
            ).toList()

            val command = capturedCommand.captured
            assertTrue(command.contains("--write-sub"))
            assertTrue(command.contains("--write-auto-sub"))
            assertTrue(command.contains("--sub-lang"))
            assertTrue(command.contains("en,de"))
            assertTrue(command.contains("--embed-subs"))
        }

        @Test
        fun `download throws YtDlpException on process error`() = runTest {
            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } throws ProcessException("yt-dlp", 1, "Download failed")

            assertFailsWith<YtDlpException> {
                downloader.download(testVideoInfo).toList()
            }
        }

        @Test
        fun `downloadAudioOnly calls download with audioOnly option`() = runTest {
            val capturedCommand = slot<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommand), any(), any(), any())
            } returns 0

            downloader.downloadAudioOnly(testVideoInfo).toList()

            val command = capturedCommand.captured
            assertTrue(command.contains("--extract-audio"))
        }
    }

    // ==================== Caption Extraction Tests ====================

    @Nested
    inner class CaptionExtractionTest {

        private val testVideoInfo = VideoInfo(
            url = "https://www.youtube.com/watch?v=test123",
            id = "test123",
            title = "Test Video",
            duration = 300
        )

        @Test
        fun `extractCaptions returns empty list when no languages specified`() = runTest {
            val results = downloader.extractCaptions(
                videoInfo = testVideoInfo,
                languages = emptyList()
            )

            assertTrue(results.isEmpty())
        }

        @Test
        fun `extractCaptions calls yt-dlp with correct parameters`() = runTest {
            val capturedCommand = slot<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommand), any(), any(), any())
            } returns 0

            downloader.extractCaptions(
                videoInfo = testVideoInfo,
                languages = listOf("en"),
                preferManual = true
            )

            val command = capturedCommand.captured
            assertTrue(command.contains("--skip-download"))
            assertTrue(command.contains("--write-sub"))
            assertTrue(command.contains("--write-auto-sub"))
            assertTrue(command.contains("--sub-lang"))
            assertTrue(command.contains("en"))
            assertTrue(command.contains("--sub-format"))
            assertTrue(command.contains("vtt"))
        }

        @Test
        fun `extractCaptions returns results for downloaded captions`() = runTest {
            val captionsDir = File(tempDir.toFile(), "cache/downloads/test123").apply { mkdirs() }
            val captionFile = File(captionsDir, "test123.en.vtt").apply {
                writeText("""
                    WEBVTT

                    00:00:00.000 --> 00:00:05.000
                    Hello world
                """.trimIndent())
            }

            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } returns 0

            val results = downloader.extractCaptions(
                videoInfo = testVideoInfo,
                languages = listOf("en")
            )

            assertEquals(1, results.size)
            assertEquals("en", results.first().language)
            assertEquals("vtt", results.first().format)
        }

        @Test
        fun `extractCaptions with Language returns Subtitles`() = runTest {
            val captionsDir = File(tempDir.toFile(), "cache/downloads/test123").apply { mkdirs() }
            File(captionsDir, "test123.en.vtt").apply {
                writeText("""
                    WEBVTT

                    00:00:00.000 --> 00:00:05.000
                    Hello world

                    00:00:05.000 --> 00:00:10.000
                    This is a test
                """.trimIndent())
            }

            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } returns 0

            val subtitles = downloader.extractCaptions(testVideoInfo, Language.ENGLISH)

            assertNotNull(subtitles)
            assertEquals(Language.ENGLISH, subtitles.language)
            assertEquals(2, subtitles.entries.size)
            assertEquals("Hello world", subtitles.entries[0].text)
            assertEquals("This is a test", subtitles.entries[1].text)
        }

        @Test
        fun `getAvailableCaptionLanguages returns languages from video info`() = runTest {
            // yt-dlp outputs JSON as a single line
            val jsonOutput = """{"id": "test", "title": "Test", "subtitles": {"en": [{"ext": "vtt"}], "de": [{"ext": "vtt"}]}, "automatic_captions": {"fr": [{"ext": "vtt"}]}}"""

            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback(jsonOutput)
                0
            }

            val languages = downloader.getAvailableCaptionLanguages(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            )

            // Should prefer manual subtitles
            assertTrue(languages.contains("en"))
            assertTrue(languages.contains("de"))
        }
    }

    // ==================== Utility Tests ====================

    @Nested
    inner class UtilityTest {

        @Test
        fun `getDownloadedVideoPath returns correct path for video`() {
            val videoInfo = VideoInfo(
                url = "https://www.youtube.com/watch?v=test123",
                id = "test123",
                title = "Test",
                duration = 100
            )

            val path = downloader.getDownloadedVideoPath(videoInfo, audioOnly = false)

            assertTrue(path.endsWith("test123.mp4"))
            assertTrue(path.contains("downloads"))
        }

        @Test
        fun `getDownloadedVideoPath returns correct path for audio`() {
            val videoInfo = VideoInfo(
                url = "https://www.youtube.com/watch?v=test123",
                id = "test123",
                title = "Test",
                duration = 100
            )

            val path = downloader.getDownloadedVideoPath(videoInfo, audioOnly = true)

            assertTrue(path.endsWith("test123.m4a"))
        }

        @Test
        fun `isAvailable delegates to processExecutor`() = runTest {
            coEvery { processExecutor.isAvailable(any()) } returns true

            val available = downloader.isAvailable()

            assertTrue(available)
            coVerify { processExecutor.isAvailable("/usr/local/bin/yt-dlp") }
        }

        @Test
        fun `getVersion delegates to processExecutor`() = runTest {
            coEvery { processExecutor.getVersion(any()) } returns "2024.01.01"

            val version = downloader.getVersion()

            assertEquals("2024.01.01", version)
            coVerify { processExecutor.getVersion("/usr/local/bin/yt-dlp") }
        }
    }

    // ==================== Progress Parsing Tests ====================

    @Nested
    inner class ProgressParsingTest {

        private val testVideoInfo = VideoInfo(
            url = "https://www.youtube.com/watch?v=test123",
            id = "test123",
            title = "Test Video",
            duration = 300
        )

        @Test
        fun `parses progress with speed and ETA`() = runTest {
            val progressLines = mutableListOf<StageProgress>()

            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback("[download]  45.2% of 123.45MiB at 5.67MiB/s ETA 00:15")
                0
            }

            downloader.download(testVideoInfo).toList().forEach {
                progressLines.add(it)
            }

            val progressUpdate = progressLines.find { it.percentage > 0f && it.percentage < 1f }
            assertNotNull(progressUpdate)
            assertTrue(progressUpdate.message.contains("45%"))
            assertTrue(progressUpdate.message.contains("5.67MiB/s"))
            assertTrue(progressUpdate.message.contains("ETA"))
        }

        @Test
        fun `handles progress lines without speed`() = runTest {
            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback("[download]  75.0%")
                0
            }

            val progressList = downloader.download(testVideoInfo).toList()

            val progressUpdate = progressList.find { it.percentage > 0.5f && it.percentage < 1f }
            assertNotNull(progressUpdate)
        }

        @Test
        fun `ignores non-progress lines`() = runTest {
            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback("[info] Downloading video info")
                callback("[info] Writing video description")
                0
            }

            val progressList = downloader.download(testVideoInfo).toList()

            // Should only have initial (0%) and final (100%) progress
            assertEquals(2, progressList.size)
        }
    }

    // ==================== Error Handling Tests ====================

    @Nested
    inner class ErrorHandlingTest {

        private val testVideoInfo = VideoInfo(
            url = "https://www.youtube.com/watch?v=test123",
            id = "test123",
            title = "Test Video",
            duration = 300
        )

        @Test
        fun `fetchVideoInfoFull throws on empty JSON response`() = runTest {
            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback("WARNING: Some warning message")
                0
            }

            val exception = assertFailsWith<YtDlpException> {
                downloader.fetchVideoInfoFull("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            }

            assertEquals(YtDlpErrorType.UNKNOWN, exception.errorType)
            assertTrue(exception.technicalMessage.contains("Empty JSON"))
        }

        @Test
        fun `fetchVideoInfoFull throws on invalid JSON`() = runTest {
            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback("{invalid json}")
                0
            }

            val exception = assertFailsWith<YtDlpException> {
                downloader.fetchVideoInfoFull("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            }

            assertEquals(YtDlpErrorType.UNKNOWN, exception.errorType)
            assertTrue(exception.userMessage.contains("parse"))
        }

        @Test
        fun `download converts ProcessException to YtDlpException`() = runTest {
            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } throws ProcessException("yt-dlp", 1, "ERROR: Video unavailable")

            val exception = assertFailsWith<YtDlpException> {
                downloader.download(testVideoInfo).toList()
            }

            assertNotNull(exception)
        }
    }
}
