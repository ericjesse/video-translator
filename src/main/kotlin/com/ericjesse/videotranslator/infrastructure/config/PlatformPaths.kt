package com.ericjesse.videotranslator.infrastructure.config

import java.io.File

/**
 * Provides OS-specific paths for configuration, data, and binaries.
 * Follows platform conventions:
 * - Windows: %APPDATA% and %LOCALAPPDATA%
 * - macOS: ~/Library/Application Support
 * - Linux: XDG directories (~/.config and ~/.local/share)
 */
class PlatformPaths {
    
    private val os: OperatingSystem = detectOS()
    private val appName = "VideoTranslator"
    private val appNameLower = "video-translator"
    
    /**
     * Directory for configuration files (settings.json, etc.)
     */
    val configDir: String by lazy {
        val dir = when (os) {
            OperatingSystem.WINDOWS -> {
                val appData = System.getenv("APPDATA") ?: "${System.getProperty("user.home")}\\AppData\\Roaming"
                "$appData\\$appName"
            }
            OperatingSystem.MACOS -> {
                "${System.getProperty("user.home")}/Library/Application Support/$appName"
            }
            OperatingSystem.LINUX -> {
                val xdgConfig = System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config"
                "$xdgConfig/$appNameLower"
            }
        }
        File(dir).mkdirs()
        dir
    }
    
    /**
     * Directory for data files (binaries, models, cache)
     */
    val dataDir: String by lazy {
        val dir = when (os) {
            OperatingSystem.WINDOWS -> {
                val localAppData = System.getenv("LOCALAPPDATA") ?: "${System.getProperty("user.home")}\\AppData\\Local"
                "$localAppData\\$appName"
            }
            OperatingSystem.MACOS -> {
                "${System.getProperty("user.home")}/Library/Application Support/$appName"
            }
            OperatingSystem.LINUX -> {
                val xdgData = System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share"
                "$xdgData/$appNameLower"
            }
        }
        File(dir).mkdirs()
        dir
    }
    
    /**
     * Directory for binary dependencies (yt-dlp, ffmpeg, whisper)
     */
    val binDir: String by lazy {
        val dir = "$dataDir${File.separator}bin"
        File(dir).mkdirs()
        dir
    }
    
    /**
     * Directory for Whisper models
     */
    val modelsDir: String by lazy {
        val dir = "$dataDir${File.separator}models"
        File(dir).mkdirs()
        dir
    }
    
    /**
     * Directory for temporary files and downloads
     */
    val cacheDir: String by lazy {
        val dir = "$dataDir${File.separator}cache"
        File(dir).mkdirs()
        dir
    }

    /**
     * Directory for LibreTranslate virtual environment and data
     */
    val libreTranslateDir: String by lazy {
        val dir = "$dataDir${File.separator}libretranslate"
        File(dir).mkdirs()
        dir
    }
    
    /**
     * Current operating system
     */
    val operatingSystem: OperatingSystem = os
    
    /**
     * Gets the full path to a binary, with platform-specific extension.
     */
    fun getBinaryPath(name: String): String {
        val binaryName = when (os) {
            OperatingSystem.WINDOWS -> "$name.exe"
            else -> name
        }
        return "$binDir${File.separator}$binaryName"
    }
    
    /**
     * Checks if a binary exists.
     */
    fun binaryExists(name: String): Boolean {
        return File(getBinaryPath(name)).exists()
    }
    
    /**
     * Checks if a Whisper model exists.
     */
    fun whisperModelExists(modelName: String): Boolean {
        val modelPath = "$modelsDir${File.separator}whisper${File.separator}ggml-$modelName.bin"
        return File(modelPath).exists()
    }
    
    /**
     * Gets the settings file path.
     */
    val settingsFile: String
        get() = "$configDir${File.separator}settings.json"
    
    /**
     * Gets the services configuration file path.
     */
    val servicesFile: String
        get() = "$configDir${File.separator}services.json"
    
    /**
     * Gets the versions file path (tracks installed dependency versions).
     */
    val versionsFile: String
        get() = "$dataDir${File.separator}versions.json"
    
    private fun detectOS(): OperatingSystem {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("win") -> OperatingSystem.WINDOWS
            osName.contains("mac") || osName.contains("darwin") -> OperatingSystem.MACOS
            else -> OperatingSystem.LINUX
        }
    }
}

/**
 * Supported operating systems.
 */
enum class OperatingSystem {
    WINDOWS,
    MACOS,
    LINUX
}
