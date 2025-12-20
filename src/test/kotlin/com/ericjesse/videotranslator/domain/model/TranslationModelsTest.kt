package com.ericjesse.videotranslator.domain.model

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.*

class TranslationModelsTest {

    // ==================== BatchConfig Tests ====================

    @Nested
    inner class BatchConfigTest {

        @Test
        fun `default values are set correctly`() {
            val config = BatchConfig()

            assertEquals(5000, config.maxCharacters)
            assertEquals(50, config.maxSegments)
            assertEquals(3000, config.maxTokens)
            assertEquals(2, config.contextSegments)
        }

        @Test
        fun `custom values are applied`() {
            val config = BatchConfig(
                maxCharacters = 10000,
                maxSegments = 100,
                maxTokens = 5000,
                contextSegments = 5
            )

            assertEquals(10000, config.maxCharacters)
            assertEquals(100, config.maxSegments)
            assertEquals(5000, config.maxTokens)
            assertEquals(5, config.contextSegments)
        }
    }

    // ==================== TranslationBatch Tests ====================

    @Nested
    inner class TranslationBatchTest {

        @Test
        fun `batch stores segments correctly`() {
            val segments = listOf("Hello", "World")
            val batch = TranslationBatch(
                segments = segments,
                startIndex = 0,
                totalCharacters = 10,
                estimatedTokens = 5
            )

            assertEquals(segments, batch.segments)
            assertEquals(0, batch.startIndex)
            assertEquals(10, batch.totalCharacters)
            assertEquals(5, batch.estimatedTokens)
            assertTrue(batch.contextPrefix.isEmpty())
        }

        @Test
        fun `batch stores context prefix`() {
            val context = listOf(
                TranslatedContext("Hello", "Hallo"),
                TranslatedContext("World", "Welt")
            )
            val batch = TranslationBatch(
                segments = listOf("Test"),
                contextPrefix = context,
                startIndex = 2,
                totalCharacters = 4,
                estimatedTokens = 2
            )

            assertEquals(2, batch.contextPrefix.size)
            assertEquals("Hello", batch.contextPrefix[0].original)
            assertEquals("Hallo", batch.contextPrefix[0].translated)
        }
    }

    // ==================== TranslatedContext Tests ====================

    @Nested
    inner class TranslatedContextTest {

        @Test
        fun `stores original and translated text`() {
            val context = TranslatedContext(
                original = "Hello world",
                translated = "Hallo Welt"
            )

            assertEquals("Hello world", context.original)
            assertEquals("Hallo Welt", context.translated)
        }
    }

    // ==================== GlossaryEntry Tests ====================

    @Nested
    inner class GlossaryEntryTest {

        @Test
        fun `default values are set correctly`() {
            val entry = GlossaryEntry(source = "API", target = "API")

            assertFalse(entry.caseSensitive)
            assertTrue(entry.wholeWord)
        }

        @Test
        fun `custom values are applied`() {
            val entry = GlossaryEntry(
                source = "JavaScript",
                target = "JavaScript",
                caseSensitive = true,
                wholeWord = false
            )

            assertTrue(entry.caseSensitive)
            assertFalse(entry.wholeWord)
        }
    }

    // ==================== Glossary Tests ====================

