package com.ericjesse.videotranslator.domain.validation

import com.ericjesse.videotranslator.domain.model.Language
import com.ericjesse.videotranslator.domain.model.SubtitleEntry
import com.ericjesse.videotranslator.domain.model.Subtitles
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Thresholds for translation validation.
 */
object TranslationThresholds {
    /** Maximum characters per translation request */
    const val MAX_CHARS_PER_REQUEST = 5000

    /** Maximum subtitle text length (chars) */
    const val MAX_SUBTITLE_LENGTH = 200

    /** Minimum confidence for language detection (0-1) */
    const val MIN_LANGUAGE_CONFIDENCE = 0.7f

    /** Maximum ratio of special characters to total (0-1) */
    const val MAX_SPECIAL_CHAR_RATIO = 0.3f
}

/**
 * Result of translation validation.
 */
sealed class TranslationValidationResult {
    /** Translation can proceed normally */
    data object Valid : TranslationValidationResult()

    /** Translation should be skipped (source = target) */
    data class Skip(
        val reason: SkipReason
    ) : TranslationValidationResult()

    /** Translation can proceed with warnings */
    data class ValidWithWarnings(
        val warnings: List<TranslationWarning>
    ) : TranslationValidationResult()

    /** Translation cannot proceed */
    data class Invalid(
        val error: TranslationError
    ) : TranslationValidationResult()

    fun isValid(): Boolean = this is Valid || this is ValidWithWarnings
    fun shouldSkip(): Boolean = this is Skip
}

/**
 * Reasons to skip translation.
 */
sealed class SkipReason {
    abstract val message: String

    /** Source and target language are the same */
    data class SameLanguage(
        val language: Language
    ) : SkipReason() {
        override val message = "Source and target language are both ${language.displayName}. No translation needed."
    }

    /** Content is already in target language */
    data class AlreadyInTargetLanguage(
        val detectedLanguage: Language
    ) : SkipReason() {
        override val message = "Content appears to already be in ${detectedLanguage.displayName}."
    }

    /** No translatable content */
    data object NoContent : SkipReason() {
        override val message = "No translatable text content found."
    }
}

/**
 * Types of translation warnings.
 */
sealed class TranslationWarning {
    abstract val message: String
    abstract val suggestion: String?

    /** Source language auto-detected */
    data class LanguageAutoDetected(
        val detectedLanguage: Language,
        val confidence: Float
    ) : TranslationWarning() {
        override val message = "Detected source language: ${detectedLanguage.displayName} (${(confidence * 100).toInt()}% confidence)"
        override val suggestion = if (confidence < TranslationThresholds.MIN_LANGUAGE_CONFIDENCE) {
            "Low confidence detection. Consider manually specifying the source language."
        } else null
    }

    /** Unknown/unsupported source language */
    data class UnknownSourceLanguage(
        val detectedCode: String
    ) : TranslationWarning() {
        override val message = "Detected language code '$detectedCode' is not in supported list"
        override val suggestion = "Translation may still work but results may vary."
    }

    /** Content contains many special characters */
    data class HighSpecialCharRatio(
        val ratio: Float
    ) : TranslationWarning() {
        override val message = "Content contains ${(ratio * 100).toInt()}% special characters"
        override val suggestion = "Special characters will be preserved but may affect translation quality."
    }

    /** Very long subtitle lines */
    data class LongSubtitleLines(
        val count: Int,
        val willBeSplit: Boolean
    ) : TranslationWarning() {
        override val message = "$count subtitle entries are very long"
        override val suggestion = if (willBeSplit) {
            "Long entries will be split for translation."
        } else {
            "Consider splitting long entries for better display."
        }
    }

    /** Mixed languages detected */
    data class MixedLanguages(
        val languages: List<String>
    ) : TranslationWarning() {
        override val message = "Multiple languages detected: ${languages.joinToString(", ")}"
        override val suggestion = "Translation will be applied uniformly. Mixed-language content may not translate optimally."
    }

    /** Contains untranslatable content */
    data class UntranslatableContent(
        val types: List<String>
    ) : TranslationWarning() {
        override val message = "Content includes: ${types.joinToString(", ")}"
        override val suggestion = "These elements will be preserved as-is."
    }

