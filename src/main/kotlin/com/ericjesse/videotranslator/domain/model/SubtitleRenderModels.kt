package com.ericjesse.videotranslator.domain.model

import kotlinx.serialization.Serializable

/**
 * Video quality preset for burned-in subtitle rendering.
 *
 * @property displayName Human-readable name for UI.
 * @property crf Constant Rate Factor for x264/x265 (lower = better quality, larger file).
 * @property description Brief description of the quality level.
 */
enum class VideoQuality(
    val displayName: String,
    val crf: Int,
    val description: String
) {
    ORIGINAL("Original", -1, "Re-encode at original bitrate (largest file)"),
    HIGH("High", 18, "Near-lossless quality (large file)"),
    MEDIUM("Medium", 23, "Balanced quality and size (recommended)"),
    LOW("Low", 28, "Smaller file size (reduced quality)"),
    VERY_LOW("Very Low", 32, "Smallest file size (noticeable quality loss)");

    companion object {
        fun fromCrf(crf: Int): VideoQuality {
            return entries.find { it.crf == crf } ?: MEDIUM
        }
    }
}

/**
 * Hardware encoder type for accelerated video encoding.
 *
 * @property displayName Human-readable name for UI.
 * @property ffmpegEncoder FFmpeg encoder name.
 * @property platform Platforms where this encoder is available.
 */
enum class HardwareEncoder(
    val displayName: String,
    val ffmpegEncoder: String,
    val platform: Set<Platform>
) {
    NONE("Software (CPU)", "libx264", Platform.entries.toSet()),
    NVENC("NVIDIA NVENC", "h264_nvenc", setOf(Platform.WINDOWS, Platform.LINUX)),
    VIDEOTOOLBOX("Apple VideoToolbox", "h264_videotoolbox", setOf(Platform.MACOS)),
    VAAPI("VA-API (Linux)", "h264_vaapi", setOf(Platform.LINUX)),
    QSV("Intel QuickSync", "h264_qsv", setOf(Platform.WINDOWS, Platform.LINUX, Platform.MACOS)),
    AMF("AMD AMF", "h264_amf", setOf(Platform.WINDOWS));

    companion object {
        /**
         * Gets available encoders for the current platform.
         */
        fun availableForPlatform(platform: Platform): List<HardwareEncoder> {
            return entries.filter { platform in it.platform }
        }
    }
}

/**
 * Platform types for hardware encoder availability.
 */
enum class Platform {
    WINDOWS,
    MACOS,
    LINUX;

    companion object {
        fun current(): Platform {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("mac") || os.contains("darwin") -> MACOS
                os.contains("win") -> WINDOWS
                else -> LINUX
            }
        }
    }
}

/**
 * Subtitle position on screen.
 *
 * @property displayName Human-readable name for UI.
 * @property assAlignment ASS alignment code (1-9, numpad layout).
 * @property assMarginV Vertical margin from edge in pixels.
 */
enum class SubtitlePosition(
    val displayName: String,
    val assAlignment: Int,
    val assMarginV: Int
) {
    BOTTOM("Bottom", 2, 20),
    BOTTOM_LEFT("Bottom Left", 1, 20),
    BOTTOM_RIGHT("Bottom Right", 3, 20),
    TOP("Top", 8, 20),
    TOP_LEFT("Top Left", 7, 20),
    TOP_RIGHT("Top Right", 9, 20),
    CENTER("Center", 5, 0),
    CENTER_LEFT("Center Left", 4, 0),
    CENTER_RIGHT("Center Right", 6, 0);
}

/**
 * Font weight options.
 */
enum class FontWeight(val displayName: String, val assValue: Int) {
    NORMAL("Normal", 400),
    BOLD("Bold", 700),
    LIGHT("Light", 300),
    EXTRA_BOLD("Extra Bold", 800);
}

/**
 * Border/outline style for subtitles.
 *
 * @property displayName Human-readable name.
 * @property assBorderStyle ASS BorderStyle value (1=outline+shadow, 3=opaque box, 4=shadow).
 */
enum class BorderStyle(val displayName: String, val assBorderStyle: Int) {
    OUTLINE("Outline", 1),
    OPAQUE_BOX("Opaque Box", 3),
    DROP_SHADOW("Drop Shadow", 4),
    NONE("None", 0);
}

