package com.ericjesse.videotranslator.domain.model

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.*

class SubtitleRenderModelsTest {

    // ==================== VideoQuality Tests ====================

    @Nested
    inner class VideoQualityTest {

        @Test
        fun `all qualities have correct CRF values`() {
            assertEquals(-1, VideoQuality.ORIGINAL.crf)
            assertEquals(18, VideoQuality.HIGH.crf)
            assertEquals(23, VideoQuality.MEDIUM.crf)
            assertEquals(28, VideoQuality.LOW.crf)
            assertEquals(32, VideoQuality.VERY_LOW.crf)
        }

        @Test
        fun `all qualities have display names`() {
            VideoQuality.entries.forEach { quality ->
                assertTrue(quality.displayName.isNotBlank())
            }
        }

        @Test
        fun `all qualities have descriptions`() {
            VideoQuality.entries.forEach { quality ->
                assertTrue(quality.description.isNotBlank())
            }
        }

        @Test
        fun `fromCrf returns correct quality`() {
            assertEquals(VideoQuality.HIGH, VideoQuality.fromCrf(18))
            assertEquals(VideoQuality.MEDIUM, VideoQuality.fromCrf(23))
            assertEquals(VideoQuality.LOW, VideoQuality.fromCrf(28))
        }

        @Test
        fun `fromCrf returns MEDIUM for unknown CRF`() {
            assertEquals(VideoQuality.MEDIUM, VideoQuality.fromCrf(20))
            assertEquals(VideoQuality.MEDIUM, VideoQuality.fromCrf(99))
        }
    }

    // ==================== HardwareEncoder Tests ====================

    @Nested
    inner class HardwareEncoderTest {

        @Test
        fun `NONE encoder is libx264`() {
            assertEquals("libx264", HardwareEncoder.NONE.ffmpegEncoder)
        }

        @Test
        fun `NVENC encoder is h264_nvenc`() {
            assertEquals("h264_nvenc", HardwareEncoder.NVENC.ffmpegEncoder)
        }

        @Test
        fun `VIDEOTOOLBOX encoder is h264_videotoolbox`() {
            assertEquals("h264_videotoolbox", HardwareEncoder.VIDEOTOOLBOX.ffmpegEncoder)
        }

        @Test
        fun `VAAPI encoder is h264_vaapi`() {
            assertEquals("h264_vaapi", HardwareEncoder.VAAPI.ffmpegEncoder)
        }

        @Test
        fun `QSV encoder is h264_qsv`() {
            assertEquals("h264_qsv", HardwareEncoder.QSV.ffmpegEncoder)
        }

        @Test
        fun `AMF encoder is h264_amf`() {
            assertEquals("h264_amf", HardwareEncoder.AMF.ffmpegEncoder)
        }

        @Test
        fun `NONE encoder is available on all platforms`() {
            assertEquals(Platform.entries.toSet(), HardwareEncoder.NONE.platform)
        }

        @Test
        fun `VIDEOTOOLBOX is only available on macOS`() {
            assertEquals(setOf(Platform.MACOS), HardwareEncoder.VIDEOTOOLBOX.platform)
        }

        @Test
        fun `NVENC is available on Windows and Linux`() {
            assertEquals(setOf(Platform.WINDOWS, Platform.LINUX), HardwareEncoder.NVENC.platform)
        }

        @Test
        fun `AMF is only available on Windows`() {
            assertEquals(setOf(Platform.WINDOWS), HardwareEncoder.AMF.platform)
        }

        @Test
        fun `availableForPlatform returns correct encoders for macOS`() {
            val available = HardwareEncoder.availableForPlatform(Platform.MACOS)

            assertTrue(available.contains(HardwareEncoder.NONE))
            assertTrue(available.contains(HardwareEncoder.VIDEOTOOLBOX))
            assertTrue(available.contains(HardwareEncoder.QSV))
            assertFalse(available.contains(HardwareEncoder.NVENC))
            assertFalse(available.contains(HardwareEncoder.AMF))
        }

        @Test
        fun `availableForPlatform returns correct encoders for Windows`() {
            val available = HardwareEncoder.availableForPlatform(Platform.WINDOWS)

            assertTrue(available.contains(HardwareEncoder.NONE))
            assertTrue(available.contains(HardwareEncoder.NVENC))
            assertTrue(available.contains(HardwareEncoder.QSV))
            assertTrue(available.contains(HardwareEncoder.AMF))
            assertFalse(available.contains(HardwareEncoder.VIDEOTOOLBOX))
        }

        @Test
        fun `availableForPlatform returns correct encoders for Linux`() {
            val available = HardwareEncoder.availableForPlatform(Platform.LINUX)

            assertTrue(available.contains(HardwareEncoder.NONE))
            assertTrue(available.contains(HardwareEncoder.NVENC))
            assertTrue(available.contains(HardwareEncoder.VAAPI))
            assertTrue(available.contains(HardwareEncoder.QSV))
            assertFalse(available.contains(HardwareEncoder.VIDEOTOOLBOX))
            assertFalse(available.contains(HardwareEncoder.AMF))
        }

        @Test
        fun `all encoders have display names`() {
            HardwareEncoder.entries.forEach { encoder ->
                assertTrue(encoder.displayName.isNotBlank())
            }
        }
    }