    @Nested
    inner class GlossaryTest {

        @Test
        fun `applyPreTranslation replaces terms with placeholders`() {
            val glossary = Glossary(
                name = "Tech Terms",
                sourceLanguage = "en",
                targetLanguage = "de",
                entries = listOf(
                    GlossaryEntry("API", "API"),
                    GlossaryEntry("SDK", "SDK")
                )
            )

            val (processed, replacements) = glossary.applyPreTranslation("The API and SDK are great")

            assertTrue(processed.contains("⟦GLOSS_0⟧"))
            assertTrue(processed.contains("⟦GLOSS_1⟧"))
            assertFalse(processed.contains("API"))
            assertFalse(processed.contains("SDK"))
            assertEquals(2, replacements.size)
        }

        @Test
        fun `applyPostTranslation restores terms`() {
            val glossary = Glossary(
                name = "Tech Terms",
                sourceLanguage = "en",
                targetLanguage = "de",
                entries = listOf(GlossaryEntry("API", "API"))
            )

            val replacements = mapOf("⟦GLOSS_0⟧" to "API")
            val result = glossary.applyPostTranslation(
                "Das ⟦GLOSS_0⟧ ist toll",
                replacements
            )

            assertEquals("Das API ist toll", result)
        }

        @Test
        fun `case insensitive matching works`() {
            val glossary = Glossary(
                name = "Terms",
                sourceLanguage = "en",
                targetLanguage = "de",
                entries = listOf(
                    GlossaryEntry("api", "API", caseSensitive = false)
                )
            )

            val (processed, replacements) = glossary.applyPreTranslation("The API is great")

            assertTrue(processed.contains("⟦GLOSS_0⟧"))
            assertEquals(1, replacements.size)
        }

        @Test
        fun `case sensitive matching works`() {
            val glossary = Glossary(
                name = "Terms",
                sourceLanguage = "en",
                targetLanguage = "de",
                entries = listOf(
                    GlossaryEntry("API", "API", caseSensitive = true)
                )
            )

            val (processed1, replacements1) = glossary.applyPreTranslation("The API is great")
            val (processed2, replacements2) = glossary.applyPreTranslation("The api is great")

            assertEquals(1, replacements1.size)
            assertEquals(0, replacements2.size)
            assertTrue(processed2.contains("api"))
        }

        @Test
        fun `whole word matching works`() {
            val glossary = Glossary(
                name = "Terms",
                sourceLanguage = "en",
                targetLanguage = "de",
                entries = listOf(
                    GlossaryEntry("API", "API", wholeWord = true)
                )
            )

            val (processed1, replacements1) = glossary.applyPreTranslation("The API is great")
            val (processed2, replacements2) = glossary.applyPreTranslation("RAPIDAPI is great")

            assertEquals(1, replacements1.size)
            assertEquals(0, replacements2.size)
        }

        @Test
        fun `partial word matching works`() {
            val glossary = Glossary(
                name = "Terms",
                sourceLanguage = "en",
                targetLanguage = "de",
                entries = listOf(
                    GlossaryEntry("Script", "Script", wholeWord = false)
                )
            )

            val (processed, replacements) = glossary.applyPreTranslation("JavaScript is TypeScript")

            // One glossary entry creates one replacement (even though it matches multiple times)
            assertEquals(1, replacements.size)
            // Verify both occurrences were replaced
            assertFalse(processed.contains("Script"))
            assertTrue(processed.contains("⟦GLOSS_0⟧"))
            // Should have replaced both occurrences
            assertEquals(2, processed.split("⟦GLOSS_0⟧").size - 1)
        }

        @Test
        fun `empty text returns empty result`() {
            val glossary = Glossary(
                name = "Terms",
                sourceLanguage = "en",
                targetLanguage = "de",
                entries = listOf(GlossaryEntry("API", "API"))
            )

            val (processed, replacements) = glossary.applyPreTranslation("")

            assertEquals("", processed)
            assertTrue(replacements.isEmpty())
        }

        @Test
        fun `no matches returns original text`() {
            val glossary = Glossary(
                name = "Terms",
                sourceLanguage = "en",
                targetLanguage = "de",
                entries = listOf(GlossaryEntry("API", "API"))
            )

            val (processed, replacements) = glossary.applyPreTranslation("Hello world")

            assertEquals("Hello world", processed)
            assertTrue(replacements.isEmpty())
        }
    }

    // ==================== TranslationCache Tests ====================

