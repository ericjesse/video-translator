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
 * Service for transcribing audio to text using Whisper.
 * Wraps the whisper.cpp CLI binary.
 */
class TranscriberService(
    private val processExecutor: ProcessExecutor,
    private val platformPaths: PlatformPaths,
    private val configManager: ConfigManager
) {
    
    private var lastResult: Subtitles? = null
    
    private val whisperPath: String
        get() = platformPaths.getBinaryPath("whisper")
    
    private val ffmpegPath: String
        get() = platformPaths.getBinaryPath("ffmpeg")
    
    /**
     * Transcribes audio from a video file.
     * Uses Whisper for speech-to-text.
     */
    fun transcribe(videoPath: String, sourceLanguage: Language?): Flow<StageProgress> = flow {
        emit(StageProgress(0f, "Extracting audio..."))
        
        // Step 1: Extract audio from video using FFmpeg
        val audioPath = extractAudio(videoPath)
        emit(StageProgress(0.1f, "Audio extracted, starting transcription..."))
        
        // Step 2: Run Whisper transcription
        val outputPath = File(audioPath).parentFile.resolve("transcription")
        val modelPath = getModelPath()
        
        val command = buildList {
            add(whisperPath)
            add("--model"); add(modelPath)
            add("--output-srt")
            add("--output-file"); add(outputPath.absolutePath)
            
            // If source language specified, use it
            sourceLanguage?.let {
                add("--language"); add(it.code)
            }
            
            add("--print-progress")
            add(audioPath)
        }
        
        logger.debug { "Running Whisper: ${command.joinToString(" ")}" }
        
        var detectedLanguage = sourceLanguage ?: Language.ENGLISH
        
        processExecutor.execute(command) { line ->
            // Parse Whisper progress output
            val progress = parseWhisperProgress(line)
            if (progress != null) {
                emit(progress)
            }
            
            // Detect language from output
            if (line.contains("detected language:")) {
                val langCode = extractLanguageCode(line)
                Language.fromCode(langCode)?.let { detectedLanguage = it }
                logger.info { "Detected language: $detectedLanguage" }
            }
        }
        
        // Step 3: Parse the generated SRT file
        val srtFile = File("${outputPath.absolutePath}.srt")
        if (!srtFile.exists()) {
            throw IllegalStateException("Whisper did not produce output file")
        }
        
        val entries = parseSrtFile(srtFile)
        lastResult = Subtitles(entries, detectedLanguage)
        
        // Cleanup
        File(audioPath).delete()
        srtFile.delete()
        
        emit(StageProgress(1f, "Transcription complete"))
    }
    
    /**
     * Returns the result of the last transcription.
     */
    fun getTranscriptionResult(): Subtitles {
        return lastResult ?: throw IllegalStateException("No transcription result available")
    }
    
    private suspend fun extractAudio(videoPath: String): String {
        val outputPath = File(videoPath).parentFile.resolve("audio.wav").absolutePath
        
        val command = listOf(
            ffmpegPath,
            "-i", videoPath,
            "-vn",                    // No video
            "-acodec", "pcm_s16le",   // PCM 16-bit
            "-ar", "16000",           // 16kHz sample rate (required by Whisper)
            "-ac", "1",               // Mono
            "-y",                     // Overwrite
            outputPath
        )
        
        processExecutor.execute(command)
        return outputPath
    }
    
    private fun getModelPath(): String {
        val settings = configManager.getSettings()
        val modelName = settings.transcription.whisperModel
        val modelFile = "ggml-$modelName.bin"
        return File(platformPaths.modelsDir, "whisper/$modelFile").absolutePath
    }
    
    private fun parseWhisperProgress(line: String): StageProgress? {
        // Whisper progress format varies, this handles common patterns
        val percentRegex = """(\d+)%""".toRegex()
        val match = percentRegex.find(line)
        
        return match?.let {
            val percent = it.groupValues[1].toFloatOrNull()?.div(100f) ?: return null
            StageProgress(0.1f + percent * 0.9f, "Transcribing: ${(percent * 100).toInt()}%")
        }
    }
    
    private fun extractLanguageCode(line: String): String {
        val pattern = """language:\s*(\w+)""".toRegex(RegexOption.IGNORE_CASE)
        return pattern.find(line)?.groupValues?.get(1)?.lowercase() ?: "en"
    }
    
    private fun parseSrtFile(file: File): List<SubtitleEntry> {
        val entries = mutableListOf<SubtitleEntry>()
        val content = file.readText()
        
        // SRT format:
        // 1
        // 00:00:00,000 --> 00:00:05,000
        // Subtitle text
        //
        // 2
        // ...
        
        val blocks = content.split("""\n\n+""".toRegex())
        
        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.size >= 3) {
                val index = lines[0].toIntOrNull() ?: continue
                val timeLine = lines[1]
                val text = lines.drop(2).joinToString(" ")
                
                val times = timeLine.split("-->").map { it.trim() }
                if (times.size == 2) {
                    entries.add(SubtitleEntry(
                        index = index,
                        startTime = parseSrtTimestamp(times[0]),
                        endTime = parseSrtTimestamp(times[1]),
                        text = text
                    ))
                }
            }
        }
        
        return entries
    }
    
    private fun parseSrtTimestamp(timestamp: String): Long {
        // Format: HH:MM:SS,mmm
        val parts = timestamp.replace(",", ":").split(":")
        if (parts.size != 4) return 0
        
        val hours = parts[0].toLongOrNull() ?: 0
        val minutes = parts[1].toLongOrNull() ?: 0
        val seconds = parts[2].toLongOrNull() ?: 0
        val millis = parts[3].toLongOrNull() ?: 0
        
        return (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
    }
}