    // ==================== Platform Tests ====================

    @Nested
    inner class PlatformTest {

        @Test
        fun `current returns valid platform`() {
            val platform = Platform.current()
            assertTrue(platform in Platform.entries)
        }

        @Test
        fun `platform entries contain all expected values`() {
            assertEquals(3, Platform.entries.size)
            assertTrue(Platform.entries.contains(Platform.WINDOWS))
            assertTrue(Platform.entries.contains(Platform.MACOS))
            assertTrue(Platform.entries.contains(Platform.LINUX))
        }
    }

    // ==================== SubtitlePosition Tests ====================

    @Nested
    inner class SubtitlePositionTest {

        @Test
        fun `BOTTOM has alignment 2`() {
            assertEquals(2, SubtitlePosition.BOTTOM.assAlignment)
        }

        @Test
        fun `TOP has alignment 8`() {
            assertEquals(8, SubtitlePosition.TOP.assAlignment)
        }

        @Test
        fun `CENTER has alignment 5`() {
            assertEquals(5, SubtitlePosition.CENTER.assAlignment)
        }

        @Test
        fun `all positions have correct alignment values`() {
            // Bottom row: 1, 2, 3
            assertEquals(1, SubtitlePosition.BOTTOM_LEFT.assAlignment)
            assertEquals(2, SubtitlePosition.BOTTOM.assAlignment)
            assertEquals(3, SubtitlePosition.BOTTOM_RIGHT.assAlignment)
            // Middle row: 4, 5, 6
            assertEquals(4, SubtitlePosition.CENTER_LEFT.assAlignment)
            assertEquals(5, SubtitlePosition.CENTER.assAlignment)
            assertEquals(6, SubtitlePosition.CENTER_RIGHT.assAlignment)
            // Top row: 7, 8, 9
            assertEquals(7, SubtitlePosition.TOP_LEFT.assAlignment)
            assertEquals(8, SubtitlePosition.TOP.assAlignment)
            assertEquals(9, SubtitlePosition.TOP_RIGHT.assAlignment)
        }

        @Test
        fun `bottom positions have margin 20`() {
            assertEquals(20, SubtitlePosition.BOTTOM.assMarginV)
            assertEquals(20, SubtitlePosition.BOTTOM_LEFT.assMarginV)
            assertEquals(20, SubtitlePosition.BOTTOM_RIGHT.assMarginV)
        }

        @Test
        fun `top positions have margin 20`() {
            assertEquals(20, SubtitlePosition.TOP.assMarginV)
            assertEquals(20, SubtitlePosition.TOP_LEFT.assMarginV)
            assertEquals(20, SubtitlePosition.TOP_RIGHT.assMarginV)
        }

        @Test
        fun `center positions have margin 0`() {
            assertEquals(0, SubtitlePosition.CENTER.assMarginV)
            assertEquals(0, SubtitlePosition.CENTER_LEFT.assMarginV)
            assertEquals(0, SubtitlePosition.CENTER_RIGHT.assMarginV)
        }

        @Test
        fun `all positions have display names`() {
            SubtitlePosition.entries.forEach { position ->
                assertTrue(position.displayName.isNotBlank())
            }
        }
    }

    // ==================== FontWeight Tests ====================

    @Nested
    inner class FontWeightTest {

        @Test
        fun `NORMAL has ASS value 400`() {
            assertEquals(400, FontWeight.NORMAL.assValue)
        }

        @Test
        fun `BOLD has ASS value 700`() {
            assertEquals(700, FontWeight.BOLD.assValue)
        }

        @Test
        fun `LIGHT has ASS value 300`() {
            assertEquals(300, FontWeight.LIGHT.assValue)
        }

        @Test
        fun `EXTRA_BOLD has ASS value 800`() {
            assertEquals(800, FontWeight.EXTRA_BOLD.assValue)
        }

        @Test
        fun `all weights have display names`() {
            FontWeight.entries.forEach { weight ->
                assertTrue(weight.displayName.isNotBlank())
            }
        }
    }

