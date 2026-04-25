package com.ericjesse.videotranslator.di

import com.ericjesse.videotranslator.infrastructure.network.ConnectivityChecker
import com.ericjesse.videotranslator.infrastructure.resources.DiskSpaceChecker
import com.ericjesse.videotranslator.infrastructure.resources.ResourceManager
import com.ericjesse.videotranslator.infrastructure.resources.TempFileManager
import com.ericjesse.videotranslator.infrastructure.translation.LibreTranslateService
import com.ericjesse.videotranslator.infrastructure.update.UpdateManager
import io.ktor.client.HttpClient
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatform

/**
 * Application module that initializes and manages Koin dependency injection.
 * Use this object to initialize the DI container at application startup
 * and close it when the application exits.
 */
object AppModule {

    private var isInitialized = false

    /**
     * Initializes the Koin dependency injection container.
     * Call this once at application startup before accessing any dependencies.
     */
    fun init() {
        if (isInitialized) {
            return
        }

        startKoin {
            modules(infrastructureModule, domainModule)
        }

        isInitialized = true

        // Purge setup-wizard cache entries older than one week on startup.
        // Runs on a daemon thread so slow disks can't delay app launch; any
        // failure is swallowed because cache cleanup must never block startup.
        Thread({
            try {
                KoinPlatform.getKoin().get<UpdateManager>().cleanupStaleCache()
            } catch (_: Throwable) {
                // Best-effort cleanup.
            }
        }, "install-cache-cleanup").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Closes all resources and stops the Koin container.
     * Call this when the application is shutting down.
     */
    fun close() {
        if (!isInitialized) {
            return
        }

        try {
            val koin = KoinPlatform.getKoin()

            // Close resources in reverse order of dependency
            koin.get<LibreTranslateService>().dispose()
            koin.get<ConnectivityChecker>().close()
            koin.get<ResourceManager>().close()
            koin.get<DiskSpaceChecker>().close()
            koin.get<TempFileManager>().close()
            koin.get<HttpClient>().close()
        } finally {
            stopKoin()
            isInitialized = false
        }
    }

    /**
     * Gets the Koin instance. Use this for direct access to the DI container.
     * Prefer using koinInject() in Compose or constructor injection in classes.
     */
    fun getKoin() = KoinPlatform.getKoin()
}