    @Nested
    inner class TranslationCacheTest {

        @Test
        fun `put and get work correctly`() {
            val cache = TranslationCache()

            cache.put("Hello", "Hallo", "en", "de", "deepl")
            val result = cache.get("Hello", "en", "de", "deepl")

            assertEquals("Hallo", result)
        }

        @Test
        fun `get returns null for missing entry`() {
            val cache = TranslationCache()

            val result = cache.get("Hello", "en", "de", "deepl")

            assertNull(result)
        }

        @Test
        fun `different languages are separate cache entries`() {
            val cache = TranslationCache()

            cache.put("Hello", "Hallo", "en", "de", "deepl")
            cache.put("Hello", "Bonjour", "en", "fr", "deepl")

            assertEquals("Hallo", cache.get("Hello", "en", "de", "deepl"))
            assertEquals("Bonjour", cache.get("Hello", "en", "fr", "deepl"))
        }

        @Test
        fun `different services are separate cache entries`() {
            val cache = TranslationCache()

            cache.put("Hello", "Hallo1", "en", "de", "deepl")
            cache.put("Hello", "Hallo2", "en", "de", "openai")

            assertEquals("Hallo1", cache.get("Hello", "en", "de", "deepl"))
            assertEquals("Hallo2", cache.get("Hello", "en", "de", "openai"))
        }

        @Test
        fun `getMultiple returns cached entries`() {
            val cache = TranslationCache()

            cache.put("Hello", "Hallo", "en", "de", "deepl")
            cache.put("World", "Welt", "en", "de", "deepl")

            val results = cache.getMultiple(
                listOf("Hello", "World", "Missing"),
                "en", "de", "deepl"
            )

            assertEquals(2, results.size)
            assertEquals("Hallo", results["Hello"])
            assertEquals("Welt", results["World"])
            assertNull(results["Missing"])
        }

        @Test
        fun `putMultiple stores all entries`() {
            val cache = TranslationCache()

            cache.putMultiple(
                mapOf("Hello" to "Hallo", "World" to "Welt"),
                "en", "de", "deepl"
            )

            assertEquals("Hallo", cache.get("Hello", "en", "de", "deepl"))
            assertEquals("Welt", cache.get("World", "en", "de", "deepl"))
        }

        @Test
        fun `clear removes all entries`() {
            val cache = TranslationCache()

            cache.put("Hello", "Hallo", "en", "de", "deepl")
            cache.put("World", "Welt", "en", "de", "deepl")
            assertEquals(2, cache.size())

            cache.clear()

            assertEquals(0, cache.size())
            assertNull(cache.get("Hello", "en", "de", "deepl"))
        }

        @Test
        fun `size returns correct count`() {
            val cache = TranslationCache()

            assertEquals(0, cache.size())

            cache.put("Hello", "Hallo", "en", "de", "deepl")
            assertEquals(1, cache.size())

            cache.put("World", "Welt", "en", "de", "deepl")
            assertEquals(2, cache.size())
        }

        @Test
        fun `eviction occurs when max size reached`() {
            val cache = TranslationCache(maxSize = 10)

            // Add 15 entries
            repeat(15) { i ->
                cache.put("text$i", "translation$i", "en", "de", "deepl")
            }

            // Should have evicted some entries
            assertTrue(cache.size() <= 10)
        }
    }

    // ==================== FormattingPreserver Tests ====================