    // ==================== BorderStyle Tests ====================

    @Nested
    inner class BorderStyleTest {

        @Test
        fun `OUTLINE has ASS BorderStyle 1`() {
            assertEquals(1, BorderStyle.OUTLINE.assBorderStyle)
        }

        @Test
        fun `OPAQUE_BOX has ASS BorderStyle 3`() {
            assertEquals(3, BorderStyle.OPAQUE_BOX.assBorderStyle)
        }

        @Test
        fun `DROP_SHADOW has ASS BorderStyle 4`() {
            assertEquals(4, BorderStyle.DROP_SHADOW.assBorderStyle)
        }

        @Test
        fun `NONE has ASS BorderStyle 0`() {
            assertEquals(0, BorderStyle.NONE.assBorderStyle)
        }

        @Test
        fun `all border styles have display names`() {
            BorderStyle.entries.forEach { style ->
                assertTrue(style.displayName.isNotBlank())
            }
        }
    }

    // ==================== SubtitleStyle Tests ====================

    @Nested
    inner class SubtitleStyleTest {

        @Test
        fun `default values are set correctly`() {
            val style = SubtitleStyle()

            assertEquals("Arial", style.fontFamily)
            assertEquals(24, style.fontSize)
            assertEquals(FontWeight.NORMAL, style.fontWeight)
            assertEquals("#FFFFFF", style.primaryColor)
            assertEquals("#FFFF00", style.secondaryColor)
            assertEquals("#000000", style.outlineColor)
            assertEquals("#000000", style.shadowColor)
            assertEquals(2f, style.outlineWidth)
            assertEquals(1f, style.shadowDepth)
            assertEquals(BorderStyle.OUTLINE, style.borderStyle)
            assertEquals(SubtitlePosition.BOTTOM, style.position)
            assertEquals(10, style.marginLeft)
            assertEquals(10, style.marginRight)
            assertNull(style.marginVertical)
            assertFalse(style.italic)
            assertFalse(style.underline)
            assertFalse(style.strikeout)
            assertEquals(100, style.scaleX)
            assertEquals(100, style.scaleY)
            assertEquals(0f, style.spacing)
            assertEquals(0f, style.angle)
        }

        @Test
        fun `effectiveMarginVertical uses position default when not overridden`() {
            val style = SubtitleStyle(position = SubtitlePosition.BOTTOM)

            assertEquals(20, style.effectiveMarginVertical)
        }

        @Test
        fun `effectiveMarginVertical uses override when provided`() {
            val style = SubtitleStyle(
                position = SubtitlePosition.BOTTOM,
                marginVertical = 50
            )

            assertEquals(50, style.effectiveMarginVertical)
        }

        @Test
        fun `colorToAss converts 6-digit hex correctly`() {
            val style = SubtitleStyle()

            assertEquals("&H00FFFFFF", style.colorToAss("#FFFFFF"))
            assertEquals("&H00000000", style.colorToAss("#000000"))
            assertEquals("&H00FF0000", style.colorToAss("#0000FF")) // RGB to BGR
            assertEquals("&H0000FF00", style.colorToAss("#00FF00"))
            assertEquals("&H000000FF", style.colorToAss("#FF0000"))
        }

        @Test
        fun `colorToAss converts 8-digit hex correctly`() {
            val style = SubtitleStyle()

            assertEquals("&H80FFFFFF", style.colorToAss("#80FFFFFF"))
            assertEquals("&HFF000000", style.colorToAss("#FF000000"))
        }

        @Test
        fun `colorToAss handles missing hash`() {
            val style = SubtitleStyle()

            assertEquals("&H00FFFFFF", style.colorToAss("FFFFFF"))
        }

        @Test
        fun `colorToAss returns default for invalid color`() {
            val style = SubtitleStyle()

            assertEquals("&H00FFFFFF", style.colorToAss("invalid"))
            assertEquals("&H00FFFFFF", style.colorToAss("FFF"))
        }

        @Test
        fun `custom values are applied`() {
            val style = SubtitleStyle(
                fontFamily = "Helvetica",
                fontSize = 36,
                fontWeight = FontWeight.BOLD,
                primaryColor = "#FF0000",
                italic = true,
                underline = true,
                position = SubtitlePosition.TOP
            )

            assertEquals("Helvetica", style.fontFamily)
            assertEquals(36, style.fontSize)
            assertEquals(FontWeight.BOLD, style.fontWeight)
            assertEquals("#FF0000", style.primaryColor)
            assertTrue(style.italic)
            assertTrue(style.underline)
            assertEquals(SubtitlePosition.TOP, style.position)
        }
    }

