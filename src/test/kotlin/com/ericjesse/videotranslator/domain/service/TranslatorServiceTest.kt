package com.ericjesse.videotranslator.domain.service

import com.ericjesse.videotranslator.domain.model.*
import com.ericjesse.videotranslator.infrastructure.config.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import io.mockk.*
import kotlin.test.*

class TranslatorServiceTest {

    private lateinit var configManager: ConfigManager
    private lateinit var translatorService: TranslatorService

    private fun createMockHttpClient(
        handler: MockRequestHandler
    ): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler(handler)
            }
        }
    }

    private fun createSubtitles(texts: List<String>, language: Language = Language.ENGLISH): Subtitles {
        val entries = texts.mapIndexed { index, text ->
            SubtitleEntry(
                index = index + 1,
                startTime = (index * 5000).toLong(),
                endTime = ((index + 1) * 5000).toLong(),
                text = text
            )
        }
        return Subtitles(entries, language)
    }

    @BeforeEach
    fun setup() {
        configManager = mockk()

        val settings = AppSettings(
            translation = TranslationSettings(defaultService = "libretranslate")
        )
        every { configManager.getSettings() } returns settings

        val serviceConfig = TranslationServiceConfig(
            libreTranslateUrl = "https://libretranslate.com",
            deeplApiKey = null,
            openaiApiKey = null,
            googleApiKey = null
        )
        every { configManager.getTranslationServiceConfig() } returns serviceConfig
    }

    // ==================== Basic Translation Tests ====================

    @Nested
    inner class BasicTranslationTest {

        @Test
        fun `translate emits progress updates`() = runTest {
            val httpClient = createMockHttpClient { request ->
                respond(
                    content = """{"translatedText": "Hallo"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello"))

            val progress = translatorService.translate(subtitles, Language.GERMAN).toList()

            assertTrue(progress.isNotEmpty())
            assertEquals(0f, progress.first().percentage)
            assertEquals(1f, progress.last().percentage)
        }

        @Test
        fun `translate returns translated subtitles`() = runTest {
            val httpClient = createMockHttpClient { request ->
                respond(
                    content = """{"translatedText": "Hallo Welt"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello World"))

            translatorService.translate(subtitles, Language.GERMAN).toList()
            val result = translatorService.getTranslationResult()

            assertEquals(Language.GERMAN, result.language)
            assertEquals(1, result.entries.size)
            assertEquals("Hallo Welt", result.entries[0].text)
        }

        @Test
        fun `getTranslationResult throws when no result available`() {
            val httpClient = createMockHttpClient { respond("") }
            translatorService = TranslatorService(httpClient, configManager)

            assertFailsWith<IllegalStateException> {
                translatorService.getTranslationResult()
            }
        }

        @Test
        fun `translate preserves subtitle timing`() = runTest {
            val httpClient = createMockHttpClient { request ->
                respond(
                    content = """{"translatedText": "Translated"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Original"))

            translatorService.translate(subtitles, Language.GERMAN).toList()
            val result = translatorService.getTranslationResult()

            assertEquals(0L, result.entries[0].startTime)
            assertEquals(5000L, result.entries[0].endTime)
        }
    }

    // ==================== Caching Tests ====================

    @Nested
    inner class CachingTest {

        @Test
        fun `cached translations are not re-fetched`() = runTest {
            var apiCallCount = 0
            val httpClient = createMockHttpClient { request ->
                apiCallCount++
                respond(
                    content = """{"translatedText": "Hallo"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello"))

            // First translation
            translatorService.translate(subtitles, Language.GERMAN).toList()
            val firstCallCount = apiCallCount

            // Second translation with same text
            translatorService.translate(subtitles, Language.GERMAN).toList()

            // API should not be called again
            assertEquals(firstCallCount, apiCallCount)
        }

        @Test
        fun `clearCache removes cached entries`() = runTest {
            var apiCallCount = 0
            val httpClient = createMockHttpClient { request ->
                apiCallCount++
                respond(
                    content = """{"translatedText": "Hallo"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello"))

            translatorService.translate(subtitles, Language.GERMAN).toList()
            translatorService.clearCache()
            translatorService.translate(subtitles, Language.GERMAN).toList()

            assertEquals(2, apiCallCount)
        }

        @Test
        fun `getCacheSize returns correct count`() = runTest {
            val httpClient = createMockHttpClient { request ->
                respond(
                    content = """{"translatedText": "Translated"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)

            assertEquals(0, translatorService.getCacheSize())

            val subtitles = createSubtitles(listOf("Hello", "World"))
            translatorService.translate(subtitles, Language.GERMAN).toList()

            assertEquals(2, translatorService.getCacheSize())
        }

        @Test
        fun `stats show cached segments`() = runTest {
            val httpClient = createMockHttpClient { request ->
                respond(
                    content = """{"translatedText": "Translated"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello", "World"))

            // First translation - no cache
            translatorService.translate(subtitles, Language.GERMAN).toList()
            val stats1 = translatorService.getLastStats()

            assertEquals(2, stats1?.totalSegments)
            assertEquals(0, stats1?.cachedSegments)

            // Second translation - all cached
            translatorService.translate(subtitles, Language.GERMAN).toList()
            val stats2 = translatorService.getLastStats()

            assertEquals(2, stats2?.totalSegments)
            assertEquals(2, stats2?.cachedSegments)
        }
    }

    // ==================== Glossary Tests ====================

    @Nested
    inner class GlossaryTest {

        @Test
        fun `setGlossary and getGlossary work correctly`() {
            val httpClient = createMockHttpClient { respond("") }
            translatorService = TranslatorService(httpClient, configManager)

            assertNull(translatorService.getGlossary())

            val glossary = Glossary(
                name = "Tech Terms",
                sourceLanguage = "en",
                targetLanguage = "de",
                entries = listOf(GlossaryEntry("API", "API"))
            )
            translatorService.setGlossary(glossary)

            assertEquals(glossary, translatorService.getGlossary())
        }

        @Test
        fun `glossary terms are preserved during translation`() = runTest {
            val httpClient = createMockHttpClient { request ->
                // Return translation with placeholder intact
                respond(
                    content = """{"translatedText": "Das ⟦GLOSS_0⟧ ist toll"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)

            val glossary = Glossary(
                name = "Tech Terms",
                sourceLanguage = "en",
                targetLanguage = "de",
                entries = listOf(GlossaryEntry("API", "API"))
            )
            translatorService.setGlossary(glossary)

            val subtitles = createSubtitles(listOf("The API is great"))
            translatorService.translate(subtitles, Language.GERMAN).toList()

            val result = translatorService.getTranslationResult()
            assertEquals("Das API ist toll", result.entries[0].text)
        }

        @Test
        fun `glossary not applied for wrong language pair`() = runTest {
            val httpClient = createMockHttpClient { request ->
                respond(
                    content = """{"translatedText": "L'API est super"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)

            // Glossary is for en->de but we translate to French
            val glossary = Glossary(
                name = "Tech Terms",
                sourceLanguage = "en",
                targetLanguage = "de",
                entries = listOf(GlossaryEntry("API", "API"))
            )
            translatorService.setGlossary(glossary)

            val subtitles = createSubtitles(listOf("The API is great"))
            translatorService.translate(subtitles, Language.FRENCH).toList()

            val result = translatorService.getTranslationResult()
            // API should not be replaced since glossary is for different target language
            assertEquals("L'API est super", result.entries[0].text)
        }
    }

    // ==================== Formatting Preservation Tests ====================

    @Nested
    inner class FormattingPreservationTest {

        @Test
        fun `italic tags are preserved`() = runTest {
            val httpClient = createMockHttpClient { request ->
                // Return translation with placeholders
                respond(
                    content = """{"translatedText": "⟦ITALIC_O_0⟧Hallo⟦ITALIC_C_0⟧ Welt"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("<i>Hello</i> World"))

            translatorService.translate(subtitles, Language.GERMAN).toList()
            val result = translatorService.getTranslationResult()

            assertEquals("<i>Hallo</i> Welt", result.entries[0].text)
        }

        @Test
        fun `line breaks are preserved`() = runTest {
            val httpClient = createMockHttpClient { request ->
                respond(
                    content = """{"translatedText": "Zeile 1 ⟦NL⟧ Zeile 2"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Line 1\nLine 2"))

            translatorService.translate(subtitles, Language.GERMAN).toList()
            val result = translatorService.getTranslationResult()

            assertEquals("Zeile 1\nZeile 2", result.entries[0].text)
        }

        @Test
        fun `music symbols are preserved`() = runTest {
            val httpClient = createMockHttpClient { request ->
                respond(
                    content = """{"translatedText": "⟦MUSIC_0⟧ Musik spielt ⟦MUSIC_1⟧"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("♪ Music playing ♪"))

            translatorService.translate(subtitles, Language.GERMAN).toList()
            val result = translatorService.getTranslationResult()

            assertEquals("♪ Musik spielt ♪", result.entries[0].text)
        }
    }

    // ==================== Rate Limit Tests ====================

    @Nested
    inner class RateLimitTest {

        @Test
        fun `rate limit triggers retry with backoff`() = runTest {
            var callCount = 0
            val httpClient = createMockHttpClient { request ->
                callCount++
                if (callCount == 1) {
                    respond(
                        content = "",
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf("Retry-After", "1")
                    )
                } else {
                    respond(
                        content = """{"translatedText": "Hallo"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello"))

            val progress = translatorService.translate(subtitles, Language.GERMAN).toList()

            // Should have retried and succeeded
            assertEquals(2, callCount)
            assertTrue(progress.any { it.message.contains("Rate limited") })
        }
    }

    // ==================== Service Fallback Tests ====================

    @Nested
    inner class FallbackTest {

        @Test
        fun `falls back to next service on error`() = runTest {
            var libreTranslateCallCount = 0
            var deeplCallCount = 0

            val httpClient = createMockHttpClient { request ->
                when {
                    request.url.toString().contains("libretranslate") -> {
                        libreTranslateCallCount++
                        respond(
                            content = """{"error": "Server error"}""",
                            status = HttpStatusCode.InternalServerError
                        )
                    }
                    request.url.toString().contains("deepl") -> {
                        deeplCallCount++
                        respond(
                            content = """{"translations": [{"text": "Hallo"}]}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                    else -> respond("")
                }
            }

            // Configure with fallback
            val serviceConfig = TranslationServiceConfig(
                libreTranslateUrl = "https://libretranslate.com",
                deeplApiKey = "test-key"
            )
            every { configManager.getTranslationServiceConfig() } returns serviceConfig

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello"))

            translatorService.translate(subtitles, Language.GERMAN).toList()

            assertTrue(libreTranslateCallCount > 0)
            assertTrue(deeplCallCount > 0)

            val stats = translatorService.getLastStats()
            assertTrue(stats?.fallbacksUsed?.isNotEmpty() == true)
        }
    }

    // ==================== Statistics Tests ====================

    @Nested
    inner class StatisticsTest {

        @Test
        fun `getLastStats returns null before any translation`() {
            val httpClient = createMockHttpClient { respond("") }
            translatorService = TranslatorService(httpClient, configManager)

            assertNull(translatorService.getLastStats())
        }

        @Test
        fun `getLastStats returns correct statistics`() = runTest {
            val httpClient = createMockHttpClient { request ->
                respond(
                    content = """{"translatedText": "Translated"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello", "World", "Test"))

            translatorService.translate(subtitles, Language.GERMAN).toList()

            val stats = translatorService.getLastStats()
            assertNotNull(stats)
            assertEquals(3, stats.totalSegments)
            assertEquals(0, stats.cachedSegments)
            assertTrue(stats.apiCalls > 0)
            assertTrue(stats.durationMs >= 0)
            assertEquals(TranslationService.LIBRE_TRANSLATE, stats.serviceUsed)
        }
    }

    // ==================== LibreTranslate Backend Tests ====================

    @Nested
    inner class LibreTranslateBackendTest {

        @Test
        fun `translates multiple segments`() = runTest {
            var translationIndex = 0
            val translations = listOf("Hallo", "Welt", "Test")

            val httpClient = createMockHttpClient { request ->
                val translation = translations[translationIndex++]
                respond(
                    content = """{"translatedText": "$translation"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello", "World", "Test"))

            translatorService.translate(subtitles, Language.GERMAN).toList()
            val result = translatorService.getTranslationResult()

            assertEquals(3, result.entries.size)
            assertEquals("Hallo", result.entries[0].text)
            assertEquals("Welt", result.entries[1].text)
            assertEquals("Test", result.entries[2].text)
        }
    }

    // ==================== DeepL Backend Tests ====================

    @Nested
    inner class DeepLBackendTest {

        @BeforeEach
        fun setupDeepL() {
            val settings = AppSettings(
                translation = TranslationSettings(defaultService = "deepl")
            )
            every { configManager.getSettings() } returns settings

            val serviceConfig = TranslationServiceConfig(
                libreTranslateUrl = null,
                deeplApiKey = "test-api-key"
            )
            every { configManager.getTranslationServiceConfig() } returns serviceConfig
        }

        @Test
        fun `uses correct API endpoint for free key`() = runTest {
            var requestUrl: String? = null

            val serviceConfig = TranslationServiceConfig(
                deeplApiKey = "test-api-key:fx" // Free key
            )
            every { configManager.getTranslationServiceConfig() } returns serviceConfig

            val httpClient = createMockHttpClient { request ->
                requestUrl = request.url.toString()
                respond(
                    content = """{"translations": [{"text": "Hallo"}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello"))

            translatorService.translate(subtitles, Language.GERMAN).toList()

            assertTrue(requestUrl?.contains("api-free.deepl.com") == true)
        }

        @Test
        fun `uses correct API endpoint for pro key`() = runTest {
            var requestUrl: String? = null

            val serviceConfig = TranslationServiceConfig(
                deeplApiKey = "test-api-key" // Pro key (no :fx suffix)
            )
            every { configManager.getTranslationServiceConfig() } returns serviceConfig

            val httpClient = createMockHttpClient { request ->
                requestUrl = request.url.toString()
                respond(
                    content = """{"translations": [{"text": "Hallo"}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello"))

            translatorService.translate(subtitles, Language.GERMAN).toList()

            assertTrue(requestUrl?.contains("api.deepl.com") == true)
            assertFalse(requestUrl?.contains("api-free") == true)
        }

        @Test
        fun `batch translates segments`() = runTest {
            val httpClient = createMockHttpClient { request ->
                respond(
                    content = """{"translations": [{"text": "Hallo"}, {"text": "Welt"}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello", "World"))

            translatorService.translate(subtitles, Language.GERMAN).toList()
            val result = translatorService.getTranslationResult()

            assertEquals(2, result.entries.size)
            assertEquals("Hallo", result.entries[0].text)
            assertEquals("Welt", result.entries[1].text)
        }
    }

    // ==================== OpenAI Backend Tests ====================

    @Nested
    inner class OpenAIBackendTest {

        @BeforeEach
        fun setupOpenAI() {
            val settings = AppSettings(
                translation = TranslationSettings(defaultService = "openai")
            )
            every { configManager.getSettings() } returns settings

            val serviceConfig = TranslationServiceConfig(
                libreTranslateUrl = null,
                openaiApiKey = "test-openai-key"
            )
            every { configManager.getTranslationServiceConfig() } returns serviceConfig
        }

        @Test
        fun `parses JSON array response`() = runTest {
            val httpClient = createMockHttpClient { request ->
                respond(
                    content = """{
                        "choices": [{
                            "message": {
                                "content": "{\"translations\": [\"Hallo\", \"Welt\"]}"
                            }
                        }]
                    }""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello", "World"))

            translatorService.translate(subtitles, Language.GERMAN).toList()
            val result = translatorService.getTranslationResult()

            assertEquals(2, result.entries.size)
            assertEquals("Hallo", result.entries[0].text)
            assertEquals("Welt", result.entries[1].text)
        }
    }

    // ==================== Error Handling Tests ====================

    @Nested
    inner class ErrorHandlingTest {

        @Test
        fun `handles network error gracefully`() = runTest {
            val httpClient = createMockHttpClient { request ->
                throw java.io.IOException("Network error")
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello"))

            assertFailsWith<TranslationException> {
                translatorService.translate(subtitles, Language.GERMAN).toList()
            }
        }

        @Test
        fun `handles invalid JSON response`() = runTest {
            val httpClient = createMockHttpClient { request ->
                respond(
                    content = "not valid json",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello"))

            assertFailsWith<Exception> {
                translatorService.translate(subtitles, Language.GERMAN).toList()
            }
        }

        @Test
        fun `handles 500 server error`() = runTest {
            val httpClient = createMockHttpClient { request ->
                respond(
                    content = """{"error": "Internal server error"}""",
                    status = HttpStatusCode.InternalServerError
                )
            }

            // Only LibreTranslate configured, no fallback
            val serviceConfig = TranslationServiceConfig(
                libreTranslateUrl = "https://libretranslate.com"
            )
            every { configManager.getTranslationServiceConfig() } returns serviceConfig

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello"))

            assertFailsWith<TranslationException> {
                translatorService.translate(subtitles, Language.GERMAN).toList()
            }
        }

        @Test
        fun `handles configuration error`() = runTest {
            val httpClient = createMockHttpClient { request ->
                respond("")
            }

            // No services configured
            val serviceConfig = TranslationServiceConfig(
                libreTranslateUrl = null,
                deeplApiKey = null,
                openaiApiKey = null
            )
            every { configManager.getTranslationServiceConfig() } returns serviceConfig

            translatorService = TranslatorService(httpClient, configManager)
            val subtitles = createSubtitles(listOf("Hello"))

            assertFailsWith<TranslationException> {
                translatorService.translate(subtitles, Language.GERMAN).toList()
            }
        }
    }

    // ==================== Batching Tests ====================

    @Nested
    inner class BatchingTest {

        @Test
        fun `respects batch size limits`() = runTest {
            var batchCount = 0

            val httpClient = createMockHttpClient { request ->
                batchCount++
                respond(
                    content = """{"translatedText": "Translated"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            translatorService = TranslatorService(httpClient, configManager)

            // Create many segments
            val texts = (1..200).map { "Segment $it" }
            val subtitles = createSubtitles(texts)

            translatorService.translate(subtitles, Language.GERMAN).toList()

            // LibreTranslate translates one at a time, so should have 200 calls
            assertEquals(200, batchCount)
        }
    }
}
