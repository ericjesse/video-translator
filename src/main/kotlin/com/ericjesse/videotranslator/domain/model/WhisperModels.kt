package com.ericjesse.videotranslator.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Available Whisper model sizes with their characteristics.
 *
 * @property modelName Name used in file (ggml-{modelName}.bin).
 * @property displayName Human-readable name for UI.
 * @property parameters Number of model parameters.
 * @property englishOnly Whether this is an English-only model.
 * @property vramRequired Approximate VRAM required in MB.
 * @property relativeSpeedy Relative speed compared to base model (higher = faster).
 */
enum class WhisperModel(
    val modelName: String,
    val displayName: String,
    val parameters: String,
    val englishOnly: Boolean,
    val vramRequired: Int,
    val relativeSpeed: Float
) {
    TINY("tiny", "Tiny", "39M", false, 390, 10f),
    TINY_EN("tiny.en", "Tiny (English)", "39M", true, 390, 10f),
    BASE("base", "Base", "74M", false, 500, 7f),
    BASE_EN("base.en", "Base (English)", "74M", true, 500, 7f),
    SMALL("small", "Small", "244M", false, 1000, 4f),
    SMALL_EN("small.en", "Small (English)", "244M", true, 1000, 4f),
    MEDIUM("medium", "Medium", "769M", false, 2600, 2f),
    MEDIUM_EN("medium.en", "Medium (English)", "769M", true, 2600, 2f),
    LARGE_V1("large-v1", "Large v1", "1550M", false, 4700, 1f),
    LARGE_V2("large-v2", "Large v2", "1550M", false, 4700, 1f),
    LARGE_V3("large-v3", "Large v3", "1550M", false, 4700, 1f),
    LARGE("large", "Large", "1550M", false, 4700, 1f);

    companion object {
        /**
         * Gets a model by its model name.
         */
        fun fromModelName(name: String): WhisperModel? =
            entries.find { it.modelName.equals(name, ignoreCase = true) }

        /**
         * Gets all multilingual models (non-English-only).
         */
        val multilingualModels: List<WhisperModel>
            get() = entries.filter { !it.englishOnly }

        /**
         * Gets all English-only models.
         */
        val englishOnlyModels: List<WhisperModel>
            get() = entries.filter { it.englishOnly }
    }
}

/**
 * Options for Whisper transcription.
 *
 * @property model Whisper model to use.
 * @property language Source language code, or null for auto-detection.
 * @property translate Whether to translate to English (only works with multilingual models).
 * @property wordTimestamps Enable word-level timestamps for better subtitle timing.
 * @property maxSegmentLength Maximum segment length in characters (0 = no limit).
 * @property useGpu Whether to use GPU acceleration if available.
 * @property threads Number of CPU threads to use (0 = auto).
 * @property beamSize Beam size for beam search decoding (higher = better quality, slower).
 * @property bestOf Number of candidates to consider (higher = better quality, slower).
 * @property temperature Sampling temperature (0 = greedy, higher = more random).
 * @property prompt Initial prompt to guide transcription style.
 * @property splitOnWord Split on word boundaries when segmenting.
 * @property maxContext Maximum context for decoding (tokens, -1 = default).
 */
@Serializable
data class WhisperOptions(
    val model: String = "base",
    val language: String? = null,
    val translate: Boolean = false,
    val wordTimestamps: Boolean = false,
    val maxSegmentLength: Int = 0,
    val useGpu: Boolean = true,
    val threads: Int = 0,
    val beamSize: Int = 5,
    val bestOf: Int = 5,
    val temperature: Float = 0f,
    val prompt: String? = null,
    val splitOnWord: Boolean = true,
    val maxContext: Int = -1
)

/**
 * Result of Whisper transcription.
 *
 * @property segments List of transcribed segments.
 * @property detectedLanguage Detected or specified language.
 * @property duration Total audio duration in milliseconds.
 * @property processingTime Time taken for transcription in milliseconds.
 */
