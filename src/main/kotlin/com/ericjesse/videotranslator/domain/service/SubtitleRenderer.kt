package com.ericjesse.videotranslator.domain.service

import com.ericjesse.videotranslator.domain.model.*
import com.ericjesse.videotranslator.domain.pipeline.StageProgress
import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import com.ericjesse.videotranslator.infrastructure.process.ProcessExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Service for rendering subtitles into video.
 * Supports both soft subtitles (embedded) and burned-in subtitles.
 */
class SubtitleRenderer(
    private val processExecutor: ProcessExecutor,
    private val platformPaths: PlatformPaths,
    private val configManager: ConfigManager
) {
    
    private var lastResult: TranslationResult? = null
    
    private val ffmpegPath: String
        get() = platformPaths.getBinaryPath("ffmpeg")
    
    /**
     * Renders subtitles into the video.
     */
    fun render(
        videoPath: String,
        subtitles: Subtitles,
        outputOptions: OutputOptions,
        videoInfo: VideoInfo
    ): Flow<StageProgress> = flow {
        emit(StageProgress(0f, "Preparing output..."))
        
        // Generate output filename
        val sanitizedTitle = sanitizeFilename(videoInfo.title)
        val langSuffix = subtitles.language.code.uppercase()
        val extension = if (outputOptions.subtitleType == SubtitleType.SOFT) "mkv" else "mp4"
        val outputFilename = "${sanitizedTitle}_$langSuffix.$extension"
        val outputPath = File(outputOptions.outputDirectory, outputFilename).absolutePath
        
        // Ensure output directory exists
        File(outputOptions.outputDirectory).mkdirs()
        
        // Generate SRT file
        emit(StageProgress(0.1f, "Generating subtitle file..."))
        val srtPath = File(outputOptions.outputDirectory, "${sanitizedTitle}_$langSuffix.srt")
        writeSrtFile(subtitles, srtPath)
        
        // Render video
        emit(StageProgress(0.2f, "Rendering video..."))
        
        when (outputOptions.subtitleType) {
            SubtitleType.SOFT -> {
                renderSoftSubtitles(videoPath, srtPath.absolutePath, outputPath) { progress ->
                    emit(StageProgress(0.2f + progress * 0.8f, "Rendering: ${(progress * 100).toInt()}%"))
                }
            }
            SubtitleType.BURNED_IN -> {
                val style = outputOptions.burnedInStyle ?: BurnedInSubtitleStyle()
                renderBurnedInSubtitles(videoPath, srtPath.absolutePath, outputPath, style) { progress ->
                    emit(StageProgress(0.2f + progress * 0.8f, "Rendering: ${(progress * 100).toInt()}%"))
                }
            }
        }
        
        // Handle SRT export option
        val exportedSrtPath = if (outputOptions.exportSrt) {
            srtPath.absolutePath
        } else {
            srtPath.delete()
            null
        }
        
        // Cleanup source video
        File(videoPath).delete()
        
        lastResult = TranslationResult(
            videoFile = outputPath,
            subtitleFile = exportedSrtPath,
            duration = 0 // Will be set by orchestrator
        )
        
        emit(StageProgress(1f, "Render complete"))
    }
    
    /**
     * Returns the result of the last render.
     */
    fun getRenderResult(): TranslationResult {
        return lastResult ?: throw IllegalStateException("No render result available")
    }
    
    /**
     * Renders soft subtitles (embedded as a separate track in MKV).
     */
    private suspend fun renderSoftSubtitles(
        videoPath: String,
        srtPath: String,
        outputPath: String,
        onProgress: suspend (Float) -> Unit
    ) {
        val command = listOf(
            ffmpegPath,
            "-i", videoPath,
            "-i", srtPath,
            "-c", "copy",           // Copy video/audio streams
            "-c:s", "srt",          // Subtitle codec
            "-map", "0",            // Map all streams from input 0
            "-map", "1",            // Map subtitle from input 1
            "-y",                   // Overwrite output
            "-progress", "pipe:1",  // Progress to stdout
            outputPath
        )
        
        logger.debug { "Running FFmpeg: ${command.joinToString(" ")}" }
        
        var duration: Long? = null
        
        processExecutor.execute(command) { line ->
            // Parse FFmpeg progress output
            if (line.startsWith("Duration:")) {
                duration = parseFfmpegDuration(line)
            }
            if (line.startsWith("out_time_ms=")) {
                val currentMs = line.substringAfter("=").toLongOrNull()
                if (currentMs != null && duration != null && duration!! > 0) {
                    val progress = (currentMs.toFloat() / 1000 / duration!!).coerceIn(0f, 1f)
                    onProgress(progress)
                }
            }
        }
    }
    
    /**
     * Renders burned-in subtitles (permanently visible in video).
     */
    private suspend fun renderBurnedInSubtitles(
        videoPath: String,
        srtPath: String,
        outputPath: String,
        style: BurnedInSubtitleStyle,
        onProgress: suspend (Float) -> Unit
    ) {
        // Build FFmpeg subtitle filter with styling
        val subtitleFilter = buildSubtitleFilter(srtPath, style)
        
        val command = listOf(
            ffmpegPath,
            "-i", videoPath,
            "-vf", subtitleFilter,
            "-c:a", "copy",         // Copy audio
            "-c:v", "libx264",      // Re-encode video
            "-preset", "medium",
            "-crf", "23",
            "-y",
            "-progress", "pipe:1",
            outputPath
        )
        
        logger.debug { "Running FFmpeg: ${command.joinToString(" ")}" }
        
        var duration: Long? = null
        
        processExecutor.execute(command) { line ->
            if (line.startsWith("Duration:")) {
                duration = parseFfmpegDuration(line)
            }
            if (line.startsWith("out_time_ms=")) {
                val currentMs = line.substringAfter("=").toLongOrNull()
                if (currentMs != null && duration != null && duration!! > 0) {
                    val progress = (currentMs.toFloat() / 1000 / duration!!).coerceIn(0f, 1f)
                    onProgress(progress)
                }
            }
        }
    }
    
    private fun buildSubtitleFilter(srtPath: String, style: BurnedInSubtitleStyle): String {
        // Escape path for FFmpeg filter
        val escapedPath = srtPath.replace(":", "\\:").replace("'", "\\'")
        
        val fontColor = style.fontColor.removePrefix("#")
        
        val borderStyle = if (style.backgroundColor == BackgroundColor.NONE) {
            "BorderStyle=1" // Outline only
        } else {
            val bgColor = style.backgroundColor.hex?.removePrefix("#") ?: "000000"
            val alpha = ((1 - style.backgroundOpacity) * 255).toInt().toString(16).padStart(2, '0')
            "BorderStyle=4,BackColour=&H${alpha}${bgColor}&" // Box background
        }
        
        return "subtitles='$escapedPath':force_style='FontSize=${style.fontSize}," +
               "PrimaryColour=&H${fontColor}&,$borderStyle'"
    }
    
    private fun writeSrtFile(subtitles: Subtitles, file: File) {
        file.writeText(buildString {
            for (entry in subtitles.entries) {
                appendLine(entry.index)
                appendLine("${formatSrtTimestamp(entry.startTime)} --> ${formatSrtTimestamp(entry.endTime)}")
                appendLine(entry.text)
                appendLine()
            }
        })
    }
    
    private fun formatSrtTimestamp(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
    
    private fun parseFfmpegDuration(line: String): Long? {
        // Format: Duration: HH:MM:SS.mm
        val pattern = """Duration:\s*(\d+):(\d+):(\d+)\.(\d+)""".toRegex()
        val match = pattern.find(line) ?: return null
        
        val (h, m, s, cs) = match.destructured
        return (h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + cs.toLong() * 10
    }
    
    private fun sanitizeFilename(name: String): String {
        return name
            .replace(Regex("""[<>:"/\\|?*]"""), "_")
            .replace(Regex("""\s+"""), "_")
            .take(100) // Limit length
    }
}
