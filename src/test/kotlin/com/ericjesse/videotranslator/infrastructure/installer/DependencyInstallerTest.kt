package com.ericjesse.videotranslator.infrastructure.installer

import com.ericjesse.videotranslator.domain.installer.ComponentDescription
import com.ericjesse.videotranslator.domain.installer.ComponentId
import com.ericjesse.videotranslator.domain.installer.InstallationProgress
import com.ericjesse.videotranslator.domain.installer.InstallationSummary
import com.ericjesse.videotranslator.domain.installer.InstalledComponent
import com.ericjesse.videotranslator.domain.installer.InstallerState
import com.ericjesse.videotranslator.domain.installer.PreInstallCheckResult
import com.ericjesse.videotranslator.domain.installer.PreInstallationState
import com.ericjesse.videotranslator.infrastructure.archive.ArchiveExtractor
import com.ericjesse.videotranslator.infrastructure.config.OperatingSystem
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import com.ericjesse.videotranslator.infrastructure.process.ProcessExecutor
import io.ktor.client.HttpClient
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DependencyInstallerTest {

    // ==================== Data Model Tests ====================

    @Nested
    inner class ComponentDescriptionTest {

        @Test
        fun `ComponentDescription has correct default values`() {
            val description = ComponentDescription(
                id = ComponentId.YT_DLP,
                name = "yt-dlp",
                description = "Video downloader",
                estimatedSizeMb = 20
            )

            assertEquals(ComponentId.YT_DLP, description.id)
            assertEquals("yt-dlp", description.name)
            assertEquals(20, description.estimatedSizeMb)
            assertTrue(description.warnings.isEmpty())
            assertFalse(description.isOptional)
            assertTrue(description.dependencies.isEmpty())
        }

        @Test
        fun `ComponentDescription with dependencies`() {
            val description = ComponentDescription(
                id = ComponentId.WHISPER_CPP,
                name = "whisper.cpp",
                description = "Speech-to-text engine",
                estimatedSizeMb = 5,
                dependencies = listOf(ComponentId.FFMPEG)
            )

            assertEquals(1, description.dependencies.size)
            assertEquals(ComponentId.FFMPEG, description.dependencies.first())
        }

        @Test
        fun `ComponentDescription with warnings`() {
            val description = ComponentDescription(
                id = ComponentId.FFMPEG,
                name = "FFmpeg",
                description = "Audio/video processing",
                estimatedSizeMb = 150,
                warnings = listOf("Large download", "May take time")
            )

            assertEquals(2, description.warnings.size)
            assertTrue(description.warnings.contains("Large download"))
        }
    }

    @Nested
    inner class PreInstallCheckResultTest {

        @Test
        fun `Passed result has correct properties`() {
            val result = PreInstallCheckResult.Passed(
                checkName = "Disk Space",
                details = "500GB available"
            )

            assertEquals("Disk Space", result.checkName)
            assertEquals("500GB available", result.details)
            assertTrue(result.isPassed())
            assertFalse(result.isFailed())
            assertFalse(result.isWarning())
        }

        @Test
        fun `Failed result has correct properties`() {
            val result = PreInstallCheckResult.Failed(
                checkName = "Disk Space",
                reason = "Only 100MB available",
                suggestion = "Free up disk space"
            )

            assertEquals("Disk Space", result.checkName)
            assertEquals("Only 100MB available", result.reason)
            assertEquals("Free up disk space", result.suggestion)
            assertFalse(result.isPassed())
            assertTrue(result.isFailed())
            assertFalse(result.isWarning())
        }

        @Test
        fun `Warning result has correct properties`() {
            val result = PreInstallCheckResult.Warning(
                checkName = "Network",
                message = "Slow connection detected",
                suggestion = "Use a faster network"
            )

            assertEquals("Network", result.checkName)
            assertEquals("Slow connection detected", result.message)
            assertEquals("Use a faster network", result.suggestion)
            assertFalse(result.isPassed())
            assertFalse(result.isFailed())
            assertTrue(result.isWarning())
        }
    }

    @Nested
    inner class InstallationProgressTest {

        @Test
        fun `Starting progress has component info`() {
            val progress = InstallationProgress.Starting(
                componentId = ComponentId.YT_DLP,
                componentName = "yt-dlp",
                componentIndex = 1,
                totalComponents = 4
            )

            assertEquals(ComponentId.YT_DLP, progress.componentId)
            assertEquals("yt-dlp", progress.componentName)
            assertEquals(1, progress.componentIndex)
            assertEquals(4, progress.totalComponents)
        }

        @Test
        fun `Downloading progress has bytes info`() {
            val progress = InstallationProgress.Downloading(
                componentId = ComponentId.FFMPEG,
                componentName = "FFmpeg",
                downloadedBytes = 50_000_000,
                totalBytes = 150_000_000,
                percentage = 0.33f
            )

            assertEquals(50_000_000, progress.downloadedBytes)
            assertEquals(150_000_000, progress.totalBytes)
            assertEquals(0.33f, progress.percentage)
        }

        @Test
        fun `ComponentCompleted has install path and version`() {
            val progress = InstallationProgress.ComponentCompleted(
                componentId = ComponentId.YT_DLP,
                componentName = "yt-dlp",
                installPath = "/usr/local/bin/yt-dlp",
                version = "2024.01.01"
            )

            assertEquals("/usr/local/bin/yt-dlp", progress.installPath)
            assertEquals("2024.01.01", progress.version)
        }

        @Test
        fun `Failed progress has error and suggestion`() {
            val progress = InstallationProgress.Failed(
                error = "Network timeout",
                failedComponent = ComponentId.FFMPEG,
                suggestion = "Check your internet connection"
            )

            assertEquals("Network timeout", progress.error)
            assertEquals(ComponentId.FFMPEG, progress.failedComponent)
            assertEquals("Check your internet connection", progress.suggestion)
        }
    }

    @Nested
    inner class InstallationSummaryTest {

        @Test
        fun `InstallationSummary with installed components`() {
            val components = listOf(
                InstalledComponent(
                    id = ComponentId.YT_DLP,
                    name = "yt-dlp",
                    version = "2024.01.01",
                    installPath = "/usr/local/bin/yt-dlp",
                    sizeMb = 20
                ),
                InstalledComponent(
                    id = ComponentId.FFMPEG,
                    name = "FFmpeg",
                    version = "6.1",
                    installPath = "/usr/local/bin/ffmpeg",
                    sizeMb = 150
                )
            )

            val summary = InstallationSummary(
                installedComponents = components,
                totalSizeMb = 170,
                duration = Duration.parse("5m"),
                warnings = listOf("Slow download for FFmpeg")
            )

            assertEquals(2, summary.installedComponents.size)
            assertEquals(170, summary.totalSizeMb)
            assertEquals(1, summary.warnings.size)
        }

        @Test
        fun `Empty InstallationSummary`() {
            val summary = InstallationSummary(
                installedComponents = emptyList(),
                totalSizeMb = 0,
                duration = Duration.ZERO
            )

            assertTrue(summary.installedComponents.isEmpty())
            assertEquals(0, summary.totalSizeMb)
            assertTrue(summary.warnings.isEmpty())
        }
    }

    @Nested
    inner class InstallerStateTest {

        @Test
        fun `All InstallerState values are defined`() {
            val states = InstallerState.entries
            assertTrue(states.contains(InstallerState.IDLE))
            assertTrue(states.contains(InstallerState.CHECKING))
            assertTrue(states.contains(InstallerState.INSTALLING))
            assertTrue(states.contains(InstallerState.CANCELLING))
            assertTrue(states.contains(InstallerState.COMPLETED))
            assertTrue(states.contains(InstallerState.FAILED))
            assertTrue(states.contains(InstallerState.CANCELLED))
        }
    }

    @Nested
    inner class PreInstallationStateTest {

        @Test
        fun `PreInstallationState tracks existing components`() {
            val state = PreInstallationState(
                existingComponents = setOf(ComponentId.YT_DLP, ComponentId.FFMPEG)
            )

            assertTrue(ComponentId.YT_DLP in state.existingComponents)
            assertTrue(ComponentId.FFMPEG in state.existingComponents)
            assertFalse(ComponentId.WHISPER_CPP in state.existingComponents)
            assertTrue(state.timestamp > 0)
        }

        @Test
        fun `Empty PreInstallationState`() {
            val state = PreInstallationState(existingComponents = emptySet())

            assertTrue(state.existingComponents.isEmpty())
        }
    }

    // ==================== Factory Tests ====================

    @Nested
    inner class DependencyInstallerFactoryTest {

        private val mockPlatformPaths = mockk<PlatformPaths>()
        private val mockHttpClient = mockk<HttpClient>()
        private val mockProcessExecutor = mockk<ProcessExecutor>()
        private val mockArchiveExtractor = mockk<ArchiveExtractor>()

        @Test
        fun `Factory creates MacOSDependencyInstaller for macOS`() {
            every { mockPlatformPaths.operatingSystem } returns OperatingSystem.MACOS
            every { mockPlatformPaths.binDir } returns "/tmp/bin"
            every { mockPlatformPaths.modelsDir } returns "/tmp/models"
            every { mockPlatformPaths.cacheDir } returns "/tmp/cache"
            every { mockPlatformPaths.dataDir } returns "/tmp/data"
            every { mockPlatformPaths.libreTranslateDir } returns "/tmp/libretranslate"

            val installer = DependencyInstallerFactory.create(
                platformPaths = mockPlatformPaths,
                httpClient = mockHttpClient,
                processExecutor = mockProcessExecutor,
                archiveExtractor = mockArchiveExtractor
            )

            assertTrue(installer is MacOSDependencyInstaller)
        }

        @Test
        fun `Factory creates WindowsDependencyInstaller for Windows`() {
            every { mockPlatformPaths.operatingSystem } returns OperatingSystem.WINDOWS
            every { mockPlatformPaths.binDir } returns "C:\\bin"
            every { mockPlatformPaths.modelsDir } returns "C:\\models"
            every { mockPlatformPaths.cacheDir } returns "C:\\cache"
            every { mockPlatformPaths.dataDir } returns "C:\\data"
            every { mockPlatformPaths.libreTranslateDir } returns "C:\\libretranslate"

            val installer = DependencyInstallerFactory.create(
                platformPaths = mockPlatformPaths,
                httpClient = mockHttpClient,
                processExecutor = mockProcessExecutor,
                archiveExtractor = mockArchiveExtractor
            )

            assertTrue(installer is WindowsDependencyInstaller)
        }

        @Test
        fun `Factory creates LinuxDependencyInstaller for Linux`() {
            every { mockPlatformPaths.operatingSystem } returns OperatingSystem.LINUX
            every { mockPlatformPaths.binDir } returns "/home/user/.local/bin"
            every { mockPlatformPaths.modelsDir } returns "/home/user/.local/models"
            every { mockPlatformPaths.cacheDir } returns "/home/user/.cache"
            every { mockPlatformPaths.dataDir } returns "/home/user/.local/share"
            every { mockPlatformPaths.libreTranslateDir } returns "/home/user/.local/libretranslate"

            val installer = DependencyInstallerFactory.create(
                platformPaths = mockPlatformPaths,
                httpClient = mockHttpClient,
                processExecutor = mockProcessExecutor,
                archiveExtractor = mockArchiveExtractor
            )

            assertTrue(installer is LinuxDependencyInstaller)
        }

        @Test
        fun `Factory createFor creates specific installer regardless of current OS`() {
            every { mockPlatformPaths.operatingSystem } returns OperatingSystem.MACOS
            every { mockPlatformPaths.binDir } returns "/tmp/bin"
            every { mockPlatformPaths.modelsDir } returns "/tmp/models"
            every { mockPlatformPaths.cacheDir } returns "/tmp/cache"
            every { mockPlatformPaths.dataDir } returns "/tmp/data"
            every { mockPlatformPaths.libreTranslateDir } returns "/tmp/libretranslate"

            val installer = DependencyInstallerFactory.createFor(
                operatingSystem = OperatingSystem.WINDOWS,
                platformPaths = mockPlatformPaths,
                httpClient = mockHttpClient,
                processExecutor = mockProcessExecutor,
                archiveExtractor = mockArchiveExtractor
            )

            assertTrue(installer is WindowsDependencyInstaller)
        }
    }

    // ==================== Platform Installer Tests ====================

    @Nested
    inner class MacOSDependencyInstallerTest {

        private val mockPlatformPaths = mockk<PlatformPaths>()
        private val mockHttpClient = mockk<HttpClient>()
        private val mockProcessExecutor = mockk<ProcessExecutor>()
        private val mockArchiveExtractor = mockk<ArchiveExtractor>()

        @Test
        fun `MacOS installer has correct component descriptions`() {
            every { mockPlatformPaths.binDir } returns "/tmp/bin"
            every { mockPlatformPaths.modelsDir } returns "/tmp/models"
            every { mockPlatformPaths.cacheDir } returns "/tmp/cache"
            every { mockPlatformPaths.dataDir } returns "/tmp/data"
            every { mockPlatformPaths.libreTranslateDir } returns "/tmp/libretranslate"

            val installer = MacOSDependencyInstaller(
                platformPaths = mockPlatformPaths,
                httpClient = mockHttpClient,
                processExecutor = mockProcessExecutor,
                archiveExtractor = mockArchiveExtractor
            )

            val descriptions = installer.getComponentDescriptions()

            assertEquals(6, descriptions.size)
            assertNotNull(descriptions.find { it.id == ComponentId.YT_DLP })
            assertNotNull(descriptions.find { it.id == ComponentId.FFMPEG })
            assertNotNull(descriptions.find { it.id == ComponentId.WHISPER_CPP })
            assertNotNull(descriptions.find { it.id == ComponentId.WHISPER_MODEL_BASE })
            assertNotNull(descriptions.find { it.id == ComponentId.PYTHON })
            assertNotNull(descriptions.find { it.id == ComponentId.LIBRE_TRANSLATE })

            // Verify LibreTranslate and Python are marked as optional
            val libreTranslate = descriptions.find { it.id == ComponentId.LIBRE_TRANSLATE }
            assertTrue(libreTranslate?.isOptional == true)

            val python = descriptions.find { it.id == ComponentId.PYTHON }
            assertTrue(python?.isOptional == true)
        }

        @Test
        fun `MacOS installer starts in IDLE state`() {
            every { mockPlatformPaths.binDir } returns "/tmp/bin"
            every { mockPlatformPaths.modelsDir } returns "/tmp/models"
            every { mockPlatformPaths.cacheDir } returns "/tmp/cache"
            every { mockPlatformPaths.dataDir } returns "/tmp/data"
            every { mockPlatformPaths.libreTranslateDir } returns "/tmp/libretranslate"

            val installer = MacOSDependencyInstaller(
                platformPaths = mockPlatformPaths,
                httpClient = mockHttpClient,
                processExecutor = mockProcessExecutor,
                archiveExtractor = mockArchiveExtractor
            )

            assertEquals(InstallerState.IDLE, installer.getState())
            assertFalse(installer.isInstalling())
        }
    }

    @Nested
    inner class WindowsDependencyInstallerTest {

        private val mockPlatformPaths = mockk<PlatformPaths>()
        private val mockHttpClient = mockk<HttpClient>()
        private val mockProcessExecutor = mockk<ProcessExecutor>()
        private val mockArchiveExtractor = mockk<ArchiveExtractor>()

        @Test
        fun `Windows installer has correct component descriptions`() {
            every { mockPlatformPaths.binDir } returns "C:\\bin"
            every { mockPlatformPaths.modelsDir } returns "C:\\models"
            every { mockPlatformPaths.cacheDir } returns "C:\\cache"
            every { mockPlatformPaths.dataDir } returns "C:\\data"
            every { mockPlatformPaths.libreTranslateDir } returns "C:\\libretranslate"

            val installer = WindowsDependencyInstaller(
                platformPaths = mockPlatformPaths,
                httpClient = mockHttpClient,
                processExecutor = mockProcessExecutor,
                archiveExtractor = mockArchiveExtractor
            )

            val descriptions = installer.getComponentDescriptions()

            assertEquals(7, descriptions.size)
            assertNotNull(descriptions.find { it.id == ComponentId.VC_REDIST })
            assertNotNull(descriptions.find { it.id == ComponentId.YT_DLP })
            assertNotNull(descriptions.find { it.id == ComponentId.FFMPEG })
            assertNotNull(descriptions.find { it.id == ComponentId.WHISPER_CPP })
            assertNotNull(descriptions.find { it.id == ComponentId.WHISPER_MODEL_BASE })
            assertNotNull(descriptions.find { it.id == ComponentId.PYTHON })
            assertNotNull(descriptions.find { it.id == ComponentId.LIBRE_TRANSLATE })

            // Verify LibreTranslate and Python are marked as optional
            val libreTranslate = descriptions.find { it.id == ComponentId.LIBRE_TRANSLATE }
            assertTrue(libreTranslate?.isOptional == true)

            val python = descriptions.find { it.id == ComponentId.PYTHON }
            assertTrue(python?.isOptional == true)

            // Verify VC_REDIST is first (as a prerequisite for other components)
            assertEquals(ComponentId.VC_REDIST, descriptions.first().id)
        }

        @Test
        fun `Windows installer starts in IDLE state`() {
            every { mockPlatformPaths.binDir } returns "C:\\bin"
            every { mockPlatformPaths.modelsDir } returns "C:\\models"
            every { mockPlatformPaths.cacheDir } returns "C:\\cache"
            every { mockPlatformPaths.dataDir } returns "C:\\data"
            every { mockPlatformPaths.libreTranslateDir } returns "C:\\libretranslate"

            val installer = WindowsDependencyInstaller(
                platformPaths = mockPlatformPaths,
                httpClient = mockHttpClient,
                processExecutor = mockProcessExecutor,
                archiveExtractor = mockArchiveExtractor
            )

            assertEquals(InstallerState.IDLE, installer.getState())
            assertFalse(installer.isInstalling())
        }
    }

    @Nested
    inner class LinuxDependencyInstallerTest {

        private val mockPlatformPaths = mockk<PlatformPaths>()
        private val mockHttpClient = mockk<HttpClient>()
        private val mockProcessExecutor = mockk<ProcessExecutor>()
        private val mockArchiveExtractor = mockk<ArchiveExtractor>()

        @Test
        fun `Linux installer has correct component descriptions`() {
            every { mockPlatformPaths.binDir } returns "/home/user/.local/bin"
            every { mockPlatformPaths.modelsDir } returns "/home/user/.local/models"
            every { mockPlatformPaths.cacheDir } returns "/home/user/.cache"
            every { mockPlatformPaths.dataDir } returns "/home/user/.local/share"
            every { mockPlatformPaths.libreTranslateDir } returns "/home/user/.local/libretranslate"

            val installer = LinuxDependencyInstaller(
                platformPaths = mockPlatformPaths,
                httpClient = mockHttpClient,
                processExecutor = mockProcessExecutor,
                archiveExtractor = mockArchiveExtractor
            )

            val descriptions = installer.getComponentDescriptions()

            assertEquals(6, descriptions.size)
            assertNotNull(descriptions.find { it.id == ComponentId.YT_DLP })
            assertNotNull(descriptions.find { it.id == ComponentId.FFMPEG })
            assertNotNull(descriptions.find { it.id == ComponentId.WHISPER_CPP })
            assertNotNull(descriptions.find { it.id == ComponentId.WHISPER_MODEL_BASE })
            assertNotNull(descriptions.find { it.id == ComponentId.PYTHON })
            assertNotNull(descriptions.find { it.id == ComponentId.LIBRE_TRANSLATE })

            // Verify LibreTranslate and Python are marked as optional
            val libreTranslate = descriptions.find { it.id == ComponentId.LIBRE_TRANSLATE }
            assertTrue(libreTranslate?.isOptional == true)

            val python = descriptions.find { it.id == ComponentId.PYTHON }
            assertTrue(python?.isOptional == true)
        }

        @Test
        fun `Linux installer starts in IDLE state`() {
            every { mockPlatformPaths.binDir } returns "/home/user/.local/bin"
            every { mockPlatformPaths.modelsDir } returns "/home/user/.local/models"
            every { mockPlatformPaths.cacheDir } returns "/home/user/.cache"
            every { mockPlatformPaths.dataDir } returns "/home/user/.local/share"
            every { mockPlatformPaths.libreTranslateDir } returns "/home/user/.local/libretranslate"

            val installer = LinuxDependencyInstaller(
                platformPaths = mockPlatformPaths,
                httpClient = mockHttpClient,
                processExecutor = mockProcessExecutor,
                archiveExtractor = mockArchiveExtractor
            )

            assertEquals(InstallerState.IDLE, installer.getState())
            assertFalse(installer.isInstalling())
        }
    }

    // ==================== Component ID Tests ====================

    @Nested
    inner class ComponentIdTest {

        @Test
        fun `All ComponentId values are defined`() {
            val ids = ComponentId.entries
            assertTrue(ids.contains(ComponentId.YT_DLP))
            assertTrue(ids.contains(ComponentId.FFMPEG))
            assertTrue(ids.contains(ComponentId.WHISPER_CPP))
            assertTrue(ids.contains(ComponentId.WHISPER_MODEL_BASE))
            assertTrue(ids.contains(ComponentId.WHISPER_MODEL_SMALL))
            assertTrue(ids.contains(ComponentId.WHISPER_MODEL_MEDIUM))
            assertTrue(ids.contains(ComponentId.WHISPER_MODEL_LARGE))
            assertTrue(ids.contains(ComponentId.LIBRE_TRANSLATE))
        }
    }
}
