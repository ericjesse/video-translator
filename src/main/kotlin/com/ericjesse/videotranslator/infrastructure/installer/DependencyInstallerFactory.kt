package com.ericjesse.videotranslator.infrastructure.installer

import com.ericjesse.videotranslator.domain.installer.DependencyInstaller
import com.ericjesse.videotranslator.infrastructure.archive.ArchiveExtractor
import com.ericjesse.videotranslator.infrastructure.config.OperatingSystem
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import com.ericjesse.videotranslator.infrastructure.process.ProcessExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient

private val logger = KotlinLogging.logger {}

/**
 * Factory for creating platform-specific dependency installers.
 *
 * Usage:
 * ```kotlin
 * val installer = DependencyInstallerFactory.create(
 *     platformPaths = platformPaths,
 *     httpClient = httpClient,
 *     processExecutor = processExecutor,
 *     archiveExtractor = archiveExtractor
 * )
 * ```
 */
object DependencyInstallerFactory {

    /**
     * Creates the appropriate DependencyInstaller for the current platform.
     *
     * @param platformPaths Platform-specific paths configuration
     * @param httpClient HTTP client for downloading components
     * @param processExecutor Process executor for running commands
     * @param archiveExtractor Archive extractor for extracting downloaded archives
     * @return Platform-specific DependencyInstaller implementation
     */
    fun create(
        platformPaths: PlatformPaths,
        httpClient: HttpClient,
        processExecutor: ProcessExecutor,
        archiveExtractor: ArchiveExtractor,
    ): DependencyInstaller {
        val os = platformPaths.operatingSystem

        logger.info { "Creating dependency installer for $os" }

        return when (os) {
            OperatingSystem.MACOS -> MacOSDependencyInstaller(
                platformPaths = platformPaths,
                httpClient = httpClient,
                processExecutor = processExecutor,
                archiveExtractor = archiveExtractor
            )

            OperatingSystem.WINDOWS -> WindowsDependencyInstaller(
                platformPaths = platformPaths,
                httpClient = httpClient,
                processExecutor = processExecutor,
                archiveExtractor = archiveExtractor
            )

            OperatingSystem.LINUX -> LinuxDependencyInstaller(
                platformPaths = platformPaths,
                httpClient = httpClient,
                processExecutor = processExecutor,
                archiveExtractor = archiveExtractor
            )
        }
    }

    /**
     * Creates a DependencyInstaller for a specific operating system.
     * Useful for testing or when you need to create an installer for a different platform.
     *
     * @param operatingSystem The target operating system
     * @param platformPaths Platform-specific paths configuration
     * @param httpClient HTTP client for downloading components
     * @param processExecutor Process executor for running commands
     * @param archiveExtractor Archive extractor for extracting downloaded archives
     * @return Platform-specific DependencyInstaller implementation
     */
    fun createFor(
        operatingSystem: OperatingSystem,
        platformPaths: PlatformPaths,
        httpClient: HttpClient,
        processExecutor: ProcessExecutor,
        archiveExtractor: ArchiveExtractor,
    ): DependencyInstaller {
        logger.info { "Creating dependency installer for $operatingSystem (explicit)" }

        return when (operatingSystem) {
            OperatingSystem.MACOS -> MacOSDependencyInstaller(
                platformPaths = platformPaths,
                httpClient = httpClient,
                processExecutor = processExecutor,
                archiveExtractor = archiveExtractor
            )

            OperatingSystem.WINDOWS -> WindowsDependencyInstaller(
                platformPaths = platformPaths,
                httpClient = httpClient,
                processExecutor = processExecutor,
                archiveExtractor = archiveExtractor
            )

            OperatingSystem.LINUX -> LinuxDependencyInstaller(
                platformPaths = platformPaths,
                httpClient = httpClient,
                processExecutor = processExecutor,
                archiveExtractor = archiveExtractor
            )
        }
    }
}
