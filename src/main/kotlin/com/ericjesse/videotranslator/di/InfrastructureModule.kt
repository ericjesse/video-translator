package com.ericjesse.videotranslator.di

import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import com.ericjesse.videotranslator.infrastructure.http.HttpClientFactory
import com.ericjesse.videotranslator.infrastructure.network.ConnectivityChecker
import com.ericjesse.videotranslator.infrastructure.process.ProcessExecutor
import com.ericjesse.videotranslator.infrastructure.resources.DiskSpaceChecker
import com.ericjesse.videotranslator.infrastructure.resources.ResourceManager
import com.ericjesse.videotranslator.infrastructure.resources.TempFileManager
import com.ericjesse.videotranslator.infrastructure.translation.LibreTranslateService
import com.ericjesse.videotranslator.infrastructure.update.UpdateManager
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import io.ktor.client.HttpClient
import org.koin.dsl.module

/**
 * Koin module for infrastructure dependencies.
 * Contains all infrastructure-level services like HTTP clients, file management,
 * configuration, and platform-specific utilities.
 */
val infrastructureModule = module {
    // Core infrastructure - singletons
    single { PlatformPaths() }
    single { ConfigManager(get()) }
    single<HttpClient> { HttpClientFactory.create() }
    single { ProcessExecutor() }

    // Resource management
    single { TempFileManager(get()) }
    single { ResourceManager(get()) }
    single { DiskSpaceChecker(get<PlatformPaths>(), get<TempFileManager>()) }

    // Network and external services
    single { ConnectivityChecker(get()) }
    single { LibreTranslateService(get(), get()) }
    single { UpdateManager(get(), get(), get()) }

    // Internationalization
    single { I18nManager(get()) }
}
