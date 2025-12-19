package com.ericjesse.videotranslator.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents a YouTube video to be processed.
 */
@Serializable
data class VideoInfo(
    val url: String,
    val id: String,
    val title: String,
    val duration: Long, // in seconds
    val thumbnailUrl: String? = null
)

/**
 * Represents a single subtitle entry with timing.
 */
@Serializable
data class SubtitleEntry(
    val index: Int,
    val startTime: Long, // in milliseconds
    val endTime: Long,   // in milliseconds
    val text: String
)

/**
 * A collection of subtitle entries representing a full transcript.
 */
@Serializable
data class Subtitles(
    val entries: List<SubtitleEntry>,
    val language: Language
)

/**
 * Supported languages.
 */
@Serializable
enum class Language(val code: String, val displayName: String, val nativeName: String) {
    ENGLISH("en", "English", "English"),
    GERMAN("de", "German", "Deutsch"),
    FRENCH("fr", "French", "Français");
    
    companion object {
        fun fromCode(code: String): Language? = entries.find { it.code == code }
        
        val AUTO_DETECT = null // Represented as null in source language selection
    }
}

/**
 * Output configuration for subtitle rendering.
 */
@Serializable
data class OutputOptions(
    val outputDirectory: String,
    val subtitleType: SubtitleType,
    val exportSrt: Boolean = false,
    val burnedInStyle: BurnedInSubtitleStyle? = null
)

/**
 * Type of subtitle output.
 */
@Serializable
enum class SubtitleType {
    SOFT,      // Embedded as separate track (MKV)
    BURNED_IN  // Rendered into video frames
}

/**
 * Styling options for burned-in subtitles.
 */
@Serializable
data class BurnedInSubtitleStyle(
    val fontSize: Int = 24,
    val fontColor: String = "#FFFFFF",
    val backgroundColor: BackgroundColor = BackgroundColor.NONE,
    val backgroundOpacity: Float = 0f
)

/**
 * Background color options for burned-in subtitles.
 */
@Serializable
enum class BackgroundColor(val hex: String?) {
    NONE(null),
    BLACK("#000000"),
    DARK_GRAY("#333333"),
    WHITE("#FFFFFF")
}

/**
 * Represents the result of a translation job.
 */
@Serializable
data class TranslationResult(
    val videoFile: String,
    val subtitleFile: String? = null,
    val duration: Long // processing time in milliseconds
)

/**
 * Represents a translation job request.
 */
data class TranslationJob(
    val videoInfo: VideoInfo,
    val sourceLanguage: Language?,
    val targetLanguage: Language,
    val outputOptions: OutputOptions
)
