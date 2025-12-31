package com.ericjesse.videotranslator.domain.service

import com.ericjesse.videotranslator.domain.model.AudioCodec
import com.ericjesse.videotranslator.domain.model.EncodingConfig
import com.ericjesse.videotranslator.domain.model.EncodingPreset
import com.ericjesse.videotranslator.domain.model.HardwareEncoder
import com.ericjesse.videotranslator.domain.model.Language
import com.ericjesse.videotranslator.domain.model.OutputFormat
import com.ericjesse.videotranslator.domain.model.OutputOptions
import com.ericjesse.videotranslator.domain.model.Platform
import com.ericjesse.videotranslator.domain.model.RenderOptions
import com.ericjesse.videotranslator.domain.model.SubtitleEntry
import com.ericjesse.videotranslator.domain.model.SubtitleType
import com.ericjesse.videotranslator.domain.model.Subtitles
import com.ericjesse.videotranslator.domain.model.VideoInfo
import com.ericjesse.videotranslator.domain.model.VideoQuality
import com.ericjesse.videotranslator.infrastructure.config.AppSettings
import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import com.ericjesse.videotranslator.infrastructure.process.ProcessExecutor
import io.mockk.clearAllMocks
import io.mockk.coEvery
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

class SubtitleRendererTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var processExecutor: ProcessExecutor
    private lateinit var platformPaths: PlatformPaths
    private lateinit var configManager: ConfigManager
    private lateinit var subtitleRenderer: SubtitleRenderer

    @BeforeEach
    fun setup() {
        processExecutor = mockk()
        platformPaths = mockk()
        configManager = mockk()

        every { platformPaths.getBinaryPath("ffmpeg") } returns "/usr/local/bin/ffmpeg"
        every { platformPaths.getBinaryPath("ffprobe") } returns "/usr/local/bin/ffprobe"

        // ConfigManager mocks
        every { configManager.getBinaryPath("ffmpeg") } returns "/usr/local/bin/ffmpeg"
        every { configManager.getBinaryPath("ffprobe") } returns "/usr/local/bin/ffprobe"

        val settings = AppSettings()
        every { configManager.getSettings() } returns settings

        subtitleRenderer = SubtitleRenderer(processExecutor, platformPaths, configManager)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // ==================== Helper Functions ====================

    private fun createTestSubtitles(): Subtitles {
        return Subtitles(
            language = Language.ENGLISH,
            entries = listOf(
                SubtitleEntry(1, 0L, 5000L, "Hello world"),
                SubtitleEntry(2, 5000L, 10000L, "This is a test"),
                SubtitleEntry(3, 10000L, 15000L, "Goodbye")
            )
        )
    }

    private fun createTestVideoInfo(): VideoInfo {
        return VideoInfo(
            url = "https://example.com/video.mp4",
            id = "test123",
            title = "Test Video",
            duration = 60000L,
            thumbnailUrl = null,
            width = 1920,
            height = 1080,
            bitrate = 5000
        )
    }

    private fun createTestOutputOptions(outputDir: String): OutputOptions {
        return OutputOptions(
            outputDirectory = outputDir,
            subtitleType = SubtitleType.BURNED_IN,
            exportSrt = false,
            renderOptions = RenderOptions()
        )
    }

    // ==================== Encoder Availability Tests ====================

    @Nested
    inner class EncoderAvailabilityTest {

        @Test
        fun `isEncoderAvailable returns true for NONE encoder`() = runTest {
            val available = subtitleRenderer.isEncoderAvailable(HardwareEncoder.NONE)

            assertTrue(available)
        }

        @Test
        fun `isEncoderAvailable returns false for incompatible platform`() = runTest {
            // AMF is only available on Windows, so on non-Windows it should return false
            val platform = Platform.current()
            if (platform != Platform.WINDOWS) {
                val available = subtitleRenderer.isEncoderAvailable(HardwareEncoder.AMF)
                assertFalse(available)
            }
        }

        @Test
        fun `getAvailableEncoders always includes software encoder`() = runTest {
            // Mock all encoder tests to fail except NONE
            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } throws RuntimeException("Not available")

            val encoders = subtitleRenderer.getAvailableEncoders()

            assertTrue(encoders.contains(HardwareEncoder.NONE))
        }
    }

    // ==================== Render Result Tests ====================

    @Nested
    inner class RenderResultTest {

        @Test
        fun `getRenderResult throws when no render has been done`() {
            val exception = assertFailsWith<IllegalStateException> {
                subtitleRenderer.getRenderResult()
            }

            assertTrue(exception.message!!.contains("No render result"))
        }
    }

    // ==================== Soft Subtitles Tests ====================

    @Nested
    inner class SoftSubtitlesTest {

        @Test
        fun `render soft subtitles uses MKV format`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = createTestVideoInfo()
            val outputOptions = OutputOptions(
                outputDirectory = outputDir,
                subtitleType = SubtitleType.SOFT,
                exportSrt = false
            )

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } returns 0

            subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            // Find the FFmpeg command
            val ffmpegCommand = capturedCommands.find { it.firstOrNull()?.contains("ffmpeg") == true }
            assertNotNull(ffmpegCommand)

            // Should output MKV for soft subtitles
            val outputPath = ffmpegCommand.last()
            assertTrue(outputPath.endsWith(".mkv"))

            // Should copy video/audio streams
            assertTrue(ffmpegCommand.contains("-c"))
            assertTrue(ffmpegCommand.contains("copy"))
        }
    }

    // ==================== Burned-In Subtitles Tests ====================

    @Nested
    inner class BurnedInSubtitlesTest {

        @Test
        fun `render burned-in subtitles uses video filter`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = createTestVideoInfo()
            val outputOptions = createTestOutputOptions(outputDir)

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } returns 0

            subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            val ffmpegCommand = capturedCommands.find { it.firstOrNull()?.contains("ffmpeg") == true }
            assertNotNull(ffmpegCommand)

            // Should have video filter
            assertTrue(ffmpegCommand.contains("-vf"))
        }

        @Test
        fun `render burned-in subtitles with ASS format`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = createTestVideoInfo()
            val outputOptions = OutputOptions(
                outputDirectory = outputDir,
                subtitleType = SubtitleType.BURNED_IN,
                exportSrt = false,
                renderOptions = RenderOptions(useAssSubtitles = true)
            )

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } returns 0

            subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            val ffmpegCommand = capturedCommands.find { it.firstOrNull()?.contains("ffmpeg") == true }
            assertNotNull(ffmpegCommand)

            // Video filter should use ASS format
            val vfIndex = ffmpegCommand.indexOf("-vf")
            assertTrue(vfIndex >= 0)
            val filterValue = ffmpegCommand[vfIndex + 1]
            assertTrue(filterValue.startsWith("ass="))
        }

        @Test
        fun `render burned-in subtitles with SRT format`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = createTestVideoInfo()
            val outputOptions = OutputOptions(
                outputDirectory = outputDir,
                subtitleType = SubtitleType.BURNED_IN,
                exportSrt = false,
                renderOptions = RenderOptions(useAssSubtitles = false)
            )

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } returns 0

            subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            val ffmpegCommand = capturedCommands.find { it.firstOrNull()?.contains("ffmpeg") == true }
            assertNotNull(ffmpegCommand)

            // Video filter should use subtitles format with force_style
            val vfIndex = ffmpegCommand.indexOf("-vf")
            assertTrue(vfIndex >= 0)
            val filterValue = ffmpegCommand[vfIndex + 1]
            assertTrue(filterValue.startsWith("subtitles="))
            assertTrue(filterValue.contains("force_style"))
        }
    }

    // ==================== Encoding Options Tests ====================

    @Nested
    inner class EncodingOptionsTest {

        @Test
        fun `render uses software encoder by default`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = createTestVideoInfo()
            val outputOptions = OutputOptions(
                outputDirectory = outputDir,
                subtitleType = SubtitleType.BURNED_IN,
                exportSrt = false,
                renderOptions = RenderOptions(
                    encoding = EncodingConfig(encoder = HardwareEncoder.NONE)
                )
            )

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } returns 0

            subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            val ffmpegCommand = capturedCommands.find { it.firstOrNull()?.contains("ffmpeg") == true }
            assertNotNull(ffmpegCommand)

            // Should use libx264
            assertTrue(ffmpegCommand.contains("libx264"))
        }

        @Test
        fun `render uses specified quality preset`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = createTestVideoInfo()
            val outputOptions = OutputOptions(
                outputDirectory = outputDir,
                subtitleType = SubtitleType.BURNED_IN,
                exportSrt = false,
                renderOptions = RenderOptions(
                    encoding = EncodingConfig(quality = VideoQuality.HIGH)
                )
            )

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } returns 0

            subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            val ffmpegCommand = capturedCommands.find { it.firstOrNull()?.contains("ffmpeg") == true }
            assertNotNull(ffmpegCommand)

            // Should use CRF 18 for HIGH quality
            assertTrue(ffmpegCommand.contains("-crf"))
            assertTrue(ffmpegCommand.contains("18"))
        }

        @Test
        fun `render uses specified encoding preset`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = createTestVideoInfo()
            val outputOptions = OutputOptions(
                outputDirectory = outputDir,
                subtitleType = SubtitleType.BURNED_IN,
                exportSrt = false,
                renderOptions = RenderOptions(
                    encoding = EncodingConfig(preset = EncodingPreset.FAST)
                )
            )

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } returns 0

            subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            val ffmpegCommand = capturedCommands.find { it.firstOrNull()?.contains("ffmpeg") == true }
            assertNotNull(ffmpegCommand)

            // Should use fast preset
            assertTrue(ffmpegCommand.contains("-preset"))
            assertTrue(ffmpegCommand.contains("fast"))
        }

        @Test
        fun `render uses custom bitrate when specified`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = createTestVideoInfo()
            val outputOptions = OutputOptions(
                outputDirectory = outputDir,
                subtitleType = SubtitleType.BURNED_IN,
                exportSrt = false,
                renderOptions = RenderOptions(
                    encoding = EncodingConfig(customBitrate = 8000)
                )
            )

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } returns 0

            subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            val ffmpegCommand = capturedCommands.find { it.firstOrNull()?.contains("ffmpeg") == true }
            assertNotNull(ffmpegCommand)

            // Should use bitrate instead of CRF
            assertTrue(ffmpegCommand.contains("-b:v"))
            assertTrue(ffmpegCommand.contains("8000k"))
            assertFalse(ffmpegCommand.contains("-crf"))
        }

        @Test
        fun `render copies audio by default`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = createTestVideoInfo()
            val outputOptions = createTestOutputOptions(outputDir)

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } returns 0

            subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            val ffmpegCommand = capturedCommands.find { it.firstOrNull()?.contains("ffmpeg") == true }
            assertNotNull(ffmpegCommand)

            // Should copy audio
            val caIndex = ffmpegCommand.indexOf("-c:a")
            assertTrue(caIndex >= 0)
            assertEquals("copy", ffmpegCommand[caIndex + 1])
        }

        @Test
        fun `render re-encodes audio when specified`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = createTestVideoInfo()
            val outputOptions = OutputOptions(
                outputDirectory = outputDir,
                subtitleType = SubtitleType.BURNED_IN,
                exportSrt = false,
                renderOptions = RenderOptions(
                    encoding = EncodingConfig(
                        audioCodec = AudioCodec.AAC,
                        audioBitrate = 192
                    )
                )
            )

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } returns 0

            subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            val ffmpegCommand = capturedCommands.find { it.firstOrNull()?.contains("ffmpeg") == true }
            assertNotNull(ffmpegCommand)

            // Should use AAC codec with bitrate
            assertTrue(ffmpegCommand.contains("-c:a"))
            assertTrue(ffmpegCommand.contains("aac"))
            assertTrue(ffmpegCommand.contains("-b:a"))
            assertTrue(ffmpegCommand.contains("192k"))
        }
    }

    // ==================== Output Format Tests ====================

    @Nested
    inner class OutputFormatTest {

        @Test
        fun `render uses MP4 format by default`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = createTestVideoInfo()
            val outputOptions = OutputOptions(
                outputDirectory = outputDir,
                subtitleType = SubtitleType.BURNED_IN,
                exportSrt = false,
                renderOptions = RenderOptions(outputFormat = OutputFormat.MP4)
            )

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } returns 0

            subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            val ffmpegCommand = capturedCommands.find { it.firstOrNull()?.contains("ffmpeg") == true }
            assertNotNull(ffmpegCommand)

            val outputPath = ffmpegCommand.last()
            assertTrue(outputPath.endsWith(".mp4"))
        }

        @Test
        fun `render uses MKV format when specified`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = createTestVideoInfo()
            val outputOptions = OutputOptions(
                outputDirectory = outputDir,
                subtitleType = SubtitleType.BURNED_IN,
                exportSrt = false,
                renderOptions = RenderOptions(outputFormat = OutputFormat.MKV)
            )

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } returns 0

            subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            val ffmpegCommand = capturedCommands.find { it.firstOrNull()?.contains("ffmpeg") == true }
            assertNotNull(ffmpegCommand)

            val outputPath = ffmpegCommand.last()
            assertTrue(outputPath.endsWith(".mkv"))
        }

        @Test
        fun `output filename includes language code`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = Subtitles(
                language = Language.GERMAN,
                entries = listOf(SubtitleEntry(1, 0L, 5000L, "Hallo Welt"))
            )
            val videoInfo = createTestVideoInfo()
            val outputOptions = createTestOutputOptions(outputDir)

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } returns 0

            subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            val ffmpegCommand = capturedCommands.find { it.firstOrNull()?.contains("ffmpeg") == true }
            assertNotNull(ffmpegCommand)

            val outputPath = ffmpegCommand.last()
            assertTrue(outputPath.contains("_DE."))
        }
    }

    // ==================== SRT Export Tests ====================

    @Nested
    inner class SrtExportTest {

        @Test
        fun `exports SRT when requested`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = createTestVideoInfo()
            val outputOptions = OutputOptions(
                outputDirectory = outputDir,
                subtitleType = SubtitleType.BURNED_IN,
                exportSrt = true,
                renderOptions = RenderOptions(useAssSubtitles = true)
            )

            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } returns 0

            subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            // Check that SRT file was created
            val srtFiles = File(outputDir).listFiles { file -> file.extension == "srt" }
            assertNotNull(srtFiles)
            assertTrue(srtFiles.isNotEmpty())

            // Verify SRT content
            val srtContent = srtFiles.first().readText()
            assertTrue(srtContent.contains("00:00:00,000 --> 00:00:05,000"))
            assertTrue(srtContent.contains("Hello world"))
        }
    }

    // ==================== Progress Tracking Tests ====================

    @Nested
    inner class ProgressTrackingTest {

        @Test
        fun `render emits progress updates`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = createTestVideoInfo()
            val outputOptions = createTestOutputOptions(outputDir)

            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback("out_time_ms=15000000")
                callback("fps=30.0")
                callback("speed=2.0x")
                callback("out_time_ms=30000000")
                callback("out_time_ms=45000000")
                callback("progress=end")
                0
            }

            val progressList = subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            // Should have multiple progress updates
            assertTrue(progressList.size > 3)

            // First should be preparing
            assertEquals(0f, progressList.first().percentage)

            // Last should be complete
            assertEquals(1f, progressList.last().percentage)
        }

        @Test
        fun `render shows correct stage messages`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = createTestVideoInfo()
            val outputOptions = createTestOutputOptions(outputDir)

            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } returns 0

            val progressList = subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            // Check for stage transitions
            val messages = progressList.map { it.message }
            assertTrue(messages.any { it.contains("Preparing") })
            assertTrue(messages.any { it.contains("Generating subtitles") || it.contains("Encoding") })
            assertTrue(messages.any { it.contains("Complete") })
        }
    }

    // ==================== Video Bitrate Detection Tests ====================

    @Nested
    inner class VideoBitrateTest {

        @Test
        fun `getVideoBitrate extracts bitrate from ffprobe`() = runTest {
            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback("5000000") // 5Mbps in bps
                0
            }

            val bitrate = subtitleRenderer.getVideoBitrate(videoFile.absolutePath)

            assertEquals(5000, bitrate) // Should be 5000 kbps
        }

        @Test
        fun `getVideoBitrate returns null on error`() = runTest {
            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } throws RuntimeException("ffprobe failed")

            val bitrate = subtitleRenderer.getVideoBitrate(videoFile.absolutePath)

            assertNull(bitrate)
        }

        @Test
        fun `getVideoBitrate returns null for invalid output`() = runTest {
            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            coEvery {
                processExecutor.execute(any(), any(), any(), any())
            } coAnswers {
                val callback = arg<suspend (String) -> Unit>(3)
                callback("N/A")
                0
            }

            val bitrate = subtitleRenderer.getVideoBitrate(videoFile.absolutePath)

            assertNull(bitrate)
        }
    }

    // ==================== Filename Sanitization Tests ====================

    @Nested
    inner class FilenameSanitizationTest {

        @Test
        fun `output filename sanitizes special characters`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = VideoInfo(
                url = "https://example.com/video.mp4",
                id = "test123",
                title = "Test Video with special chars",
                duration = 60000L
            )
            val outputOptions = createTestOutputOptions(outputDir)

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } returns 0

            subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            val ffmpegCommand = capturedCommands.find { it.firstOrNull()?.contains("ffmpeg") == true }
            assertNotNull(ffmpegCommand)

            val outputPath = ffmpegCommand.last()
            val filename = File(outputPath).name
            // Should contain underscores for spaces
            assertTrue(filename.contains("_"))
        }

        @Test
        fun `output filename truncates long titles`() = runTest {
            val outputDir = tempDir.resolve("output").toString()
            File(outputDir).mkdirs()

            val videoFile = File(tempDir.toFile(), "input.mp4")
            videoFile.writeText("video content")

            val subtitles = createTestSubtitles()
            val videoInfo = VideoInfo(
                url = "https://example.com/video.mp4",
                id = "test123",
                title = "A".repeat(200), // Very long title
                duration = 60000L
            )
            val outputOptions = createTestOutputOptions(outputDir)

            val capturedCommands = mutableListOf<List<String>>()

            coEvery {
                processExecutor.execute(capture(capturedCommands), any(), any(), any())
            } returns 0

            subtitleRenderer.render(
                videoFile.absolutePath,
                subtitles,
                outputOptions,
                videoInfo
            ).toList()

            val ffmpegCommand = capturedCommands.find { it.firstOrNull()?.contains("ffmpeg") == true }
            assertNotNull(ffmpegCommand)

            val outputPath = ffmpegCommand.last()
            val filename = File(outputPath).nameWithoutExtension
            // Filename should be truncated (100 chars + language suffix)
            assertTrue(filename.length <= 110)
        }
    }
}
