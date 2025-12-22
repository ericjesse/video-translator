package com.ericjesse.videotranslator.di

import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import com.ericjesse.videotranslator.infrastructure.http.HttpClientFactory
import com.ericjesse.videotranslator.infrastructure.network.ConnectivityChecker
import com.ericjesse.videotranslator.infrastructure.process.ProcessExecutor
import com.ericjesse.videotranslator.infrastructure.update.UpdateManager
import com.ericjesse.videotranslator.domain.service.VideoDownloader
import com.ericjesse.videotranslator.domain.service.TranscriberService
import com.ericjesse.videotranslator.domain.service.TranslatorService
import com.ericjesse.videotranslator.domain.service.SubtitleRenderer
import com.ericjesse.videotranslator.domain.pipeline.PipelineOrchestrator
import com.ericjesse.videotranslator.ui.i18n.I18nManager
import io.ktor.client.*

/**
 * Simple dependency injection container.
 * Provides all application dependencies.
 */
class AppModule {
    
    // Infrastructure
    val platformPaths: PlatformPaths by lazy { PlatformPaths() }
    val configManager: ConfigManager by lazy { ConfigManager(platformPaths) }
    val httpClient: HttpClient by lazy { HttpClientFactory.create() }
    val processExecutor: ProcessExecutor by lazy { ProcessExecutor() }
    val updateManager: UpdateManager by lazy { UpdateManager(httpClient, platformPaths, configManager) }
    val connectivityChecker: ConnectivityChecker by lazy { ConnectivityChecker(httpClient) }
    val i18nManager: I18nManager by lazy { I18nManager(configManager) }
    
    // Domain Services
    val videoDownloader: VideoDownloader by lazy { 
        VideoDownloader(processExecutor, platformPaths) 
    }
    val transcriberService: TranscriberService by lazy { 
        TranscriberService(processExecutor, platformPaths, configManager) 
    }
    val translatorService: TranslatorService by lazy { 
        TranslatorService(httpClient, configManager) 
    }
    val subtitleRenderer: SubtitleRenderer by lazy { 
        SubtitleRenderer(processExecutor, platformPaths, configManager) 
    }
    
    // Pipeline
    val pipelineOrchestrator: PipelineOrchestrator by lazy {
        PipelineOrchestrator(
            videoDownloader = videoDownloader,
            transcriberService = transcriberService,
            translatorService = translatorService,
            subtitleRenderer = subtitleRenderer
        )
    }
    
    fun close() {
        connectivityChecker.close()
        httpClient.close()
    }
}