    @Nested
    inner class FormattingPreserverTest {

        @Test
        fun `extractFormatting preserves italic tags`() {
            val (processed, restore) = FormattingPreserver.extractFormatting("<i>Hello</i> world")

            assertTrue(processed.contains("⟦ITALIC_O_"))
            assertTrue(processed.contains("⟦ITALIC_C_"))
            assertTrue(processed.contains("Hello"))
            assertTrue(processed.contains("world"))

            val restored = restore(processed)
            assertEquals("<i>Hello</i> world", restored)
        }

        @Test
        fun `extractFormatting preserves bold tags`() {
            val (processed, restore) = FormattingPreserver.extractFormatting("<b>Bold</b> text")

            assertTrue(processed.contains("⟦BOLD_O_"))
            assertTrue(processed.contains("⟦BOLD_C_"))

            val restored = restore(processed)
            assertEquals("<b>Bold</b> text", restored)
        }

        @Test
        fun `extractFormatting preserves underline tags`() {
            val (processed, restore) = FormattingPreserver.extractFormatting("<u>Underlined</u>")

            assertTrue(processed.contains("⟦UNDERLINE_O_"))
            assertTrue(processed.contains("⟦UNDERLINE_C_"))

            val restored = restore(processed)
            assertEquals("<u>Underlined</u>", restored)
        }

        @Test
        fun `extractFormatting preserves ASS tags`() {
            val (processed, restore) = FormattingPreserver.extractFormatting("{\\i1}Italic{\\i0}")

            assertTrue(processed.contains("⟦ASS_"))
            assertFalse(processed.contains("{\\i1}"))

            val restored = restore(processed)
            assertEquals("{\\i1}Italic{\\i0}", restored)
        }

        @Test
        fun `extractFormatting preserves music symbols`() {
            val (processed, restore) = FormattingPreserver.extractFormatting("♪ Music playing ♪")

            assertTrue(processed.contains("⟦MUSIC_"))
            assertFalse(processed.contains("♪"))

            val restored = restore(processed)
            assertEquals("♪ Music playing ♪", restored)
        }

        @Test
        fun `extractFormatting handles nested tags`() {
            val (processed, restore) = FormattingPreserver.extractFormatting("<i><b>Bold italic</b></i>")

            val restored = restore(processed)
            assertEquals("<i><b>Bold italic</b></i>", restored)
        }

        @Test
        fun `extractFormatting handles text without formatting`() {
            val (processed, restore) = FormattingPreserver.extractFormatting("Plain text")

            assertEquals("Plain text", processed)
            assertEquals("Plain text", restore(processed))
        }

        @Test
        fun `preserveLineBreaks converts newlines to markers`() {
            val (processed, restore) = FormattingPreserver.preserveLineBreaks("Line 1\nLine 2")

            assertTrue(processed.contains("⟦NL⟧"))
            assertFalse(processed.contains("\n"))

            val restored = restore(processed)
            assertEquals("Line 1\nLine 2", restored)
        }

        @Test
        fun `preserveLineBreaks handles multiple newlines`() {
            val (processed, restore) = FormattingPreserver.preserveLineBreaks("A\nB\nC")

            val restored = restore(processed)
            assertEquals("A\nB\nC", restored)
        }

        @Test
        fun `preserveAll combines formatting and line breaks`() {
            val text = "<i>Line 1</i>\n<b>Line 2</b>"
            val (processed, restore) = FormattingPreserver.preserveAll(text)

            assertTrue(processed.contains("⟦ITALIC_O_"))
            assertTrue(processed.contains("⟦NL⟧"))

            val restored = restore(processed)
            assertEquals(text, restored)
        }

        @Test
        fun `preserveAll handles empty string`() {
            val (processed, restore) = FormattingPreserver.preserveAll("")

            assertEquals("", processed)
            assertEquals("", restore(""))
        }
    }

    // ==================== RateLimitConfig Tests ====================

    @Nested
    inner class RateLimitConfigTest {

        @Test
        fun `default values are set correctly`() {
            val config = RateLimitConfig()

            assertEquals(1000L, config.initialDelayMs)
            assertEquals(60000L, config.maxDelayMs)
            assertEquals(2.0, config.multiplier)
            assertEquals(5, config.maxRetries)
        }

        @Test
        fun `custom values are applied`() {
            val config = RateLimitConfig(
                initialDelayMs = 500,
                maxDelayMs = 30000,
                multiplier = 1.5,
                maxRetries = 3
            )

            assertEquals(500L, config.initialDelayMs)
            assertEquals(30000L, config.maxDelayMs)
            assertEquals(1.5, config.multiplier)
            assertEquals(3, config.maxRetries)
        }
    }

    // ==================== RateLimitTracker Tests ====================

