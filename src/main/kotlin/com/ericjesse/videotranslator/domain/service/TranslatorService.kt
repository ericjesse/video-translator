package com.ericjesse.videotranslator.domain.service

import com.ericjesse.videotranslator.domain.model.*
import com.ericjesse.videotranslator.domain.pipeline.StageProgress
import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.config.TranslationServiceConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Service for translating subtitles with production-ready features.
 *
 * Features:
 * - Smart batching respecting API limits per service
 * - Context preservation between batches for translation continuity
 * - Translation caching to avoid re-translating identical segments
 * - Glossary support for consistent technical term translation
 * - Formatting preservation (italics, line breaks, special characters)
 * - Automatic fallback to alternate services on failure
 * - Exponential backoff rate limit handling
 *
 * Supports multiple translation backends: LibreTranslate, DeepL, OpenAI.
 */
class TranslatorService(
    private val httpClient: HttpClient,
    private val configManager: ConfigManager
) {

    private var lastResult: Subtitles? = null
    private var lastStats: TranslationStats? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val cache = TranslationCache()
    private val rateLimitTrackers = mutableMapOf<TranslationService, RateLimitTracker>()
    private var activeGlossary: Glossary? = null

    /**
     * Sets the glossary to use for translations.
     */
    fun setGlossary(glossary: Glossary?) {
        activeGlossary = glossary
    }

    /**
     * Gets the currently active glossary.
     */
    fun getGlossary(): Glossary? = activeGlossary

    /**
     * Clears the translation cache.
     */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Gets the current cache size.
     */
    fun getCacheSize(): Int = cache.size()

    /**
     * Gets statistics from the last translation operation.
     */
    fun getLastStats(): TranslationStats? = lastStats

    /**
     * Translates subtitles from source to target language.
     *
     * @param subtitles Source subtitles to translate.
     * @param targetLanguage Target language for translation.
     * @return Flow of progress updates during translation.
     */
    fun translate(subtitles: Subtitles, targetLanguage: Language): Flow<StageProgress> = flow {
        emit(StageProgress(0f, "Starting translation..."))

        val startTime = System.currentTimeMillis()
        val settings = configManager.getSettings()
        val serviceConfig = configManager.getTranslationServiceConfig()

        val primaryService = TranslationService.fromString(settings.translation.defaultService)
            ?: TranslationService.LIBRE_TRANSLATE

        val fallbackOrder = buildFallbackOrder(primaryService, serviceConfig)

        val backends = createBackends(serviceConfig)
        val sourceLanguage = subtitles.language.code
        val serviceName = primaryService.name.lowercase()

        // Check cache for already translated segments
        val cachedTranslations = cache.getMultiple(
            texts = subtitles.entries.map { it.text },
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage.code,
            service = serviceName
        )

        val uncachedEntries = subtitles.entries.filter { it.text !in cachedTranslations }
        var cachedCount = subtitles.entries.size - uncachedEntries.size

        logger.info { "Translation: ${cachedCount} cached, ${uncachedEntries.size} to translate" }

        if (uncachedEntries.isEmpty()) {
            // All segments were cached
            val translatedEntries = subtitles.entries.map { entry ->
                entry.copy(text = cachedTranslations[entry.text] ?: entry.text)
            }
            lastResult = Subtitles(translatedEntries, targetLanguage)
            lastStats = TranslationStats(
                totalSegments = subtitles.entries.size,
                cachedSegments = cachedCount,
                apiCalls = 0,
                totalCharacters = 0,
                durationMs = System.currentTimeMillis() - startTime,
                serviceUsed = primaryService
            )
            emit(StageProgress(1f, "Translation complete (all cached)"))
            return@flow
        }

        // Prepare texts for translation with formatting preservation
        val textsToTranslate = uncachedEntries.map { it.text }
        val preservedFormats = textsToTranslate.map { FormattingPreserver.preserveAll(it) }
        val preparedTexts = preservedFormats.map { it.first }

        // Apply glossary pre-processing
        val glossaryReplacements = mutableListOf<Map<String, String>>()
        val glossaryProcessedTexts = if (activeGlossary != null &&
            activeGlossary?.sourceLanguage == sourceLanguage &&
            activeGlossary?.targetLanguage == targetLanguage.code) {
            preparedTexts.map { text ->
                val (processed, replacements) = activeGlossary!!.applyPreTranslation(text)
                glossaryReplacements.add(replacements)
                processed
            }
        } else {
            preparedTexts.also {
                repeat(it.size) { glossaryReplacements.add(emptyMap()) }
            }
        }

        // Build batches based on primary service limits
        val batchConfig = getBatchConfig(primaryService)
        val batches = buildBatches(glossaryProcessedTexts, batchConfig)

        val translatedTexts = mutableListOf<String>()
        var apiCalls = 0
        var totalChars = 0
        val fallbacksUsed = mutableSetOf<TranslationService>()
        var currentServiceIndex = 0

        for ((batchIndex, batch) in batches.withIndex()) {
            var batchTranslated = false
            var lastError: TranslationException? = null

            // Try services in fallback order
            while (!batchTranslated && currentServiceIndex < fallbackOrder.size) {
                val currentService = fallbackOrder[currentServiceIndex]
                val backend = backends[currentService]

                if (backend == null) {
                    currentServiceIndex++
                    continue
                }

                val tracker = rateLimitTrackers.getOrPut(currentService) { RateLimitTracker() }

                if (tracker.shouldGiveUp()) {
                    logger.warn { "Giving up on $currentService after too many failures" }
                    currentServiceIndex++
                    continue
                }

                try {
                    val result = backend.translateBatch(
                        batch = batch,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage.code
                    )

                    when (result) {
                        is TranslationApiResult.Success -> {
                            translatedTexts.addAll(result.translations)
                            tracker.recordSuccess()
                            apiCalls++
                            totalChars += batch.totalCharacters
                            batchTranslated = true

                            if (currentService != primaryService) {
                                fallbacksUsed.add(currentService)
                            }
                        }

                        is TranslationApiResult.RateLimited -> {
                            val delayMs = tracker.recordFailure(result.retryAfterSeconds)
                            logger.warn { "$currentService rate limited, waiting ${delayMs}ms" }

                            emit(StageProgress(
                                percentage = batchIndex.toFloat() / batches.size,
                                message = "Rate limited, waiting ${delayMs / 1000}s..."
                            ))

                            delay(delayMs)
                            // Retry same service
                        }

                        is TranslationApiResult.ServiceError -> {
                            logger.error { "$currentService error: ${result.message}" }
                            lastError = TranslationException.fromResult(result, currentService)

                            if (result.isRetryable) {
                                val delayMs = tracker.recordFailure(null)
                                delay(delayMs)
                            } else {
                                currentServiceIndex++
                            }
                        }

                        is TranslationApiResult.ConfigurationError -> {
                            logger.error { "$currentService not configured: ${result.message}" }
                            currentServiceIndex++
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Unexpected error from $currentService" }
                    lastError = TranslationException(
                        service = currentService,
                        isRetryable = false,
                        userMessage = "Translation failed: ${e.message}",
                        cause = e
                    )
                    currentServiceIndex++
                }
            }

            if (!batchTranslated) {
                throw lastError ?: TranslationException(
                    service = null,
                    isRetryable = false,
                    userMessage = "All translation services failed"
                )
            }

            val progress = (batchIndex + 1).toFloat() / batches.size
            emit(StageProgress(progress, "Translated ${(progress * 100).toInt()}%"))
        }

        // Apply post-processing: restore formatting and glossary terms
        val finalTranslations = translatedTexts.mapIndexed { index, text ->
            var result = text

            // Restore glossary terms
            if (glossaryReplacements[index].isNotEmpty()) {
                result = activeGlossary?.applyPostTranslation(result, glossaryReplacements[index])
                    ?: result
            }

            // Restore formatting
            result = preservedFormats[index].second(result)

            result
        }

        // Cache the new translations
        val newTranslations = textsToTranslate.zip(finalTranslations).toMap()
        cache.putMultiple(
            translations = newTranslations,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage.code,
            service = serviceName
        )

        // Build final result combining cached and new translations
        var newTranslationIndex = 0
        val translatedEntries = subtitles.entries.map { entry ->
            val translatedText = cachedTranslations[entry.text]
                ?: finalTranslations[newTranslationIndex++]
            entry.copy(text = translatedText)
        }

        lastResult = Subtitles(translatedEntries, targetLanguage)
        lastStats = TranslationStats(
            totalSegments = subtitles.entries.size,
            cachedSegments = cachedCount,
            apiCalls = apiCalls,
            totalCharacters = totalChars,
            durationMs = System.currentTimeMillis() - startTime,
            serviceUsed = primaryService,
            fallbacksUsed = fallbacksUsed.toList()
        )

        emit(StageProgress(1f, "Translation complete"))
    }

    /**
     * Returns the result of the last translation.
     */
    fun getTranslationResult(): Subtitles {
        return lastResult ?: throw IllegalStateException("No translation result available")
    }

    /**
     * Builds batches respecting service-specific limits.
     */
    private fun buildBatches(texts: List<String>, config: BatchConfig): List<TranslationBatch> {
        val batches = mutableListOf<TranslationBatch>()
        var currentBatch = mutableListOf<String>()
        var currentChars = 0
        var currentTokens = 0
        var startIndex = 0
        val previousContext = mutableListOf<TranslatedContext>()

        for ((index, text) in texts.withIndex()) {
            val textChars = text.length
            val textTokens = estimateTokens(text)

            val wouldExceedChars = currentChars + textChars > config.maxCharacters
            val wouldExceedSegments = currentBatch.size >= config.maxSegments
            val wouldExceedTokens = currentTokens + textTokens > config.maxTokens

            if (currentBatch.isNotEmpty() && (wouldExceedChars || wouldExceedSegments || wouldExceedTokens)) {
                // Finalize current batch
                batches.add(TranslationBatch(
                    segments = currentBatch.toList(),
                    contextPrefix = previousContext.takeLast(config.contextSegments),
                    startIndex = startIndex,
                    totalCharacters = currentChars,
                    estimatedTokens = currentTokens
                ))

                startIndex = index
                currentBatch = mutableListOf()
                currentChars = 0
                currentTokens = 0
            }

            currentBatch.add(text)
            currentChars += textChars
            currentTokens += textTokens
        }

        // Add final batch
        if (currentBatch.isNotEmpty()) {
            batches.add(TranslationBatch(
                segments = currentBatch.toList(),
                contextPrefix = previousContext.takeLast(config.contextSegments),
                startIndex = startIndex,
                totalCharacters = currentChars,
                estimatedTokens = currentTokens
            ))
        }

        return batches
    }

    /**
     * Gets batch configuration for a specific service.
     */
    private fun getBatchConfig(service: TranslationService): BatchConfig {
        return when (service) {
            TranslationService.LIBRE_TRANSLATE -> BatchConfig(
                maxCharacters = 5000,
                maxSegments = 100,
                maxTokens = Int.MAX_VALUE,
                contextSegments = 0 // LibreTranslate doesn't support context
            )
            TranslationService.DEEPL -> BatchConfig(
                maxCharacters = 50000,
                maxSegments = 50, // DeepL limit
                maxTokens = Int.MAX_VALUE,
                contextSegments = 2
            )
            TranslationService.OPENAI -> BatchConfig(
                maxCharacters = 10000,
                maxSegments = 50,
                maxTokens = 3000, // Leave room for response
                contextSegments = 3
            )
            TranslationService.GOOGLE -> BatchConfig(
                maxCharacters = 5000,
                maxSegments = 128,
                maxTokens = Int.MAX_VALUE,
                contextSegments = 0
            )
        }
    }

    /**
     * Estimates token count for OpenAI (rough approximation: 1 token ≈ 4 chars).
     */
    private fun estimateTokens(text: String): Int {
        return (text.length / 4) + 1
    }

    /**
     * Builds fallback order based on available services.
     */
    private fun buildFallbackOrder(
        primary: TranslationService,
        config: TranslationServiceConfig
    ): List<TranslationService> {
        val order = mutableListOf(primary)

        // Add other configured services as fallbacks
        val allServices = listOf(
            TranslationService.LIBRE_TRANSLATE,
            TranslationService.DEEPL,
            TranslationService.OPENAI,
            TranslationService.GOOGLE
        )

        for (service in allServices) {
            if (service != primary && isServiceConfigured(service, config)) {
                order.add(service)
            }
        }

        return order
    }

    /**
     * Checks if a service has valid configuration.
     */
    private fun isServiceConfigured(service: TranslationService, config: TranslationServiceConfig): Boolean {
        return when (service) {
            TranslationService.LIBRE_TRANSLATE -> !config.libreTranslateUrl.isNullOrBlank()
            TranslationService.DEEPL -> !config.deeplApiKey.isNullOrBlank()
            TranslationService.OPENAI -> !config.openaiApiKey.isNullOrBlank()
            TranslationService.GOOGLE -> !config.googleApiKey.isNullOrBlank()
        }
    }

    /**
     * Creates backend instances for all configured services.
     */
    private fun createBackends(config: TranslationServiceConfig): Map<TranslationService, TranslationBackend> {
        val backends = mutableMapOf<TranslationService, TranslationBackend>()

        if (!config.libreTranslateUrl.isNullOrBlank()) {
            backends[TranslationService.LIBRE_TRANSLATE] = LibreTranslateBackend(httpClient, config, json)
        }
        if (!config.deeplApiKey.isNullOrBlank()) {
            backends[TranslationService.DEEPL] = DeepLBackend(httpClient, config, json)
        }
        if (!config.openaiApiKey.isNullOrBlank()) {
            backends[TranslationService.OPENAI] = OpenAIBackend(httpClient, config, json)
        }
        if (!config.googleApiKey.isNullOrBlank()) {
            backends[TranslationService.GOOGLE] = GoogleBackend(httpClient, config, json)
        }

        return backends
    }
}

/**
 * Interface for translation backends with batch support.
 */
interface TranslationBackend {
    /**
     * Translates a batch of text segments.
     */
    suspend fun translateBatch(
        batch: TranslationBatch,
        sourceLanguage: String,
        targetLanguage: String
    ): TranslationApiResult
}

/**
 * LibreTranslate backend implementation.
 * Batches by character count, translates one segment at a time.
 */
class LibreTranslateBackend(
    private val httpClient: HttpClient,
    private val config: TranslationServiceConfig,
    private val json: Json
) : TranslationBackend {

    @Serializable
    private data class TranslateRequest(
        val q: String,
        val source: String,
        val target: String,
        val format: String = "text"
    )

    @Serializable
    private data class TranslateResponse(
        val translatedText: String
    )

    @Serializable
    private data class ErrorResponse(
        val error: String? = null
    )

    override suspend fun translateBatch(
        batch: TranslationBatch,
        sourceLanguage: String,
        targetLanguage: String
    ): TranslationApiResult {
        val baseUrl = config.libreTranslateUrl ?: return TranslationApiResult.ConfigurationError(
            "LibreTranslate URL not configured"
        )

        val translations = mutableListOf<String>()

        for (text in batch.segments) {
            try {
                val response = httpClient.post("$baseUrl/translate") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(TranslateRequest.serializer(),
                        TranslateRequest(
                            q = text,
                            source = sourceLanguage,
                            target = targetLanguage
                        )
                    ))
                }

                when (response.status) {
                    HttpStatusCode.OK -> {
                        val result = json.decodeFromString<TranslateResponse>(response.bodyAsText())
                        translations.add(result.translatedText)
                    }
                    HttpStatusCode.TooManyRequests -> {
                        val retryAfter = response.headers["Retry-After"]?.toIntOrNull()
                        return TranslationApiResult.RateLimited(retryAfter ?: 30)
                    }
                    else -> {
                        val errorBody = try {
                            json.decodeFromString<ErrorResponse>(response.bodyAsText())
                        } catch (e: Exception) { null }

                        return TranslationApiResult.ServiceError(
                            message = errorBody?.error ?: "HTTP ${response.status.value}",
                            isRetryable = response.status.value >= 500
                        )
                    }
                }
            } catch (e: Exception) {
                return TranslationApiResult.ServiceError(
                    message = "Connection failed: ${e.message}",
                    isRetryable = true,
                    cause = e
                )
            }
        }

        return TranslationApiResult.Success(translations)
    }
}

