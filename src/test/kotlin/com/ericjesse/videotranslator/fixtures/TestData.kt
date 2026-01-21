package com.ericjesse.videotranslator.fixtures

import com.ericjesse.videotranslator.domain.model.BurnedInSubtitleStyle
import com.ericjesse.videotranslator.domain.model.Glossary
import com.ericjesse.videotranslator.domain.model.GlossaryEntry
import com.ericjesse.videotranslator.domain.model.Language
import com.ericjesse.videotranslator.domain.model.OutputOptions
import com.ericjesse.videotranslator.domain.model.RenderOptions
import com.ericjesse.videotranslator.domain.model.SubtitleEntry
import com.ericjesse.videotranslator.domain.model.SubtitleType
import com.ericjesse.videotranslator.domain.model.Subtitles
import com.ericjesse.videotranslator.domain.model.TranslationJob
import com.ericjesse.videotranslator.domain.model.TranslationResult
import com.ericjesse.videotranslator.domain.model.VideoInfo
import java.io.File

/**
 * Factory methods for creating test data objects.
 * Use these to create consistent, reusable test fixtures.
 */
object TestData {

    // ==================== VideoInfo ====================

    /**
     * Creates a standard VideoInfo for testing.
     */
    fun videoInfo(
        url: String = "https://www.youtube.com/watch?v=test123456",
        id: String = "test123456",
        title: String = "Test Video Title",
        duration: Long = 300_000L, // 5 minutes in milliseconds
        thumbnailUrl: String? = "https://i.ytimg.com/vi/test123456/maxresdefault.jpg",
        width: Int? = 1920,
        height: Int? = 1080,
        bitrate: Int? = 8000,
    ) = VideoInfo(
        url = url,
        id = id,
        title = title,
        duration = duration,
        thumbnailUrl = thumbnailUrl,
        width = width,
        height = height,
        bitrate = bitrate
    )

    /**
     * Creates a short video (under 30 seconds).
     */
    fun shortVideoInfo(durationSeconds: Long = 15) = videoInfo(
        id = "shortVideo1",
        title = "Short Video",
        duration = durationSeconds * 1000
    )

    /**
     * Creates a long video (over 2 hours).
     */
    fun longVideoInfo(durationHours: Int = 3) = videoInfo(
        id = "longVideo12",
        title = "Long Video",
        duration = durationHours * 3600 * 1000L
    )

    /**
     * Creates a very long video (over 4 hours).
     */
    fun veryLongVideoInfo() = videoInfo(
        id = "veryLong123",
        title = "Very Long Video",
        duration = 5 * 3600 * 1000L // 5 hours
    )

    // ==================== Subtitles ====================

    /**
     * Creates a standard Subtitles object for testing.
     */
    fun subtitles(
        language: Language = Language.ENGLISH,
        entries: List<SubtitleEntry> = defaultSubtitleEntries(),
    ) = Subtitles(entries = entries, language = language)

    /**
     * Creates empty subtitles.
     */
    fun emptySubtitles(language: Language = Language.ENGLISH) =
        Subtitles(entries = emptyList(), language = language)

    /**
     * Creates a single subtitle entry.
     */
    fun subtitleEntry(
        index: Int = 1,
        startTime: Long = 0,
        endTime: Long = 5000,
        text: String = "Hello, world!",
    ) = SubtitleEntry(
        index = index,
        startTime = startTime,
        endTime = endTime,
        text = text
    )

    /**
     * Creates default subtitle entries for testing.
     */
    fun defaultSubtitleEntries() = listOf(
        SubtitleEntry(index = 1, startTime = 0, endTime = 3000, text = "Hello, welcome to this video."),
        SubtitleEntry(index = 2, startTime = 3000, endTime = 6000, text = "Today we'll be discussing testing."),
        SubtitleEntry(index = 3, startTime = 6000, endTime = 10000, text = "Let's get started!")
    )

    /**
     * Creates subtitles with many entries for performance testing.
     */
    fun largeSubtitles(
        entryCount: Int = 500,
        language: Language = Language.ENGLISH,
    ): Subtitles {
        val entries = (1..entryCount).map { index ->
            SubtitleEntry(
                index = index,
                startTime = (index - 1) * 3000L,
                endTime = index * 3000L,
                text = "This is subtitle entry number $index."
            )
        }
        return Subtitles(entries = entries, language = language)
    }

    // ==================== OutputOptions ====================