    @Nested
    inner class RateLimitTrackerTest {

        @Test
        fun `recordFailure uses server-provided retry-after`() {
            val tracker = RateLimitTracker()

            val delay = tracker.recordFailure(30)

            assertEquals(30000L, delay)
        }

        @Test
        fun `recordFailure uses exponential backoff without retry-after`() {
            val config = RateLimitConfig(initialDelayMs = 1000, multiplier = 2.0)
            val tracker = RateLimitTracker(config)

            assertEquals(1000L, tracker.recordFailure(null)) // 1st failure
            assertEquals(2000L, tracker.recordFailure(null)) // 2nd failure
            assertEquals(4000L, tracker.recordFailure(null)) // 3rd failure
        }

        @Test
        fun `recordFailure respects maxDelay`() {
            val config = RateLimitConfig(initialDelayMs = 10000, maxDelayMs = 15000, multiplier = 2.0)
            val tracker = RateLimitTracker(config)

            tracker.recordFailure(null)
            val delay = tracker.recordFailure(null) // Would be 20000 without cap

            assertEquals(15000L, delay)
        }

        @Test
        fun `recordSuccess resets failure count`() {
            val config = RateLimitConfig(initialDelayMs = 1000, multiplier = 2.0)
            val tracker = RateLimitTracker(config)

            tracker.recordFailure(null)
            tracker.recordFailure(null)
            tracker.recordSuccess()

            // Should restart from initial delay
            assertEquals(1000L, tracker.recordFailure(null))
        }

        @Test
        fun `shouldGiveUp returns false initially`() {
            val tracker = RateLimitTracker()

            assertFalse(tracker.shouldGiveUp())
        }

        @Test
        fun `shouldGiveUp returns true after max retries`() {
            val config = RateLimitConfig(maxRetries = 3)
            val tracker = RateLimitTracker(config)

            tracker.recordFailure(null)
            tracker.recordFailure(null)
            tracker.recordFailure(null)

            assertTrue(tracker.shouldGiveUp())
        }

        @Test
        fun `reset clears all state`() {
            val tracker = RateLimitTracker()

            tracker.recordFailure(null)
            tracker.recordFailure(null)
            tracker.reset()

            assertFalse(tracker.shouldGiveUp())
            assertEquals(1000L, tracker.recordFailure(null)) // Back to initial
        }
    }

    // ==================== TranslationApiResult Tests ====================

    @Nested
    inner class TranslationApiResultTest {

        @Test
        fun `Success stores translations`() {
            val result = TranslationApiResult.Success(listOf("Hallo", "Welt"))

            assertEquals(2, result.translations.size)
            assertEquals("Hallo", result.translations[0])
            assertEquals("Welt", result.translations[1])
        }

        @Test
        fun `RateLimited stores retry after seconds`() {
            val result = TranslationApiResult.RateLimited(30)

            assertEquals(30, result.retryAfterSeconds)
        }

        @Test
        fun `RateLimited allows null retry after`() {
            val result = TranslationApiResult.RateLimited(null)

            assertNull(result.retryAfterSeconds)
        }

        @Test
        fun `ServiceError stores all fields`() {
            val cause = RuntimeException("Network error")
            val result = TranslationApiResult.ServiceError(
                message = "Connection failed",
                isRetryable = true,
                cause = cause
            )

            assertEquals("Connection failed", result.message)
            assertTrue(result.isRetryable)
            assertEquals(cause, result.cause)
        }

        @Test
        fun `ConfigurationError stores message`() {
            val result = TranslationApiResult.ConfigurationError("API key missing")

            assertEquals("API key missing", result.message)
        }
    }

    // ==================== TranslationService Tests ====================

    @Nested
    inner class TranslationServiceTest {

        @Test
        fun `fromString returns correct service for libretranslate`() {
            assertEquals(TranslationService.LIBRE_TRANSLATE, TranslationService.fromString("libretranslate"))
            assertEquals(TranslationService.LIBRE_TRANSLATE, TranslationService.fromString("LIBRETRANSLATE"))
            assertEquals(TranslationService.LIBRE_TRANSLATE, TranslationService.fromString("LibreTranslate"))
        }

        @Test
        fun `fromString returns correct service for deepl`() {
            assertEquals(TranslationService.DEEPL, TranslationService.fromString("deepl"))
            assertEquals(TranslationService.DEEPL, TranslationService.fromString("DEEPL"))
        }

        @Test
        fun `fromString returns correct service for openai`() {
            assertEquals(TranslationService.OPENAI, TranslationService.fromString("openai"))
            assertEquals(TranslationService.OPENAI, TranslationService.fromString("OpenAI"))
        }

        @Test
        fun `fromString returns correct service for google`() {
            assertEquals(TranslationService.GOOGLE, TranslationService.fromString("google"))
            assertEquals(TranslationService.GOOGLE, TranslationService.fromString("GOOGLE"))
        }

        @Test
        fun `fromString returns null for unknown service`() {
            assertNull(TranslationService.fromString("unknown"))
            assertNull(TranslationService.fromString(""))
        }

        @Test
        fun `displayName is set correctly`() {
            assertEquals("LibreTranslate", TranslationService.LIBRE_TRANSLATE.displayName)
            assertEquals("DeepL", TranslationService.DEEPL.displayName)
            assertEquals("OpenAI", TranslationService.OPENAI.displayName)
            assertEquals("Google Translate", TranslationService.GOOGLE.displayName)
        }
    }

