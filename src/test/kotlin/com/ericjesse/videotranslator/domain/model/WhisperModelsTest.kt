package com.ericjesse.videotranslator.domain.model

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class WhisperModelsTest {

    // ==================== WhisperModel Tests ====================

    @Nested
    inner class WhisperModelTest {

        @Test
        fun `all models have valid model names`() {
            WhisperModel.entries.forEach { model ->
                assertTrue(model.modelName.isNotBlank(), "Model ${model.name} should have a valid model name")
            }
        }

        @Test
        fun `all models have display names`() {
            WhisperModel.entries.forEach { model ->
                assertTrue(model.displayName.isNotBlank(), "Model ${model.name} should have a display name")
            }
        }

        @Test
        fun `fromModelName finds correct model`() {
            assertEquals(WhisperModel.TINY, WhisperModel.fromModelName("tiny"))
            assertEquals(WhisperModel.BASE, WhisperModel.fromModelName("base"))
            assertEquals(WhisperModel.SMALL, WhisperModel.fromModelName("small"))
            assertEquals(WhisperModel.MEDIUM, WhisperModel.fromModelName("medium"))
            assertEquals(WhisperModel.LARGE, WhisperModel.fromModelName("large"))
            assertEquals(WhisperModel.LARGE_V2, WhisperModel.fromModelName("large-v2"))
            assertEquals(WhisperModel.LARGE_V3, WhisperModel.fromModelName("large-v3"))
        }

        @Test
        fun `fromModelName is case insensitive`() {
            assertEquals(WhisperModel.TINY, WhisperModel.fromModelName("TINY"))
            assertEquals(WhisperModel.BASE, WhisperModel.fromModelName("Base"))
            assertEquals(WhisperModel.LARGE_V2, WhisperModel.fromModelName("LARGE-V2"))
        }

        @Test
        fun `fromModelName returns null for unknown model`() {
            assertNull(WhisperModel.fromModelName("unknown"))
            assertNull(WhisperModel.fromModelName(""))
            assertNull(WhisperModel.fromModelName("extra-large"))
        }

        @Test
        fun `english-only models have englishOnly flag set`() {
            assertTrue(WhisperModel.TINY_EN.englishOnly)
            assertTrue(WhisperModel.BASE_EN.englishOnly)
            assertTrue(WhisperModel.SMALL_EN.englishOnly)
            assertTrue(WhisperModel.MEDIUM_EN.englishOnly)
        }

        @Test
        fun `multilingual models have englishOnly flag unset`() {
            assertFalse(WhisperModel.TINY.englishOnly)
            assertFalse(WhisperModel.BASE.englishOnly)
            assertFalse(WhisperModel.SMALL.englishOnly)
            assertFalse(WhisperModel.MEDIUM.englishOnly)
            assertFalse(WhisperModel.LARGE.englishOnly)
            assertFalse(WhisperModel.LARGE_V3.englishOnly)
        }

        @Test
        fun `multilingualModels returns only non-english models`() {
            val multilingualModels = WhisperModel.multilingualModels
            assertTrue(multilingualModels.isNotEmpty())
            multilingualModels.forEach { model ->
                assertFalse(model.englishOnly, "${model.name} should not be English-only")
            }
        }

        @Test
        fun `englishOnlyModels returns only english models`() {
            val englishModels = WhisperModel.englishOnlyModels
            assertTrue(englishModels.isNotEmpty())
            englishModels.forEach { model ->
                assertTrue(model.englishOnly, "${model.name} should be English-only")
            }
        }

        @Test
        fun `larger models require more VRAM`() {
            assertTrue(WhisperModel.LARGE.vramRequired > WhisperModel.MEDIUM.vramRequired)
            assertTrue(WhisperModel.MEDIUM.vramRequired > WhisperModel.SMALL.vramRequired)
            assertTrue(WhisperModel.SMALL.vramRequired > WhisperModel.BASE.vramRequired)
            assertTrue(WhisperModel.BASE.vramRequired > WhisperModel.TINY.vramRequired)
        }

        @Test
        fun `smaller models are faster`() {
            assertTrue(WhisperModel.TINY.relativeSpeed > WhisperModel.BASE.relativeSpeed)
            assertTrue(WhisperModel.BASE.relativeSpeed > WhisperModel.SMALL.relativeSpeed)
            assertTrue(WhisperModel.SMALL.relativeSpeed > WhisperModel.MEDIUM.relativeSpeed)
            assertTrue(WhisperModel.MEDIUM.relativeSpeed > WhisperModel.LARGE.relativeSpeed)
        }
    }

    // ==================== WhisperOptions Tests ====================

    @Nested
    inner class WhisperOptionsTest {

        @Test
        fun `default options are correct`() {
            val options = WhisperOptions()

            assertEquals("base", options.model)
            assertNull(options.language)
            assertFalse(options.translate)
            assertFalse(options.wordTimestamps)
            assertEquals(0, options.maxSegmentLength)
            assertTrue(options.useGpu)
            assertEquals(0, options.threads)
            assertEquals(5, options.beamSize)
            assertEquals(5, options.bestOf)
            assertEquals(0f, options.temperature)
            assertNull(options.prompt)
            assertFalse(options.splitOnWord)  // Disabled by default - can cause inaccurate transcription
            assertEquals(-1, options.maxContext)
        }

        @Test
        fun `options can be customized`() {
            val options = WhisperOptions(
                model = "large-v3",
                language = "de",
                translate = true,
                wordTimestamps = true,
                maxSegmentLength = 50,
                useGpu = false,
                threads = 8,
                beamSize = 10,
                bestOf = 3,
                temperature = 0.5f,
                prompt = "This is a test",
                splitOnWord = false,
                maxContext = 128
            )

            assertEquals("large-v3", options.model)
            assertEquals("de", options.language)
            assertTrue(options.translate)
            assertTrue(options.wordTimestamps)
            assertEquals(50, options.maxSegmentLength)
            assertFalse(options.useGpu)
            assertEquals(8, options.threads)
            assertEquals(10, options.beamSize)
            assertEquals(3, options.bestOf)
            assertEquals(0.5f, options.temperature)
            assertEquals("This is a test", options.prompt)
            assertFalse(options.splitOnWord)
            assertEquals(128, options.maxContext)
        }

        @Test
        fun `copy preserves unmodified fields`() {
            val original = WhisperOptions(
                model = "medium",
                language = "fr",
                useGpu = false
            )

            val copied = original.copy(wordTimestamps = true)

            assertEquals("medium", copied.model)
            assertEquals("fr", copied.language)
            assertFalse(copied.useGpu)
            assertTrue(copied.wordTimestamps)
        }
    }

    // ==================== WhisperSegment Tests ====================

    @Nested
    inner class WhisperSegmentTest {

        @Test
        fun `duration is calculated correctly`() {
            val segment = WhisperSegment(
                startTime = 1000,
                endTime = 5000,
                text = "Hello world"
            )

            assertEquals(4000, segment.duration)
        }

        @Test
        fun `hasSpeech returns true when noSpeechProb is low`() {
            val segment = WhisperSegment(
                startTime = 0,
                endTime = 1000,
                text = "Hello",
                noSpeechProb = 0.1f
            )

            assertTrue(segment.hasSpeech)
        }

        @Test
        fun `hasSpeech returns false when noSpeechProb is high`() {
            val segment = WhisperSegment(
                startTime = 0,
                endTime = 1000,
                text = "",
                noSpeechProb = 0.8f
            )

            assertFalse(segment.hasSpeech)
        }

        @Test
        fun `hasSpeech returns true when noSpeechProb is null`() {
            val segment = WhisperSegment(
                startTime = 0,
                endTime = 1000,
                text = "Hello",
                noSpeechProb = null
            )

            assertTrue(segment.hasSpeech)
        }

        @Test
        fun `segment can have word-level timestamps`() {
            val words = listOf(
                WhisperWord(startTime = 0, endTime = 500, text = "Hello"),
                WhisperWord(startTime = 500, endTime = 1000, text = "world")
            )
            val segment = WhisperSegment(
                startTime = 0,
                endTime = 1000,
                text = "Hello world",
                words = words
            )

            assertNotNull(segment.words)
            assertEquals(2, segment.words!!.size)
        }
    }

    // ==================== WhisperWord Tests ====================

    @Nested
    inner class WhisperWordTest {

        @Test
        fun `word has timing and text`() {
            val word = WhisperWord(
                startTime = 100,
                endTime = 300,
                text = "Hello",
                probability = 0.95f
            )

            assertEquals(100, word.startTime)
            assertEquals(300, word.endTime)
            assertEquals("Hello", word.text)
            assertEquals(0.95f, word.probability)
        }

        @Test
        fun `probability can be null`() {
            val word = WhisperWord(
                startTime = 0,
                endTime = 100,
                text = "Test",
                probability = null
            )

            assertNull(word.probability)
        }
    }

    // ==================== WhisperResult Tests ====================

    @Nested
    inner class WhisperResultTest {

        private val sampleSegments = listOf(
            WhisperSegment(startTime = 0, endTime = 2000, text = "Hello world"),
            WhisperSegment(startTime = 2000, endTime = 4000, text = "This is a test"),
            WhisperSegment(startTime = 4000, endTime = 6000, text = "Goodbye")
        )

        @Test
        fun `toSubtitles converts segments correctly`() {
            val result = WhisperResult(
                segments = sampleSegments,
                detectedLanguage = "en",
                duration = 6000,
                processingTime = 1000
            )

            val subtitles = result.toSubtitles(Language.ENGLISH)

            assertEquals(Language.ENGLISH, subtitles.language)
            assertEquals(3, subtitles.entries.size)
            assertEquals(1, subtitles.entries[0].index)
            assertEquals(0, subtitles.entries[0].startTime)
            assertEquals(2000, subtitles.entries[0].endTime)
            assertEquals("Hello world", subtitles.entries[0].text)
        }

        @Test
        fun `toWordLevelSubtitles groups words correctly`() {
            val wordsSegment1 = listOf(
                WhisperWord(startTime = 0, endTime = 200, text = "This"),
                WhisperWord(startTime = 200, endTime = 400, text = "is"),
                WhisperWord(startTime = 400, endTime = 600, text = "a"),
                WhisperWord(startTime = 600, endTime = 800, text = "test.")
            )
            val wordsSegment2 = listOf(
                WhisperWord(startTime = 1000, endTime = 1200, text = "Another"),
                WhisperWord(startTime = 1200, endTime = 1400, text = "sentence.")
            )

            val result = WhisperResult(
                segments = listOf(
                    WhisperSegment(startTime = 0, endTime = 800, text = "This is a test.", words = wordsSegment1),
                    WhisperSegment(startTime = 1000, endTime = 1400, text = "Another sentence.", words = wordsSegment2)
                ),
                detectedLanguage = "en",
                duration = 1400,
                processingTime = 500
            )

            // With 8 words per segment (default), should create entries based on punctuation
            val subtitles = result.toWordLevelSubtitles(Language.ENGLISH, wordsPerSegment = 8)

            assertTrue(subtitles.entries.isNotEmpty())
            // First entry should end at the period
            assertEquals("This is a test.", subtitles.entries[0].text)
        }

        @Test
        fun `toWordLevelSubtitles falls back to segment-level when no words`() {
            val result = WhisperResult(
                segments = sampleSegments,
                detectedLanguage = "en",
                duration = 6000,
                processingTime = 1000
            )

            val subtitles = result.toWordLevelSubtitles(Language.ENGLISH)

            assertEquals(3, subtitles.entries.size)
        }

        @Test
        fun `toWordLevelSubtitles respects wordsPerSegment limit`() {
            val manyWords = (1..20).map { i ->
                WhisperWord(startTime = (i - 1) * 100L, endTime = i * 100L, text = "word$i")
            }

            val result = WhisperResult(
                segments = listOf(
                    WhisperSegment(startTime = 0, endTime = 2000, text = "Many words", words = manyWords)
                ),
                detectedLanguage = "en",
                duration = 2000,
                processingTime = 500
            )

            val subtitles = result.toWordLevelSubtitles(Language.ENGLISH, wordsPerSegment = 5)

            // 20 words / 5 per segment = 4 entries
            assertEquals(4, subtitles.entries.size)
        }
    }

    // ==================== WhisperProgress Tests ====================

    @Nested
    inner class WhisperProgressTest {

        @Test
        fun `progress contains all fields`() {
            val progress = WhisperProgress(
                percentage = 0.5f,
                processedDuration = 30000,
                totalDuration = 60000,
                currentSegment = 1,
                message = "Transcribing: 50%"
            )

            assertEquals(0.5f, progress.percentage)
            assertEquals(30000, progress.processedDuration)
            assertEquals(60000, progress.totalDuration)
            assertEquals(1, progress.currentSegment)
            assertEquals("Transcribing: 50%", progress.message)
        }
    }

    // ==================== WhisperException Tests ====================

    @Nested
    inner class WhisperExceptionTest {

        @Test
        fun `fromOutput detects whisper not found`() {
            val exception = WhisperException.fromOutput(
                "error: no such file whisper",
                127
            )

            assertEquals(WhisperErrorType.WHISPER_NOT_FOUND, exception.errorType)
            assertTrue(exception.userMessage.contains("Whisper"))
        }

        @Test
        fun `fromOutput detects model not found`() {
            val outputs = listOf(
                "error: model file not found",
                "failed to load model: ggml-base.bin"
            )

            outputs.forEach { output ->
                val exception = WhisperException.fromOutput(output, 1)
                assertEquals(
                    WhisperErrorType.MODEL_NOT_FOUND,
                    exception.errorType,
                    "Should detect model not found in: $output"
                )
            }
        }

        @Test
        fun `fromOutput detects audio not found`() {
            val outputs = listOf(
                "error: audio file not found",
                "failed to open audio: test.wav"
            )

            outputs.forEach { output ->
                val exception = WhisperException.fromOutput(output, 1)
                assertEquals(
                    WhisperErrorType.AUDIO_NOT_FOUND,
                    exception.errorType,
                    "Should detect audio not found in: $output"
                )
            }
        }

        @Test
        fun `fromOutput detects ffmpeg not found`() {
            val exception = WhisperException.fromOutput(
                "ffmpeg: command not found",
                127
            )

            assertEquals(WhisperErrorType.FFMPEG_NOT_FOUND, exception.errorType)
        }

        @Test
        fun `fromOutput detects GPU not available`() {
            val outputs = listOf(
                "CUDA not available",
                "GPU acceleration not available"
            )

            outputs.forEach { output ->
                val exception = WhisperException.fromOutput(output, 1)
                assertEquals(
                    WhisperErrorType.GPU_NOT_AVAILABLE,
                    exception.errorType,
                    "Should detect GPU not available in: $output"
                )
            }
        }

        @Test
        fun `fromOutput detects out of memory`() {
            val outputs = listOf(
                "error: out of memory",
                "alloc failed: cannot allocate memory"
            )

            outputs.forEach { output ->
                val exception = WhisperException.fromOutput(output, 1)
                assertEquals(
                    WhisperErrorType.OUT_OF_MEMORY,
                    exception.errorType,
                    "Should detect OOM in: $output"
                )
            }
        }

        @Test
        fun `fromOutput detects invalid audio`() {
            val exception = WhisperException.fromOutput(
                "error: invalid audio format",
                1
            )

            assertEquals(WhisperErrorType.INVALID_AUDIO, exception.errorType)
        }

        @Test
        fun `fromOutput returns UNKNOWN for unrecognized errors`() {
            val exception = WhisperException.fromOutput(
                "some completely unknown error",
                42
            )

            assertEquals(WhisperErrorType.UNKNOWN, exception.errorType)
            assertTrue(exception.technicalMessage.contains("42"))
        }

        @Test
        fun `exception contains user and technical messages`() {
            val exception = WhisperException(
                errorType = WhisperErrorType.MODEL_NOT_FOUND,
                userMessage = "User friendly message",
                technicalMessage = "Technical details"
            )

            assertEquals("User friendly message", exception.userMessage)
            assertEquals("Technical details", exception.technicalMessage)
            assertEquals("User friendly message", exception.message)
        }

        @Test
        fun `exception preserves cause`() {
            val cause = RuntimeException("Original error")
            val exception = WhisperException(
                errorType = WhisperErrorType.UNKNOWN,
                userMessage = "Error",
                technicalMessage = "Details",
                cause = cause
            )

            assertEquals(cause, exception.cause)
        }
    }

    // ==================== AudioInfo Tests ====================

    @Nested
    inner class AudioInfoTest {

        @Test
        fun `audio info contains all fields`() {
            val info = AudioInfo(
                path = "/tmp/audio.wav",
                duration = 120000,
                sampleRate = 16000,
                channels = 1,
                format = "wav"
            )

            assertEquals("/tmp/audio.wav", info.path)
            assertEquals(120000, info.duration)
            assertEquals(16000, info.sampleRate)
            assertEquals(1, info.channels)
            assertEquals("wav", info.format)
        }
    }

    // ==================== SegmentationConfig Tests ====================

    @Nested
    inner class SegmentationConfigTest {

        @Test
        fun `default config has correct values`() {
            val config = SegmentationConfig()

            assertEquals(30 * 60 * 1000L, config.maxSegmentDuration)
            assertEquals(5000L, config.overlapDuration)
            assertEquals(-40f, config.silenceThreshold)
            assertEquals(500L, config.minSilenceDuration)
        }

        @Test
        fun `config can be customized`() {
            val config = SegmentationConfig(
                maxSegmentDuration = 10 * 60 * 1000L,
                overlapDuration = 3000L,
                silenceThreshold = -30f,
                minSilenceDuration = 1000L
            )

            assertEquals(10 * 60 * 1000L, config.maxSegmentDuration)
            assertEquals(3000L, config.overlapDuration)
            assertEquals(-30f, config.silenceThreshold)
            assertEquals(1000L, config.minSilenceDuration)
        }
    }

    // ==================== AudioSegment Tests ====================

    @Nested
    inner class AudioSegmentTest {

        @Test
        fun `audio segment contains all fields`() {
            val segment = AudioSegment(
                index = 0,
                path = "/tmp/segment_0.wav",
                startTime = 0,
                endTime = 30000,
                duration = 30000
            )

            assertEquals(0, segment.index)
            assertEquals("/tmp/segment_0.wav", segment.path)
            assertEquals(0, segment.startTime)
            assertEquals(30000, segment.endTime)
            assertEquals(30000, segment.duration)
        }
    }
}