    /** Translation may expand text length */
    data class ExpectedTextExpansion(
        val sourceLanguage: Language,
        val targetLanguage: Language,
        val estimatedExpansion: Float
    ) : TranslationWarning() {
        override val message = "Translating ${sourceLanguage.displayName} to ${targetLanguage.displayName} " +
                "may increase text length by ~${(estimatedExpansion * 100).toInt()}%"
        override val suggestion = "Subtitles may need more screen time for readability."
    }
}

/**
 * Types of translation errors.
 */
sealed class TranslationError {
    abstract val code: String
    abstract val message: String
    abstract val suggestion: String?
    abstract val retryable: Boolean

    /** Language not supported */
    data class UnsupportedLanguage(
        val language: String,
        val isSource: Boolean
    ) : TranslationError() {
        override val code = "UNSUPPORTED_LANGUAGE"
        override val message = "The ${if (isSource) "source" else "target"} language '$language' is not supported"
        override val suggestion = "Choose a different ${if (isSource) "source" else "target"} language."
        override val retryable = false
    }

    /** Translation service error */
    data class ServiceError(
        val serviceName: String,
        val details: String?
    ) : TranslationError() {
        override val code = "TRANSLATION_SERVICE_ERROR"
        override val message = "$serviceName translation failed: ${details ?: "unknown error"}"
        override val suggestion = "Try again later or use a different translation service."
        override val retryable = true
    }

    /** Rate limited */
    data class RateLimited(
        val retryAfterSeconds: Int?
    ) : TranslationError() {
        override val code = "RATE_LIMITED"
        override val message = "Translation service rate limit reached"
        override val suggestion = if (retryAfterSeconds != null) {
            "Please wait ${retryAfterSeconds} seconds before trying again."
        } else {
            "Please wait a moment before trying again."
        }
        override val retryable = true
    }

    /** Content too large */
    data class ContentTooLarge(
        val characterCount: Int,
        val limit: Int
    ) : TranslationError() {
        override val code = "CONTENT_TOO_LARGE"
        override val message = "Content exceeds translation limit: $characterCount characters (max: $limit)"
        override val suggestion = "Content will be translated in batches automatically."
        override val retryable = true
    }

    /** Invalid content */
    data class InvalidContent(
        val reason: String
    ) : TranslationError() {
        override val code = "INVALID_CONTENT"
        override val message = "Content cannot be translated: $reason"
        override val suggestion = "Check the source content for issues."
        override val retryable = false
    }
}

/**
 * Validates translation inputs and outputs.
 */
class TranslationValidator {

    /**
     * Validates translation parameters before translation.
     *
     * @param subtitles Source subtitles to translate.
     * @param sourceLanguage Source language (null for auto-detect).
     * @param targetLanguage Target language.
     * @return TranslationValidationResult indicating if translation should proceed.
     */
    fun validateBeforeTranslation(
        subtitles: Subtitles,
        sourceLanguage: Language?,
        targetLanguage: Language
    ): TranslationValidationResult {
        val warnings = mutableListOf<TranslationWarning>()

        // Check for empty content
        val textContent = subtitles.entries.joinToString(" ") { it.text }
        if (textContent.isBlank()) {
            return TranslationValidationResult.Skip(SkipReason.NoContent)
        }

        // Check if source = target
        if (sourceLanguage != null && sourceLanguage == targetLanguage) {
            return TranslationValidationResult.Skip(
                SkipReason.SameLanguage(sourceLanguage)
            )
        }

        // Check detected language vs target
        if (subtitles.language == targetLanguage) {
            // Content might already be in target language
            warnings.add(TranslationWarning.LanguageAutoDetected(
                detectedLanguage = subtitles.language,
                confidence = 1.0f
            ))
        }

        // Check for special characters
        val specialCharRatio = calculateSpecialCharRatio(textContent)
        if (specialCharRatio > TranslationThresholds.MAX_SPECIAL_CHAR_RATIO) {
            warnings.add(TranslationWarning.HighSpecialCharRatio(specialCharRatio))
        }

        // Check for long entries
        val longEntries = subtitles.entries.filter { it.text.length > TranslationThresholds.MAX_SUBTITLE_LENGTH }
        if (longEntries.isNotEmpty()) {
            warnings.add(TranslationWarning.LongSubtitleLines(
                count = longEntries.size,
                willBeSplit = true
            ))
        }

        // Check for untranslatable content
        val untranslatableTypes = detectUntranslatableContent(subtitles)
        if (untranslatableTypes.isNotEmpty()) {
            warnings.add(TranslationWarning.UntranslatableContent(untranslatableTypes))
        }

        // Estimate text expansion
        val expansion = estimateTextExpansion(subtitles.language, targetLanguage)
        if (expansion > 0.2f) { // More than 20% expansion expected
            warnings.add(TranslationWarning.ExpectedTextExpansion(
                sourceLanguage = subtitles.language,
                targetLanguage = targetLanguage,
                estimatedExpansion = expansion
            ))
        }

        return if (warnings.isEmpty()) {
            TranslationValidationResult.Valid
        } else {
            TranslationValidationResult.ValidWithWarnings(warnings)
        }
    }