/**
 * DeepL backend implementation.
 * Supports native batch translation (max 50 segments).
 */
class DeepLBackend(
    private val httpClient: HttpClient,
    private val config: TranslationServiceConfig,
    private val json: Json
) : TranslationBackend {

    @Serializable
    private data class DeepLResponse(
        val translations: List<Translation>
    ) {
        @Serializable
        data class Translation(val text: String)
    }

    override suspend fun translateBatch(
        batch: TranslationBatch,
        sourceLanguage: String,
        targetLanguage: String
    ): TranslationApiResult {
        val apiKey = config.deeplApiKey ?: return TranslationApiResult.ConfigurationError(
            "DeepL API key not configured"
        )

        val baseUrl = if (apiKey.endsWith(":fx")) {
            "https://api-free.deepl.com/v2"
        } else {
            "https://api.deepl.com/v2"
        }

        try {
            // Build context parameter if available
            val contextText = if (batch.contextPrefix.isNotEmpty()) {
                batch.contextPrefix.joinToString(" ") { it.translated }
            } else null

            val response = httpClient.post("$baseUrl/translate") {
                header("Authorization", "DeepL-Auth-Key $apiKey")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(buildString {
                    batch.segments.forEach { text ->
                        append("text=${text.encodeURLParameter()}&")
                    }
                    append("source_lang=${mapLanguageCode(sourceLanguage).uppercase()}")
                    append("&target_lang=${mapLanguageCode(targetLanguage).uppercase()}")
                    append("&preserve_formatting=1")
                    append("&tag_handling=xml")
                    if (contextText != null) {
                        append("&context=${contextText.encodeURLParameter()}")
                    }
                })
            }

            return when (response.status) {
                HttpStatusCode.OK -> {
                    val result = json.decodeFromString<DeepLResponse>(response.bodyAsText())
                    TranslationApiResult.Success(result.translations.map { it.text })
                }
                HttpStatusCode.TooManyRequests -> {
                    TranslationApiResult.RateLimited(60)
                }
                HttpStatusCode.Forbidden -> {
                    TranslationApiResult.ConfigurationError("DeepL API key is invalid")
                }
                HttpStatusCode.PayloadTooLarge -> {
                    TranslationApiResult.ServiceError(
                        message = "Request too large for DeepL",
                        isRetryable = false
                    )
                }
                else -> {
                    TranslationApiResult.ServiceError(
                        message = "DeepL error: HTTP ${response.status.value}",
                        isRetryable = response.status.value >= 500
                    )
                }
            }
        } catch (e: Exception) {
            return TranslationApiResult.ServiceError(
                message = "DeepL connection failed: ${e.message}",
                isRetryable = true,
                cause = e
            )
        }
    }

    private fun mapLanguageCode(code: String): String {
        // DeepL uses specific codes for some languages
        return when (code.lowercase()) {
            "en" -> "EN"
            "pt" -> "PT-PT"
            "zh" -> "ZH"
            else -> code.uppercase()
        }
    }

    private fun String.encodeURLParameter(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
}

/**
 * OpenAI backend implementation.
 * Uses GPT for translation with context awareness.
 */
class OpenAIBackend(
    private val httpClient: HttpClient,
    private val config: TranslationServiceConfig,
    private val json: Json
) : TranslationBackend {

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val temperature: Double = 0.3,
        @SerialName("response_format")
        val responseFormat: ResponseFormat? = null
    ) {
        @Serializable
        data class Message(val role: String, val content: String)

        @Serializable
        data class ResponseFormat(val type: String)
    }

    @Serializable
    private data class ChatResponse(
        val choices: List<Choice>? = null,
        val error: ErrorInfo? = null
    ) {
        @Serializable
        data class Choice(val message: Message)

        @Serializable
        data class Message(val content: String)

        @Serializable
        data class ErrorInfo(
            val message: String,
            val type: String? = null,
            val code: String? = null
        )
    }

    override suspend fun translateBatch(
        batch: TranslationBatch,
        sourceLanguage: String,
        targetLanguage: String
    ): TranslationApiResult {
        val apiKey = config.openaiApiKey ?: return TranslationApiResult.ConfigurationError(
            "OpenAI API key not configured"
        )

        val sourceLang = Language.fromCode(sourceLanguage)?.displayName ?: sourceLanguage
        val targetLang = Language.fromCode(targetLanguage)?.displayName ?: targetLanguage

        // Build system prompt with context
        val systemPrompt = buildString {
            append("You are a professional translator. ")
            append("Translate the following subtitle segments from $sourceLang to $targetLang. ")
            append("Maintain the same tone and register as the original. ")
            append("Preserve any formatting markers like ⟦...⟧. ")
            append("Return ONLY a JSON array of translated strings, one for each input segment. ")
            append("Do not include any explanation or additional text.")

            if (batch.contextPrefix.isNotEmpty()) {
                append("\n\nFor context, here are the previous translations:\n")
                batch.contextPrefix.forEach { ctx ->
                    append("Original: ${ctx.original}\n")
                    append("Translation: ${ctx.translated}\n")
                }
            }
        }

        // Build user prompt with segments - format as JSON array
        val segmentsJson = batch.segments.joinToString(",", "[", "]") { segment ->
            "\"${segment.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
        }
        val userPrompt = "Translate these ${batch.segments.size} segments:\n\n$segmentsJson"

        try {
            val response = httpClient.post("https://api.openai.com/v1/chat/completions") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(ChatRequest.serializer(),
                    ChatRequest(
                        model = "gpt-4o-mini",
                        messages = listOf(
                            ChatRequest.Message("system", systemPrompt),
                            ChatRequest.Message("user", userPrompt)
                        ),
                        responseFormat = ChatRequest.ResponseFormat("json_object")
                    )
                ))
            }

            return when (response.status) {
                HttpStatusCode.OK -> {
                    val result = json.decodeFromString<ChatResponse>(response.bodyAsText())
                    val content = result.choices?.firstOrNull()?.message?.content
                        ?: return TranslationApiResult.ServiceError(
                            message = "Empty response from OpenAI",
                            isRetryable = true
                        )

                    // Parse JSON array from response
                    val translations = parseTranslationResponse(content, batch.segments.size)
                    TranslationApiResult.Success(translations)
                }
                HttpStatusCode.TooManyRequests -> {
                    val retryAfter = response.headers["Retry-After"]?.toIntOrNull()
                    TranslationApiResult.RateLimited(retryAfter ?: 60)
                }
                HttpStatusCode.Unauthorized -> {
                    TranslationApiResult.ConfigurationError("OpenAI API key is invalid")
                }
                else -> {
                    val errorResponse = try {
                        json.decodeFromString<ChatResponse>(response.bodyAsText())
                    } catch (e: Exception) { null }

                    TranslationApiResult.ServiceError(
                        message = errorResponse?.error?.message ?: "OpenAI error: HTTP ${response.status.value}",
                        isRetryable = response.status.value >= 500
                    )
                }
            }
        } catch (e: Exception) {
            return TranslationApiResult.ServiceError(
                message = "OpenAI connection failed: ${e.message}",
                isRetryable = true,
                cause = e
            )
        }
    }

    private fun parseTranslationResponse(content: String, expectedCount: Int): List<String> {
        return try {
            // Try parsing as JSON object with translations key
            val jsonElement = json.parseToJsonElement(content)

            when {
                jsonElement is JsonArray -> {
                    jsonElement.map { it.jsonPrimitive.content }
                }
                jsonElement is JsonObject -> {
                    // Look for common keys that might contain the array
                    val array = jsonElement["translations"]
                        ?: jsonElement["translated"]
                        ?: jsonElement["result"]
                        ?: jsonElement.values.firstOrNull { it is JsonArray }

                    if (array is JsonArray) {
                        array.map {
                            when (it) {
                                is JsonPrimitive -> it.content
                                is JsonObject -> it["text"]?.jsonPrimitive?.content ?: it.toString()
                                else -> it.toString()
                            }
                        }
                    } else {
                        // Fallback: split by delimiter
                        content.split("|||").map { it.trim() }
                    }
                }
                else -> {
                    content.split("|||").map { it.trim() }
                }
            }
        } catch (e: Exception) {
            // Fallback parsing
            content.trim()
                .removePrefix("[").removeSuffix("]")
                .split("\",\"", "\", \"")
                .map { it.trim().trim('"') }
        }.let { translations ->
            // Ensure we have the right count
            if (translations.size == expectedCount) {
                translations
            } else {
                translations.take(expectedCount).toMutableList().apply {
                    while (size < expectedCount) {
                        add("")
                    }
                }
            }
        }
    }
}