data class WhisperResult(
    val segments: List<WhisperSegment>,
    val detectedLanguage: String,
    val duration: Long,
    val processingTime: Long
) {
    /**
     * Converts to Subtitles format.
     */
    fun toSubtitles(language: Language): Subtitles {
        val entries = segments.mapIndexed { index, segment ->
            SubtitleEntry(
                index = index + 1,
                startTime = segment.startTime,
                endTime = segment.endTime,
                text = segment.text.trim()
            )
        }
        return Subtitles(entries, language)
    }

    /**
     * Gets word-level entries for more precise subtitle timing.
     */
    fun toWordLevelSubtitles(language: Language, wordsPerSegment: Int = 8): Subtitles {
        val allWords = segments.flatMap { it.words ?: emptyList() }
        if (allWords.isEmpty()) {
            return toSubtitles(language)
        }

        val entries = mutableListOf<SubtitleEntry>()
        var index = 1
        var wordBuffer = mutableListOf<WhisperWord>()

        for (word in allWords) {
            wordBuffer.add(word)

            if (wordBuffer.size >= wordsPerSegment || word.text.endsWith(".") ||
                word.text.endsWith("?") || word.text.endsWith("!")
            ) {
                if (wordBuffer.isNotEmpty()) {
                    entries.add(
                        SubtitleEntry(
                            index = index++,
                            startTime = wordBuffer.first().startTime,
                            endTime = wordBuffer.last().endTime,
                            text = wordBuffer.joinToString(" ") { it.text.trim() }
                        )
                    )
                    wordBuffer = mutableListOf()
                }
            }
        }

        // Add remaining words
        if (wordBuffer.isNotEmpty()) {
            entries.add(
                SubtitleEntry(
                    index = index,
                    startTime = wordBuffer.first().startTime,
                    endTime = wordBuffer.last().endTime,
                    text = wordBuffer.joinToString(" ") { it.text.trim() }
                )
            )
        }

        return Subtitles(entries, language)
    }
}

/**
 * A transcribed segment from Whisper.
 *
 * @property startTime Segment start time in milliseconds.
 * @property endTime Segment end time in milliseconds.
 * @property text Transcribed text.
 * @property words Word-level timing information (if enabled).
 * @property avgLogProb Average log probability (confidence indicator).
 * @property noSpeechProb Probability that this segment contains no speech.
 */
@Serializable
data class WhisperSegment(
    @SerialName("start")
    val startTime: Long,
    @SerialName("end")
    val endTime: Long,
    val text: String,
    val words: List<WhisperWord>? = null,
    @SerialName("avg_logprob")
    val avgLogProb: Float? = null,
    @SerialName("no_speech_prob")
    val noSpeechProb: Float? = null
) {
    /**
     * Duration of this segment in milliseconds.
     */
    val duration: Long get() = endTime - startTime

    /**
     * Whether this segment likely contains speech.
     */
    val hasSpeech: Boolean get() = (noSpeechProb ?: 0f) < 0.5f
}

/**
 * Word-level timing from Whisper.
 *
 * @property startTime Word start time in milliseconds.
 * @property endTime Word end time in milliseconds.
 * @property text The word text.
 * @property probability Confidence probability for this word.
 */
@Serializable
data class WhisperWord(
    @SerialName("start")
    val startTime: Long,
    @SerialName("end")
    val endTime: Long,
    @SerialName("word")
    val text: String,
    @SerialName("probability")
    val probability: Float? = null
)

/**
 * Progress information during transcription.
 *
 * @property percentage Progress from 0.0 to 1.0.
 * @property processedDuration Audio processed so far in milliseconds.
 * @property totalDuration Total audio duration in milliseconds.
 * @property currentSegment Currently processing segment index.
 * @property message Human-readable progress message.
 */
data class WhisperProgress(
    val percentage: Float,
    val processedDuration: Long,
    val totalDuration: Long,
    val currentSegment: Int,
    val message: String
)

/**
 * Error types that can occur during transcription.
 */
enum class WhisperErrorType {
    /** Whisper binary not found. */
    WHISPER_NOT_FOUND,

    /** Model file not found. */
    MODEL_NOT_FOUND,

    /** Audio file not found or unreadable. */
    AUDIO_NOT_FOUND,

    /** FFmpeg not found for audio extraction. */
    FFMPEG_NOT_FOUND,

    /** GPU not available but was requested. */
    GPU_NOT_AVAILABLE,

    /** Out of memory. */
    OUT_OF_MEMORY,

