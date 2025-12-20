package com.ericjesse.videotranslator.domain.service

import com.ericjesse.videotranslator.domain.model.*
import com.ericjesse.videotranslator.domain.pipeline.StageProgress
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import com.ericjesse.videotranslator.infrastructure.process.ProcessExecutor
import com.ericjesse.videotranslator.infrastructure.process.ProcessException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Service for downloading YouTube videos and extracting captions.
 * Wraps the yt-dlp binary with full feature support.
 *
 * Features:
 * - YouTube URL validation (youtube.com, youtu.be, shorts)
 * - Video info fetching with proper JSON parsing
 * - Audio-only download when captions exist (saves bandwidth)
 * - Caption extraction for multiple languages
 * - Age-restricted video handling (cookies support)
 * - Download speed limiting
 * - User-friendly error messages
 *
 * @property processExecutor Executor for running yt-dlp commands.
 * @property platformPaths Platform-specific paths for binaries and cache.
 */
class VideoDownloader(
    private val processExecutor: ProcessExecutor,
    private val platformPaths: PlatformPaths
) {

    private val ytDlpPath: String
        get() = platformPaths.getBinaryPath("yt-dlp")

    private val downloadDir: File
        get() = File(platformPaths.cacheDir, "downloads").also { it.mkdirs() }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    companion object {
        /**
         * Regex patterns for validating YouTube URLs.
         * Supports:
         * - youtube.com/watch?v=VIDEO_ID
         * - youtu.be/VIDEO_ID
         * - youtube.com/shorts/VIDEO_ID
         * - youtube.com/embed/VIDEO_ID
         * - youtube.com/v/VIDEO_ID
         * - youtube.com/live/VIDEO_ID
         */
        private val YOUTUBE_PATTERNS = listOf(
            // Standard watch URLs
            Regex("""(?:https?://)?(?:www\.)?youtube\.com/watch\?(?:.*&)?v=([a-zA-Z0-9_-]{11})(?:&.*)?"""),
            // Short URLs
            Regex("""(?:https?://)?(?:www\.)?youtu\.be/([a-zA-Z0-9_-]{11})(?:\?.*)?"""),
            // Shorts
            Regex("""(?:https?://)?(?:www\.)?youtube\.com/shorts/([a-zA-Z0-9_-]{11})(?:\?.*)?"""),
            // Embed URLs
            Regex("""(?:https?://)?(?:www\.)?youtube\.com/embed/([a-zA-Z0-9_-]{11})(?:\?.*)?"""),
            // Old-style URLs
            Regex("""(?:https?://)?(?:www\.)?youtube\.com/v/([a-zA-Z0-9_-]{11})(?:\?.*)?"""),
            // Live URLs
            Regex("""(?:https?://)?(?:www\.)?youtube\.com/live/([a-zA-Z0-9_-]{11})(?:\?.*)?"""),
            // Music URLs
            Regex("""(?:https?://)?music\.youtube\.com/watch\?(?:.*&)?v=([a-zA-Z0-9_-]{11})(?:&.*)?""")
        )

        /** Maximum allowed download speed limit in KB/s. */
        const val MAX_RATE_LIMIT_KBPS = 100_000

        /** Minimum allowed download speed limit in KB/s. */
        const val MIN_RATE_LIMIT_KBPS = 50
    }

    // ==================== URL Validation ====================

    /**
     * Validates if the given URL is a valid YouTube URL.
     *
     * @param url The URL to validate.
     * @return true if the URL is a valid YouTube video URL.
     */
    fun isValidYouTubeUrl(url: String): Boolean {
        return YOUTUBE_PATTERNS.any { it.matches(url.trim()) }
    }

    /**
     * Extracts the video ID from a YouTube URL.
     *
     * @param url The YouTube URL.
     * @return The video ID, or null if the URL is invalid.
     */
    fun extractVideoId(url: String): String? {
        for (pattern in YOUTUBE_PATTERNS) {
            val match = pattern.find(url.trim())
            if (match != null) {
                return match.groupValues.getOrNull(1)
            }
        }
        return null
    }

    /**
     * Validates a URL and throws if invalid.
     *
     * @param url The URL to validate.
     * @throws YtDlpException if the URL is not a valid YouTube URL.
     */
    fun validateUrl(url: String) {
        if (!isValidYouTubeUrl(url)) {
            throw YtDlpException(
                errorType = YtDlpErrorType.INVALID_URL,
                userMessage = "Invalid YouTube URL. Please provide a valid youtube.com or youtu.be link.",
                technicalMessage = "URL does not match any known YouTube patterns: $url"
            )
        }
    }

    // ==================== Video Info ====================

    /**
     * Fetches complete video metadata without downloading.
     * Uses kotlinx.serialization for proper JSON parsing.
     *
     * @param url The YouTube URL to fetch info for.
     * @param options Optional download options (for cookies, etc.).
     * @return Complete video information from yt-dlp.
     * @throws YtDlpException if the fetch fails.
     */
    suspend fun fetchVideoInfoFull(
        url: String,
        options: YtDlpDownloadOptions = YtDlpDownloadOptions()
    ): YtDlpVideoInfo {
        validateUrl(url)

        val command = buildList {
            add(ytDlpPath)
            add("--dump-json")
            add("--no-download")
            add("--no-playlist")

            // Add cookie options for age-restricted content
            addCookieOptions(options)

            add(url)
        }

        logger.debug { "Fetching video info: ${command.joinToString(" ")}" }

        val output = StringBuilder()
        val errors = StringBuilder()

        try {
            processExecutor.execute(command) { line ->
                // yt-dlp outputs JSON on stdout, progress/warnings on stderr
                if (line.trimStart().startsWith("{")) {
                    output.append(line)
                } else {
                    errors.appendLine(line)
                    logger.debug { "[yt-dlp] $line" }
                }
            }

            val jsonStr = output.toString().trim()
            if (jsonStr.isEmpty()) {
                throw YtDlpException(
                    errorType = YtDlpErrorType.UNKNOWN,
                    userMessage = "Could not retrieve video information.",
                    technicalMessage = "Empty JSON response. Errors: ${errors.toString().take(500)}"
                )
            }

            return json.decodeFromString<YtDlpVideoInfo>(jsonStr)

        } catch (e: ProcessException) {
            throw YtDlpException.fromOutput(errors.toString().ifBlank { e.message ?: "" }, e.exitCode)
        } catch (e: YtDlpException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse video info JSON" }
            throw YtDlpException(
                errorType = YtDlpErrorType.UNKNOWN,
                userMessage = "Failed to parse video information.",
                technicalMessage = "JSON parse error: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Fetches simplified video metadata.
     * Convenience method that returns the simpler VideoInfo model.
     *
     * @param url The YouTube URL.
     * @param options Optional download options.
     * @return Simplified video information.
     */
    suspend fun fetchVideoInfo(
        url: String,
        options: YtDlpDownloadOptions = YtDlpDownloadOptions()
    ): VideoInfo {
        return fetchVideoInfoFull(url, options).toVideoInfo()
    }

    // ==================== Download ====================

    /**
     * Downloads a YouTube video.
     * Emits progress updates as the download proceeds.
     *
     * @param videoInfo Video to download.
     * @param options Download options (format, speed limit, cookies, etc.).
     * @return Flow of progress updates.
     */
    fun download(
        videoInfo: VideoInfo,
        options: YtDlpDownloadOptions = YtDlpDownloadOptions()
    ): Flow<StageProgress> = flow {
        emit(StageProgress(0f, "Initializing download..."))

        val outputPath = getDownloadedVideoPath(videoInfo, options.audioOnly)

        val command = buildList {
            add(ytDlpPath)
            add("--no-playlist")

            // Format selection
            if (options.audioOnly) {
                add("--format")
                add("bestaudio[ext=m4a]/bestaudio/best")
                add("--extract-audio")
                add("--audio-format")
                add("m4a")
            } else {
                add("--format")
                val heightFilter = options.maxHeight?.let { "[height<=$it]" } ?: ""
                add("bestvideo${heightFilter}[ext=mp4]+bestaudio[ext=m4a]/best${heightFilter}[ext=mp4]/best")
                add("--merge-output-format")
                add(options.preferredFormat)
            }

            add("--output")
            add(outputPath)
            add("--progress")
            add("--newline")

            // Rate limiting
            options.rateLimitKbps?.let { limit ->
                val safeLimit = limit.coerceIn(MIN_RATE_LIMIT_KBPS, MAX_RATE_LIMIT_KBPS)
                add("--rate-limit")
                add("${safeLimit}K")
                logger.debug { "Download rate limited to ${safeLimit}KB/s" }
            }

            // Cookie options for age-restricted content
            addCookieOptions(options)

            // Subtitle options
            if (options.writeSubtitles && options.subtitleLanguages.isNotEmpty()) {
                add("--write-sub")
                add("--write-auto-sub")
                add("--sub-lang")
                add(options.subtitleLanguages.joinToString(","))
                add("--sub-format")
                add("vtt")

                if (options.embedSubtitles) {
                    add("--embed-subs")
                }
            }

            add(videoInfo.url)
        }

        logger.info { "Starting download: ${videoInfo.title}" }
        logger.debug { "Command: ${command.joinToString(" ")}" }

        val errors = StringBuilder()

        try {
            processExecutor.execute(command) { line ->
                val progress = parseProgress(line)
                if (progress != null) {
                    emit(progress)
                } else {
                    errors.appendLine(line)
                }
            }

            emit(StageProgress(1f, "Download complete"))
            logger.info { "Download complete: $outputPath" }

        } catch (e: ProcessException) {
            throw YtDlpException.fromOutput(errors.toString().ifBlank { e.message ?: "" }, e.exitCode)
        }
    }

    /**
     * Downloads only audio from a video.
     * Useful when captions are available and video isn't needed for processing.
     *
     * @param videoInfo Video to extract audio from.
     * @param options Download options.
     * @return Flow of progress updates.
     */
    fun downloadAudioOnly(
        videoInfo: VideoInfo,
        options: YtDlpDownloadOptions = YtDlpDownloadOptions()
    ): Flow<StageProgress> {
        return download(videoInfo, options.copy(audioOnly = true))
    }

    // ==================== Captions ====================

    /**
     * Extracts existing captions from a video for multiple languages.
     * Tries manual captions first, falls back to automatic captions.
     *
     * @param videoInfo Video to extract captions from.
     * @param languages List of language codes to try, in order of preference.
     * @param preferManual If true, prefers manual captions over automatic.
     * @param options Download options (for cookies, etc.).
     * @return List of successfully downloaded captions.
     */
    suspend fun extractCaptions(
        videoInfo: VideoInfo,
        languages: List<String>,
        preferManual: Boolean = true,
        options: YtDlpDownloadOptions = YtDlpDownloadOptions()
    ): List<CaptionDownloadResult> {
        if (languages.isEmpty()) {
            logger.debug { "No languages specified for caption extraction" }
            return emptyList()
        }

        val results = mutableListOf<CaptionDownloadResult>()
        val outputDir = File(downloadDir, videoInfo.id).also { it.mkdirs() }

        for (langCode in languages) {
            try {
                val result = extractCaptionForLanguage(videoInfo, langCode, preferManual, outputDir, options)
                if (result != null) {
                    results.add(result)
                    logger.info { "Extracted captions for language: $langCode (auto: ${result.isAutoGenerated})" }
                }
            } catch (e: Exception) {
                logger.debug { "Failed to extract captions for $langCode: ${e.message}" }
            }
        }

        return results
    }

    /**
     * Extracts captions for a single language.
     */
    private suspend fun extractCaptionForLanguage(
        videoInfo: VideoInfo,
        langCode: String,
        preferManual: Boolean,
        outputDir: File,
        options: YtDlpDownloadOptions
    ): CaptionDownloadResult? {
        val command = buildList {
            add(ytDlpPath)
            add("--skip-download")

            if (preferManual) {
                add("--write-sub")
            }
            add("--write-auto-sub")

            add("--sub-lang")
            add(langCode)
            add("--sub-format")
            add("vtt")
            add("--output")
            add(File(outputDir, videoInfo.id).absolutePath)

            addCookieOptions(options)

            add(videoInfo.url)
        }

        logger.debug { "Extracting captions for $langCode: ${command.joinToString(" ")}" }

        try {
            processExecutor.execute(command) { line ->
                logger.trace { "[yt-dlp] $line" }
            }

            // Check for downloaded subtitle files
            val manualSubFile = File(outputDir, "${videoInfo.id}.$langCode.vtt")
            val autoSubFile = File(outputDir, "${videoInfo.id}.$langCode.vtt")

            // yt-dlp names auto-subs with language code
            val foundFile = when {
                manualSubFile.exists() -> manualSubFile
                autoSubFile.exists() -> autoSubFile
                else -> {
                    // Try to find any matching subtitle file
                    outputDir.listFiles()?.find {
                        it.name.startsWith(videoInfo.id) &&
                                it.name.contains(langCode) &&
                                it.extension == "vtt"
                    }
                }
            }

            return if (foundFile != null && foundFile.exists()) {
                CaptionDownloadResult(
                    language = langCode,
                    isAutoGenerated = foundFile.name.contains(".auto.", ignoreCase = true) ||
                            !preferManual,
                    format = "vtt",
                    filePath = foundFile.absolutePath
                )
            } else {
                null
            }

        } catch (e: Exception) {
            logger.debug { "Caption extraction failed for $langCode: ${e.message}" }
            return null
        }
    }

    /**
     * Attempts to extract existing captions from the video.
     * Returns null if no captions are available.
     *
     * @param videoInfo Video to extract captions from.
     * @param preferredLanguage Preferred language for captions.
     * @return Subtitles if available, null otherwise.
     */
    suspend fun extractCaptions(videoInfo: VideoInfo, preferredLanguage: Language?): Subtitles? {
        val langCode = preferredLanguage?.code ?: "en"
        val results = extractCaptions(
            videoInfo = videoInfo,
            languages = listOf(langCode),
            preferManual = true
        )

        val result = results.firstOrNull() ?: return null
        val file = File(result.filePath)

        if (!file.exists()) return null

        return try {
            val entries = parseVttFile(file)
            val language = preferredLanguage ?: Language.ENGLISH
            file.delete()
            Subtitles(entries, language)
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse VTT file" }
            null
        }
    }

    /**
     * Gets available caption languages for a video.
     *
     * @param url YouTube URL.
     * @param options Download options.
     * @return List of available language codes.
     */
    suspend fun getAvailableCaptionLanguages(
        url: String,
        options: YtDlpDownloadOptions = YtDlpDownloadOptions()
    ): List<String> {
        val info = fetchVideoInfoFull(url, options)
        return info.getAvailableCaptionLanguages()
    }

    // ==================== Utility ====================

    /**
     * Gets the path where a video would be downloaded.
     *
     * @param videoInfo Video information.
     * @param audioOnly Whether audio-only format is used.
     * @return Absolute path to the downloaded file.
     */
    fun getDownloadedVideoPath(videoInfo: VideoInfo, audioOnly: Boolean = false): String {
        val extension = if (audioOnly) "m4a" else "mp4"
        return File(downloadDir, "${videoInfo.id}.$extension").absolutePath
    }

    /**
     * Checks if yt-dlp is installed and available.
     *
     * @return true if yt-dlp is available.
     */
    suspend fun isAvailable(): Boolean {
        return processExecutor.isAvailable(ytDlpPath)
    }

    /**
     * Gets the installed yt-dlp version.
     *
     * @return Version string, or null if not available.
     */
    suspend fun getVersion(): String? {
        return processExecutor.getVersion(ytDlpPath)
    }

    // ==================== Private Helpers ====================

    private fun MutableList<String>.addCookieOptions(options: YtDlpDownloadOptions) {
        // Cookie file takes precedence
        options.cookiesFile?.let { cookieFile ->
            if (File(cookieFile).exists()) {
                add("--cookies")
                add(cookieFile)
                logger.debug { "Using cookies from file: $cookieFile" }
            } else {
                logger.warn { "Cookie file not found: $cookieFile" }
            }
        }

        // Fall back to browser cookies
        if (options.cookiesFile == null && options.cookiesFromBrowser != null) {
            add("--cookies-from-browser")
            add(options.cookiesFromBrowser)
            logger.debug { "Using cookies from browser: ${options.cookiesFromBrowser}" }
        }
    }

    private fun parseProgress(line: String): StageProgress? {
        // yt-dlp progress format: [download]  45.2% of 123.45MiB at 5.67MiB/s ETA 00:15
        val percentRegex = """(\d+\.?\d*)%""".toRegex()
        val match = percentRegex.find(line)

        return match?.let {
            val percent = it.groupValues[1].toFloatOrNull()?.div(100f) ?: return null
            val speedMatch = """at\s+([\d.]+\s*\w+/s)""".toRegex().find(line)
            val etaMatch = """ETA\s+(\d+:\d+(?::\d+)?)""".toRegex().find(line)

            val message = buildString {
                append("Downloading: ${(percent * 100).toInt()}%")
                speedMatch?.let { append(" • ${it.groupValues[1]}") }
                etaMatch?.let { append(" • ETA ${it.groupValues[1]}") }
            }

            StageProgress(percent, message)
        }
    }

    private fun parseVttFile(file: File): List<SubtitleEntry> {
        val entries = mutableListOf<SubtitleEntry>()
        val lines = file.readLines()

        var index = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()

            // Look for timestamp lines like "00:00:00.000 --> 00:00:05.000"
            if (line.contains("-->")) {
                val parts = line.split("-->").map { it.trim().split(" ").first() }
                if (parts.size == 2) {
                    val startTime = parseVttTimestamp(parts[0])
                    val endTime = parseVttTimestamp(parts[1])

                    // Collect text lines until empty line
                    val textLines = mutableListOf<String>()
                    i++
                    while (i < lines.size && lines[i].isNotBlank()) {
                        // Remove VTT styling tags like <c>, </c>, <00:00:01.234>, etc.
                        val cleanedLine = lines[i].trim()
                            .replace(Regex("<[^>]+>"), "")
                            .trim()
                        if (cleanedLine.isNotEmpty()) {
                            textLines.add(cleanedLine)
                        }
                        i++
                    }

                    if (textLines.isNotEmpty()) {
                        entries.add(
                            SubtitleEntry(
                                index = index++,
                                startTime = startTime,
                                endTime = endTime,
                                text = textLines.joinToString(" ")
                            )
                        )
                    }
                }
            }
            i++
        }

        return entries
    }

    private fun parseVttTimestamp(timestamp: String): Long {
        // Format: HH:MM:SS.mmm or MM:SS.mmm
        val parts = timestamp.split(":", ".")
        return when (parts.size) {
            4 -> { // HH:MM:SS.mmm
                val hours = parts[0].toLongOrNull() ?: 0
                val minutes = parts[1].toLongOrNull() ?: 0
                val seconds = parts[2].toLongOrNull() ?: 0
                val millis = parts[3].toLongOrNull() ?: 0
                (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
            }
            3 -> { // MM:SS.mmm
                val minutes = parts[0].toLongOrNull() ?: 0
                val seconds = parts[1].toLongOrNull() ?: 0
                val millis = parts[2].toLongOrNull() ?: 0
                (minutes * 60 + seconds) * 1000 + millis
            }
            else -> 0
        }
    }
}
