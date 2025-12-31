package com.ericjesse.videotranslator.domain.service

import com.ericjesse.videotranslator.domain.model.*
import com.ericjesse.videotranslator.domain.pipeline.StageProgress
import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import com.ericjesse.videotranslator.infrastructure.process.CancellationToken
import com.ericjesse.videotranslator.infrastructure.process.ProcessConfig
import com.ericjesse.videotranslator.infrastructure.process.ProcessException
import com.ericjesse.videotranslator.infrastructure.process.ProcessExecutor
import com.ericjesse.videotranslator.infrastructure.resources.TempFileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import kotlin.math.min

private val logger = KotlinLogging.logger {}

/**
 * Service for transcribing audio to text using Whisper.
 * Wraps the whisper.cpp CLI binary with full feature support.
 *
 * Features:
 * - Accurate progress tracking based on audio duration
 * - Support for all Whisper model sizes
 * - Language detection result parsing
 * - Word-level timestamp support
 * - Long audio file segmentation
 * - GPU acceleration support
 * - Proper cleanup of temporary files
 *
 * @property processExecutor Executor for running Whisper and FFmpeg commands.
 * @property platformPaths Platform-specific paths for binaries and models.
 * @property configManager Configuration manager for settings.
 */
class TranscriberService(
    private val processExecutor: ProcessExecutor,
    private val platformPaths: PlatformPaths,
    private val configManager: ConfigManager,
    private val tempFileManager: TempFileManager? = null,
    private val subtitleDeduplicator: SubtitleDeduplicator = SubtitleDeduplicator()
) {

    private var lastResult: WhisperResult? = null
    private var currentOperationId: String? = null

    private val whisperPath: String
        get() = configManager.getBinaryPath("whisper")

    private val ffmpegPath: String
        get() = configManager.getBinaryPath("ffmpeg")

    private val ffprobePath: String
        get() = configManager.getBinaryPath("ffprobe")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    companion object {
        /** Default maximum segment duration for long audio files (30 minutes). */
        const val DEFAULT_MAX_SEGMENT_DURATION_MS = 30 * 60 * 1000L

        /** Required sample rate for Whisper (16kHz). */
        const val WHISPER_SAMPLE_RATE = 16000

        /** Progress weight for audio extraction phase. */
        private const val EXTRACTION_PROGRESS_WEIGHT = 0.05f

        /** Progress weight for transcription phase. */
        private const val TRANSCRIPTION_PROGRESS_WEIGHT = 0.90f

        /** Progress weight for cleanup phase. */
        private const val CLEANUP_PROGRESS_WEIGHT = 0.05f
    }

    // ==================== Public API ====================

    /**
     * Transcribes audio from a video or audio file.
     * Uses Whisper for speech-to-text.
     *
     * @param inputPath Path to the video or audio file.
     * @param options Transcription options.
     * @param cancellationToken Optional token for cancellation.
     * @return Flow of progress updates.
     */
    fun transcribe(
        inputPath: String,
        options: WhisperOptions = WhisperOptions(),
        cancellationToken: CancellationToken? = null
    ): Flow<StageProgress> = channelFlow {
        val startTime = System.currentTimeMillis()

        try {
            send(StageProgress(0f, "Preparing audio..."))

            // Step 1: Extract/convert audio to WAV format
            val audioInfo = prepareAudio(inputPath, cancellationToken)
            send(StageProgress(EXTRACTION_PROGRESS_WEIGHT, "Audio prepared, analyzing..."))
            logger.info { "Audio prepared: ${audioInfo.duration}ms duration" }

            // Step 2: Check if segmentation is needed for long files
            val segments = if (audioInfo.duration > DEFAULT_MAX_SEGMENT_DURATION_MS) {
                send(StageProgress(EXTRACTION_PROGRESS_WEIGHT, "Long audio detected, segmenting..."))
                segmentAudio(audioInfo, SegmentationConfig(), cancellationToken)
            } else {
                listOf(
                    AudioSegment(
                        index = 0,
                        path = audioInfo.path,
                        startTime = 0,
                        endTime = audioInfo.duration,
                        duration = audioInfo.duration
                    )
                )
            }

            // Step 3: Transcribe each segment
            val allSegments = mutableListOf<WhisperSegment>()
            var detectedLanguage = options.language ?: "en"
            val totalDuration = audioInfo.duration

            for ((index, segment) in segments.withIndex()) {
                val segmentStartProgress = EXTRACTION_PROGRESS_WEIGHT +
                        (index.toFloat() / segments.size) * TRANSCRIPTION_PROGRESS_WEIGHT

                send(
                    StageProgress(
                        segmentStartProgress,
                        if (segments.size > 1) "Transcribing segment ${index + 1}/${segments.size}..."
                        else "Starting transcription..."
                    )
                )

                val result = transcribeSegment(
                    segment = segment,
                    options = options,
                    totalDuration = totalDuration,
                    baseProgress = segmentStartProgress,
                    progressWeight = TRANSCRIPTION_PROGRESS_WEIGHT / segments.size,
                    cancellationToken = cancellationToken
                ) { progress ->
                    send(progress)
                }

                // Adjust timestamps for segmented audio
                val adjustedSegments = if (segment.startTime > 0) {
                    result.segments.map { seg ->
                        seg.copy(
                            startTime = seg.startTime + segment.startTime,
                            endTime = seg.endTime + segment.startTime,
                            words = seg.words?.map { word ->
                                word.copy(
                                    startTime = word.startTime + segment.startTime,
                                    endTime = word.endTime + segment.startTime
                                )
                            }
                        )
                    }
                } else {
                    result.segments
                }

                allSegments.addAll(adjustedSegments)

                // Use detected language from first segment
                if (index == 0) {
                    detectedLanguage = result.detectedLanguage
                }
            }

            // Step 4: Create final result
            val processingTime = System.currentTimeMillis() - startTime
            lastResult = WhisperResult(
                segments = mergeOverlappingSegments(allSegments),
                detectedLanguage = detectedLanguage,
                duration = totalDuration,
                processingTime = processingTime
            )

            // Step 5: Finalize (files kept in cache until app shutdown)
            send(StageProgress(EXTRACTION_PROGRESS_WEIGHT + TRANSCRIPTION_PROGRESS_WEIGHT, "Finalizing..."))

            send(StageProgress(1f, "Transcription complete"))
            logger.info { "Transcription completed in ${processingTime}ms" }

        } catch (e: Exception) {
            logger.error(e) { "Transcription failed: ${e.message}" }
            logger.error { "Transcription error details - Input: $inputPath, Options: $options" }
            if (e is WhisperException) {
                logger.error { "Whisper error type: ${e.errorType}, Technical: ${e.technicalMessage}" }
            }
            // Files kept in cache for debugging, will be cleaned up on app shutdown
            throw e
        }
    }

    /**
     * Transcribes audio with simplified interface.
     * Uses settings from ConfigManager.
     *
     * @param videoPath Path to the video file.
     * @param sourceLanguage Source language, or null for auto-detection.
     * @return Flow of progress updates.
     */
    fun transcribe(videoPath: String, sourceLanguage: Language?): Flow<StageProgress> {
        val settings = configManager.getSettings()
        val options = WhisperOptions(
            model = settings.transcription.whisperModel,
            language = sourceLanguage?.code,
            wordTimestamps = true
        )
        return transcribe(videoPath, options)
    }

    /**
     * Returns the result of the last transcription.
     *
     * @throws IllegalStateException if no transcription has been performed.
     */
    fun getTranscriptionResult(): Subtitles {
        val result = lastResult ?: throw IllegalStateException("No transcription result available")
        val language = Language.fromCode(result.detectedLanguage) ?: Language.ENGLISH
        return result.toSubtitles(language)
    }

    /**
     * Returns the full Whisper result with word-level timing.
     *
     * @throws IllegalStateException if no transcription has been performed.
     */
    fun getFullResult(): WhisperResult {
        return lastResult ?: throw IllegalStateException("No transcription result available")
    }

    /**
     * Gets word-level subtitles for more precise timing.
     *
     * @param wordsPerSegment Maximum words per subtitle segment.
     * @throws IllegalStateException if no transcription has been performed.
     */
    fun getWordLevelSubtitles(wordsPerSegment: Int = 8): Subtitles {
        val result = lastResult ?: throw IllegalStateException("No transcription result available")
        val language = Language.fromCode(result.detectedLanguage) ?: Language.ENGLISH
        return result.toWordLevelSubtitles(language, wordsPerSegment)
    }

    /**
     * Checks if Whisper is available.
     */
    suspend fun isAvailable(): Boolean {
        return processExecutor.isAvailable(whisperPath)
    }

    /**
     * Gets the installed Whisper version.
     */
    suspend fun getVersion(): String? {
        return processExecutor.getVersion(whisperPath)
    }

    /**
     * Checks if GPU acceleration is available.
     */
    suspend fun isGpuAvailable(): Boolean {
        return try {
            val result = processExecutor.executeAndCapture(
                listOf(whisperPath, "--help"),
                ProcessConfig(timeoutMinutes = 1)
            )
            result.stdout.lowercase().contains("gpu") ||
                    result.stdout.lowercase().contains("cuda") ||
                    result.stdout.lowercase().contains("metal")
        } catch (e: Exception) {
            logger.debug { "GPU check failed: ${e.message}" }
            false
        }
    }

    /**
     * Gets the path to a Whisper model file.
     *
     * @param model The model to get the path for.
     * @return Path to the model file, or null if not found.
     */
    fun getModelPath(model: WhisperModel): String? {
        val modelFile = File(platformPaths.modelsDir, "whisper/ggml-${model.modelName}.bin")
        return if (modelFile.exists()) modelFile.absolutePath else null
    }

    /**
     * Gets all available (downloaded) models.
     */
    fun getAvailableModels(): List<WhisperModel> {
        val modelsDir = File(platformPaths.modelsDir, "whisper")
        if (!modelsDir.exists()) return emptyList()

        return modelsDir.listFiles()
            ?.filter { it.extension == "bin" && it.name.startsWith("ggml-") }
            ?.mapNotNull { file ->
                val modelName = file.nameWithoutExtension.removePrefix("ggml-")
                WhisperModel.fromModelName(modelName)
            }
            ?: emptyList()
    }

    // ==================== Private Methods ====================

    /**
     * Prepares audio for transcription (extracts from video or converts format).
     * Uses a two-step process:
     * 1. Extract audio from video to MP3 (intermediate format)
     * 2. Convert MP3 to WAV (16kHz mono for Whisper)
     */
    private suspend fun prepareAudio(
        inputPath: String,
        cancellationToken: CancellationToken?
    ): AudioInfo {
        val inputFile = File(inputPath)
        if (!inputFile.exists()) {
            throw WhisperException(
                WhisperErrorType.AUDIO_NOT_FOUND,
                "Input file not found: $inputPath",
                "File does not exist: $inputPath"
            )
        }

        // Create temp directory for audio files
        val tempDir = File(platformPaths.cacheDir, "transcription").also { it.mkdirs() }

        // Step 1: Extract audio from video to MP3 (intermediate format)
        val mp3Path = File(tempDir, "${inputFile.nameWithoutExtension}_audio.mp3")
        trackTempFile(mp3Path, "intermediate MP3 audio")

        val mp3Command = listOf(
            ffmpegPath,
            "-i", inputPath,
            "-c:a", "libmp3lame",       // MP3 codec
            "-ac", "2",                 // Stereo
            "-q:a", "2",                // Quality (0-9, lower is better)
            "-y",                       // Overwrite
            mp3Path.absolutePath
        )

        println("=== FFmpeg: Extracting audio to MP3 ===")
        println("Command: ${mp3Command.joinToString(" ")}")
        logger.info { "Extracting audio to MP3: ${mp3Command.joinToString(" ")}" }

        try {
            processExecutor.execute(mp3Command, ProcessConfig(), cancellationToken) { line ->
                println("[FFmpeg] $line")
                logger.debug { "[FFmpeg MP3] $line" }
            }
            println("=== MP3 extraction complete: ${mp3Path.absolutePath} ===")
            logger.info { "MP3 extraction complete: ${mp3Path.absolutePath}" }
        } catch (e: ProcessException) {
            println("=== FFmpeg MP3 extraction FAILED ===")
            println("Exit code: ${e.exitCode}")
            println("Error: ${e.message}")
            logger.error { "MP3 extraction failed with exit code ${e.exitCode}" }
            logger.error { "FFmpeg command: ${mp3Command.joinToString(" ")}" }
            logger.error { "FFmpeg error output: ${e.message}" }
            throw WhisperException.fromOutput(e.message ?: "", e.exitCode)
        }

        // Step 2: Convert MP3 to WAV (16kHz mono for Whisper)
        val wavPath = File(tempDir, "${inputFile.nameWithoutExtension}_audio.wav")
        trackTempFile(wavPath, "WAV audio for Whisper")

        val wavCommand = listOf(
            ffmpegPath,
            "-i", mp3Path.absolutePath,
            "-vn",                      // No video
            "-acodec", "pcm_s16le",     // PCM 16-bit
            "-ar", WHISPER_SAMPLE_RATE.toString(),
            "-ac", "1",                 // Mono
            "-y",                       // Overwrite
            wavPath.absolutePath
        )

        println("=== FFmpeg: Converting MP3 to WAV (16kHz mono) ===")
        println("Command: ${wavCommand.joinToString(" ")}")
        logger.info { "Converting MP3 to WAV: ${wavCommand.joinToString(" ")}" }

        try {
            processExecutor.execute(wavCommand, ProcessConfig(), cancellationToken) { line ->
                println("[FFmpeg] $line")
                logger.debug { "[FFmpeg WAV] $line" }
            }
            println("=== WAV conversion complete: ${wavPath.absolutePath} ===")
            logger.info { "WAV conversion complete: ${wavPath.absolutePath}" }
        } catch (e: ProcessException) {
            println("=== FFmpeg WAV conversion FAILED ===")
            println("Exit code: ${e.exitCode}")
            println("Error: ${e.message}")
            logger.error { "WAV conversion failed with exit code ${e.exitCode}" }
            logger.error { "FFmpeg command: ${wavCommand.joinToString(" ")}" }
            logger.error { "FFmpeg error output: ${e.message}" }
            throw WhisperException.fromOutput(e.message ?: "", e.exitCode)
        }

        // Get audio duration
        val duration = getAudioDuration(wavPath.absolutePath)
        println("=== Audio prepared: duration=${duration}ms ===")

        return AudioInfo(
            path = wavPath.absolutePath,
            duration = duration,
            sampleRate = WHISPER_SAMPLE_RATE,
            channels = 1,
            format = "wav"
        )
    }

    /**
     * Gets the duration of an audio file using FFprobe.
     */
    private suspend fun getAudioDuration(audioPath: String): Long {
        val command = listOf(
            ffprobePath,
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            audioPath
        )

        return try {
            val result = processExecutor.executeAndCapture(command, ProcessConfig(timeoutMinutes = 1))
            val seconds = result.stdout.trim().toDoubleOrNull() ?: 0.0
            (seconds * 1000).toLong()
        } catch (e: Exception) {
            logger.warn { "Could not determine audio duration: ${e.message}" }
            0L
        }
    }

    /**
     * Segments a long audio file into smaller chunks.
     */
    private suspend fun segmentAudio(
        audioInfo: AudioInfo,
        config: SegmentationConfig,
        cancellationToken: CancellationToken?
    ): List<AudioSegment> {
        val segments = mutableListOf<AudioSegment>()
        var currentStart = 0L
        var index = 0
        val tempDir = File(audioInfo.path).parentFile

        while (currentStart < audioInfo.duration) {
            val segmentEnd = min(currentStart + config.maxSegmentDuration, audioInfo.duration)
            val segmentDuration = segmentEnd - currentStart

            val segmentFile = File(tempDir, "segment_${index}.wav")
            trackTempFile(segmentFile, "audio segment $index")

            // Extract segment using FFmpeg
            val command = listOf(
                ffmpegPath,
                "-i", audioInfo.path,
                "-ss", (currentStart / 1000.0).toString(),
                "-t", (segmentDuration / 1000.0).toString(),
                "-acodec", "pcm_s16le",
                "-ar", WHISPER_SAMPLE_RATE.toString(),
                "-ac", "1",
                "-y",
                segmentFile.absolutePath
            )

            try {
                processExecutor.execute(command, ProcessConfig(), cancellationToken)
            } catch (e: ProcessException) {
                logger.error { "Audio segmentation failed for segment $index" }
                logger.error { "Segment range: ${currentStart}ms - ${segmentEnd}ms" }
                logger.error { "FFmpeg error: ${e.message}" }
                throw WhisperException.fromOutput(e.message ?: "", e.exitCode)
            }

            segments.add(
                AudioSegment(
                    index = index,
                    path = segmentFile.absolutePath,
                    startTime = currentStart,
                    endTime = segmentEnd,
                    duration = segmentDuration
                )
            )

            // Move to next segment with overlap for context
            currentStart = segmentEnd - config.overlapDuration
            index++
        }

        logger.info { "Split audio into ${segments.size} segments" }
        return segments
    }

    /**
     * Transcribes a single audio segment.
     */
    private suspend fun transcribeSegment(
        segment: AudioSegment,
        options: WhisperOptions,
        totalDuration: Long,
        baseProgress: Float,
        progressWeight: Float,
        cancellationToken: CancellationToken?,
        onProgress: suspend (StageProgress) -> Unit
    ): WhisperResult {
        val modelPath = getModelPathFromSettings(options.model)
        val outputPath = File(segment.path).parentFile.resolve("output_${segment.index}")
        val jsonOutputPath = File("${outputPath.absolutePath}.json")
        trackTempFile(jsonOutputPath, "Whisper JSON output")
        trackTempFile(File("${outputPath.absolutePath}.srt"), "Whisper SRT output")
        trackTempFile(File("${outputPath.absolutePath}.txt"), "Whisper TXT output")

        val command = buildWhisperCommand(segment.path, modelPath, outputPath.absolutePath, options)

        // Print whisper command to console for debugging
        println("=== Whisper: Starting transcription for segment ${segment.index} ===")
        println("Command: ${command.joinToString(" ")}")
        println("Model: $modelPath")
        println("Audio: ${segment.path}")
        println("Duration: ${segment.duration}ms")
        logger.info { "Running Whisper: ${command.joinToString(" ")}" }

        var detectedLanguage = options.language ?: "en"
        var lastProgressTime = 0L
        val errors = StringBuilder()
        val allOutput = StringBuilder()

        try {
            processExecutor.execute(command, ProcessConfig(timeoutMinutes = 120), cancellationToken) { line ->
                // Print all whisper output to console for debugging
                println("[Whisper] $line")
                allOutput.appendLine(line)

                // Parse progress
                val progress = parseWhisperProgress(line, segment.duration, totalDuration)
                if (progress != null) {
                    val adjustedProgress = baseProgress + progress.percentage * progressWeight
                    onProgress(StageProgress(adjustedProgress, progress.message))
                    lastProgressTime = progress.processedDuration
                }

                // Parse language detection
                if (line.contains("auto-detected language:", ignoreCase = true) ||
                    line.contains("detected language:", ignoreCase = true)
                ) {
                    detectedLanguage = extractLanguageCode(line)
                    println("[Whisper] Detected language: $detectedLanguage")
                    logger.info { "Detected language: $detectedLanguage" }
                }

                // Capture errors
                if (line.contains("error", ignoreCase = true) ||
                    line.contains("failed", ignoreCase = true)
                ) {
                    errors.appendLine(line)
                }
            }
            println("=== Whisper: Transcription complete for segment ${segment.index} ===")
            logger.info { "Whisper transcription complete for segment ${segment.index}" }
        } catch (e: ProcessException) {
            val errorOutput = errors.toString().ifBlank { e.message ?: "" }
            println("=== Whisper: Transcription FAILED for segment ${segment.index} ===")
            println("Exit code: ${e.exitCode}")
            println("Error output: $errorOutput")
            println("Full output:\n$allOutput")
            logger.error { "Whisper transcription failed for segment ${segment.index}" }
            logger.error { "Whisper command: ${command.joinToString(" ")}" }
            logger.error { "Exit code: ${e.exitCode}" }
            logger.error { "Whisper error output: $errorOutput" }
            logger.error { "Model path: $modelPath" }
            logger.error { "Audio path: ${segment.path}" }
            throw WhisperException.fromOutput(errorOutput, e.exitCode)
        }

        // Parse output
        println("=== Parsing Whisper output ===")
        val segments = parseWhisperOutput(outputPath.absolutePath, options.wordTimestamps)
        println("=== Parsed ${segments.size} segments ===")
        segments.take(3).forEachIndexed { i, seg ->
            println("  Segment $i: [${seg.startTime}ms - ${seg.endTime}ms] ${seg.text.take(50)}...")
        }
        if (segments.size > 3) {
            println("  ... and ${segments.size - 3} more segments")
        }

        return WhisperResult(
            segments = segments,
            detectedLanguage = detectedLanguage,
            duration = segment.duration,
            processingTime = 0 // Will be set by caller
        )
    }

    /**
     * Builds the Whisper command with all options.
     */
    private fun buildWhisperCommand(
        audioPath: String,
        modelPath: String,
        outputPath: String,
        options: WhisperOptions
    ): List<String> = buildList {
        add(whisperPath)
        add("--model"); add(modelPath)
        add("--output-json-full")  // Use full JSON for detailed timestamps
        add("--output-srt")
        add("--output-file"); add(outputPath)

        // Language
        options.language?.let {
            add("--language"); add(it)
        }

        // Translation mode
        if (options.translate) {
            add("--translate")
        }

        // Segment length control for better timestamp accuracy
        if (options.wordTimestamps) {
            add("--max-len"); add("0")  // Disable max length for word timestamps
        } else if (options.maxSegmentLength > 0) {
            add("--max-len"); add(options.maxSegmentLength.toString())
        }
        // Note: removed default --max-len as it didn't improve timestamps

        // Enable token-level timestamps using DTW for more accurate timing
        // This aligns text with audio more precisely than default segmentation
        add("--dtw"); add(options.model)

        // Split on word boundaries (can cause issues, disabled by default)
        if (options.splitOnWord) {
            add("--split-on-word")
        }

        // GPU acceleration (enabled by default in whisper-cpp, use --no-gpu to disable)
        if (!options.useGpu) {
            add("--no-gpu")
        }

        // Threading
        if (options.threads > 0) {
            add("--threads"); add(options.threads.toString())
        }

        // Beam search
        if (options.beamSize != 5) {
            add("--beam-size"); add(options.beamSize.toString())
        }

        if (options.bestOf != 5) {
            add("--best-of"); add(options.bestOf.toString())
        }

        // Temperature
        if (options.temperature > 0) {
            add("--temperature"); add(options.temperature.toString())
        }

        // Prompt
        options.prompt?.let {
            add("--prompt"); add(it)
        }

        // Voice Activity Detection (VAD) for more accurate timestamps
        if (options.useVad) {
            add("--vad")
            add("--vad-threshold"); add(options.vadThreshold.toString())
            add("--vad-min-speech-duration-ms"); add(options.vadMinSpeechDurationMs.toString())
            add("--vad-min-silence-duration-ms"); add(options.vadMinSilenceDurationMs.toString())
            add("--vad-speech-pad-ms"); add(options.vadSpeechPadMs.toString())
        }

        // Progress output
        add("--print-progress")

        // Input file
        add(audioPath)
    }

    /**
     * Parses Whisper progress output.
     * whisper.cpp outputs: "whisper_print_progress_callback: progress = X%"
     * or timestamp-based: "[00:01:23.456 --> 00:01:25.789]"
     */
    private fun parseWhisperProgress(
        line: String,
        segmentDuration: Long,
        totalDuration: Long
    ): WhisperProgress? {
        // Try percentage format first
        val percentRegex = """progress\s*=?\s*(\d+(?:\.\d+)?)\s*%""".toRegex(RegexOption.IGNORE_CASE)
        percentRegex.find(line)?.let { match ->
            val percent = match.groupValues[1].toFloatOrNull()?.div(100f) ?: return null
            val processedMs = (percent * segmentDuration).toLong()
            return WhisperProgress(
                percentage = percent,
                processedDuration = processedMs,
                totalDuration = totalDuration,
                currentSegment = 0,
                message = "Transcribing: ${(percent * 100).toInt()}%"
            )
        }

        // Try timestamp format: [HH:MM:SS.mmm --> HH:MM:SS.mmm]
        val timestampRegex = """\[(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s*-->""".toRegex()
        timestampRegex.find(line)?.let { match ->
            val hours = match.groupValues[1].toLongOrNull() ?: 0
            val minutes = match.groupValues[2].toLongOrNull() ?: 0
            val seconds = match.groupValues[3].toLongOrNull() ?: 0
            val millis = match.groupValues[4].toLongOrNull() ?: 0
            val processedMs = (hours * 3600 + minutes * 60 + seconds) * 1000 + millis

            val percent = if (totalDuration > 0) {
                (processedMs.toFloat() / totalDuration).coerceIn(0f, 1f)
            } else 0f

            return WhisperProgress(
                percentage = percent,
                processedDuration = processedMs,
                totalDuration = totalDuration,
                currentSegment = 0,
                message = "Transcribing: ${formatDuration(processedMs)} / ${formatDuration(totalDuration)}"
            )
        }

        return null
    }

    /**
     * Formats duration in milliseconds to MM:SS format.
     */
    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    /**
     * Extracts language code from Whisper output.
     */
    private fun extractLanguageCode(line: String): String {
        // Patterns: "detected language: English" or "auto-detected language: en"
        val patterns = listOf(
            """language:\s*(\w+)""".toRegex(RegexOption.IGNORE_CASE),
            """'(\w{2})'""".toRegex()
        )

        for (pattern in patterns) {
            pattern.find(line)?.let { match ->
                val lang = match.groupValues[1].lowercase()
                // Map language names to codes
                return when (lang) {
                    "english" -> "en"
                    "german", "deutsch" -> "de"
                    "french", "français" -> "fr"
                    "spanish", "español" -> "es"
                    "italian", "italiano" -> "it"
                    "portuguese", "português" -> "pt"
                    "russian", "русский" -> "ru"
                    "chinese", "中文" -> "zh"
                    "japanese", "日本語" -> "ja"
                    "korean", "한국어" -> "ko"
                    else -> if (lang.length == 2) lang else "en"
                }
            }
        }

        return "en"
    }

    /**
     * Parses Whisper output files (JSON or SRT).
     */
    private fun parseWhisperOutput(outputPath: String, wordTimestamps: Boolean): List<WhisperSegment> {
        val jsonFile = File("$outputPath.json")
        val srtFile = File("$outputPath.srt")

        // Prefer JSON output for word timestamps
        if (jsonFile.exists()) {
            try {
                return parseJsonOutput(jsonFile, wordTimestamps)
            } catch (e: Exception) {
                logger.warn { "Failed to parse JSON output: ${e.message}" }
            }
        }

        // Fall back to SRT
        if (srtFile.exists()) {
            return parseSrtOutput(srtFile)
        }

        logger.error { "Whisper did not produce output files" }
        logger.error { "Expected JSON file: $jsonFile (exists: ${jsonFile.exists()})" }
        logger.error { "Expected SRT file: $srtFile (exists: ${srtFile.exists()})" }
        logger.error { "Output directory contents: ${jsonFile.parentFile?.listFiles()?.map { it.name }}" }
        throw WhisperException(
            WhisperErrorType.UNKNOWN,
            "Whisper did not produce output files",
            "Neither $jsonFile nor $srtFile exists"
        )
    }

    /**
     * Parses Whisper JSON output.
     */
    private fun parseJsonOutput(file: File, includeWords: Boolean): List<WhisperSegment> {
        val content = file.readText()

        // whisper.cpp JSON format varies, try to handle different versions
        return try {
            // Try parsing as a list of segments
            @Suppress("UNCHECKED_CAST")
            val parsed = json.parseToJsonElement(content)
            val transcription = parsed.jsonObject["transcription"]?.jsonArray
                ?: parsed.jsonArray

            transcription.map { element ->
                val obj = element.jsonObject
                val timestamps = obj["timestamps"]?.jsonObject
                val offsets = obj["offsets"]?.jsonObject

                // Get timing - try different field names
                val startMs = timestamps?.get("from")?.jsonPrimitive?.content?.toLongOrNull()
                    ?: offsets?.get("from")?.jsonPrimitive?.content?.toLongOrNull()
                    ?: (obj["start"]?.jsonPrimitive?.content?.toDoubleOrNull()?.times(1000))?.toLong()
                    ?: 0L

                val endMs = timestamps?.get("to")?.jsonPrimitive?.content?.toLongOrNull()
                    ?: offsets?.get("to")?.jsonPrimitive?.content?.toLongOrNull()
                    ?: (obj["end"]?.jsonPrimitive?.content?.toDoubleOrNull()?.times(1000))?.toLong()
                    ?: 0L

                val text = obj["text"]?.jsonPrimitive?.content ?: ""

                // Parse word-level timestamps if available
                val words = if (includeWords) {
                    obj["words"]?.jsonArray?.map { wordElement ->
                        val wordObj = wordElement.jsonObject
                        WhisperWord(
                            startTime = (wordObj["start"]?.jsonPrimitive?.content?.toDoubleOrNull()?.times(1000))?.toLong() ?: 0L,
                            endTime = (wordObj["end"]?.jsonPrimitive?.content?.toDoubleOrNull()?.times(1000))?.toLong() ?: 0L,
                            text = wordObj["word"]?.jsonPrimitive?.content ?: "",
                            probability = wordObj["probability"]?.jsonPrimitive?.content?.toFloatOrNull()
                        )
                    }
                } else null

                WhisperSegment(
                    startTime = startMs,
                    endTime = endMs,
                    text = text,
                    words = words,
                    avgLogProb = obj["avg_logprob"]?.jsonPrimitive?.content?.toFloatOrNull(),
                    noSpeechProb = obj["no_speech_prob"]?.jsonPrimitive?.content?.toFloatOrNull()
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse Whisper JSON" }
            emptyList()
        }
    }

    /**
     * Parses SRT output file.
     */
    private fun parseSrtOutput(file: File): List<WhisperSegment> {
        val entries = mutableListOf<WhisperSegment>()
        val content = file.readText()

        // SRT format:
        // 1
        // 00:00:00,000 --> 00:00:05,000
        // Subtitle text

        val blocks = content.split("""\n\n+""".toRegex())

        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.size >= 3) {
                val timeLine = lines[1]
                val text = lines.drop(2).joinToString(" ")

                val times = timeLine.split("-->").map { it.trim() }
                if (times.size == 2) {
                    entries.add(
                        WhisperSegment(
                            startTime = parseSrtTimestamp(times[0]),
                            endTime = parseSrtTimestamp(times[1]),
                            text = text
                        )
                    )
                }
            }
        }

        return entries
    }

    /**
     * Parses SRT timestamp format.
     */
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

    /**
     * Merges overlapping segments and removes duplicate text.
     * Handles both time-based overlaps and text-based repetition (common Whisper issue).
     */
    private fun mergeOverlappingSegments(segments: List<WhisperSegment>): List<WhisperSegment> {
        if (segments.isEmpty()) return segments

        // First pass: merge time-based overlaps
        val timeMerged = mutableListOf<WhisperSegment>()
        var current = segments.first()

        for (i in 1 until segments.size) {
            val next = segments[i]

            // Check for time overlap
            if (next.startTime < current.endTime) {
                // Merge by extending current segment
                current = current.copy(
                    endTime = maxOf(current.endTime, next.endTime),
                    text = "${current.text} ${next.text}".trim(),
                    words = if (current.words != null && next.words != null) {
                        current.words + next.words
                    } else {
                        current.words ?: next.words
                    }
                )
            } else {
                timeMerged.add(current)
                current = next
            }
        }
        timeMerged.add(current)

        // Note: Text-based deduplication was removed as it was too aggressive
        // for Whisper output and caused inaccurate transcriptions.
        // The time-based overlap merging above is sufficient.
        return timeMerged
    }

    /**
     * Removes overlapping/repeated text between consecutive segments using the shared deduplicator.
     */
    private fun deduplicateSegmentText(segments: List<WhisperSegment>): List<WhisperSegment> {
        val result = subtitleDeduplicator.deduplicate(
            entries = segments,
            toTimedText = { SubtitleDeduplicator.TimedText(it.startTime, it.endTime, it.text) },
            updateText = { segment, newText -> segment.copy(text = newText) },
            reindex = { segment, _ -> segment } // WhisperSegment doesn't have an index field
        )
        return result.entries
    }

    /**
     * Gets the model path from settings.
     */
    private fun getModelPathFromSettings(modelName: String): String {
        val modelFile = File(platformPaths.modelsDir, "whisper/ggml-$modelName.bin")
        if (!modelFile.exists()) {
            throw WhisperException(
                WhisperErrorType.MODEL_NOT_FOUND,
                "Whisper model '$modelName' not found. Please download it in Settings.",
                "Model file not found: ${modelFile.absolutePath}"
            )
        }
        return modelFile.absolutePath
    }

    /**
     * Tracks a temporary file for cleanup on app shutdown.
     * Files are kept in cache during the session for debugging/inspection.
     */
    private fun trackTempFile(file: File, description: String = "") {
        val operationId = currentOperationId ?: "transcription-${System.currentTimeMillis()}"
        tempFileManager?.trackFile(file.toPath(), operationId, false, description)
        logger.debug { "Tracked temp file: ${file.name} ($description)" }
    }

    /**
     * Sets the current operation ID for temp file tracking.
     */
    fun setOperationId(operationId: String) {
        currentOperationId = operationId
    }
}

// Extension properties for JSON parsing
private val kotlinx.serialization.json.JsonElement.jsonObject: kotlinx.serialization.json.JsonObject
    get() = this as kotlinx.serialization.json.JsonObject

private val kotlinx.serialization.json.JsonElement.jsonArray: kotlinx.serialization.json.JsonArray
    get() = this as kotlinx.serialization.json.JsonArray

private val kotlinx.serialization.json.JsonElement.jsonPrimitive: kotlinx.serialization.json.JsonPrimitive
    get() = this as kotlinx.serialization.json.JsonPrimitive