    /** Invalid audio format. */
    INVALID_AUDIO,

    /** Process was cancelled. */
    CANCELLED,

    /** Unknown error. */
    UNKNOWN
}

/**
 * Exception thrown when transcription fails.
 *
 * @property errorType Categorized error type.
 * @property userMessage User-friendly error message.
 * @property technicalMessage Technical details for debugging.
 */
class WhisperException(
    val errorType: WhisperErrorType,
    val userMessage: String,
    val technicalMessage: String,
    cause: Throwable? = null
) : Exception(userMessage, cause) {

    companion object {
        /**
         * Parses Whisper error output and creates an appropriate exception.
         */
        fun fromOutput(output: String, exitCode: Int): WhisperException {
            val lowerOutput = output.lowercase()

            return when {
                "no such file" in lowerOutput && "whisper" in lowerOutput ->
                    WhisperException(
                        WhisperErrorType.WHISPER_NOT_FOUND,
                        "Whisper is not installed. Please install it in Settings.",
                        output
                    )

                "model" in lowerOutput && ("not found" in lowerOutput || "failed to load" in lowerOutput) ->
                    WhisperException(
                        WhisperErrorType.MODEL_NOT_FOUND,
                        "Whisper model not found. Please download the model in Settings.",
                        output
                    )

                "audio" in lowerOutput && ("not found" in lowerOutput || "failed to open" in lowerOutput) ->
                    WhisperException(
                        WhisperErrorType.AUDIO_NOT_FOUND,
                        "Could not read the audio file.",
                        output
                    )

                "ffmpeg" in lowerOutput && "not found" in lowerOutput ->
                    WhisperException(
                        WhisperErrorType.FFMPEG_NOT_FOUND,
                        "FFmpeg is required for audio extraction. Please install it in Settings.",
                        output
                    )

                "cuda" in lowerOutput || "gpu" in lowerOutput && "not available" in lowerOutput ->
                    WhisperException(
                        WhisperErrorType.GPU_NOT_AVAILABLE,
                        "GPU acceleration is not available. Falling back to CPU.",
                        output
                    )

                "out of memory" in lowerOutput || "alloc" in lowerOutput && "failed" in lowerOutput ->
                    WhisperException(
                        WhisperErrorType.OUT_OF_MEMORY,
                        "Out of memory. Try using a smaller model or closing other applications.",
                        output
                    )

                "invalid" in lowerOutput && "audio" in lowerOutput ->
                    WhisperException(
                        WhisperErrorType.INVALID_AUDIO,
                        "Invalid audio format. The file may be corrupted.",
                        output
                    )

                else ->
                    WhisperException(
                        WhisperErrorType.UNKNOWN,
                        "Transcription failed. Please try again.",
                        "Exit code: $exitCode, Output: $output"
                    )
            }
        }
    }
}

/**
 * Audio file information.
 *
 * @property path Path to the audio file.
 * @property duration Duration in milliseconds.
 * @property sampleRate Sample rate in Hz.
 * @property channels Number of audio channels.
 * @property format Audio format (e.g., "wav", "mp3").
 */
data class AudioInfo(
    val path: String,
    val duration: Long,
    val sampleRate: Int,
    val channels: Int,
    val format: String
)

/**
 * Configuration for audio segmentation.
 *
 * @property maxSegmentDuration Maximum segment duration in milliseconds (default: 30 minutes).
 * @property overlapDuration Overlap between segments in milliseconds for context.
 * @property silenceThreshold Silence detection threshold in dB.
 * @property minSilenceDuration Minimum silence duration for split point in milliseconds.
 */
@Serializable
data class SegmentationConfig(
    val maxSegmentDuration: Long = 30 * 60 * 1000L,
    val overlapDuration: Long = 5000L,
    val silenceThreshold: Float = -40f,
    val minSilenceDuration: Long = 500L
)

/**
 * An audio segment for processing long files.
 *
 * @property index Segment index.
 * @property path Path to the segment file.
 * @property startTime Start time in the original audio (milliseconds).
 * @property endTime End time in the original audio (milliseconds).
 * @property duration Segment duration in milliseconds.
 */
data class AudioSegment(
    val index: Int,
    val path: String,
    val startTime: Long,
    val endTime: Long,
    val duration: Long
)