/**
 * Complete subtitle style configuration.
 *
 * @property fontFamily Font family name (must be installed on system).
 * @property fontSize Font size in pixels.
 * @property fontWeight Font weight.
 * @property primaryColor Primary text color in hex format (#RRGGBB or #AARRGGBB).
 * @property secondaryColor Secondary color for karaoke effects.
 * @property outlineColor Outline/border color.
 * @property shadowColor Shadow/background color.
 * @property outlineWidth Outline thickness in pixels.
 * @property shadowDepth Shadow offset in pixels.
 * @property borderStyle Border/outline style.
 * @property position Subtitle position on screen.
 * @property marginLeft Left margin in pixels.
 * @property marginRight Right margin in pixels.
 * @property marginVertical Vertical margin in pixels (overrides position default).
 * @property italic Enable italic text.
 * @property underline Enable underlined text.
 * @property strikeout Enable strikethrough text.
 * @property scaleX Horizontal scale percentage (100 = normal).
 * @property scaleY Vertical scale percentage (100 = normal).
 * @property spacing Letter spacing in pixels.
 * @property angle Rotation angle in degrees.
 */
@Serializable
data class SubtitleStyle(
    val fontFamily: String = "Arial",
    val fontSize: Int = 24,
    val fontWeight: FontWeight = FontWeight.NORMAL,
    val primaryColor: String = "#FFFFFF",
    val secondaryColor: String = "#FFFF00",
    val outlineColor: String = "#000000",
    val shadowColor: String = "#000000",
    val outlineWidth: Float = 2f,
    val shadowDepth: Float = 1f,
    val borderStyle: BorderStyle = BorderStyle.OUTLINE,
    val position: SubtitlePosition = SubtitlePosition.BOTTOM,
    val marginLeft: Int = 10,
    val marginRight: Int = 10,
    val marginVertical: Int? = null,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikeout: Boolean = false,
    val scaleX: Int = 100,
    val scaleY: Int = 100,
    val spacing: Float = 0f,
    val angle: Float = 0f
) {
    /**
     * Gets the effective vertical margin, using position default if not overridden.
     */
    val effectiveMarginVertical: Int
        get() = marginVertical ?: position.assMarginV

    /**
     * Converts a hex color to ASS format (&HAABBGGRR).
     */
    fun colorToAss(hexColor: String): String {
        val color = hexColor.removePrefix("#")
        return when (color.length) {
            6 -> {
                // #RRGGBB -> &H00BBGGRR
                val r = color.substring(0, 2)
                val g = color.substring(2, 4)
                val b = color.substring(4, 6)
                "&H00${b}${g}${r}"
            }
            8 -> {
                // #AARRGGBB -> &HAABBGGRR
                val a = color.substring(0, 2)
                val r = color.substring(2, 4)
                val g = color.substring(4, 6)
                val b = color.substring(6, 8)
                "&H${a}${b}${g}${r}"
            }
            else -> "&H00FFFFFF"
        }
    }
}

/**
 * Video encoding configuration.
 *
 * @property quality Video quality preset.
 * @property encoder Hardware encoder to use.
 * @property preset Encoding speed/quality preset (ultrafast to veryslow).
 * @property customBitrate Custom bitrate in kbps (null = use CRF).
 * @property twoPass Enable two-pass encoding for better quality.
 * @property audioCodec Audio codec (copy, aac, opus).
 * @property audioBitrate Audio bitrate in kbps (null = copy or default).
 */
@Serializable
data class EncodingConfig(
    val quality: VideoQuality = VideoQuality.MEDIUM,
    val encoder: HardwareEncoder = HardwareEncoder.NONE,
    val preset: EncodingPreset = EncodingPreset.MEDIUM,
    val customBitrate: Int? = null,
    val twoPass: Boolean = false,
    val audioCodec: AudioCodec = AudioCodec.COPY,
    val audioBitrate: Int? = null
)

/**
 * Encoding speed preset.
 */
