package com.ericjesse.videotranslator.domain.service

import com.ericjesse.videotranslator.domain.model.*
import com.ericjesse.videotranslator.domain.pipeline.StageProgress
import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.config.TranslationServiceConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Service for translating subtitles.
 * Supports multiple translation backends: LibreTranslate, DeepL, OpenAI.
 */
class TranslatorService(
    private val httpClient: HttpClient,
    private val configManager: ConfigManager
) {
    
    private var lastResult: Subtitles? = null
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }
    
    /**
     * Translates subtitles from source to target language.
     */
    fun translate(subtitles: Subtitles, targetLanguage: Language): Flow<StageProgress> = flow {
        emit(StageProgress(0f, "Starting translation..."))
        
        val settings = configManager.getSettings()
        val serviceConfig = configManager.getTranslationServiceConfig()
        
        val translator = when (settings.translation.defaultService) {
            "libretranslate" -> LibreTranslateBackend(httpClient, serviceConfig, json)
            "deepl" -> DeepLBackend(httpClient, serviceConfig, json)
            "openai" -> OpenAIBackend(httpClient, serviceConfig, json)
            else -> LibreTranslateBackend(httpClient, serviceConfig, json)
        }
        
        val translatedEntries = mutableListOf<SubtitleEntry>()
        val total = subtitles.entries.size
        
        // Translate in batches to show progress and handle rate limits
        val batchSize = 10
        val batches = subtitles.entries.chunked(batchSize)
        
        for ((batchIndex, batch) in batches.withIndex()) {
            val batchText = batch.map { it.text }
            
            try {
                val translatedTexts = translator.translate(
                    texts = batchText,
                    sourceLanguage = subtitles.language.code,
                    targetLanguage = targetLanguage.code
                )
                
                for ((i, entry) in batch.withIndex()) {
                    translatedEntries.add(entry.copy(
                        text = translatedTexts.getOrElse(i) { entry.text }
                    ))
                }
                
            } catch (e: RateLimitException) {
                logger.warn { "Rate limited, waiting ${e.retryAfterSeconds}s..." }
                emit(StageProgress(
                    percentage = (batchIndex * batchSize).toFloat() / total,
                    message = "Rate limited, waiting ${e.retryAfterSeconds}s..."
                ))
                delay(e.retryAfterSeconds * 1000L)
                // Retry this batch
                continue
            }
            
            val progress = ((batchIndex + 1) * batchSize).coerceAtMost(total).toFloat() / total
            emit(StageProgress(progress, "Translated ${(progress * 100).toInt()}%"))
        }
        
        lastResult = Subtitles(translatedEntries, targetLanguage)
        emit(StageProgress(1f, "Translation complete"))
    }
    
    /**
     * Returns the result of the last translation.
     */
    fun getTranslationResult(): Subtitles {
        return lastResult ?: throw IllegalStateException("No translation result available")
    }
}

/**
 * Interface for translation backends.
 */
interface TranslationBackend {
    suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): List<String>
}

/**
 * Exception thrown when rate limited.
 */
class RateLimitException(val retryAfterSeconds: Int) : Exception("Rate limited")

/**
 * LibreTranslate backend implementation.
 */
class LibreTranslateBackend(
    private val httpClient: HttpClient,
    private val config: TranslationServiceConfig,
    private val json: Json
) : TranslationBackend {
    
    @Serializable
    data class TranslateRequest(
        val q: String,
        val source: String,
        val target: String,
        val format: String = "text"
    )
    
    @Serializable
    data class TranslateResponse(
        val translatedText: String
    )
    
    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): List<String> {
        val baseUrl = config.libreTranslateUrl ?: "https://libretranslate.com"
        
        return texts.map { text ->
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
            
            if (response.status == HttpStatusCode.TooManyRequests) {
                val retryAfter = response.headers["Retry-After"]?.toIntOrNull() ?: 30
                throw RateLimitException(retryAfter)
            }
            
            val result = json.decodeFromString<TranslateResponse>(response.bodyAsText())
            result.translatedText
        }
    }
}

/**
 * DeepL backend implementation.
 */
class DeepLBackend(
    private val httpClient: HttpClient,
    private val config: TranslationServiceConfig,
    private val json: Json
) : TranslationBackend {
    
    @Serializable
    data class DeepLResponse(
        val translations: List<Translation>
    ) {
        @Serializable
        data class Translation(val text: String)
    }
    
    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): List<String> {
        val apiKey = config.deeplApiKey 
            ?: throw IllegalStateException("DeepL API key not configured")
        
        val baseUrl = if (apiKey.endsWith(":fx")) {
            "https://api-free.deepl.com/v2"
        } else {
            "https://api.deepl.com/v2"
        }
        
        val response = httpClient.post("$baseUrl/translate") {
            header("Authorization", "DeepL-Auth-Key $apiKey")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(buildString {
                texts.forEach { append("text=${it.encodeURLParameter()}&") }
                append("source_lang=${sourceLanguage.uppercase()}")
                append("&target_lang=${targetLanguage.uppercase()}")
            })
        }
        
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw RateLimitException(60)
        }
        
        val result = json.decodeFromString<DeepLResponse>(response.bodyAsText())
        return result.translations.map { it.text }
    }
    
    private fun String.encodeURLParameter(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
}

/**
 * OpenAI backend implementation.
 */
class OpenAIBackend(
    private val httpClient: HttpClient,
    private val config: TranslationServiceConfig,
    private val json: Json
) : TranslationBackend {
    
    @Serializable
    data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val temperature: Double = 0.3
    ) {
        @Serializable
        data class Message(val role: String, val content: String)
    }
    
    @Serializable
    data class ChatResponse(
        val choices: List<Choice>
    ) {
        @Serializable
        data class Choice(val message: Message)
        @Serializable
        data class Message(val content: String)
    }
    
    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): List<String> {
        val apiKey = config.openaiApiKey 
            ?: throw IllegalStateException("OpenAI API key not configured")
        
        // Join texts with delimiter for batch translation
        val delimiter = "|||"
        val combinedText = texts.joinToString(delimiter)
        
        val prompt = """
            Translate the following text from $sourceLanguage to $targetLanguage.
            The text contains multiple segments separated by "$delimiter".
            Keep the same delimiter in your response.
            Only respond with the translation, nothing else.
            
            Text: $combinedText
        """.trimIndent()
        
        val response = httpClient.post("https://api.openai.com/v1/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ChatRequest.serializer(),
                ChatRequest(
                    model = "gpt-4o-mini",
                    messages = listOf(
                        ChatRequest.Message("user", prompt)
                    )
                )
            ))
        }
        
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw RateLimitException(60)
        }
        
        val result = json.decodeFromString<ChatResponse>(response.bodyAsText())
        val translatedText = result.choices.firstOrNull()?.message?.content ?: ""
        
        return translatedText.split(delimiter).map { it.trim() }
    }
}
