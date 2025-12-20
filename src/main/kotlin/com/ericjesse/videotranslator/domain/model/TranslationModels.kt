package com.ericjesse.videotranslator.domain.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Configuration for smart batching of translation requests.
 *
 * @property maxCharacters Maximum characters per batch (for LibreTranslate).
 * @property maxSegments Maximum segments per batch (for DeepL, max 50).
 * @property maxTokens Maximum estimated tokens per batch (for OpenAI).
 * @property contextSegments Number of previous segments to include for context.
 */
@Serializable
data class BatchConfig(
    val maxCharacters: Int = 5000,
    val maxSegments: Int = 50,
    val maxTokens: Int = 3000,
    val contextSegments: Int = 2
)

/**
 * A batch of text segments to be translated together.
 *
 * @property segments Text segments in this batch.
 * @property contextPrefix Previous translated segments for context.
 * @property startIndex Starting index in the original segment list.
 * @property totalCharacters Total character count in the batch.
 * @property estimatedTokens Estimated token count for the batch.
 */
data class TranslationBatch(
    val segments: List<String>,
    val contextPrefix: List<TranslatedContext> = emptyList(),
    val startIndex: Int,
    val totalCharacters: Int,
    val estimatedTokens: Int
)

/**
 * Context from previous translation for continuity.
 *
 * @property original Original text.
 * @property translated Translated text.
 */
data class TranslatedContext(
    val original: String,
    val translated: String
)

/**
 * Glossary entry for technical term translation.
 *
 * @property source Source language term.
 * @property target Target language term.
 * @property caseSensitive Whether matching should be case-sensitive.
 * @property wholeWord Whether to match whole words only.
 */
@Serializable
data class GlossaryEntry(
    val source: String,
    val target: String,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = true
)

/**
 * A glossary of technical terms for consistent translation.
 *
 * @property name Name of this glossary.
 * @property sourceLanguage Source language code.
 * @property targetLanguage Target language code.
 * @property entries Glossary entries.
 */
@Serializable
data class Glossary(
    val name: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val entries: List<GlossaryEntry>
) {
    /**
     * Applies glossary replacements to text before translation.
     * Replaces source terms with placeholder markers.
     */
    fun applyPreTranslation(text: String): Pair<String, Map<String, String>> {
        var result = text
        val replacements = mutableMapOf<String, String>()

        entries.forEachIndexed { index, entry ->
            val placeholder = "⟦GLOSS_${index}⟧"
            val pattern = buildPattern(entry)

            if (pattern.containsMatchIn(result)) {
                replacements[placeholder] = entry.target
                result = pattern.replace(result, placeholder)
            }
        }

        return result to replacements
    }

    /**
     * Applies glossary replacements after translation.
     * Replaces placeholder markers with target terms.
     */
    fun applyPostTranslation(text: String, replacements: Map<String, String>): String {
        var result = text
        replacements.forEach { (placeholder, target) ->
            result = result.replace(placeholder, target)
        }
        return result
    }

    private fun buildPattern(entry: GlossaryEntry): Regex {
        val escapedSource = Regex.escape(entry.source)
        val pattern = if (entry.wholeWord) {
            "\\b$escapedSource\\b"
        } else {
            escapedSource
        }
        val options = if (entry.caseSensitive) {
            emptySet()
        } else {
            setOf(RegexOption.IGNORE_CASE)
        }
        return Regex(pattern, options)
    }
}

/**
 * Cache for storing translated segments to avoid re-translation.
 * Thread-safe implementation using ConcurrentHashMap.
 *
 * @property maxSize Maximum number of entries to cache.
 */
class TranslationCache(private val maxSize: Int = 10000) {

    private data class CacheKey(
        val textHash: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val service: String
    )

    private data class CacheEntry(
        val translation: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()

    /**
     * Gets a cached translation if available.
     */
    fun get(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        service: String
    ): String? {
        val key = createKey(text, sourceLanguage, targetLanguage, service)
        return cache[key]?.translation
    }

    /**
     * Stores a translation in the cache.
     */
    fun put(
        text: String,
        translation: String,
        sourceLanguage: String,
        targetLanguage: String,
        service: String
    ) {
        // Evict oldest entries if at capacity
        if (cache.size >= maxSize) {
            evictOldest(maxSize / 10) // Remove 10% of entries
        }

        val key = createKey(text, sourceLanguage, targetLanguage, service)
        cache[key] = CacheEntry(translation)
    }

    /**
     * Gets multiple cached translations at once.
     * Returns a map of text to translation for found entries.
     */
    fun getMultiple(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
        service: String
    ): Map<String, String> {
        return texts.mapNotNull { text ->
            get(text, sourceLanguage, targetLanguage, service)?.let { text to it }
        }.toMap()
    }

    /**
     * Stores multiple translations at once.
     */
    fun putMultiple(
        translations: Map<String, String>,
        sourceLanguage: String,
        targetLanguage: String,
        service: String
    ) {
        translations.forEach { (text, translation) ->
            put(text, translation, sourceLanguage, targetLanguage, service)
        }
    }

    /**
     * Clears all cached entries.
     */
    fun clear() {
        cache.clear()
    }

    /**
     * Returns the current cache size.
     */
    fun size(): Int = cache.size

    private fun createKey(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        service: String
    ): CacheKey {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return CacheKey(hash, sourceLanguage, targetLanguage, service)
    }

    private fun evictOldest(count: Int) {
        cache.entries
            .sortedBy { it.value.timestamp }
            .take(count)
            .forEach { cache.remove(it.key) }
    }
}

/**
 * Formatting markers that should be preserved during translation.
 */
object FormattingPreserver {

