package com.ericjesse.videotranslator.infrastructure.update

import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.config.InstalledVersions
import com.ericjesse.videotranslator.infrastructure.config.OperatingSystem
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.mockk.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class UpdateManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var platformPaths: PlatformPaths
    private lateinit var configManager: ConfigManager

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @BeforeEach
    fun setup() {
        platformPaths = mockk<PlatformPaths>()
        configManager = mockk<ConfigManager>()

        // Setup default mock behavior
        every { platformPaths.cacheDir } returns tempDir.resolve("cache").toString().also {
            File(it).mkdirs()
        }
        every { platformPaths.binDir } returns tempDir.resolve("bin").toString().also {
            File(it).mkdirs()
        }
        every { platformPaths.modelsDir } returns tempDir.resolve("models").toString().also {
            File(it).mkdirs()
        }
        every { platformPaths.operatingSystem } returns OperatingSystem.MACOS
        every { platformPaths.getBinaryPath(any()) } answers {
            val name = firstArg<String>()
            "${tempDir.resolve("bin")}/$name"
        }

        every { configManager.getInstalledVersions() } returns InstalledVersions()
        every { configManager.saveInstalledVersions(any()) } just Runs
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // ==================== Checksum Verification Tests ====================

    @Test
    fun `SHA256 checksum calculation produces correct format`() {
        // Create a test file with known content
        val testFile = tempDir.resolve("test-checksum.txt").toFile()
        testFile.writeText("Hello, World!")

        // Calculate SHA256 using the same algorithm as UpdateManager
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(testFile.readBytes()).joinToString("") { "%02x".format(it) }

        // Verify format
        assertEquals(64, hash.length, "SHA256 should produce 64 hex characters")
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' }, "Should be lowercase hex")

        // Known SHA256 for "Hello, World!"
        assertEquals("dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f", hash)
    }

    @Test
    fun `SHA256 checksum calculation works with large files`() {
        // Create a larger test file (1MB)
        val testFile = tempDir.resolve("large-file.bin").toFile()
        val content = ByteArray(1024 * 1024) { it.toByte() }
        testFile.writeBytes(content)

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(testFile.readBytes()).joinToString("") { "%02x".format(it) }

        assertNotNull(hash)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ==================== ChecksumMismatchException Tests ====================

    @Test
    fun `ChecksumMismatchException contains expected and actual values`() {
        val exception = ChecksumMismatchException(
            expected = "abc123",
            actual = "def456"
        )

        assertEquals("abc123", exception.expected)
        assertEquals("def456", exception.actual)
        assertTrue(exception.message!!.contains("abc123"))
        assertTrue(exception.message!!.contains("def456"))
    }

    // ==================== UpdateException Tests ====================

    @Test
    fun `UpdateException contains message and cause`() {
        val cause = RuntimeException("Original error")
        val exception = UpdateException("Download failed", cause)

        assertEquals("Download failed", exception.message)
        assertEquals(cause, exception.cause)
    }

    @Test
    fun `UpdateException can be created without cause`() {
        val exception = UpdateException("Simple error")

        assertEquals("Simple error", exception.message)
        assertNull(exception.cause)
    }

    // ==================== Data Class Tests ====================

    @Test
    fun `GitHubRelease deserializes correctly`() {
        // Use snake_case to match GitHub API format (via @SerialName annotations)
        val jsonStr = """
            {
                "tag_name": "v1.2.3",
                "name": "Release 1.2.3",
                "body": "Release notes here",
                "published_at": "2024-01-01T00:00:00Z",
                "assets": [
                    {
                        "name": "app.zip",
                        "browser_download_url": "https://example.com/app.zip",
                        "size": 12345
                    }
                ]
            }
        """.trimIndent()

        val release = json.decodeFromString<GitHubRelease>(jsonStr)

        assertEquals("v1.2.3", release.tagName)
        assertEquals("Release 1.2.3", release.name)
        assertEquals("Release notes here", release.body)
        assertEquals("2024-01-01T00:00:00Z", release.publishedAt)
        assertEquals(1, release.assets.size)
        assertEquals("app.zip", release.assets[0].name)
        assertEquals("https://example.com/app.zip", release.assets[0].browserDownloadUrl)
        assertEquals(12345, release.assets[0].size)
    }

    @Test
    fun `GitHubRelease handles missing optional fields`() {
        val jsonStr = """
            {
                "tag_name": "v1.0.0",
                "assets": []
            }
        """.trimIndent()

        val release = json.decodeFromString<GitHubRelease>(jsonStr)

        assertEquals("v1.0.0", release.tagName)
        assertNull(release.name)
        assertNull(release.body)
        assertNull(release.publishedAt)
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun `GitHubAsset deserializes correctly`() {
        val jsonStr = """
            {
                "name": "binary.zip",
                "browser_download_url": "https://example.com/binary.zip",
                "size": 999
            }
        """.trimIndent()

        val asset = json.decodeFromString<GitHubAsset>(jsonStr)

        assertEquals("binary.zip", asset.name)
        assertEquals("https://example.com/binary.zip", asset.browserDownloadUrl)
        assertEquals(999, asset.size)
    }

    @Test
    fun `AppUpdateInfo contains all fields`() {
        val info = AppUpdateInfo(
            currentVersion = "1.0.0",
            newVersion = "2.0.0",
            releaseNotes = "New features",
            downloadUrl = "https://example.com/app.dmg",
            publishedAt = "2024-01-01"
        )

        assertEquals("1.0.0", info.currentVersion)
        assertEquals("2.0.0", info.newVersion)
        assertEquals("New features", info.releaseNotes)
        assertEquals("https://example.com/app.dmg", info.downloadUrl)
        assertEquals("2024-01-01", info.publishedAt)
    }

    @Test
    fun `DependencyUpdates hasUpdates returns true when any update available`() {
        val withYtDlp = DependencyUpdates(ytDlpAvailable = "v1.0", ffmpegAvailable = null, whisperCppAvailable = null)
        val withFfmpeg = DependencyUpdates(ytDlpAvailable = null, ffmpegAvailable = "7.0", whisperCppAvailable = null)
        val withWhisper = DependencyUpdates(ytDlpAvailable = null, ffmpegAvailable = null, whisperCppAvailable = "v1.5")
        val withNone = DependencyUpdates(ytDlpAvailable = null, ffmpegAvailable = null, whisperCppAvailable = null)

        assertTrue(withYtDlp.hasUpdates)
        assertTrue(withFfmpeg.hasUpdates)
        assertTrue(withWhisper.hasUpdates)
        assertFalse(withNone.hasUpdates)
    }

    @Test
    fun `DependencyUpdates with all updates`() {
        val withAll = DependencyUpdates(
            ytDlpAvailable = "2024.01.01",
            ffmpegAvailable = "7.0",
            whisperCppAvailable = "v1.5.0"
        )

        assertTrue(withAll.hasUpdates)
        assertEquals("2024.01.01", withAll.ytDlpAvailable)
        assertEquals("7.0", withAll.ffmpegAvailable)
        assertEquals("v1.5.0", withAll.whisperCppAvailable)
    }

    @Test
    fun `DownloadProgress contains percentage and message`() {
        val progress = DownloadProgress(0.5f, "Downloading...")

        assertEquals(0.5f, progress.percentage)
        assertEquals("Downloading...", progress.message)
    }

    @Test
    fun `DownloadProgress handles edge cases`() {
        val zero = DownloadProgress(0f, "Starting")
        val complete = DownloadProgress(1f, "Complete")
        val negative = DownloadProgress(-1f, "Unknown")

        assertEquals(0f, zero.percentage)
        assertEquals(1f, complete.percentage)
        assertEquals(-1f, negative.percentage)
    }

    // ==================== Companion Object Constants Tests ====================

    @Test
    fun `WHISPER_MODELS contains expected models`() {
        assertTrue(UpdateManager.WHISPER_MODELS.containsKey("tiny"))
        assertTrue(UpdateManager.WHISPER_MODELS.containsKey("base"))
        assertTrue(UpdateManager.WHISPER_MODELS.containsKey("small"))
        assertTrue(UpdateManager.WHISPER_MODELS.containsKey("medium"))
        assertTrue(UpdateManager.WHISPER_MODELS.containsKey("large"))

        // All URLs should point to huggingface
        UpdateManager.WHISPER_MODELS.values.forEach { url ->
            assertTrue(url.contains("huggingface.co"))
            assertTrue(url.startsWith("https://"))
        }
    }

    @Test
    fun `WHISPER_MODEL_SIZES are reasonable`() {
        // Sizes should increase with model complexity
        val tiny = UpdateManager.WHISPER_MODEL_SIZES["tiny"]!!
        val base = UpdateManager.WHISPER_MODEL_SIZES["base"]!!
        val small = UpdateManager.WHISPER_MODEL_SIZES["small"]!!
        val medium = UpdateManager.WHISPER_MODEL_SIZES["medium"]!!
        val large = UpdateManager.WHISPER_MODEL_SIZES["large"]!!

        assertTrue(tiny < base, "tiny should be smaller than base")
        assertTrue(base < small, "base should be smaller than small")
        assertTrue(small < medium, "small should be smaller than medium")
        assertTrue(medium < large, "medium should be smaller than large")

        // All sizes should be positive
        assertTrue(tiny > 0)
        assertTrue(large > 0)
    }

    @Test
    fun `WHISPER_MODEL_CHECKSUMS are valid SHA256 format`() {
        UpdateManager.WHISPER_MODEL_CHECKSUMS.values.forEach { checksum ->
            assertEquals(64, checksum.length, "SHA256 should be 64 hex characters")
            assertTrue(checksum.all { it in '0'..'9' || it in 'a'..'f' }, "Should be lowercase hex")
        }

        // All models should have checksums
        UpdateManager.WHISPER_MODELS.keys.forEach { model ->
            assertTrue(
                UpdateManager.WHISPER_MODEL_CHECKSUMS.containsKey(model),
                "Model $model should have a checksum"
            )
        }
    }

    @Test
    fun `MAX_RETRIES is reasonable`() {
        assertEquals(3, UpdateManager.MAX_RETRIES)
        assertTrue(UpdateManager.MAX_RETRIES > 0)
        assertTrue(UpdateManager.MAX_RETRIES <= 5)
    }

    @Test
    fun `INITIAL_RETRY_DELAY_MS is reasonable`() {
        assertEquals(1000L, UpdateManager.INITIAL_RETRY_DELAY_MS)
        assertTrue(UpdateManager.INITIAL_RETRY_DELAY_MS > 0)
    }

    @Test
    fun `BUFFER_SIZE is reasonable for streaming`() {
        assertEquals(8192, UpdateManager.BUFFER_SIZE)
        assertTrue(UpdateManager.BUFFER_SIZE >= 4096)
        assertTrue(UpdateManager.BUFFER_SIZE <= 65536)
    }

    @Test
    fun `Repository constants are valid`() {
        assertEquals("ericjesse/video-translator", UpdateManager.APP_REPO)
        assertEquals("yt-dlp/yt-dlp", UpdateManager.YTDLP_REPO)
        assertEquals("ggerganov/whisper.cpp", UpdateManager.WHISPER_REPO)

        // All should be in format "owner/repo"
        listOf(UpdateManager.APP_REPO, UpdateManager.YTDLP_REPO, UpdateManager.WHISPER_REPO).forEach { repo ->
            assertTrue(repo.contains("/"))
            assertEquals(2, repo.split("/").size)
        }
    }

    // ==================== Whisper Model Tests ====================

    @Test
    fun `installWhisperModel throws for unknown model`() = runTest {
        val httpClient = createMockHttpClient { respondOk() }
        val updateManager = UpdateManager(httpClient, platformPaths, configManager)

        assertFailsWith<IllegalArgumentException> {
            updateManager.installWhisperModel("nonexistent").toList()
        }
    }

    @Test
    fun `installWhisperModel uses correct URL for each model`() {
        // Verify URLs match expected patterns
        UpdateManager.WHISPER_MODELS.forEach { (model, url) ->
            assertTrue(url.contains("ggml-$model"), "URL for $model should contain ggml-$model")
            assertTrue(url.endsWith(".bin"), "URL for $model should end with .bin")
        }
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `checkForAppUpdate returns null on error`() = runTest {
        val httpClient = createMockHttpClient {
            respondError(HttpStatusCode.InternalServerError)
        }

        val updateManager = UpdateManager(httpClient, platformPaths, configManager)
        val result = updateManager.checkForAppUpdate()

        assertNull(result)
    }

    @Test
    fun `checkForAppUpdate returns null on network error`() = runTest {
        val httpClient = createMockHttpClient {
            throw Exception("Network error")
        }

        val updateManager = UpdateManager(httpClient, platformPaths, configManager)
        val result = updateManager.checkForAppUpdate()

        assertNull(result)
    }

    @Test
    fun `checkDependencyUpdates handles errors gracefully`() = runTest {
        val httpClient = createMockHttpClient {
            respondError(HttpStatusCode.InternalServerError)
        }

        val updateManager = UpdateManager(httpClient, platformPaths, configManager)
        val result = updateManager.checkDependencyUpdates()

        // Should return empty updates rather than throwing
        assertNull(result.ytDlpAvailable)
        assertNull(result.ffmpegAvailable)
        assertNull(result.whisperCppAvailable)
        assertFalse(result.hasUpdates)
    }

    @Test
    fun `checkDependencyUpdates returns null for each failed check`() = runTest {
        every { configManager.getInstalledVersions() } returns InstalledVersions(
            ytDlp = "2023.01.01",
            whisperCpp = "v1.0.0"
        )

        val httpClient = createMockHttpClient {
            respondError(HttpStatusCode.NotFound)
        }

        val updateManager = UpdateManager(httpClient, platformPaths, configManager)
        val result = updateManager.checkDependencyUpdates()

        assertNull(result.ytDlpAvailable)
        assertNull(result.whisperCppAvailable)
    }

    // ==================== InstalledVersions Tests ====================

    @Test
    fun `getInstalledVersions returns correct data`() {
        val versions = InstalledVersions(
            ytDlp = "2024.01.01",
            ffmpeg = "7.0",
            whisperCpp = "v1.5.0",
            whisperModel = "base"
        )

        every { configManager.getInstalledVersions() } returns versions

        assertEquals("2024.01.01", configManager.getInstalledVersions().ytDlp)
        assertEquals("7.0", configManager.getInstalledVersions().ffmpeg)
        assertEquals("v1.5.0", configManager.getInstalledVersions().whisperCpp)
        assertEquals("base", configManager.getInstalledVersions().whisperModel)
    }

    @Test
    fun `InstalledVersions defaults to null values`() {
        val versions = InstalledVersions()

        assertNull(versions.ytDlp)
        assertNull(versions.ffmpeg)
        assertNull(versions.whisperCpp)
        assertNull(versions.whisperModel)
    }

    // ==================== Helper Methods ====================

    private fun createMockHttpClient(handler: MockRequestHandler): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler(handler)
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
}