    // ==================== TranslationStats Tests ====================

    @Nested
    inner class TranslationStatsTest {

        @Test
        fun `default values are set correctly`() {
            val stats = TranslationStats()

            assertEquals(0, stats.totalSegments)
            assertEquals(0, stats.cachedSegments)
            assertEquals(0, stats.apiCalls)
            assertEquals(0, stats.totalCharacters)
            assertEquals(0L, stats.durationMs)
            assertNull(stats.serviceUsed)
            assertTrue(stats.fallbacksUsed.isEmpty())
        }

        @Test
        fun `custom values are applied`() {
            val stats = TranslationStats(
                totalSegments = 100,
                cachedSegments = 20,
                apiCalls = 5,
                totalCharacters = 5000,
                durationMs = 3000,
                serviceUsed = TranslationService.DEEPL,
                fallbacksUsed = listOf(TranslationService.OPENAI)
            )

            assertEquals(100, stats.totalSegments)
            assertEquals(20, stats.cachedSegments)
            assertEquals(5, stats.apiCalls)
            assertEquals(5000, stats.totalCharacters)
            assertEquals(3000L, stats.durationMs)
            assertEquals(TranslationService.DEEPL, stats.serviceUsed)
            assertEquals(1, stats.fallbacksUsed.size)
        }
    }

    // ==================== TranslationException Tests ====================

    @Nested
    inner class TranslationExceptionTest {

        @Test
        fun `fromResult creates exception from ServiceError`() {
            val result = TranslationApiResult.ServiceError(
                message = "Connection failed",
                isRetryable = true,
                cause = RuntimeException("Network error")
            )

            val exception = TranslationException.fromResult(result, TranslationService.DEEPL)

            assertEquals(TranslationService.DEEPL, exception.service)
            assertTrue(exception.isRetryable)
            assertEquals("Connection failed", exception.userMessage)
            assertNotNull(exception.cause)
        }

        @Test
        fun `fromResult creates exception from ConfigurationError`() {
            val result = TranslationApiResult.ConfigurationError("API key missing")

            val exception = TranslationException.fromResult(result, TranslationService.OPENAI)

            assertEquals(TranslationService.OPENAI, exception.service)
            assertFalse(exception.isRetryable)
            assertEquals("API key missing", exception.userMessage)
        }

        @Test
        fun `fromResult creates exception from RateLimited`() {
            val result = TranslationApiResult.RateLimited(30)

            val exception = TranslationException.fromResult(result, TranslationService.GOOGLE)

            assertEquals(TranslationService.GOOGLE, exception.service)
            assertTrue(exception.isRetryable)
            assertEquals("Rate limited by Google Translate", exception.userMessage)
        }

        @Test
        fun `fromResult throws for Success result`() {
            val result = TranslationApiResult.Success(listOf("test"))

            assertFailsWith<IllegalArgumentException> {
                TranslationException.fromResult(result, TranslationService.DEEPL)
            }
        }

        @Test
        fun `exception message is user message`() {
            val exception = TranslationException(
                service = TranslationService.DEEPL,
                isRetryable = false,
                userMessage = "Custom error message"
            )

            assertEquals("Custom error message", exception.message)
        }

        @Test
        fun `exception allows null service`() {
            val exception = TranslationException(
                service = null,
                isRetryable = false,
                userMessage = "Unknown service failed"
            )

            assertNull(exception.service)
        }
    }
}