    // ==================== EncodingConfig Tests ====================

    @Nested
    inner class EncodingConfigTest {

        @Test
        fun `default values are set correctly`() {
            val config = EncodingConfig()

            assertEquals(VideoQuality.MEDIUM, config.quality)
            assertEquals(HardwareEncoder.NONE, config.encoder)
            assertEquals(EncodingPreset.MEDIUM, config.preset)
            assertNull(config.customBitrate)
            assertFalse(config.twoPass)
            assertEquals(AudioCodec.COPY, config.audioCodec)
            assertNull(config.audioBitrate)
        }

        @Test
        fun `custom values are applied`() {
            val config = EncodingConfig(
                quality = VideoQuality.HIGH,
                encoder = HardwareEncoder.NVENC,
                preset = EncodingPreset.FAST,
                customBitrate = 8000,
                twoPass = true,
                audioCodec = AudioCodec.AAC,
                audioBitrate = 192
            )

            assertEquals(VideoQuality.HIGH, config.quality)
            assertEquals(HardwareEncoder.NVENC, config.encoder)
            assertEquals(EncodingPreset.FAST, config.preset)
            assertEquals(8000, config.customBitrate)
            assertTrue(config.twoPass)
            assertEquals(AudioCodec.AAC, config.audioCodec)
            assertEquals(192, config.audioBitrate)
        }
    }

    // ==================== EncodingPreset Tests ====================

    @Nested
    inner class EncodingPresetTest {

        @Test
        fun `all presets have ffmpeg values`() {
            assertEquals("ultrafast", EncodingPreset.ULTRAFAST.ffmpegValue)
            assertEquals("superfast", EncodingPreset.SUPERFAST.ffmpegValue)
            assertEquals("veryfast", EncodingPreset.VERYFAST.ffmpegValue)
            assertEquals("faster", EncodingPreset.FASTER.ffmpegValue)
            assertEquals("fast", EncodingPreset.FAST.ffmpegValue)
            assertEquals("medium", EncodingPreset.MEDIUM.ffmpegValue)
            assertEquals("slow", EncodingPreset.SLOW.ffmpegValue)
            assertEquals("slower", EncodingPreset.SLOWER.ffmpegValue)
            assertEquals("veryslow", EncodingPreset.VERYSLOW.ffmpegValue)
        }

        @Test
        fun `all presets have display names`() {
            EncodingPreset.entries.forEach { preset ->
                assertTrue(preset.displayName.isNotBlank())
            }
        }
    }

    // ==================== AudioCodec Tests ====================

    @Nested
    inner class AudioCodecTest {

        @Test
        fun `all codecs have ffmpeg values`() {
            assertEquals("copy", AudioCodec.COPY.ffmpegValue)
            assertEquals("aac", AudioCodec.AAC.ffmpegValue)
            assertEquals("libopus", AudioCodec.OPUS.ffmpegValue)
            assertEquals("libmp3lame", AudioCodec.MP3.ffmpegValue)
        }

        @Test
        fun `all codecs have display names`() {
            AudioCodec.entries.forEach { codec ->
                assertTrue(codec.displayName.isNotBlank())
            }
        }
    }

    // ==================== RenderOptions Tests ====================

    @Nested
    inner class RenderOptionsTest {

        @Test
        fun `default values are set correctly`() {
            val options = RenderOptions()

            assertNotNull(options.subtitleStyle)
            assertNotNull(options.encoding)
            assertEquals(OutputFormat.MP4, options.outputFormat)
            assertTrue(options.useAssSubtitles)
        }

        @Test
        fun `custom values are applied`() {
            val options = RenderOptions(
                subtitleStyle = SubtitleStyle(fontSize = 32),
                encoding = EncodingConfig(quality = VideoQuality.HIGH),
                outputFormat = OutputFormat.MKV,
                useAssSubtitles = false
            )

            assertEquals(32, options.subtitleStyle.fontSize)
            assertEquals(VideoQuality.HIGH, options.encoding.quality)
            assertEquals(OutputFormat.MKV, options.outputFormat)
            assertFalse(options.useAssSubtitles)
        }
    }

    // ==================== OutputFormat Tests ====================