    private data class Placeholder(
        val marker: String,
        val original: String
    )

    // Common subtitle formatting tags
    private val FORMATTING_PATTERNS = listOf(
        Regex("""<i>.*?</i>""", RegexOption.DOT_MATCHES_ALL),
        Regex("""<b>.*?</b>""", RegexOption.DOT_MATCHES_ALL),
        Regex("""<u>.*?</u>""", RegexOption.DOT_MATCHES_ALL),
        Regex("""<font[^>]*>.*?</font>""", RegexOption.DOT_MATCHES_ALL),
        Regex("""\{\\[^}]+\}"""), // ASS/SSA tags like {\i1}
        Regex("""♪.*?♪""", RegexOption.DOT_MATCHES_ALL), // Music notes
    )

    // Tags that wrap content - we preserve the tag but translate content
    private val CONTENT_TAGS = listOf(
        Triple("<i>", "</i>", "ITALIC"),
        Triple("<b>", "</b>", "BOLD"),
        Triple("<u>", "</u>", "UNDERLINE")
    )

    /**
     * Extracts and replaces formatting with placeholders.
     * Returns the modified text and a function to restore formatting.
     */
    fun extractFormatting(text: String): Pair<String, (String) -> String> {
        var result = text
        val placeholders = mutableListOf<Placeholder>()
        var placeholderIndex = 0

        // Handle content tags specially - extract tag markers but keep content
        for ((openTag, closeTag, name) in CONTENT_TAGS) {
            val tagPattern = Regex("""${Regex.escape(openTag)}(.*?)${Regex.escape(closeTag)}""",
                RegexOption.DOT_MATCHES_ALL)

            result = tagPattern.replace(result) { match ->
                val openMarker = "⟦${name}_O_${placeholderIndex}⟧"
                val closeMarker = "⟦${name}_C_${placeholderIndex}⟧"
                placeholders.add(Placeholder(openMarker, openTag))
                placeholders.add(Placeholder(closeMarker, closeTag))
                placeholderIndex++
                "$openMarker${match.groupValues[1]}$closeMarker"
            }
        }

        // Handle ASS/SSA tags - preserve completely
        val assPattern = Regex("""\{\\[^}]+\}""")
        result = assPattern.replace(result) { match ->
            val marker = "⟦ASS_${placeholderIndex++}⟧"
            placeholders.add(Placeholder(marker, match.value))
            marker
        }

        // Handle music symbols
        val musicPattern = Regex("""♪""")
        result = musicPattern.replace(result) { match ->
            val marker = "⟦MUSIC_${placeholderIndex++}⟧"
            placeholders.add(Placeholder(marker, match.value))
            marker
        }

        // Return restore function
        val restoreFunction: (String) -> String = { translatedText ->
            var restored = translatedText
            placeholders.forEach { placeholder ->
                restored = restored.replace(placeholder.marker, placeholder.original)
            }
            restored
        }

        return result to restoreFunction
    }

    /**
     * Preserves line breaks by converting to markers and back.
     */
    fun preserveLineBreaks(text: String): Pair<String, (String) -> String> {
        val marker = "⟦NL⟧"
        val modified = text.replace("\n", " $marker ")

        val restoreFunction: (String) -> String = { translatedText ->
            translatedText
                .replace(" $marker ", "\n")
                .replace("$marker ", "\n")
                .replace(" $marker", "\n")
                .replace(marker, "\n")
        }

        return modified to restoreFunction
    }

