package com.ericjesse.videotranslator.infrastructure.http

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Factory for creating configured HTTP clients.
 */
object HttpClientFactory {
    
    /**
     * Creates a new HTTP client with standard configuration.
     */
    fun create(): HttpClient {
        return HttpClient(CIO) {
            // JSON serialization
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    prettyPrint = false
                })
            }
            
            // Logging (debug only)
            install(Logging) {
                level = LogLevel.NONE // Set to INFO or BODY for debugging
            }
            
            // Timeouts
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 60_000
            }
            
            // Default request configuration
            defaultRequest {
                headers.append("User-Agent", "VideoTranslator/1.0")
            }
            
            // Retry on failure
            install(HttpRequestRetry) {
                maxRetries = 3
                retryIf { _, response ->
                    response.status.value in listOf(408, 429, 500, 502, 503, 504)
                }
                delayMillis { retry ->
                    retry * 1000L // Linear backoff
                }
            }
        }
    }
}
