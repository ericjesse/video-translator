package com.ericjesse.videotranslator.di

import com.ericjesse.videotranslator.domain.pipeline.CheckpointManager
import com.ericjesse.videotranslator.domain.pipeline.PipelineOrchestrator
import com.ericjesse.videotranslator.domain.service.api.RenderingService
import com.ericjesse.videotranslator.domain.service.api.TranscriptionService
import com.ericjesse.videotranslator.domain.service.api.TranslationServiceApi
import com.ericjesse.videotranslator.domain.service.api.VideoDownloadService
import com.ericjesse.videotranslator.infrastructure.service.ffmpeg.SubtitleRenderer
import com.ericjesse.videotranslator.infrastructure.service.translation.TranslatorService
import com.ericjesse.videotranslator.infrastructure.service.whisper.TranscriberService
import com.ericjesse.videotranslator.infrastructure.service.ytdlp.VideoDownloader
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Koin module for domain services.
 * Contains all domain-level services like video downloading, transcription,
 * translation, rendering, and the pipeline orchestrator.
 */
val domainModule = module {
    // Domain services - registered both as concrete types and interfaces
    single { VideoDownloader(get(), get(), get()) } bind VideoDownloadService::class
    single { TranscriberService(get(), get(), get(), get()) } bind TranscriptionService::class
    single { TranslatorService(get(), get(), get()) } bind TranslationServiceApi::class
    single { SubtitleRenderer(get(), get(), get()) } bind RenderingService::class

    // Pipeline support services
    single { CheckpointManager() }

    // Pipeline orchestrator
    single {
        PipelineOrchestrator(
            videoDownloader = get<VideoDownloader>(),
            transcriberService = get<TranscriberService>(),
            translatorService = get<TranslatorService>(),
            subtitleRenderer = get<SubtitleRenderer>(),
            configManager = get(),
            resourceManager = get(),
            tempFileManager = get(),
            diskSpaceChecker = get(),
            checkpointManager = get()
        )
    }
}
