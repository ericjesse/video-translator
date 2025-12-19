package com.ericjesse.videotranslator.ui.i18n

import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.oshai.kotlinlogging.KotlinLogging
import java.text.MessageFormat
import java.util.*

private val logger = KotlinLogging.logger {}

/**
 * Manages internationalization (i18n) for the application.
 * Uses properties files for message resources with placeholder support.
 * 
 * Supports English, German, and French.
 */
class I18nManager(private val configManager: ConfigManager) {
    
    private val _currentLocale = MutableStateFlow(Locale.ENGLISH)
    val currentLocale: StateFlow<Locale> = _currentLocale.asStateFlow()
    
    private var resourceBundle: ResourceBundle = loadBundle(Locale.ENGLISH)
    
    init {
        // Load saved locale
        val savedLang = configManager.getSettings().language
        val locale = Locale.fromCode(savedLang) ?: Locale.ENGLISH
        setLocale(locale)
    }
    
    /**
     * Changes the current locale.
     */
    fun setLocale(locale: Locale) {
        _currentLocale.value = locale
        resourceBundle = loadBundle(locale)
        
        // Persist
        configManager.updateSettings { it.copy(language = locale.code) }
        logger.info { "Locale changed to ${locale.code}" }
    }
    
    /**
     * Gets a message by key.
     */
    fun get(key: String): String {
        return try {
            resourceBundle.getString(key)
        } catch (e: MissingResourceException) {
            logger.warn { "Missing translation key: $key" }
            key
        }
    }
    
    /**
     * Gets a message by key with argument substitution.
     * Placeholders use {0}, {1}, etc. format.
     */
    fun format(key: String, vararg args: Any): String {
        val pattern = get(key)
        return try {
            MessageFormat.format(pattern, *args)
        } catch (e: Exception) {
            logger.warn { "Failed to format message '$key' with args: ${args.toList()}" }
            pattern
        }
    }
    
    /**
     * Operator for convenient access: i18n["key"]
     */
    operator fun get(key: String, vararg args: Any): String {
        return if (args.isEmpty()) get(key) else format(key, *args)
    }
    
    private fun loadBundle(locale: Locale): ResourceBundle {
        val javaLocale = java.util.Locale.forLanguageTag(locale.code)
        return try {
            ResourceBundle.getBundle("i18n/messages", javaLocale, UTF8Control())
        } catch (e: MissingResourceException) {
            logger.error { "Failed to load resource bundle for locale ${locale.code}, falling back to English" }
            ResourceBundle.getBundle("i18n/messages", java.util.Locale.ENGLISH, UTF8Control())
        }
    }
}

/**
 * Supported locales.
 */
enum class Locale(val code: String, val displayName: String, val nativeName: String) {
    ENGLISH("en", "English", "English"),
    GERMAN("de", "German", "Deutsch"),
    FRENCH("fr", "French", "Français");
    
    companion object {
        fun fromCode(code: String): Locale? = entries.find { it.code == code }
    }
}

/**
 * Custom ResourceBundle.Control for UTF-8 properties files.
 */
private class UTF8Control : ResourceBundle.Control() {
    override fun newBundle(
        baseName: String,
        locale: java.util.Locale,
        format: String,
        loader: ClassLoader,
        reload: Boolean
    ): ResourceBundle? {
        val bundleName = toBundleName(baseName, locale)
        val resourceName = toResourceName(bundleName, "properties")
        
        val stream = if (reload) {
            loader.getResource(resourceName)?.openConnection()?.apply {
                useCaches = false
            }?.getInputStream()
        } else {
            loader.getResourceAsStream(resourceName)
        }
        
        return stream?.use { 
            PropertyResourceBundle(it.reader(Charsets.UTF_8)) 
        }
    }
}