    /**
     * Combines formatting and line break preservation.
     */
    fun preserveAll(text: String): Pair<String, (String) -> String> {
        val (formattingPreserved, restoreFormatting) = extractFormatting(text)
        val (lineBreaksPreserved, restoreLineBreaks) = preserveLineBreaks(formattingPreserved)

        val combinedRestore: (String) -> String = { translatedText ->
            restoreFormatting(restoreLineBreaks(translatedText))
        }

        return lineBreaksPreserved to combinedRestore
    }
}

/**
 * Rate limit handling configuration.
 *
 * @property initialDelayMs Initial delay in milliseconds for backoff.
 * @property maxDelayMs Maximum delay in milliseconds.
 * @property multiplier Multiplier for exponential backoff.
 * @property maxRetries Maximum number of retries before failing.
 */
@Serializable
data class RateLimitConfig(
    val initialDelayMs: Long = 1000,
    val maxDelayMs: Long = 60000,
    val multiplier: Double = 2.0,
    val maxRetries: Int = 5
)

/**
 * Tracks rate limit state for exponential backoff.
 */
class RateLimitTracker(private val config: RateLimitConfig = RateLimitConfig()) {

    private var consecutiveFailures = 0
    private var lastFailureTime = 0L

    /**
     * Records a rate limit failure and returns the delay to wait.
     */
    fun recordFailure(retryAfterSeconds: Int?): Long {
        consecutiveFailures++
        lastFailureTime = System.currentTimeMillis()

        // Use server-provided retry-after if available
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            return retryAfterSeconds * 1000L
        }

        // Otherwise use exponential backoff
        val delay = (config.initialDelayMs * Math.pow(config.multiplier,
            (consecutiveFailures - 1).toDouble())).toLong()
        return delay.coerceAtMost(config.maxDelayMs)
    }

    /**
     * Records a successful request, resetting the failure count.
     */
    fun recordSuccess() {
        consecutiveFailures = 0
    }

    /**
     * Checks if we should give up after too many failures.
     */
    fun shouldGiveUp(): Boolean = consecutiveFailures >= config.maxRetries

    /**
     * Resets the tracker state.
     */
    fun reset() {
        consecutiveFailures = 0
        lastFailureTime = 0L
    }
}

/**
 * Result of a translation API call.
 */
sealed class TranslationApiResult {
    /**
     * Successful translation.
     */
    data class Success(val translations: List<String>) : TranslationApiResult()

    /**
     * Rate limited - should retry after delay.
     */
    data class RateLimited(val retryAfterSeconds: Int?) : TranslationApiResult()

    /**
     * Service error - may want to try fallback.
     */
    data class ServiceError(
        val message: String,
        val isRetryable: Boolean,
        val cause: Throwable? = null
    ) : TranslationApiResult()

    /**
     * Configuration error - service not properly configured.
     */
    data class ConfigurationError(val message: String) : TranslationApiResult()
}

/**
 * Available translation services in order of preference.
 */
enum class TranslationService(val displayName: String) {
    LIBRE_TRANSLATE("LibreTranslate"),
    DEEPL("DeepL"),
    OPENAI("OpenAI"),
    GOOGLE("Google Translate");

    companion object {
        fun fromString(value: String): TranslationService? {
            return when (value.lowercase()) {
                "libretranslate" -> LIBRE_TRANSLATE
                "deepl" -> DEEPL
                "openai" -> OPENAI
                "google" -> GOOGLE
                else -> null
            }
        }
    }
}

/**
 * Statistics about a translation operation.
 *
 * @property totalSegments Total number of segments translated.
 * @property cachedSegments Number of segments served from cache.
 * @property apiCalls Number of API calls made.
 * @property totalCharacters Total characters translated.
 * @property durationMs Time taken in milliseconds.
 * @property serviceUsed Primary service used.
 * @property fallbacksUsed List of fallback services used.
 */
data class TranslationStats(
    val totalSegments: Int = 0,
    val cachedSegments: Int = 0,
    val apiCalls: Int = 0,
    val totalCharacters: Int = 0,
    val durationMs: Long = 0,
    val serviceUsed: TranslationService? = null,
    val fallbacksUsed: List<TranslationService> = emptyList()
)

/**
 * Exception thrown during translation with context about what failed.
 *
 * @property service Service that failed.
 * @property isRetryable Whether the operation can be retried.
 * @property userMessage User-friendly error message.
 */
class TranslationException(
    val service: TranslationService?,
    val isRetryable: Boolean,
    val userMessage: String,
    cause: Throwable? = null
) : Exception(userMessage, cause) {

    companion object {
        fun fromResult(result: TranslationApiResult, service: TranslationService): TranslationException {
            return when (result) {
                is TranslationApiResult.ServiceError -> TranslationException(
                    service = service,
                    isRetryable = result.isRetryable,
                    userMessage = result.message,
                    cause = result.cause
                )
                is TranslationApiResult.ConfigurationError -> TranslationException(
                    service = service,
                    isRetryable = false,
                    userMessage = result.message
                )
                is TranslationApiResult.RateLimited -> TranslationException(
                    service = service,
                    isRetryable = true,
                    userMessage = "Rate limited by ${service.displayName}"
                )
                is TranslationApiResult.Success -> throw IllegalArgumentException(
                    "Cannot create exception from success result"
                )
            }
        }
    }
}
