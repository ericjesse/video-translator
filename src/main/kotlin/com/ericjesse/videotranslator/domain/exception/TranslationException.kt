package com.ericjesse.videotranslator.domain.exception

import com.ericjesse.videotranslator.domain.model.Language
import com.ericjesse.videotranslator.domain.model.TranslationService

/**
 * Exception hierarchy for translation errors.
 * Provides structured error handling for translation operations.
 */
sealed class TranslationDomainException(
    message: String,
    cause: Throwable? = null,
    userMessage: String,
    suggestion: String? = null,
    isRetryable: Boolean = false,
) : DomainException(message, cause, userMessage, suggestion, isRetryable) {

    /**
     * Rate limit exceeded for a translation service.
     */
    class RateLimitExceeded(
        val service: TranslationService,
        val retryAfterSeconds: Int? = null,
        cause: Throwable? = null,
    ) : TranslationDomainException(
        message = "Rate limit exceeded for ${service.displayName}",
        cause = cause,
        userMessage = "Too many requests to ${service.displayName}.",
        suggestion = retryAfterSeconds?.let { "Please wait $it seconds before trying again." }
            ?: "Please wait a few minutes before trying again.",
        isRetryable = true
    )

    /**
     * Invalid or missing API key for a translation service.
     */
    class InvalidApiKey(
        val service: TranslationService,
        cause: Throwable? = null,
    ) : TranslationDomainException(
        message = "Invalid API key for ${service.displayName}",
        cause = cause,
        userMessage = "Invalid API key for ${service.displayName}.",
        suggestion = "Please check your API key in Settings.",
        isRetryable = false
    )

    /**
     * Translation service is unavailable.
     */
    class ServiceUnavailable(
        val service: TranslationService,
        cause: Throwable? = null,
    ) : TranslationDomainException(
        message = "${service.displayName} service is unavailable",
        cause = cause,
        userMessage = "${service.displayName} is currently unavailable.",
        suggestion = "Please try again later or use a different translation service.",
        isRetryable = true
    )

    /**
     * The source/target language pair is not supported.
     */
    class UnsupportedLanguagePair(
        val source: Language,
        val target: Language,
        val service: TranslationService,
        cause: Throwable? = null,
    ) : TranslationDomainException(
        message = "Unsupported language pair: ${source.code} -> ${target.code} for ${service.displayName}",
        cause = cause,
        userMessage = "Translation from ${source.displayName} to ${target.displayName} is not supported by ${service.displayName}.",
        suggestion = "Try using a different translation service or language pair.",
        isRetryable = false
    )

    /**
     * Network error during translation.
     */
    class NetworkError(
        val service: TranslationService? = null,
        cause: Throwable? = null,
    ) : TranslationDomainException(
        message = "Network error during translation${service?.let { " with ${it.displayName}" } ?: ""}",
        cause = cause,
        userMessage = "Network error during translation. Please check your internet connection.",
        suggestion = "Check your internet connection and try again.",
        isRetryable = true
    )

    /**
     * Service not configured (e.g., missing API key or server not running).
     */
    class ServiceNotConfigured(
        val service: TranslationService,
        cause: Throwable? = null,
    ) : TranslationDomainException(
        message = "${service.displayName} is not configured",
        cause = cause,
        userMessage = "${service.displayName} is not configured.",
        suggestion = when (service) {
            TranslationService.LIBRE_TRANSLATE -> "Please ensure the local translation server is running."
            else -> "Please configure your API key in Settings."
        },
        isRetryable = false
    )

    /**
     * All translation services failed.
     */
    class AllServicesFailed(
        val services: List<TranslationService>,
        cause: Throwable? = null,
    ) : TranslationDomainException(
        message = "All translation services failed: ${services.map { it.displayName }}",
        cause = cause,
        userMessage = "All translation services failed.",
        suggestion = "Please check your configuration and try again.",
        isRetryable = true
    )

    /**
     * Unknown translation error.
     */
    class Unknown(
        message: String,
        val service: TranslationService? = null,
        cause: Throwable? = null,
    ) : TranslationDomainException(
        message = message,
        cause = cause,
        userMessage = "Translation failed${service?.let { " with ${it.displayName}" } ?: ""}.",
        suggestion = "Please try again. If the problem persists, check the logs for details.",
        isRetryable = true
    )

    companion object {
        /**
         * Creates a TranslationDomainException from the existing TranslationException.
         */
        fun fromTranslationException(
            e: com.ericjesse.videotranslator.domain.model.TranslationException,
        ): TranslationDomainException {
            return when {
                e.userMessage.contains("rate limit", ignoreCase = true) ->
                    e.service?.let { RateLimitExceeded(it, cause = e) }
                        ?: Unknown(e.userMessage, cause = e)

                e.userMessage.contains("api key", ignoreCase = true) ||
                        e.userMessage.contains("invalid", ignoreCase = true) ->
                    e.service?.let { InvalidApiKey(it, e) }
                        ?: Unknown(e.userMessage, cause = e)

                e.userMessage.contains("unavailable", ignoreCase = true) ->
                    e.service?.let { ServiceUnavailable(it, e) }
                        ?: Unknown(e.userMessage, cause = e)

                else -> Unknown(e.userMessage, e.service, e)
            }
        }
    }
}