    @Nested
    inner class OutputFormatTest {

        @Test
        fun `MP4 has correct extension`() {
            assertEquals("mp4", OutputFormat.MP4.extension)
        }

        @Test
        fun `MKV has correct extension`() {
            assertEquals("mkv", OutputFormat.MKV.extension)
        }

        @Test
        fun `MOV has correct extension`() {
            assertEquals("mov", OutputFormat.MOV.extension)
        }

        @Test
        fun `WEBM has correct extension`() {
            assertEquals("webm", OutputFormat.WEBM.extension)
        }

        @Test
        fun `only MKV supports soft subtitles`() {
            assertTrue(OutputFormat.MKV.supportsSoftSubs)
            assertFalse(OutputFormat.MP4.supportsSoftSubs)
            assertFalse(OutputFormat.MOV.supportsSoftSubs)
            assertFalse(OutputFormat.WEBM.supportsSoftSubs)
        }

        @Test
        fun `all formats have display names`() {
            OutputFormat.entries.forEach { format ->
                assertTrue(format.displayName.isNotBlank())
            }
        }
    }

    // ==================== RenderProgress Tests ====================

    @Nested
    inner class RenderProgressTest {

        @Test
        fun `default values are set correctly`() {
            val progress = RenderProgress(
                percentage = 0.5f,
                currentTime = 30000,
                totalTime = 60000
            )

            assertEquals(0.5f, progress.percentage)
            assertEquals(30000, progress.currentTime)
            assertEquals(60000, progress.totalTime)
            assertEquals(0f, progress.fps)
            assertEquals(0f, progress.bitrate)
            assertEquals(0f, progress.speed)
            assertEquals(RenderStage.PREPARING, progress.stage)
            assertNull(progress.eta)
        }

        @Test
        fun `message for PREPARING stage`() {
            val progress = RenderProgress(
                percentage = 0f,
                currentTime = 0,
                totalTime = 60000,
                stage = RenderStage.PREPARING
            )

            assertEquals("Preparing...", progress.message)
        }

        @Test
        fun `message for GENERATING_SUBTITLES stage`() {
            val progress = RenderProgress(
                percentage = 0.1f,
                currentTime = 0,
                totalTime = 60000,
                stage = RenderStage.GENERATING_SUBTITLES
            )

            assertEquals("Generating subtitles...", progress.message)
        }

        @Test
        fun `message for ENCODING stage without speed`() {
            val progress = RenderProgress(
                percentage = 0.5f,
                currentTime = 30000,
                totalTime = 60000,
                stage = RenderStage.ENCODING,
                speed = 0f
            )

            assertEquals("Encoding: 50%", progress.message)
        }

        @Test
        fun `message for ENCODING stage with speed`() {
            val progress = RenderProgress(
                percentage = 0.5f,
                currentTime = 30000,
                totalTime = 60000,
                stage = RenderStage.ENCODING,
                speed = 2.5f
            )

            // Locale-independent check (decimal separator may vary)
            assertTrue(progress.message.startsWith("Encoding: 50%"))
            assertTrue(progress.message.contains("2") && progress.message.contains("5x"))
        }

        @Test
        fun `message for ENCODING stage with ETA in seconds`() {
            val progress = RenderProgress(
                percentage = 0.5f,
                currentTime = 30000,
                totalTime = 60000,
                stage = RenderStage.ENCODING,
                speed = 2.5f,
                eta = 30
            )

            // Locale-independent check
            assertTrue(progress.message.startsWith("Encoding: 50%"))
            assertTrue(progress.message.contains("30s remaining"))
        }

        @Test
        fun `message for ENCODING stage with ETA in minutes`() {
            val progress = RenderProgress(
                percentage = 0.25f,
                currentTime = 15000,
                totalTime = 60000,
                stage = RenderStage.ENCODING,
                speed = 1.0f,
                eta = 125 // 2m 5s
            )

            // Locale-independent check
            assertTrue(progress.message.startsWith("Encoding: 25%"))
            assertTrue(progress.message.contains("2m 5s remaining"))
        }

        @Test
        fun `message for ENCODING stage with ETA in hours`() {
            val progress = RenderProgress(
                percentage = 0.1f,
                currentTime = 6000,
                totalTime = 60000,
                stage = RenderStage.ENCODING,
                speed = 0.5f,
                eta = 3725 // 1h 2m 5s
            )

            // Locale-independent check
            assertTrue(progress.message.startsWith("Encoding: 10%"))
            assertTrue(progress.message.contains("1h 2m remaining"))
        }

        @Test
        fun `message for FINALIZING stage`() {
            val progress = RenderProgress(
                percentage = 0.95f,
                currentTime = 57000,
                totalTime = 60000,
                stage = RenderStage.FINALIZING
            )

            assertEquals("Finalizing...", progress.message)
        }

        @Test
        fun `message for COMPLETE stage`() {
            val progress = RenderProgress(
                percentage = 1f,
                currentTime = 60000,
                totalTime = 60000,
                stage = RenderStage.COMPLETE
            )

            assertEquals("Complete", progress.message)
        }
    }