    /**
     * Validates translation output.
     *
     * @param original Original subtitles.
     * @param translated Translated subtitles.
     * @return List of any issues detected.
     */
    fun validateTranslationOutput(
        original: Subtitles,
        translated: Subtitles
    ): List<TranslationWarning> {
        val warnings = mutableListOf<TranslationWarning>()

        // Check entry count matches
        if (original.entries.size != translated.entries.size) {
            logger.warn { "Translation entry count mismatch: ${original.entries.size} -> ${translated.entries.size}" }
        }

        // Check for untranslated entries
        val untranslatedCount = original.entries.zip(translated.entries).count { (orig, trans) ->
            orig.text.trim() == trans.text.trim()
        }
        if (untranslatedCount > original.entries.size * 0.3) {
            warnings.add(TranslationWarning.MixedLanguages(
                listOf(original.language.displayName, translated.language.displayName)
            ))
        }

        return warnings
    }

    /**
     * Preprocesses subtitle text for translation.
     * Handles special characters, normalizes spacing, etc.
     *
     * @param text Text to preprocess.
     * @return Preprocessed text.
     */
    fun preprocessText(text: String): String {
        return text
            // Normalize line breaks
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            // Normalize whitespace (but preserve newlines)
            .lines().joinToString("\n") { line ->
                line.trim().replace(Regex("\\s+"), " ")
            }
            // Remove timing markers that might have leaked into text
            .replace(Regex("\\[\\d+:\\d+:\\d+[,.]\\d+\\]"), "")
            .replace(Regex("\\d+:\\d+:\\d+[,.]\\d+ --> \\d+:\\d+:\\d+[,.]\\d+"), "")
    }

    /**
     * Postprocesses translated text.
     * Cleans up translation artifacts, ensures proper formatting.
     *
     * @param text Translated text.
     * @return Postprocessed text.
     */
    fun postprocessText(text: String): String {
        return text
            // Remove any translation artifacts
            .replace(Regex("^\\s*-\\s*"), "")
            // Normalize quotes
            .replace("「", "\"").replace("」", "\"")
            .replace("『", "\"").replace("』", "\"")
            // Ensure proper spacing after punctuation
            .replace(Regex("([.!?])([A-Z])"), "$1 $2")
            // Trim each line
            .lines().joinToString("\n") { it.trim() }
            .trim()
    }

    /**
     * Splits long subtitle entries for better translation.
     *
     * @param entry Original entry.
     * @param maxLength Maximum length per segment.
     * @return List of split entries (may be single if no split needed).
     */
    fun splitLongEntry(
        entry: SubtitleEntry,
        maxLength: Int = TranslationThresholds.MAX_SUBTITLE_LENGTH
    ): List<SubtitleEntry> {
        if (entry.text.length <= maxLength) {
            return listOf(entry)
        }

        val segments = splitTextAtSentences(entry.text, maxLength)
        if (segments.size == 1) {
            return listOf(entry)
        }

        // Distribute timing across segments
        val totalDuration = entry.endTime - entry.startTime
        val durationPerSegment = totalDuration / segments.size

        return segments.mapIndexed { index, text ->
            val segmentStart = entry.startTime + (durationPerSegment * index)
            val segmentEnd = if (index == segments.size - 1) {
                entry.endTime
            } else {
                segmentStart + durationPerSegment
            }

            SubtitleEntry(
                index = entry.index * 100 + index, // Preserve order with sub-indices
                startTime = segmentStart,
                endTime = segmentEnd,
                text = text.trim()
            )
        }
    }

    /**
     * Calculates the ratio of special characters in text.
     */
    private fun calculateSpecialCharRatio(text: String): Float {
        if (text.isEmpty()) return 0f

        val specialChars = text.count { !it.isLetterOrDigit() && !it.isWhitespace() }
        return specialChars.toFloat() / text.length
    }