    /**
     * Creates standard OutputOptions for testing.
     */
    fun outputOptions(
        outputDirectory: String = System.getProperty("java.io.tmpdir"),
        subtitleType: SubtitleType = SubtitleType.SOFT,
        exportSrt: Boolean = false,
        burnedInStyle: BurnedInSubtitleStyle? = null,
        renderOptions: RenderOptions? = null,
    ) = OutputOptions(
        outputDirectory = outputDirectory,
        subtitleType = subtitleType,
        exportSrt = exportSrt,
        burnedInStyle = burnedInStyle,
        renderOptions = renderOptions
    )

    /**
     * Creates OutputOptions for burned-in subtitles.
     */
    fun burnedInOutputOptions(
        outputDirectory: String = System.getProperty("java.io.tmpdir"),
        fontSize: Int = 24,
        fontColor: String = "#FFFFFF",
    ) = OutputOptions(
        outputDirectory = outputDirectory,
        subtitleType = SubtitleType.BURNED_IN,
        exportSrt = false,
        burnedInStyle = BurnedInSubtitleStyle(
            fontSize = fontSize,
            fontColor = fontColor
        )
    )

    // ==================== TranslationJob ====================

    /**
     * Creates a standard TranslationJob for testing.
     */
    fun translationJob(
        videoInfo: VideoInfo = videoInfo(),
        sourceLanguage: Language? = Language.ENGLISH,
        targetLanguage: Language = Language.GERMAN,
        outputOptions: OutputOptions = outputOptions(),
    ) = TranslationJob(
        videoInfo = videoInfo,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        outputOptions = outputOptions
    )

    // ==================== TranslationResult ====================

    /**
     * Creates a standard TranslationResult for testing.
     */
    fun translationResult(
        videoFile: String = "/tmp/output_video.mp4",
        subtitleFile: String? = "/tmp/output_subtitles.srt",
        duration: Long = 30_000L,
    ) = TranslationResult(
        videoFile = videoFile,
        subtitleFile = subtitleFile,
        duration = duration
    )

    // ==================== Glossary ====================

    /**
     * Creates a standard Glossary for testing.
     */
    fun glossary(
        name: String = "Test Glossary",
        sourceLanguage: String = "en",
        targetLanguage: String = "de",
        entries: List<GlossaryEntry> = listOf(
            GlossaryEntry("hello", "hallo"),
            GlossaryEntry("world", "welt"),
            GlossaryEntry("testing", "testen")
        ),
    ) = Glossary(
        name = name,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        entries = entries
    )

    /**
     * Creates an empty glossary.
     */
    fun emptyGlossary(
        name: String = "Empty Glossary",
        sourceLanguage: String = "en",
        targetLanguage: String = "de",
    ) = Glossary(
        name = name,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        entries = emptyList()
    )

    // ==================== File Helpers ====================

    /**
     * Creates a temporary directory for testing.
     */
    fun tempDirectory(prefix: String = "test"): File {
        return File(System.getProperty("java.io.tmpdir"), "$prefix-${System.currentTimeMillis()}").apply {
            mkdirs()
        }
    }

    /**
     * Creates a temporary file with content.
     */
    fun tempFile(
        prefix: String = "test",
        suffix: String = ".txt",
        content: String = "",
    ): File {
        return File.createTempFile(prefix, suffix).apply {
            deleteOnExit()
            if (content.isNotEmpty()) {
                writeText(content)
            }
        }
    }

    /**
     * Creates a temporary video file path (doesn't create actual file).
     */
    fun tempVideoPath(videoId: String = "test123456"): String {
        return File(System.getProperty("java.io.tmpdir"), "$videoId.mp4").absolutePath
    }

    /**
     * Creates sample VTT content for caption testing.
     */
    fun sampleVttContent() = """
        WEBVTT

        00:00:00.000 --> 00:00:03.000
        Hello, welcome to this video.

        00:00:03.000 --> 00:00:06.000
        Today we'll be discussing testing.

        00:00:06.000 --> 00:00:10.000
        Let's get started!
    """.trimIndent()

    /**
     * Creates sample SRT content for subtitle testing.
     */
    fun sampleSrtContent() = """
        1
        00:00:00,000 --> 00:00:03,000
        Hello, welcome to this video.

        2
        00:00:03,000 --> 00:00:06,000
        Today we'll be discussing testing.

        3
        00:00:06,000 --> 00:00:10,000
        Let's get started!
    """.trimIndent()
}