    // ==================== RenderStage Tests ====================

    @Nested
    inner class RenderStageTest {

        @Test
        fun `all stages exist`() {
            assertEquals(5, RenderStage.entries.size)
            assertTrue(RenderStage.entries.contains(RenderStage.PREPARING))
            assertTrue(RenderStage.entries.contains(RenderStage.GENERATING_SUBTITLES))
            assertTrue(RenderStage.entries.contains(RenderStage.ENCODING))
            assertTrue(RenderStage.entries.contains(RenderStage.FINALIZING))
            assertTrue(RenderStage.entries.contains(RenderStage.COMPLETE))
        }
    }

    // ==================== AssGenerator Tests ====================

    @Nested
    inner class AssGeneratorTest {

        @Test
        fun `generate creates valid ASS content`() {
            val subtitles = Subtitles(
                language = Language.ENGLISH,
                entries = listOf(
                    SubtitleEntry(1, 0L, 1000L, "Hello"),
                    SubtitleEntry(2, 1000L, 2000L, "World")
                )
            )
            val style = SubtitleStyle()

            val result = AssGenerator.generate(subtitles, style)

            assertTrue(result.contains("[Script Info]"))
            assertTrue(result.contains("ScriptType: v4.00+"))
            assertTrue(result.contains("PlayResX: 1920"))
            assertTrue(result.contains("PlayResY: 1080"))
            assertTrue(result.contains("[V4+ Styles]"))
            assertTrue(result.contains("Style: Default,Arial,24"))
            assertTrue(result.contains("[Events]"))
            assertTrue(result.contains("Dialogue: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,Hello"))
            assertTrue(result.contains("Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,World"))
        }

        @Test
        fun `generate uses custom video dimensions`() {
            val subtitles = Subtitles(
                language = Language.ENGLISH,
                entries = emptyList()
            )
            val style = SubtitleStyle()

            val result = AssGenerator.generate(subtitles, style, 1280, 720)

            assertTrue(result.contains("PlayResX: 1280"))
            assertTrue(result.contains("PlayResY: 720"))
        }

        @Test
        fun `generate handles special characters`() {
            val subtitles = Subtitles(
                language = Language.ENGLISH,
                entries = listOf(
                    SubtitleEntry(1, 0L, 1000L, "Test {special} \\chars")
                )
            )
            val style = SubtitleStyle()

            val result = AssGenerator.generate(subtitles, style)

            assertTrue(result.contains("Test \\{special\\} \\\\chars"))
        }

        @Test
        fun `generate handles newlines`() {
            val subtitles = Subtitles(
                language = Language.ENGLISH,
                entries = listOf(
                    SubtitleEntry(1, 0L, 1000L, "Line 1\nLine 2")
                )
            )
            val style = SubtitleStyle()

            val result = AssGenerator.generate(subtitles, style)

            assertTrue(result.contains("Line 1\\NLine 2"))
        }

        @Test
        fun `generate applies font weight`() {
            val subtitles = Subtitles(
                language = Language.ENGLISH,
                entries = emptyList()
            )
            val style = SubtitleStyle(fontWeight = FontWeight.BOLD)

            val result = AssGenerator.generate(subtitles, style)

            // Bold is -1 in the style line
            assertTrue(result.contains(",-1,0,0,0,")) // Bold=true, Italic=false, Underline=false, Strikeout=false
        }

        @Test
        fun `generate applies italic`() {
            val subtitles = Subtitles(
                language = Language.ENGLISH,
                entries = emptyList()
            )
            val style = SubtitleStyle(italic = true)

            val result = AssGenerator.generate(subtitles, style)

            assertTrue(result.contains(",0,-1,0,0,")) // Bold=false, Italic=true, Underline=false, Strikeout=false
        }

        @Test
        fun `generate formats long timestamps correctly`() {
            val subtitles = Subtitles(
                language = Language.ENGLISH,
                entries = listOf(
                    SubtitleEntry(1, 3661000L, 3662500L, "Over an hour") // 1:01:01.00 to 1:01:02.50
                )
            )
            val style = SubtitleStyle()

            val result = AssGenerator.generate(subtitles, style)

            assertTrue(result.contains("Dialogue: 0,1:01:01.00,1:01:02.50,Default,,0,0,0,,Over an hour"))
        }
    }

