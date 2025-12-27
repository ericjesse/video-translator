package com.ericjesse.videotranslator.domain.service

import com.ericjesse.videotranslator.domain.model.*
import com.ericjesse.videotranslator.domain.pipeline.StageProgress
import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import com.ericjesse.videotranslator.infrastructure.process.ProcessExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Service for rendering subtitles into video.
 * Supports both soft subtitles (embedded) and burned-in subtitles.
 *
 * Features:
 * - ASS/SSA subtitle generation for better styling control
 * - Full font configuration (family, weight, outline, shadow)
 * - Subtitle position options (9 positions)
 * - Multi-line subtitle formatting
 * - Video quality options (CRF-based)
 * - Hardware encoding support (NVENC, VideoToolbox, VAAPI, QSV, AMF)
 * - Real-time FFmpeg progress tracking
 */
class SubtitleRenderer(
    private val processExecutor: ProcessExecutor,
    private val platformPaths: PlatformPaths,
    private val configManager: ConfigManager
) {

    private var lastResult: TranslationResult? = null

    private val ffmpegPath: String
        get() = platformPaths.getBinaryPath("ffmpeg")

    private val ffprobePath: String
        get() = platformPaths.getBinaryPath("ffprobe")

    /**
     * Renders subtitles into the video with full styling and encoding options.
     */
    fun render(
        videoPath: String,
        subtitles: Subtitles,
        outputOptions: OutputOptions,
        videoInfo: VideoInfo
    ): Flow<StageProgress> = channelFlow {
        val renderOptions = outputOptions.renderOptions ?: RenderOptions()

        send(StageProgress(0f, RenderProgress(
            percentage = 0f,
            currentTime = 0,
            totalTime = videoInfo.duration,
            stage = RenderStage.PREPARING
        ).message))

        // Generate output filename
        val sanitizedTitle = sanitizeFilename(videoInfo.title)
        val langSuffix = subtitles.language.code.uppercase()
        val format = if (outputOptions.subtitleType == SubtitleType.SOFT) {
            OutputFormat.MKV // MKV supports soft subtitles
        } else {
            renderOptions.outputFormat
        }
        val outputFilename = "${sanitizedTitle}_$langSuffix.${format.extension}"
        val outputPath = File(outputOptions.outputDirectory, outputFilename).absolutePath

        // Ensure output directory exists
        File(outputOptions.outputDirectory).mkdirs()

        send(StageProgress(0.05f, RenderProgress(
            percentage = 0.05f,
            currentTime = 0,
            totalTime = videoInfo.duration,
            stage = RenderStage.GENERATING_SUBTITLES
        ).message))

        // Generate subtitle file (ASS or SRT)
        val subtitleFile: File
        val useAss = renderOptions.useAssSubtitles && outputOptions.subtitleType == SubtitleType.BURNED_IN

        if (useAss) {
            subtitleFile = File(outputOptions.outputDirectory, "${sanitizedTitle}_$langSuffix.ass")
            writeAssFile(subtitles, subtitleFile, renderOptions.subtitleStyle, videoInfo)
        } else {
            subtitleFile = File(outputOptions.outputDirectory, "${sanitizedTitle}_$langSuffix.srt")
            writeSrtFile(subtitles, subtitleFile)
        }

        send(StageProgress(0.1f, RenderProgress(
            percentage = 0.1f,
            currentTime = 0,
            totalTime = videoInfo.duration,
            stage = RenderStage.ENCODING
        ).message))

        // Render video
        when (outputOptions.subtitleType) {
            SubtitleType.SOFT -> {
                renderSoftSubtitles(videoPath, subtitleFile.absolutePath, outputPath, videoInfo.duration) { progress ->
                    send(StageProgress(0.1f + progress.percentage * 0.85f, progress.message))
                }
            }
            SubtitleType.BURNED_IN -> {
                renderBurnedInSubtitles(
                    videoPath,
                    subtitleFile.absolutePath,
                    outputPath,
                    renderOptions,
                    useAss,
                    videoInfo.duration
                ) { progress ->
                    send(StageProgress(0.1f + progress.percentage * 0.85f, progress.message))
                }
            }
        }

        send(StageProgress(0.95f, RenderProgress(
            percentage = 0.95f,
            currentTime = videoInfo.duration,
            totalTime = videoInfo.duration,
            stage = RenderStage.FINALIZING
        ).message))

        // Handle subtitle export option
        val exportedSubtitlePath = if (outputOptions.exportSrt) {
            // If we generated ASS but user wants SRT export, generate SRT too
            if (useAss) {
                val srtFile = File(outputOptions.outputDirectory, "${sanitizedTitle}_$langSuffix.srt")
                writeSrtFile(subtitles, srtFile)
                srtFile.absolutePath
            } else {
                subtitleFile.absolutePath
            }
        } else {
            null
        }

        // Cleanup temp subtitle file if not exporting
        if (!outputOptions.exportSrt || (useAss && outputOptions.exportSrt)) {
            subtitleFile.delete()
        }

        // Cleanup source video
        File(videoPath).delete()

        lastResult = TranslationResult(
            videoFile = outputPath,
            subtitleFile = exportedSubtitlePath,
            duration = 0 // Will be set by orchestrator
        )

        send(StageProgress(1f, RenderProgress(
            percentage = 1f,
            currentTime = videoInfo.duration,
            totalTime = videoInfo.duration,
            stage = RenderStage.COMPLETE
        ).message))
    }

    /**
     * Returns the result of the last render.
     */
    fun getRenderResult(): TranslationResult {
        return lastResult ?: throw IllegalStateException("No render result available")
    }

    /**
     * Checks if a hardware encoder is available on this system.
     */
    suspend fun isEncoderAvailable(encoder: HardwareEncoder): Boolean {
        if (encoder == HardwareEncoder.NONE) return true

        // Check platform compatibility
        if (Platform.current() !in encoder.platform) {
            return false
        }

        // Test encoder with FFmpeg
        val testCommand = HardwareEncoderDetector.getTestCommand(ffmpegPath, encoder)
        return try {
            var success = true
            processExecutor.execute(testCommand) { line ->
                if (line.contains("Cannot open") || line.contains("No device") ||
                    line.contains("Error") || line.contains("not found")) {
                    success = false
                }
            }
            success
        } catch (e: Exception) {
            logger.debug { "Encoder ${encoder.displayName} not available: ${e.message}" }
            false
        }
    }

    /**
     * Gets list of available hardware encoders for this system.
     */
    suspend fun getAvailableEncoders(): List<HardwareEncoder> {
        val available = mutableListOf(HardwareEncoder.NONE) // Software always available

        for (encoder in HardwareEncoder.availableForPlatform(Platform.current())) {
            if (encoder != HardwareEncoder.NONE && isEncoderAvailable(encoder)) {
                available.add(encoder)
            }
        }

        return available
    }

    /**
     * Renders soft subtitles (embedded as a separate track in MKV).
     */
    private suspend fun renderSoftSubtitles(
        videoPath: String,
        subtitlePath: String,
        outputPath: String,
        totalDuration: Long,
        onProgress: suspend (RenderProgress) -> Unit
    ) {
        val subtitleCodec = if (subtitlePath.endsWith(".ass")) "ass" else "srt"

        val command = listOf(
            ffmpegPath,
            "-i", videoPath,
            "-i", subtitlePath,
            "-c", "copy",           // Copy video/audio streams
            "-c:s", subtitleCodec,  // Subtitle codec
            "-map", "0",            // Map all streams from input 0
            "-map", "1",            // Map subtitle from input 1
            "-y",                   // Overwrite output
            "-progress", "pipe:1",  // Progress to stdout
            outputPath
        )

        logger.debug { "Running FFmpeg (soft subs): ${command.joinToString(" ")}" }

        executeWithProgress(command, totalDuration, onProgress)
    }

    /**
     * Renders burned-in subtitles with full styling and encoding options.
     */
    private suspend fun renderBurnedInSubtitles(
        videoPath: String,
        subtitlePath: String,
        outputPath: String,
        options: RenderOptions,
        isAssFormat: Boolean,
        totalDuration: Long,
        onProgress: suspend (RenderProgress) -> Unit
    ) {
        val command = buildBurnedInCommand(
            videoPath,
            subtitlePath,
            outputPath,
            options,
            isAssFormat
        )

        logger.debug { "Running FFmpeg (burned-in): ${command.joinToString(" ")}" }

        executeWithProgress(command, totalDuration, onProgress)
    }

    /**
     * Builds the FFmpeg command for burned-in subtitles.
     */
    private fun buildBurnedInCommand(
        videoPath: String,
        subtitlePath: String,
        outputPath: String,
        options: RenderOptions,
        isAssFormat: Boolean
    ): List<String> {
        val command = mutableListOf<String>()

        command.add(ffmpegPath)
        command.add("-i")
        command.add(videoPath)

        // Add hardware decoding for VAAPI if using VAAPI encoder
        if (options.encoding.encoder == HardwareEncoder.VAAPI) {
            command.addAll(listOf("-vaapi_device", "/dev/dri/renderD128"))
        }

        // Build video filter
        val subtitleFilter = buildSubtitleFilter(subtitlePath, options.subtitleStyle, isAssFormat)
        val videoFilter = if (options.encoding.encoder == HardwareEncoder.VAAPI) {
            // VAAPI needs format conversion and upload
            "$subtitleFilter,format=nv12,hwupload"
        } else {
            subtitleFilter
        }

        command.add("-vf")
        command.add(videoFilter)

        // Add encoder-specific arguments
        val encoderArgs = HardwareEncoderDetector.getEncoderArgs(
            options.encoding.encoder,
            options.encoding.quality,
            options.encoding.preset,
            options.encoding.customBitrate
        )
        command.addAll(encoderArgs)

        // Audio codec
        command.add("-c:a")
        command.add(options.encoding.audioCodec.ffmpegValue)
        if (options.encoding.audioCodec != AudioCodec.COPY && options.encoding.audioBitrate != null) {
            command.add("-b:a")
            command.add("${options.encoding.audioBitrate}k")
        }

        // Output options
        command.add("-y")
        command.add("-progress")
        command.add("pipe:1")
        command.add(outputPath)

        return command
    }

    /**
     * Builds the FFmpeg subtitle filter with optional styling.
     */
    private fun buildSubtitleFilter(
        subtitlePath: String,
        style: SubtitleStyle,
        isAssFormat: Boolean
    ): String {
        // Escape path for FFmpeg filter
        val escapedPath = subtitlePath
            .replace("\\", "\\\\\\\\")
            .replace(":", "\\\\:")
            .replace("'", "\\\\'")
            .replace("[", "\\\\[")
            .replace("]", "\\\\]")

        return if (isAssFormat) {
            // ASS format - styling is in the file, just reference it
            "ass='$escapedPath'"
        } else {
            // SRT format - apply styling via force_style
            val forceStyle = buildForceStyle(style)
            "subtitles='$escapedPath':force_style='$forceStyle'"
        }
    }

    /**
     * Builds FFmpeg force_style string for SRT subtitles.
     */
    private fun buildForceStyle(style: SubtitleStyle): String {
        val parts = mutableListOf<String>()

        parts.add("Fontname=${style.fontFamily}")
        parts.add("Fontsize=${style.fontSize}")

        // Convert colors to ASS format
        parts.add("PrimaryColour=${style.colorToAss(style.primaryColor)}")
        parts.add("OutlineColour=${style.colorToAss(style.outlineColor)}")
        parts.add("BackColour=${style.colorToAss(style.shadowColor)}")

        // Font weight
        val bold = if (style.fontWeight == FontWeight.BOLD || style.fontWeight == FontWeight.EXTRA_BOLD) -1 else 0
        parts.add("Bold=$bold")

        // Style options
        if (style.italic) parts.add("Italic=-1")
        if (style.underline) parts.add("Underline=-1")
        if (style.strikeout) parts.add("StrikeOut=-1")

        // Border style
        parts.add("BorderStyle=${style.borderStyle.assBorderStyle}")
        parts.add("Outline=${style.outlineWidth}")
        parts.add("Shadow=${style.shadowDepth}")

        // Position
        parts.add("Alignment=${style.position.assAlignment}")
        parts.add("MarginL=${style.marginLeft}")
        parts.add("MarginR=${style.marginRight}")
        parts.add("MarginV=${style.effectiveMarginVertical}")

        return parts.joinToString(",")
    }

    /**
     * Executes FFmpeg command with progress tracking.
     */
    private suspend fun executeWithProgress(
        command: List<String>,
        totalDuration: Long,
        onProgress: suspend (RenderProgress) -> Unit
    ) {
        var currentProgress = RenderProgress(
            percentage = 0f,
            currentTime = 0,
            totalTime = totalDuration,
            stage = RenderStage.ENCODING
        )

        processExecutor.execute(command) { line ->
            val updatedProgress = FfmpegProgressParser.parseLine(line, totalDuration, currentProgress)
            if (updatedProgress != null) {
                currentProgress = updatedProgress
                onProgress(currentProgress)
            }
        }
    }

    /**
     * Writes subtitles to an ASS file with styling.
     */
    private fun writeAssFile(
        subtitles: Subtitles,
        file: File,
        style: SubtitleStyle,
        videoInfo: VideoInfo
    ) {
        val width = videoInfo.width ?: 1920
        val height = videoInfo.height ?: 1080
        val content = AssGenerator.generate(subtitles, style, width, height)
        file.writeText(content)
        logger.debug { "Generated ASS file: ${file.absolutePath}" }
    }

    /**
     * Writes subtitles to an SRT file.
     */
    private fun writeSrtFile(subtitles: Subtitles, file: File) {
        file.writeText(buildString {
            for (entry in subtitles.entries) {
                appendLine(entry.index)
                appendLine("${formatSrtTimestamp(entry.startTime)} --> ${formatSrtTimestamp(entry.endTime)}")
                appendLine(entry.text)
                appendLine()
            }
        })
        logger.debug { "Generated SRT file: ${file.absolutePath}" }
    }

    /**
     * Formats a timestamp for SRT format (HH:MM:SS,mmm).
     */
    private fun formatSrtTimestamp(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    /**
     * Sanitizes a filename by removing or replacing invalid characters.
     */
    private fun sanitizeFilename(name: String): String {
        return name
            .replace(Regex("""[<>:"/\\|?*]"""), "_")
            .replace(Regex("""\s+"""), "_")
            .take(100) // Limit length
    }

    /**
     * Gets video bitrate for original quality encoding.
     */
    suspend fun getVideoBitrate(videoPath: String): Int? {
        val command = listOf(
            ffprobePath,
            "-v", "error",
            "-select_streams", "v:0",
            "-show_entries", "stream=bit_rate",
            "-of", "default=noprint_wrappers=1:nokey=1",
            videoPath
        )

        var bitrate: Int? = null

        try {
            processExecutor.execute(command) { line ->
                val value = line.trim().toLongOrNull()
                if (value != null) {
                    bitrate = (value / 1000).toInt() // Convert to kbps
                }
            }
        } catch (e: Exception) {
            logger.warn { "Could not determine video bitrate: ${e.message}" }
        }

        return bitrate
    }
}