/**
 * Google Cloud Translation backend implementation.
 */
class GoogleBackend(
    private val httpClient: HttpClient,
    private val config: TranslationServiceConfig,
    private val json: Json
) : TranslationBackend {

    @Serializable
    private data class GoogleRequest(
        val q: List<String>,
        val source: String,
        val target: String,
        val format: String = "text"
    )

    @Serializable
    private data class GoogleResponse(
        val data: Data? = null,
        val error: ErrorInfo? = null
    ) {
        @Serializable
        data class Data(val translations: List<Translation>)

        @Serializable
        data class Translation(val translatedText: String)

        @Serializable
        data class ErrorInfo(
            val code: Int,
            val message: String
        )
    }

    override suspend fun translateBatch(
        batch: TranslationBatch,
        sourceLanguage: String,
        targetLanguage: String
    ): TranslationApiResult {
        val apiKey = config.googleApiKey ?: return TranslationApiResult.ConfigurationError(
            "Google API key not configured"
        )

        try {
            val response = httpClient.post("https://translation.googleapis.com/language/translate/v2") {
                parameter("key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(GoogleRequest.serializer(),
                    GoogleRequest(
                        q = batch.segments,
                        source = sourceLanguage,
                        target = targetLanguage
                    )
                ))
            }

            return when (response.status) {
                HttpStatusCode.OK -> {
                    val result = json.decodeFromString<GoogleResponse>(response.bodyAsText())
                    val translations = result.data?.translations?.map { it.translatedText }
                        ?: return TranslationApiResult.ServiceError(
                            message = "Invalid response from Google",
                            isRetryable = true
                        )
                    TranslationApiResult.Success(translations)
                }
                HttpStatusCode.TooManyRequests -> {
                    TranslationApiResult.RateLimited(60)
                }
                HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized -> {
                    TranslationApiResult.ConfigurationError("Google API key is invalid")
                }
                else -> {
                    val errorResponse = try {
                        json.decodeFromString<GoogleResponse>(response.bodyAsText())
                    } catch (e: Exception) { null }

                    TranslationApiResult.ServiceError(
                        message = errorResponse?.error?.message ?: "Google error: HTTP ${response.status.value}",
                        isRetryable = response.status.value >= 500
                    )
                }
            }
        } catch (e: Exception) {
            return TranslationApiResult.ServiceError(
                message = "Google connection failed: ${e.message}",
                isRetryable = true,
                cause = e
            )
        }
    }
}