    // ==================== FfmpegProgressParser Tests ====================

    @Nested
    inner class FfmpegProgressParserTest {

        @Test
        fun `parseLine returns null for unrecognized line`() {
            val progress = RenderProgress(0f, 0, 60000, stage = RenderStage.ENCODING)

            val result = FfmpegProgressParser.parseLine("some random text", 60000, progress)

            assertNull(result)
        }

        @Test
        fun `parseLine parses out_time_ms`() {
            val progress = RenderProgress(0f, 0, 60000, stage = RenderStage.ENCODING)

            val result = FfmpegProgressParser.parseLine("out_time_ms=30000000", 60000, progress)

            assertNotNull(result)
            assertEquals(0.5f, result.percentage, 0.01f)
            assertEquals(30000, result.currentTime)
        }

        @Test
        fun `parseLine parses fps`() {
            val progress = RenderProgress(0f, 0, 60000, stage = RenderStage.ENCODING)

            val result = FfmpegProgressParser.parseLine("fps=30.5", 60000, progress)

            assertNotNull(result)
            assertEquals(30.5f, result.fps)
        }

        @Test
        fun `parseLine parses bitrate`() {
            val progress = RenderProgress(0f, 0, 60000, stage = RenderStage.ENCODING)

            val result = FfmpegProgressParser.parseLine("bitrate=5000.5kbits/s", 60000, progress)

            assertNotNull(result)
            assertEquals(5000.5f, result.bitrate)
        }

        @Test
        fun `parseLine parses speed`() {
            val progress = RenderProgress(0f, 0, 60000, stage = RenderStage.ENCODING)

            val result = FfmpegProgressParser.parseLine("speed=2.5x", 60000, progress)

            assertNotNull(result)
            assertEquals(2.5f, result.speed)
        }

        @Test
        fun `parseLine handles progress end`() {
            val progress = RenderProgress(0.9f, 54000, 60000, stage = RenderStage.ENCODING)

            val result = FfmpegProgressParser.parseLine("progress=end", 60000, progress)

            assertNotNull(result)
            assertEquals(1f, result.percentage)
            assertEquals(RenderStage.COMPLETE, result.stage)
        }

        @Test
        fun `parseLine returns null for invalid out_time_ms`() {
            val progress = RenderProgress(0f, 0, 60000, stage = RenderStage.ENCODING)

            val result = FfmpegProgressParser.parseLine("out_time_ms=invalid", 60000, progress)

            assertNull(result)
        }

        @Test
        fun `parseLine calculates ETA when speed is available`() {
            val progress = RenderProgress(0f, 0, 60000, speed = 2.0f, stage = RenderStage.ENCODING)

            val result = FfmpegProgressParser.parseLine("out_time_ms=30000000", 60000, progress)

            assertNotNull(result)
            assertNotNull(result.eta)
            // ETA calculation: remainingMs / 1000 / speed = 30000000 / 1000 / 2.0 = 15000
            assertTrue(result.eta!! > 0, "ETA should be positive")
        }

        @Test
        fun `parseDuration parses valid duration string`() {
            val result = FfmpegProgressParser.parseDuration("Duration: 01:30:45.50")

            assertNotNull(result)
            assertEquals(5445500, result) // 1h 30m 45.5s in ms
        }

        @Test
        fun `parseDuration returns null for invalid format`() {
            val result = FfmpegProgressParser.parseDuration("Invalid duration string")

            assertNull(result)
        }

        @Test
        fun `parseDuration handles short durations`() {
            val result = FfmpegProgressParser.parseDuration("Duration: 00:01:30.00")

            assertNotNull(result)
            assertEquals(90000, result) // 1m 30s in ms
        }
    }

    // ==================== HardwareEncoderDetector Tests ====================

