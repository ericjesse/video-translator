package com.ericjesse.videotranslator.domain.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.*

class YtDlpModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ==================== YtDlpVideoInfo Tests ====================

    @Nested
    inner class YtDlpVideoInfoTest {

        @Test
        fun `deserializes minimal JSON correctly`() {
            val jsonStr = """
                {
                    "id": "dQw4w9WgXcQ",
                    "title": "Test Video"
                }
            """.trimIndent()

            val info = json.decodeFromString<YtDlpVideoInfo>(jsonStr)

            assertEquals("dQw4w9WgXcQ", info.id)
            assertEquals("Test Video", info.title)
            assertNull(info.duration)
            assertNull(info.description)
        }

        @Test
        fun `deserializes full JSON correctly`() {
            val jsonStr = """
                {
                    "id": "abc123",
                    "title": "Full Test Video",
                    "description": "A test description",
                    "upload_date": "20231215",
                    "uploader": "Test Channel",
                    "uploader_id": "@testchannel",
                    "duration": 300,
                    "view_count": 1000000,
                    "like_count": 50000,
                    "thumbnail": "https://example.com/thumb.jpg",
                    "age_limit": 0,
                    "webpage_url": "https://www.youtube.com/watch?v=abc123",
                    "is_live": false,
                    "was_live": false,
                    "extractor": "youtube"
                }
            """.trimIndent()

            val info = json.decodeFromString<YtDlpVideoInfo>(jsonStr)

            assertEquals("abc123", info.id)
            assertEquals("Full Test Video", info.title)
            assertEquals("A test description", info.description)
            assertEquals("20231215", info.uploadDate)
            assertEquals("Test Channel", info.uploader)
            assertEquals(300.0, info.duration)
            assertEquals(1000000L, info.viewCount)
            assertEquals(50000L, info.likeCount)
            assertEquals("https://example.com/thumb.jpg", info.thumbnail)
            assertEquals(0, info.ageLimit)
            assertFalse(info.isLive ?: true)
            assertFalse(info.wasLive ?: true)
        }

        @Test
        fun `hasCaptions returns false when no captions`() {
            val info = YtDlpVideoInfo(
                id = "test",
                title = "Test",
                subtitles = null,
                automaticCaptions = null
            )

            assertFalse(info.hasCaptions)
        }

        @Test
        fun `hasCaptions returns true with manual subtitles`() {
            val info = YtDlpVideoInfo(
                id = "test",
                title = "Test",
                subtitles = mapOf(
                    "en" to listOf(YtDlpSubtitle(ext = "vtt", url = "http://example.com/en.vtt"))
                ),
                automaticCaptions = null
            )

            assertTrue(info.hasCaptions)
            assertTrue(info.hasManualCaptions)
        }

        @Test
        fun `hasCaptions returns true with automatic captions`() {
            val info = YtDlpVideoInfo(
                id = "test",
                title = "Test",
                subtitles = null,
                automaticCaptions = mapOf(
                    "en" to listOf(YtDlpSubtitle(ext = "vtt", url = "http://example.com/en.vtt"))
                )
            )

            assertTrue(info.hasCaptions)
            assertFalse(info.hasManualCaptions)
        }

        @Test
        fun `isAgeRestricted returns true for age_limit greater than 0`() {
            val restricted = YtDlpVideoInfo(id = "test", title = "Test", ageLimit = 18)
            val notRestricted = YtDlpVideoInfo(id = "test", title = "Test", ageLimit = 0)
            val nullLimit = YtDlpVideoInfo(id = "test", title = "Test", ageLimit = null)

            assertTrue(restricted.isAgeRestricted)
            assertFalse(notRestricted.isAgeRestricted)
            assertFalse(nullLimit.isAgeRestricted)
        }

        @Test
        fun `getAvailableCaptionLanguages prefers manual when available`() {
            val info = YtDlpVideoInfo(
                id = "test",
                title = "Test",
                subtitles = mapOf(
                    "en" to listOf(YtDlpSubtitle(ext = "vtt")),
                    "de" to listOf(YtDlpSubtitle(ext = "vtt"))
                ),
                automaticCaptions = mapOf(
                    "fr" to listOf(YtDlpSubtitle(ext = "vtt")),
                    "es" to listOf(YtDlpSubtitle(ext = "vtt"))
                )
            )

            val languages = info.getAvailableCaptionLanguages(preferManual = true)

            assertTrue(languages.contains("en"))
            assertTrue(languages.contains("de"))
            assertFalse(languages.contains("fr"))
            assertFalse(languages.contains("es"))
        }

        @Test
        fun `getAvailableCaptionLanguages returns automatic when no manual`() {
            val info = YtDlpVideoInfo(
                id = "test",
                title = "Test",
                subtitles = emptyMap(),
                automaticCaptions = mapOf(
                    "fr" to listOf(YtDlpSubtitle(ext = "vtt"))
                )
            )

            val languages = info.getAvailableCaptionLanguages(preferManual = true)

            assertTrue(languages.contains("fr"))
        }

        @Test
        fun `hasCaptionsForLanguage checks both manual and automatic`() {
            val info = YtDlpVideoInfo(
                id = "test",
                title = "Test",
                subtitles = mapOf("en" to listOf(YtDlpSubtitle(ext = "vtt"))),
                automaticCaptions = mapOf("de" to listOf(YtDlpSubtitle(ext = "vtt")))
            )

            assertTrue(info.hasCaptionsForLanguage("en"))
            assertTrue(info.hasCaptionsForLanguage("de"))
            assertFalse(info.hasCaptionsForLanguage("fr"))
        }

        @Test
        fun `toVideoInfo converts correctly`() {
            val ytInfo = YtDlpVideoInfo(
                id = "abc123",
                title = "Test Video",
                duration = 180.0, // seconds from yt-dlp
                thumbnail = "https://example.com/thumb.jpg",
                webpageUrl = "https://www.youtube.com/watch?v=abc123"
            )

            val videoInfo = ytInfo.toVideoInfo()

            assertEquals("abc123", videoInfo.id)
            assertEquals("Test Video", videoInfo.title)
            assertEquals(180000L, videoInfo.duration) // converted to milliseconds
            assertEquals("https://example.com/thumb.jpg", videoInfo.thumbnailUrl)
            assertEquals("https://www.youtube.com/watch?v=abc123", videoInfo.url)
        }

        @Test
        fun `toVideoInfo uses originalUrl when webpageUrl is null`() {
            val ytInfo = YtDlpVideoInfo(
                id = "abc123",
                title = "Test",
                webpageUrl = null,
                originalUrl = "https://youtu.be/abc123"
            )

            val videoInfo = ytInfo.toVideoInfo()
            assertEquals("https://youtu.be/abc123", videoInfo.url)
        }
    }

    // ==================== YtDlpFormat Tests ====================

    @Nested
    inner class YtDlpFormatTest {

        @Test
        fun `hasVideo returns true when vcodec is not none`() {
            val format = YtDlpFormat(formatId = "22", vcodec = "avc1", acodec = "mp4a")
            assertTrue(format.hasVideo)
        }

        @Test
        fun `hasVideo returns false when vcodec is none`() {
            val format = YtDlpFormat(formatId = "140", vcodec = "none", acodec = "mp4a")
            assertFalse(format.hasVideo)
        }

        @Test
        fun `hasAudio returns true when acodec is not none`() {
            val format = YtDlpFormat(formatId = "140", vcodec = "none", acodec = "mp4a")
            assertTrue(format.hasAudio)
        }

        @Test
        fun `hasAudio returns false when acodec is none`() {
            val format = YtDlpFormat(formatId = "247", vcodec = "vp9", acodec = "none")
            assertFalse(format.hasAudio)
        }

        @Test
        fun `isAudioOnly returns true for audio-only formats`() {
            val format = YtDlpFormat(formatId = "140", vcodec = "none", acodec = "mp4a")
            assertTrue(format.isAudioOnly)
        }

        @Test
        fun `isAudioOnly returns false for video formats`() {
            val format = YtDlpFormat(formatId = "22", vcodec = "avc1", acodec = "mp4a")
            assertFalse(format.isAudioOnly)
        }

        @Test
        fun `deserializes format JSON correctly`() {
            val jsonStr = """
                {
                    "format_id": "22",
                    "format_note": "720p",
                    "ext": "mp4",
                    "resolution": "1280x720",
                    "fps": 30.0,
                    "vcodec": "avc1.64001F",
                    "acodec": "mp4a.40.2",
                    "filesize": 52428800,
                    "tbr": 2500.5
                }
            """.trimIndent()

            val format = json.decodeFromString<YtDlpFormat>(jsonStr)

            assertEquals("22", format.formatId)
            assertEquals("720p", format.formatNote)
            assertEquals("mp4", format.ext)
            assertEquals("1280x720", format.resolution)
            assertEquals(30.0f, format.fps)
            assertEquals(52428800L, format.filesize)
        }
    }

    // ==================== YtDlpDownloadOptions Tests ====================

    @Nested
    inner class YtDlpDownloadOptionsTest {

        @Test
        fun `default options are correct`() {
            val options = YtDlpDownloadOptions()

            assertFalse(options.audioOnly)
            assertEquals("mp4", options.preferredFormat)
            assertNull(options.maxHeight)
            assertNull(options.rateLimitKbps)
            assertNull(options.cookiesFile)
            assertNull(options.cookiesFromBrowser)
            assertFalse(options.writeSubtitles)
            assertTrue(options.subtitleLanguages.isEmpty())
            assertFalse(options.embedSubtitles)
            assertTrue(options.noPlaylist)
        }

        @Test
        fun `options can be customized`() {
            val options = YtDlpDownloadOptions(
                audioOnly = true,
                preferredFormat = "webm",
                maxHeight = 720,
                rateLimitKbps = 5000,
                cookiesFromBrowser = "firefox",
                writeSubtitles = true,
                subtitleLanguages = listOf("en", "de"),
                embedSubtitles = true,
                noPlaylist = false
            )

            assertTrue(options.audioOnly)
            assertEquals("webm", options.preferredFormat)
            assertEquals(720, options.maxHeight)
            assertEquals(5000, options.rateLimitKbps)
            assertEquals("firefox", options.cookiesFromBrowser)
            assertTrue(options.writeSubtitles)
            assertEquals(listOf("en", "de"), options.subtitleLanguages)
            assertTrue(options.embedSubtitles)
            assertFalse(options.noPlaylist)
        }

        @Test
        fun `copy preserves unmodified fields`() {
            val original = YtDlpDownloadOptions(
                rateLimitKbps = 1000,
                cookiesFromBrowser = "chrome"
            )

            val copied = original.copy(audioOnly = true)

            assertTrue(copied.audioOnly)
            assertEquals(1000, copied.rateLimitKbps)
            assertEquals("chrome", copied.cookiesFromBrowser)
        }
    }

    // ==================== YtDlpException Tests ====================

    @Nested
    inner class YtDlpExceptionTest {

        @Test
        fun `fromOutput detects video unavailable`() {
            val exception = YtDlpException.fromOutput(
                "ERROR: Video unavailable. This video is not available",
                1
            )

            assertEquals(YtDlpErrorType.VIDEO_NOT_FOUND, exception.errorType)
            assertTrue(exception.userMessage.contains("unavailable"))
        }

        @Test
        fun `fromOutput detects private video`() {
            val exception = YtDlpException.fromOutput(
                "ERROR: Private video. Sign in if you've been granted access",
                1
            )

            assertEquals(YtDlpErrorType.PRIVATE_VIDEO, exception.errorType)
            assertTrue(exception.userMessage.contains("private"))
        }

        @Test
        fun `fromOutput detects age-restricted video`() {
            val outputs = listOf(
                "ERROR: Sign in to confirm your age. This video may be inappropriate",
                "ERROR: This video is age-restricted",
                "ERROR: Sign in to confirm your age"
            )

            outputs.forEach { output ->
                val exception = YtDlpException.fromOutput(output, 1)
                assertEquals(
                    YtDlpErrorType.AGE_RESTRICTED,
                    exception.errorType,
                    "Should detect age restriction in: $output"
                )
            }
        }

        @Test
        fun `fromOutput detects geo-restriction`() {
            val exception = YtDlpException.fromOutput(
                "ERROR: Video not available in your country due to geo blocking",
                1
            )

            assertEquals(YtDlpErrorType.GEO_RESTRICTED, exception.errorType)
            assertTrue(exception.userMessage.lowercase().contains("region"))
        }

        @Test
        fun `fromOutput detects copyright claim`() {
            val exception = YtDlpException.fromOutput(
                "ERROR: This video contains content from Sony, who has blocked it on copyright grounds",
                1
            )

            assertEquals(YtDlpErrorType.COPYRIGHT_CLAIM, exception.errorType)
            assertTrue(exception.userMessage.contains("copyright"))
        }

        @Test
        fun `fromOutput detects live stream`() {
            val exception = YtDlpException.fromOutput(
                "ERROR: This video is live and cannot be downloaded",
                1
            )

            assertEquals(YtDlpErrorType.LIVE_STREAM, exception.errorType)
            assertTrue(exception.userMessage.lowercase().contains("live"))
        }

        @Test
        fun `fromOutput detects rate limiting`() {
            val outputs = listOf(
                "ERROR: HTTP Error 429: Too Many Requests",
                "ERROR: rate limit exceeded"
            )

            outputs.forEach { output ->
                val exception = YtDlpException.fromOutput(output, 1)
                assertEquals(
                    YtDlpErrorType.RATE_LIMITED,
                    exception.errorType,
                    "Should detect rate limit in: $output"
                )
            }
        }

        @Test
        fun `fromOutput detects network error`() {
            val exception = YtDlpException.fromOutput(
                "ERROR: Unable to download webpage: network error",
                1
            )

            assertEquals(YtDlpErrorType.NETWORK_ERROR, exception.errorType)
            assertTrue(exception.userMessage.lowercase().contains("network") ||
                    exception.userMessage.lowercase().contains("connection"))
        }

        @Test
        fun `fromOutput returns UNKNOWN for unrecognized errors`() {
            val exception = YtDlpException.fromOutput(
                "ERROR: Some completely unknown error occurred",
                1
            )

            assertEquals(YtDlpErrorType.UNKNOWN, exception.errorType)
            assertTrue(exception.userMessage.isNotBlank())
        }

        @Test
        fun `exception contains technical message`() {
            val exception = YtDlpException(
                errorType = YtDlpErrorType.VIDEO_NOT_FOUND,
                userMessage = "User friendly message",
                technicalMessage = "Detailed technical info"
            )

            assertEquals("User friendly message", exception.userMessage)
            assertEquals("Detailed technical info", exception.technicalMessage)
            assertEquals("User friendly message", exception.message)
        }

        @Test
        fun `exception preserves cause`() {
            val cause = RuntimeException("Original error")
            val exception = YtDlpException(
                errorType = YtDlpErrorType.UNKNOWN,
                userMessage = "Error",
                technicalMessage = "Details",
                cause = cause
            )

            assertEquals(cause, exception.cause)
        }
    }

    // ==================== YtDlpThumbnail Tests ====================

    @Nested
    inner class YtDlpThumbnailTest {

        @Test
        fun `deserializes thumbnail JSON`() {
            val jsonStr = """
                {
                    "url": "https://example.com/thumb.jpg",
                    "id": "0",
                    "width": 1280,
                    "height": 720,
                    "resolution": "1280x720"
                }
            """.trimIndent()

            val thumbnail = json.decodeFromString<YtDlpThumbnail>(jsonStr)

            assertEquals("https://example.com/thumb.jpg", thumbnail.url)
            assertEquals("0", thumbnail.id)
            assertEquals(1280, thumbnail.width)
            assertEquals(720, thumbnail.height)
            assertEquals("1280x720", thumbnail.resolution)
        }
    }

    // ==================== YtDlpSubtitle Tests ====================

    @Nested
    inner class YtDlpSubtitleTest {

        @Test
        fun `deserializes subtitle JSON`() {
            val jsonStr = """
                {
                    "ext": "vtt",
                    "url": "https://example.com/subs.vtt",
                    "name": "English"
                }
            """.trimIndent()

            val subtitle = json.decodeFromString<YtDlpSubtitle>(jsonStr)

            assertEquals("vtt", subtitle.ext)
            assertEquals("https://example.com/subs.vtt", subtitle.url)
            assertEquals("English", subtitle.name)
        }
    }

    // ==================== CaptionDownloadResult Tests ====================

    @Nested
    inner class CaptionDownloadResultTest {

        @Test
        fun `creates result correctly`() {
            val result = CaptionDownloadResult(
                language = "en",
                isAutoGenerated = false,
                format = "vtt",
                filePath = "/tmp/video.en.vtt"
            )

            assertEquals("en", result.language)
            assertFalse(result.isAutoGenerated)
            assertEquals("vtt", result.format)
            assertEquals("/tmp/video.en.vtt", result.filePath)
        }
    }

    // ==================== Complex JSON Parsing Tests ====================

    @Nested
    inner class ComplexJsonParsingTest {

        @Test
        fun `deserializes video info with formats and subtitles`() {
            val jsonStr = """
                {
                    "id": "test123",
                    "title": "Complex Test",
                    "duration": 600,
                    "formats": [
                        {
                            "format_id": "22",
                            "ext": "mp4",
                            "vcodec": "avc1",
                            "acodec": "mp4a"
                        },
                        {
                            "format_id": "140",
                            "ext": "m4a",
                            "vcodec": "none",
                            "acodec": "mp4a"
                        }
                    ],
                    "subtitles": {
                        "en": [
                            {"ext": "vtt", "url": "http://example.com/en.vtt"}
                        ]
                    },
                    "automatic_captions": {
                        "de": [
                            {"ext": "vtt", "url": "http://example.com/de.vtt"}
                        ]
                    }
                }
            """.trimIndent()

            val info = json.decodeFromString<YtDlpVideoInfo>(jsonStr)

            assertEquals("test123", info.id)
            assertEquals(600.0, info.duration)

            // Verify formats
            assertNotNull(info.formats)
            assertEquals(2, info.formats!!.size)

            val videoFormat = info.formats!!.find { it.formatId == "22" }
            assertNotNull(videoFormat)
            assertTrue(videoFormat!!.hasVideo)
            assertTrue(videoFormat.hasAudio)
            assertFalse(videoFormat.isAudioOnly)

            val audioFormat = info.formats!!.find { it.formatId == "140" }
            assertNotNull(audioFormat)
            assertFalse(audioFormat!!.hasVideo)
            assertTrue(audioFormat.hasAudio)
            assertTrue(audioFormat.isAudioOnly)

            // Verify subtitles
            assertTrue(info.hasCaptions)
            assertTrue(info.hasManualCaptions)
            assertTrue(info.hasCaptionsForLanguage("en"))
            assertTrue(info.hasCaptionsForLanguage("de"))
            assertFalse(info.hasCaptionsForLanguage("fr"))
        }

        @Test
        fun `handles missing optional fields gracefully`() {
            val jsonStr = """{"id": "minimal", "title": "Minimal Video"}"""

            val info = json.decodeFromString<YtDlpVideoInfo>(jsonStr)

            assertNull(info.duration)
            assertNull(info.formats)
            assertNull(info.subtitles)
            assertNull(info.automaticCaptions)
            assertNull(info.thumbnail)
            assertFalse(info.hasCaptions)
            assertFalse(info.isAgeRestricted)
        }

        @Test
        fun `handles unknown JSON fields gracefully`() {
            val jsonStr = """
                {
                    "id": "test",
                    "title": "Test",
                    "unknown_field": "should be ignored",
                    "another_unknown": 12345
                }
            """.trimIndent()

            val info = json.decodeFromString<YtDlpVideoInfo>(jsonStr)

            assertEquals("test", info.id)
            assertEquals("Test", info.title)
        }
    }
}
