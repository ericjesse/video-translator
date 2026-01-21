package com.ericjesse.videotranslator.domain.service.api

import com.ericjesse.videotranslator.domain.model.Glossary
import com.ericjesse.videotranslator.domain.model.Language
import com.ericjesse.videotranslator.domain.model.Subtitles
import com.ericjesse.videotranslator.domain.pipeline.StageProgress
import kotlinx.coroutines.flow.Flow

/**
 * Service interface for translating subtitles.
 * Provides abstraction over translation backends (LibreTranslate, DeepL, OpenAI, Google).
 */
interface TranslationServiceApi {

    /**
     * Translates subtitles from source to target language.
     *
     * @param subtitles Source subtitles to translate.
     * @param targetLanguage Target language for translation.
     * @return Flow of progress updates during translation.
     */
    fun translate(subtitles: Subtitles, targetLanguage: Language): Flow<StageProgress>

    /**
     * Returns the result of the last translation.
     *
     * @throws IllegalStateException if no translation has been performed.
     */
    fun getTranslationResult(): Subtitles

    /**
     * Sets the glossary to use for translations.
     */
    fun setGlossary(glossary: Glossary?)

    /**
     * Clears the translation cache.
     */
    fun clearCache()

    /**
     * Ensures the local LibreTranslate server is running.
     * Call this before translation if using the local server.
     *
     * @return true if the server is running or was started successfully.
     */
    suspend fun ensureLocalServiceRunning(): Boolean
}
