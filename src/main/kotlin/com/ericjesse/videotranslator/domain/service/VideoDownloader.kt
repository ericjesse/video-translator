package com.ericjesse.videotranslator.domain.service

import com.ericjesse.videotranslator.domain.model.*
import com.ericjesse.videotranslator.domain.pipeline.StageProgress
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import com.ericjesse.videotranslator.infrastructure.process.ProcessExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Service for downloading YouTube videos and extracting captions.
 * Wraps the yt-dlp binary.
 */
class VideoDownloader(
    private val processExecutor: ProcessExecutor,
    private val platformPaths: PlatformPaths
) {
    
    private val ytDlpPath: String
        get() = platformPaths.getBinaryPath("yt-dlp")
    
    private val downloadDir: File
        get() = File(platformPaths.cacheDir, "downloads").also { it.mkdirs() }
    
    /**
     * Downloads a YouTube video.
     * Emits progress updates as the download proceeds.
     */
    fun download(videoInfo: VideoInfo): Flow<StageProgress> = flow {
        emit(StageProgress(0f, "Initializing download..."))
        
        val outputPath = getDownloadedVideoPath(videoInfo)
        
        // yt-dlp command to download video with best quality
        val command = listOf(
            ytDlpPath,
            "--no-playlist",
            "--format", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
            "--merge-output-format", "mp4",
            "--output", outputPath,
            "--progress",
            "--newline",
            videoInfo.url
        )
        
        logger.debug { "Running: ${command.joinToString(" ")}" }
        
        processExecutor.execute(command) { line ->
            val progress = parseProgress(line)
            if (progress != null) {
                emit(progress)
            }
        }
        
        emit(StageProgress(1f, "Download complete"))
    }
    
    /**
     * Attempts to extract existing captions from the video.
     * Returns null if no captions are available.
     */
    suspend fun extractCaptions(videoInfo: VideoInfo, preferredLanguage: Language?): Subtitles? {
        val langCode = preferredLanguage?.code ?: "en"
        val subtitlePath = File(downloadDir, "${videoInfo.id}.$langCode.vtt")
        
        val command = listOf(
            ytDlpPath,
            "--skip-download",
            "--write-sub",
            "--write-auto-sub",
            "--sub-lang", langCode,
            "--sub-format", "vtt",
            "--output", File(downloadDir, videoInfo.id).absolutePath,
            videoInfo.url
        )
        
        try {
            processExecutor.execute(command)
            
            if (subtitlePath.exists()) {
                val entries = parseVttFile(subtitlePath)
                val language = preferredLanguage ?: Language.ENGLISH
                subtitlePath.delete()
                return Subtitles(entries, language)
            }
        } catch (e: Exception) {
            logger.debug { "No captions found: ${e.message}" }
        }
        
        return null
    }
    
    /**
     * Gets the path where a video would be downloaded.
     */
    fun getDownloadedVideoPath(videoInfo: VideoInfo): String {
        return File(downloadDir, "${videoInfo.id}.mp4").absolutePath
    }
    
    /**
     * Fetches video metadata without downloading.
     */
    suspend fun fetchVideoInfo(url: String): VideoInfo {
        val command = listOf(
            ytDlpPath,
            "--dump-json",
            "--no-download",
            url
        )
        
        val output = StringBuilder()
        processExecutor.execute(command) { line ->
            output.append(line)
        }
        
        // Parse JSON output from yt-dlp
        // This is a simplified implementation - full version would use kotlinx.serialization
        val json = output.toString()
        val id = extractJsonField(json, "id") ?: throw IllegalStateException("Could not parse video ID")
        val title = extractJsonField(json, "title") ?: "Unknown"
        val duration = extractJsonField(json, "duration")?.toLongOrNull() ?: 0L
        val thumbnail = extractJsonField(json, "thumbnail")
        
        return VideoInfo(
            url = url,
            id = id,
            title = title,
            duration = duration,
            thumbnailUrl = thumbnail
        )
    }
    
    private fun parseProgress(line: String): StageProgress? {
        // yt-dlp progress format: [download]  45.2% of 123.45MiB at 5.67MiB/s ETA 00:15
        val percentRegex = """(\d+\.?\d*)%""".toRegex()
        val match = percentRegex.find(line)
        
        return match?.let {
            val percent = it.groupValues[1].toFloatOrNull()?.div(100f) ?: return null
            StageProgress(percent, "Downloading: ${(percent * 100).toInt()}%")
        }
    }
    
    private fun parseVttFile(file: File): List<SubtitleEntry> {
        val entries = mutableListOf<SubtitleEntry>()
        val lines = file.readLines()
        
        var index = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            
            // Look for timestamp lines like "00:00:00.000 --> 00:00:05.000"
            if (line.contains("-->")) {
                val parts = line.split("-->").map { it.trim() }
                if (parts.size == 2) {
                    val startTime = parseVttTimestamp(parts[0])
                    val endTime = parseVttTimestamp(parts[1])
                    
                    // Collect text lines until empty line
                    val textLines = mutableListOf<String>()
                    i++
                    while (i < lines.size && lines[i].isNotBlank()) {
                        textLines.add(lines[i].trim())
                        i++
                    }
                    
                    if (textLines.isNotEmpty()) {
                        entries.add(SubtitleEntry(
                            index = index++,
                            startTime = startTime,
                            endTime = endTime,
                            text = textLines.joinToString(" ")
                        ))
                    }
                }
            }
            i++
        }
        
        return entries
    }
    
    private fun parseVttTimestamp(timestamp: String): Long {
        // Format: HH:MM:SS.mmm or MM:SS.mmm
        val parts = timestamp.split(":", ".")
        return when (parts.size) {
            4 -> { // HH:MM:SS.mmm
                val hours = parts[0].toLongOrNull() ?: 0
                val minutes = parts[1].toLongOrNull() ?: 0
                val seconds = parts[2].toLongOrNull() ?: 0
                val millis = parts[3].toLongOrNull() ?: 0
                (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
            }
            3 -> { // MM:SS.mmm
                val minutes = parts[0].toLongOrNull() ?: 0
                val seconds = parts[1].toLongOrNull() ?: 0
                val millis = parts[2].toLongOrNull() ?: 0
                (minutes * 60 + seconds) * 1000 + millis
            }
            else -> 0
        }
    }
    
    private fun extractJsonField(json: String, field: String): String? {
        val pattern = """"$field"\s*:\s*"([^"]+)"""".toRegex()
        val numPattern = """"$field"\s*:\s*(\d+\.?\d*)""".toRegex()
        
        pattern.find(json)?.let { return it.groupValues[1] }
        numPattern.find(json)?.let { return it.groupValues[1] }
        return null
    }
}