    /**
     * Detects untranslatable content types.
     */
    private fun detectUntranslatableContent(subtitles: Subtitles): List<String> {
        val types = mutableListOf<String>()
        val allText = subtitles.entries.joinToString(" ") { it.text }

        // Musical notes/lyrics indicators
        if (allText.contains("♪") || allText.contains("♫") ||
            allText.contains("[Music]", ignoreCase = true) ||
            allText.contains("[Singing]", ignoreCase = true)) {
            types.add("music/lyrics")
        }

        // Sound effects
        if (allText.contains("[", ignoreCase = true) &&
            (allText.contains("sound", ignoreCase = true) ||
             allText.contains("noise", ignoreCase = true) ||
             allText.contains("applause", ignoreCase = true) ||
             allText.contains("laughter", ignoreCase = true))) {
            types.add("sound effects")
        }

        // Speaker labels
        if (Regex("^[A-Z]+:").containsMatchIn(allText) ||
            allText.contains(">>")) {
            types.add("speaker labels")
        }

        // URLs
        if (allText.contains("http://") || allText.contains("https://") ||
            allText.contains("www.")) {
            types.add("URLs")
        }

        // Timestamps
        if (Regex("\\d{1,2}:\\d{2}").containsMatchIn(allText)) {
            types.add("timestamps")
        }

        return types
    }

    /**
     * Estimates text expansion ratio for language pair.
     * Based on common translation expansion patterns.
     */
    private fun estimateTextExpansion(source: Language, target: Language): Float {
        // Rough estimates based on linguistic research
        return when {
            // CJK to European languages typically expand significantly
            source == Language.ENGLISH && target == Language.GERMAN -> 0.30f
            source == Language.ENGLISH && target == Language.FRENCH -> 0.20f
            source == Language.GERMAN && target == Language.ENGLISH -> -0.15f
            source == Language.FRENCH && target == Language.ENGLISH -> -0.10f
            else -> 0.15f // Default assumption
        }
    }

    /**
     * Splits text at sentence boundaries.
     */
    private fun splitTextAtSentences(text: String, maxLength: Int): List<String> {
        if (text.length <= maxLength) {
            return listOf(text)
        }

        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val result = mutableListOf<String>()
        var current = StringBuilder()

        for (sentence in sentences) {
            if (current.isEmpty()) {
                current.append(sentence)
            } else if (current.length + sentence.length + 1 <= maxLength) {
                current.append(" ").append(sentence)
            } else {
                result.add(current.toString())
                current = StringBuilder(sentence)
            }
        }

        if (current.isNotEmpty()) {
            result.add(current.toString())
        }

        // If still too long, split at word boundaries
        return result.flatMap { segment ->
            if (segment.length <= maxLength) {
                listOf(segment)
            } else {
                splitAtWords(segment, maxLength)
            }
        }
    }

    /**
     * Splits text at word boundaries.
     */
    private fun splitAtWords(text: String, maxLength: Int): List<String> {
        val words = text.split(" ")
        val result = mutableListOf<String>()
        var current = StringBuilder()

        for (word in words) {
            if (current.isEmpty()) {
                current.append(word)
            } else if (current.length + word.length + 1 <= maxLength) {
                current.append(" ").append(word)
            } else {
                result.add(current.toString())
                current = StringBuilder(word)
            }
        }

        if (current.isNotEmpty()) {
            result.add(current.toString())
        }

        return result
    }

    companion object {
        /**
         * Checks if a language pair is likely to need special handling.
         */
        fun requiresSpecialHandling(source: Language, target: Language): Boolean {
            // Currently all supported languages use Latin script
            // Add special handling for RTL, CJK, etc. when supported
            return false
        }

        /**
         * Gets recommended batch size for translation.
         */
        fun getRecommendedBatchSize(service: String): Int {
            return when (service.lowercase()) {
                "libretranslate" -> 10
                "deepl" -> 50
                "openai" -> 20
                "google" -> 100
                else -> 20
            }
        }
    }
}

/**
 * Extension to validate subtitles for translation.
 */
fun Subtitles.validateForTranslation(
    sourceLanguage: Language?,
    targetLanguage: Language
): TranslationValidationResult {
    return TranslationValidator().validateBeforeTranslation(this, sourceLanguage, targetLanguage)
}