enum class EncodingPreset(val displayName: String, val ffmpegValue: String) {
    ULTRAFAST("Ultra Fast", "ultrafast"),
    SUPERFAST("Super Fast", "superfast"),
    VERYFAST("Very Fast", "veryfast"),
    FASTER("Faster", "faster"),
    FAST("Fast", "fast"),
    MEDIUM("Medium", "medium"),
    SLOW("Slow", "slow"),
    SLOWER("Slower", "slower"),
    VERYSLOW("Very Slow", "veryslow");
}

/**
 * Audio codec options.
 */
enum class AudioCodec(val displayName: String, val ffmpegValue: String) {
    COPY("Copy (No Re-encode)", "copy"),
    AAC("AAC", "aac"),
    OPUS("Opus", "libopus"),
    MP3("MP3", "libmp3lame");
}

/**
 * Render options combining all configuration.
 *
 * @property subtitleStyle Subtitle styling options.
 * @property encoding Video encoding options.
 * @property outputFormat Output container format.
 * @property useAssSubtitles Use ASS format for better styling (vs SRT).
 */
@Serializable
data class RenderOptions(
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val encoding: EncodingConfig = EncodingConfig(),
    val outputFormat: OutputFormat = OutputFormat.MP4,
    val useAssSubtitles: Boolean = true
)

/**
 * Output container format.
 */
enum class OutputFormat(
    val displayName: String,
    val extension: String,
    val supportsSoftSubs: Boolean
) {
    MP4("MP4", "mp4", false),
    MKV("MKV (Matroska)", "mkv", true),
    MOV("MOV (QuickTime)", "mov", false),
    WEBM("WebM", "webm", false);
}

/**
 * Render progress information.
 *
 * @property percentage Progress from 0.0 to 1.0.
 * @property currentTime Current position in video (milliseconds).
 * @property totalTime Total video duration (milliseconds).
 * @property fps Current encoding frame rate.
 * @property bitrate Current encoding bitrate (kbps).
 * @property speed Encoding speed relative to realtime.
 * @property stage Current stage of rendering.
 * @property eta Estimated time remaining in seconds.
 */
data class RenderProgress(
    val percentage: Float,
    val currentTime: Long,
    val totalTime: Long,
    val fps: Float = 0f,
    val bitrate: Float = 0f,
    val speed: Float = 0f,
    val stage: RenderStage = RenderStage.PREPARING,
    val eta: Long? = null
) {
    val message: String
        get() = when (stage) {
            RenderStage.PREPARING -> "Preparing..."
            RenderStage.GENERATING_SUBTITLES -> "Generating subtitles..."
            RenderStage.ENCODING -> {
                val pct = (percentage * 100).toInt()
                val etaStr = eta?.let { formatEta(it) } ?: ""
                if (speed > 0) {
                    "Encoding: $pct% (${String.format("%.1f", speed)}x)$etaStr"
                } else {
                    "Encoding: $pct%$etaStr"
                }
            }
            RenderStage.FINALIZING -> "Finalizing..."
            RenderStage.COMPLETE -> "Complete"
        }

    private fun formatEta(seconds: Long): String {
        return when {
            seconds < 60 -> " - ${seconds}s remaining"
            seconds < 3600 -> " - ${seconds / 60}m ${seconds % 60}s remaining"
            else -> " - ${seconds / 3600}h ${(seconds % 3600) / 60}m remaining"
        }
    }
}

/**
 * Stages of the rendering process.
 */
enum class RenderStage {
    PREPARING,
    GENERATING_SUBTITLES,
    ENCODING,
    FINALIZING,
    COMPLETE
}

/**
 * ASS/SSA subtitle file generator.
 */
object AssGenerator {

    /**
     * Generates an ASS subtitle file content from subtitles with styling.
     */
    fun generate(subtitles: Subtitles, style: SubtitleStyle, videoWidth: Int = 1920, videoHeight: Int = 1080): String {
        return buildString {
            // Script Info section
            appendLine("[Script Info]")
            appendLine("ScriptType: v4.00+")
            appendLine("PlayResX: $videoWidth")
            appendLine("PlayResY: $videoHeight")
            appendLine("ScaledBorderAndShadow: yes")
            appendLine("YCbCr Matrix: TV.709")
            appendLine()

            // Styles section
            appendLine("[V4+ Styles]")
            appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
            appendLine(buildStyleLine("Default", style))
            appendLine()

            // Events section
            appendLine("[Events]")
            appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
            for (entry in subtitles.entries) {
                val start = formatAssTime(entry.startTime)
                val end = formatAssTime(entry.endTime)
                val text = escapeAssText(entry.text)
                appendLine("Dialogue: 0,$start,$end,Default,,0,0,0,,$text")
            }
        }
    }