    @Nested
    inner class HardwareEncoderDetectorTest {

        @Test
        fun `getTestCommand returns valid command`() {
            val command = HardwareEncoderDetector.getTestCommand("/usr/bin/ffmpeg", HardwareEncoder.NVENC)

            assertEquals("/usr/bin/ffmpeg", command[0])
            assertTrue(command.contains("-c:v"))
            assertTrue(command.contains("h264_nvenc"))
            assertTrue(command.contains("-f"))
            assertTrue(command.contains("null"))
        }

        @Test
        fun `getEncoderArgs for NONE uses libx264`() {
            val args = HardwareEncoderDetector.getEncoderArgs(
                HardwareEncoder.NONE,
                VideoQuality.MEDIUM,
                EncodingPreset.FAST,
                null
            )

            assertTrue(args.contains("-c:v"))
            assertTrue(args.contains("libx264"))
            assertTrue(args.contains("-preset"))
            assertTrue(args.contains("fast"))
            assertTrue(args.contains("-crf"))
            assertTrue(args.contains("23"))
        }

        @Test
        fun `getEncoderArgs uses custom bitrate when provided`() {
            val args = HardwareEncoderDetector.getEncoderArgs(
                HardwareEncoder.NONE,
                VideoQuality.MEDIUM,
                EncodingPreset.MEDIUM,
                8000
            )

            assertTrue(args.contains("-b:v"))
            assertTrue(args.contains("8000k"))
            assertFalse(args.contains("-crf"))
        }

        @Test
        fun `getEncoderArgs for NVENC includes NVENC-specific options`() {
            val args = HardwareEncoderDetector.getEncoderArgs(
                HardwareEncoder.NVENC,
                VideoQuality.HIGH,
                EncodingPreset.FAST,
                null
            )

            assertTrue(args.contains("-c:v"))
            assertTrue(args.contains("h264_nvenc"))
            assertTrue(args.contains("-cq"))
            assertTrue(args.contains("-rc"))
            assertTrue(args.contains("vbr"))
        }

        @Test
        fun `getEncoderArgs for VIDEOTOOLBOX includes allow_sw`() {
            val args = HardwareEncoderDetector.getEncoderArgs(
                HardwareEncoder.VIDEOTOOLBOX,
                VideoQuality.MEDIUM,
                EncodingPreset.MEDIUM,
                null
            )

            assertTrue(args.contains("-c:v"))
            assertTrue(args.contains("h264_videotoolbox"))
            assertTrue(args.contains("-allow_sw"))
            assertTrue(args.contains("1"))
        }

        @Test
        fun `getEncoderArgs for VAAPI includes device`() {
            val args = HardwareEncoderDetector.getEncoderArgs(
                HardwareEncoder.VAAPI,
                VideoQuality.MEDIUM,
                EncodingPreset.MEDIUM,
                null
            )

            assertTrue(args.contains("-c:v"))
            assertTrue(args.contains("h264_vaapi"))
            assertTrue(args.contains("-vaapi_device"))
            assertTrue(args.contains("/dev/dri/renderD128"))
        }

        @Test
        fun `getEncoderArgs for QSV includes preset`() {
            val args = HardwareEncoderDetector.getEncoderArgs(
                HardwareEncoder.QSV,
                VideoQuality.MEDIUM,
                EncodingPreset.FAST,
                null
            )

            assertTrue(args.contains("-c:v"))
            assertTrue(args.contains("h264_qsv"))
            assertTrue(args.contains("-preset"))
        }

        @Test
        fun `getEncoderArgs for AMF includes quality`() {
            val args = HardwareEncoderDetector.getEncoderArgs(
                HardwareEncoder.AMF,
                VideoQuality.MEDIUM,
                EncodingPreset.MEDIUM,
                null
            )

            assertTrue(args.contains("-c:v"))
            assertTrue(args.contains("h264_amf"))
            assertTrue(args.contains("-quality"))
            assertTrue(args.contains("balanced"))
        }

        @Test
        fun `getEncoderArgs for AMF with custom bitrate uses cbr`() {
            val args = HardwareEncoderDetector.getEncoderArgs(
                HardwareEncoder.AMF,
                VideoQuality.MEDIUM,
                EncodingPreset.MEDIUM,
                5000
            )

            assertTrue(args.contains("-rc"))
            assertTrue(args.contains("cbr"))
            assertTrue(args.contains("-b:v"))
            assertTrue(args.contains("5000k"))
        }
    }

    // ==================== SystemFonts Tests ====================

    @Nested
    inner class SystemFontsTest {

        @Test
        fun `commonFonts contains expected fonts`() {
            assertTrue(SystemFonts.commonFonts.contains("Arial"))
            assertTrue(SystemFonts.commonFonts.contains("Helvetica"))
            assertTrue(SystemFonts.commonFonts.contains("Times New Roman"))
            assertTrue(SystemFonts.commonFonts.contains("Courier New"))
        }

        @Test
        fun `getFontDirectories returns non-empty list`() {
            val directories = SystemFonts.getFontDirectories()

            assertTrue(directories.isNotEmpty())
        }

        @Test
        fun `getFontDirectories contains user font directory`() {
            val directories = SystemFonts.getFontDirectories()
            val userHome = System.getProperty("user.home")

            assertTrue(directories.any { it.contains(userHome) })
        }
    }
}