    private fun buildStyleLine(name: String, style: SubtitleStyle): String {
        val bold = if (style.fontWeight == FontWeight.BOLD || style.fontWeight == FontWeight.EXTRA_BOLD) -1 else 0
        val italic = if (style.italic) -1 else 0
        val underline = if (style.underline) -1 else 0
        val strikeout = if (style.strikeout) -1 else 0

        return "Style: $name,${style.fontFamily},${style.fontSize}," +
                "${style.colorToAss(style.primaryColor)}," +
                "${style.colorToAss(style.secondaryColor)}," +
                "${style.colorToAss(style.outlineColor)}," +
                "${style.colorToAss(style.shadowColor)}," +
                "$bold,$italic,$underline,$strikeout," +
                "${style.scaleX},${style.scaleY},${style.spacing},${style.angle}," +
                "${style.borderStyle.assBorderStyle}," +
                "${style.outlineWidth},${style.shadowDepth}," +
                "${style.position.assAlignment}," +
                "${style.marginLeft},${style.marginRight},${style.effectiveMarginVertical}," +
                "1" // Encoding (1 = default)
    }

    private fun formatAssTime(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        val seconds = (ms % 60000) / 1000
        val centiseconds = (ms % 1000) / 10
        return String.format("%d:%02d:%02d.%02d", hours, minutes, seconds, centiseconds)
    }

    private fun escapeAssText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("\n", "\\N") // ASS uses \N for line breaks
    }
}

/**
 * FFmpeg progress parser for accurate progress tracking.
 */
object FfmpegProgressParser {

    /**
     * Parse FFmpeg progress output line.
     * Returns updated progress data or null if line doesn't contain progress info.
     *
     * Note: FFmpeg outputs progress in this order: out_time_us/out_time_ms, fps, bitrate, speed, progress
     * So we calculate ETA when we receive the speed line, since by then we have both
     * current time and current speed.
     *
     * FFmpeg versions may output either out_time_us (microseconds) or out_time_ms (milliseconds).
     */
    fun parseLine(line: String, totalDurationMs: Long, currentProgress: RenderProgress): RenderProgress? {
        return when {
            // Handle out_time_us (microseconds) - explicit microsecond format
            line.startsWith("out_time_us=") -> {
                val currentUs = line.substringAfter("=").toLongOrNull() ?: return null
                val currentMs = currentUs / 1000
                val percentage = if (totalDurationMs > 0) {
                    (currentMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
                } else 0f

                currentProgress.copy(
                    percentage = percentage,
                    currentTime = currentMs
                )
            }
            // Handle out_time_ms - despite the name, FFmpeg outputs this in microseconds
            line.startsWith("out_time_ms=") -> {
                val currentUs = line.substringAfter("=").toLongOrNull() ?: return null
                val currentMs = currentUs / 1000
                val percentage = if (totalDurationMs > 0) {
                    (currentMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
                } else 0f

                currentProgress.copy(
                    percentage = percentage,
                    currentTime = currentMs
                )
            }
            // Handle timestamp format (HH:MM:SS.microseconds)
            line.startsWith("out_time=") -> {
                val timeStr = line.substringAfter("=").trim()
                val currentMs = parseTimeToMs(timeStr) ?: return null
                val percentage = if (totalDurationMs > 0) {
                    (currentMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
                } else 0f

                currentProgress.copy(
                    percentage = percentage,
                    currentTime = currentMs
                )
            }
            line.startsWith("fps=") -> {
                val fps = line.substringAfter("=").trim().toFloatOrNull() ?: return null
                currentProgress.copy(fps = fps)
            }
            line.startsWith("bitrate=") -> {
                val bitrateStr = line.substringAfter("=").trim().removeSuffix("kbits/s")
                val bitrate = bitrateStr.toFloatOrNull() ?: return null
                currentProgress.copy(bitrate = bitrate)
            }
            line.startsWith("speed=") -> {
                val speedStr = line.substringAfter("=").trim().removeSuffix("x")
                val speed = speedStr.toFloatOrNull() ?: return null

                // Calculate ETA now that we have both currentTime and speed
                // currentTime and totalDurationMs are both in milliseconds
                val eta = if (speed > 0 && totalDurationMs > 0 && currentProgress.currentTime > 0) {
                    val remainingMs = totalDurationMs - currentProgress.currentTime
                    val remainingSec = remainingMs / 1000
                    (remainingSec / speed).toLong().coerceAtLeast(0)
                } else null

                currentProgress.copy(speed = speed, eta = eta)
            }
            line.startsWith("progress=") -> {
                val status = line.substringAfter("=").trim()
                if (status == "end") {
                    currentProgress.copy(percentage = 1f, stage = RenderStage.COMPLETE)
                } else null
            }
            else -> null
        }
    }

    /**
     * Parse duration from FFmpeg output.
     * Format: Duration: HH:MM:SS.ss
     */
    fun parseDuration(line: String): Long? {
        val pattern = """Duration:\s*(\d+):(\d+):(\d+)\.(\d+)""".toRegex()
        val match = pattern.find(line) ?: return null

        val (h, m, s, cs) = match.destructured
        return (h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + cs.toLong() * 10
    }

    /**
     * Parse FFmpeg time format to milliseconds.
     * Format: HH:MM:SS.microseconds (e.g., "00:01:30.500000")
     */
    private fun parseTimeToMs(timeStr: String): Long? {
        // Format: HH:MM:SS.microseconds
        val pattern = """(\d+):(\d+):(\d+)\.(\d+)""".toRegex()
        val match = pattern.find(timeStr) ?: return null

        val (h, m, s, us) = match.destructured
        val baseMs = (h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000
        // Convert microseconds to milliseconds (first 3 digits)
        val microStr = us.padEnd(6, '0').take(3)
        val ms = microStr.toLongOrNull() ?: 0
        return baseMs + ms
    }
}

/**
 * Hardware encoder availability checker.
 */
object HardwareEncoderDetector {

    /**
     * FFmpeg command to check if an encoder is available.
     */
    fun getTestCommand(ffmpegPath: String, encoder: HardwareEncoder): List<String> {
        return listOf(
            ffmpegPath,
            "-f", "lavfi",
            "-i", "nullsrc=s=1920x1080:d=1",
            "-c:v", encoder.ffmpegEncoder,
            "-f", "null",
            "-"
        )
    }

    /**
     * Gets FFmpeg arguments for the specified encoder.
     */
    fun getEncoderArgs(
        encoder: HardwareEncoder,
        quality: VideoQuality,
        preset: EncodingPreset,
        customBitrate: Int?
    ): List<String> {
        val args = mutableListOf<String>()

        args.add("-c:v")
        args.add(encoder.ffmpegEncoder)

        when (encoder) {
            HardwareEncoder.NONE -> {
                // Software x264
                args.add("-preset")
                args.add(preset.ffmpegValue)
                if (customBitrate != null) {
                    args.add("-b:v")
                    args.add("${customBitrate}k")
                } else if (quality != VideoQuality.ORIGINAL) {
                    args.add("-crf")
                    args.add(quality.crf.toString())
                }
            }
            HardwareEncoder.NVENC -> {
                // NVIDIA NVENC
                args.add("-preset")
                args.add(mapPresetToNvenc(preset))
                if (customBitrate != null) {
                    args.add("-b:v")
                    args.add("${customBitrate}k")
                } else {
                    args.add("-cq")
                    args.add((quality.crf + 3).coerceIn(0, 51).toString()) // NVENC uses slightly different scale
                }
                args.add("-rc")
                args.add(if (customBitrate != null) "cbr" else "vbr")
            }
            HardwareEncoder.VIDEOTOOLBOX -> {
                // Apple VideoToolbox
                if (customBitrate != null) {
                    args.add("-b:v")
                    args.add("${customBitrate}k")
                } else {
                    args.add("-q:v")
                    args.add((65 - quality.crf).coerceIn(1, 100).toString()) // VT uses inverse quality scale
                }
                args.add("-allow_sw")
                args.add("1")
            }
            HardwareEncoder.VAAPI -> {
                // VA-API
                args.add("-vaapi_device")
                args.add("/dev/dri/renderD128")
                args.add("-vf")
                args.add("format=nv12,hwupload")
                if (customBitrate != null) {
                    args.add("-b:v")
                    args.add("${customBitrate}k")
                } else {
                    args.add("-qp")
                    args.add(quality.crf.toString())
                }
            }
            HardwareEncoder.QSV -> {
                // Intel QuickSync
                args.add("-preset")
                args.add(mapPresetToQsv(preset))
                if (customBitrate != null) {
                    args.add("-b:v")
                    args.add("${customBitrate}k")
                } else {
                    args.add("-global_quality")
                    args.add(quality.crf.toString())
                }
            }
            HardwareEncoder.AMF -> {
                // AMD AMF
                args.add("-quality")
                args.add(mapPresetToAmf(preset))
                if (customBitrate != null) {
                    args.add("-b:v")
                    args.add("${customBitrate}k")
                    args.add("-rc")
                    args.add("cbr")
                } else {
                    args.add("-qp_i")
                    args.add(quality.crf.toString())
                    args.add("-qp_p")
                    args.add(quality.crf.toString())
                    args.add("-rc")
                    args.add("cqp")
                }
            }
        }

        return args
    }

    private fun mapPresetToNvenc(preset: EncodingPreset): String {
        return when (preset) {
            EncodingPreset.ULTRAFAST, EncodingPreset.SUPERFAST -> "p1"
            EncodingPreset.VERYFAST, EncodingPreset.FASTER -> "p2"
            EncodingPreset.FAST -> "p3"
            EncodingPreset.MEDIUM -> "p4"
            EncodingPreset.SLOW -> "p5"
            EncodingPreset.SLOWER -> "p6"
            EncodingPreset.VERYSLOW -> "p7"
        }
    }

    private fun mapPresetToQsv(preset: EncodingPreset): String {
        return when (preset) {
            EncodingPreset.ULTRAFAST, EncodingPreset.SUPERFAST -> "veryfast"
            EncodingPreset.VERYFAST, EncodingPreset.FASTER -> "faster"
            EncodingPreset.FAST -> "fast"
            EncodingPreset.MEDIUM -> "medium"
            EncodingPreset.SLOW -> "slow"
            EncodingPreset.SLOWER, EncodingPreset.VERYSLOW -> "veryslow"
        }
    }

    private fun mapPresetToAmf(preset: EncodingPreset): String {
        return when (preset) {
            EncodingPreset.ULTRAFAST, EncodingPreset.SUPERFAST, EncodingPreset.VERYFAST -> "speed"
            EncodingPreset.FASTER, EncodingPreset.FAST, EncodingPreset.MEDIUM -> "balanced"
            EncodingPreset.SLOW, EncodingPreset.SLOWER, EncodingPreset.VERYSLOW -> "quality"
        }
    }
}

/**
 * System font discovery utility.
 */
object SystemFonts {

    /**
     * Common fonts available on most systems.
     */
    val commonFonts = listOf(
        "Arial",
        "Helvetica",
        "Times New Roman",
        "Courier New",
        "Verdana",
        "Georgia",
        "Trebuchet MS",
        "Comic Sans MS",
        "Impact",
        "Lucida Console"
    )

    /**
     * Gets platform-specific font directories.
     */
    fun getFontDirectories(): List<String> {
        return when (Platform.current()) {
            Platform.MACOS -> listOf(
                "/System/Library/Fonts",
                "/Library/Fonts",
                "${System.getProperty("user.home")}/Library/Fonts"
            )
            Platform.WINDOWS -> listOf(
                "${System.getenv("WINDIR") ?: "C:\\Windows"}\\Fonts"
            )
            Platform.LINUX -> listOf(
                "/usr/share/fonts",
                "/usr/local/share/fonts",
                "${System.getProperty("user.home")}/.fonts",
                "${System.getProperty("user.home")}/.local/share/fonts"
            )
        }
    }
}
