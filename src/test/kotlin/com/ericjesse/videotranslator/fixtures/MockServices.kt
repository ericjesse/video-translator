package com.ericjesse.videotranslator.fixtures

import com.ericjesse.videotranslator.domain.model.Glossary
import com.ericjesse.videotranslator.domain.model.HardwareEncoder
import com.ericjesse.videotranslator.domain.model.Language
import com.ericjesse.videotranslator.domain.model.OutputOptions
import com.ericjesse.videotranslator.domain.model.Subtitles
import com.ericjesse.videotranslator.domain.model.TranslationResult
import com.ericjesse.videotranslator.domain.model.VideoInfo
import com.ericjesse.videotranslator.domain.model.WhisperModel
import com.ericjesse.videotranslator.domain.model.YtDlpDownloadOptions
import com.ericjesse.videotranslator.domain.pipeline.StageProgress
import com.ericjesse.videotranslator.domain.service.api.RenderingService
import com.ericjesse.videotranslator.domain.service.api.TranscriptionService
import com.ericjesse.videotranslator.domain.service.api.TranslationServiceApi
import com.ericjesse.videotranslator.domain.service.api.VideoDownloadService
import com.ericjesse.videotranslator.domain.validation.VideoValidationResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Mock implementation of VideoDownloadService for testing.
 * Allows configuring responses and tracking calls.
 */
class MockVideoDownloadService : VideoDownloadService {

    // Configurable responses
    var fetchVideoInfoResponse: VideoInfo = TestData.videoInfo()
    var downloadProgress: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    var extractCaptionsResponse: Subtitles? = null
    var isAvailableResponse: Boolean = true
    var versionResponse: String? = "2024.01.01"
    var validationResponse: VideoValidationResult = VideoValidationResult.Valid
    var downloadedVideoPathResponse: String = "/tmp/test_video.mp4"

    // Error simulation
    var fetchVideoInfoError: Exception? = null
    var downloadError: Exception? = null
    var extractCaptionsError: Exception? = null

    // Call tracking
    var fetchVideoInfoCalls = mutableListOf<String>()
    var downloadCalls = mutableListOf<Pair<VideoInfo, YtDlpDownloadOptions>>()
    var extractCaptionsCalls = mutableListOf<Pair<VideoInfo, Language?>>()

    // Delay simulation (milliseconds)
    var downloadDelayMs: Long = 0

    override fun isValidYouTubeUrl(url: String): Boolean {
        return url.contains("youtube.com") || url.contains("youtu.be")
    }

    override fun extractVideoId(url: String): String? {
        val regex = Regex("""(?:v=|youtu\.be/)([a-zA-Z0-9_-]{11})""")
        return regex.find(url)?.groupValues?.get(1)
    }

    override suspend fun fetchVideoInfo(url: String, options: YtDlpDownloadOptions): VideoInfo {
        fetchVideoInfoCalls.add(url)
        fetchVideoInfoError?.let { throw it }
        return fetchVideoInfoResponse
    }

    override fun download(videoInfo: VideoInfo, options: YtDlpDownloadOptions): Flow<StageProgress> = flow {
        downloadCalls.add(videoInfo to options)
        downloadError?.let { throw it }

        for (progress in downloadProgress) {
            if (downloadDelayMs > 0) delay(downloadDelayMs)
            emit(
                StageProgress(
                    percentage = progress,
                    message = "Downloading ${(progress * 100).toInt()}%"
                )
            )
        }
    }

    override fun downloadAudioOnly(videoInfo: VideoInfo, options: YtDlpDownloadOptions): Flow<StageProgress> {
        return download(videoInfo, options.copy(audioOnly = true))
    }

    override suspend fun extractCaptions(videoInfo: VideoInfo, preferredLanguage: Language?): Subtitles? {
        extractCaptionsCalls.add(videoInfo to preferredLanguage)
        extractCaptionsError?.let { throw it }
        return extractCaptionsResponse
    }

    override suspend fun isAvailable(): Boolean = isAvailableResponse

    override suspend fun getVersion(): String? = versionResponse

    override fun validateVideo(videoInfo: VideoInfo): VideoValidationResult = validationResponse

    override fun getDownloadedVideoPath(videoInfo: VideoInfo, audioOnly: Boolean): String = downloadedVideoPathResponse

    fun reset() {
        fetchVideoInfoCalls.clear()
        downloadCalls.clear()
        extractCaptionsCalls.clear()
        fetchVideoInfoError = null
        downloadError = null
        extractCaptionsError = null
    }
}

/**
 * Mock implementation of TranscriptionService for testing.
 */
class MockTranscriptionService : TranscriptionService {

    // Configurable responses
    private var _transcriptionResult: Subtitles = TestData.subtitles()
    var transcribeProgress: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    var isAvailableResponse: Boolean = true
    var versionResponse: String? = "1.0.0"
    var isGpuAvailableResponse: Boolean = false
    private var _availableModels: List<WhisperModel> = listOf(WhisperModel.BASE, WhisperModel.SMALL)
    var modelPaths: Map<WhisperModel, String> = mapOf(
        WhisperModel.BASE to "/models/base.bin",
        WhisperModel.SMALL to "/models/small.bin"
    )

    // Error simulation
    var transcribeError: Exception? = null

    // Call tracking
    var transcribeCalls = mutableListOf<Pair<String, Language?>>()
    var currentOperationId: String? = null

    // Delay simulation
    var transcribeDelayMs: Long = 0

    fun setTranscriptionResponse(subtitles: Subtitles) {
        _transcriptionResult = subtitles
    }

    fun setAvailableModelsResponse(models: List<WhisperModel>) {
        _availableModels = models
    }

    override fun transcribe(videoPath: String, sourceLanguage: Language?): Flow<StageProgress> = flow {
        transcribeCalls.add(videoPath to sourceLanguage)
        transcribeError?.let { throw it }

        for (progress in transcribeProgress) {
            if (transcribeDelayMs > 0) delay(transcribeDelayMs)
            emit(
                StageProgress(
                    percentage = progress,
                    message = "Transcribing ${(progress * 100).toInt()}%"
                )
            )
        }
    }

    override fun getTranscriptionResult(): Subtitles = _transcriptionResult

    override suspend fun isAvailable(): Boolean = isAvailableResponse

    override suspend fun getVersion(): String? = versionResponse

    override suspend fun isGpuAvailable(): Boolean = isGpuAvailableResponse

    override fun getAvailableModels(): List<WhisperModel> = _availableModels

    override fun getModelPath(model: WhisperModel): String? = modelPaths[model]

    override fun setOperationId(operationId: String) {
        currentOperationId = operationId
    }

    fun reset() {
        transcribeCalls.clear()
        transcribeError = null
        currentOperationId = null
    }
}

/**
 * Mock implementation of TranslationServiceApi for testing.
 */
class MockTranslationService : TranslationServiceApi {

    // Configurable responses
    private var _translationResult: Subtitles = TestData.subtitles(language = Language.GERMAN)
    var translateProgress: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    var ensureLocalServiceResponse: Boolean = true

    // Error simulation
    var translateError: Exception? = null
    var ensureLocalServiceError: Exception? = null

    // Call tracking
    var translateCalls = mutableListOf<Pair<Subtitles, Language>>()
    var setGlossaryCalls = mutableListOf<Glossary?>()
    var clearCacheCalled = false
    var ensureLocalServiceCalled = false

    // State
    var currentGlossary: Glossary? = null

    // Delay simulation
    var translateDelayMs: Long = 0

    fun setTranslationResponse(subtitles: Subtitles) {
        _translationResult = subtitles
    }

    override fun translate(subtitles: Subtitles, targetLanguage: Language): Flow<StageProgress> = flow {
        translateCalls.add(subtitles to targetLanguage)
        translateError?.let { throw it }

        for (progress in translateProgress) {
            if (translateDelayMs > 0) delay(translateDelayMs)
            emit(
                StageProgress(
                    percentage = progress,
                    message = "Translating ${(progress * 100).toInt()}%"
                )
            )
        }
    }

    override fun getTranslationResult(): Subtitles = _translationResult

    override fun setGlossary(glossary: Glossary?) {
        setGlossaryCalls.add(glossary)
        currentGlossary = glossary
    }

    override fun clearCache() {
        clearCacheCalled = true
    }

    override suspend fun ensureLocalServiceRunning(): Boolean {
        ensureLocalServiceCalled = true
        ensureLocalServiceError?.let { throw it }
        return ensureLocalServiceResponse
    }

    fun reset() {
        translateCalls.clear()
        setGlossaryCalls.clear()
        clearCacheCalled = false
        ensureLocalServiceCalled = false
        translateError = null
        ensureLocalServiceError = null
        currentGlossary = null
    }
}

/**
 * Mock implementation of RenderingService for testing.
 */
class MockRenderingService : RenderingService {

    // Configurable responses
    private var _renderResult: TranslationResult = TestData.translationResult()
    var renderProgress: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    private var _availableEncoders: List<HardwareEncoder> = listOf(HardwareEncoder.NONE)
    var encoderAvailability: Map<HardwareEncoder, Boolean> = mapOf(
        HardwareEncoder.NONE to true,
        HardwareEncoder.NVENC to false,
        HardwareEncoder.VIDEOTOOLBOX to false
    )
    var videoBitrateResponse: Int? = 8000

    // Error simulation
    var renderError: Exception? = null
    var getBitrateError: Exception? = null

    // Call tracking
    data class RenderCall(
        val videoPath: String,
        val subtitles: Subtitles,
        val outputOptions: OutputOptions,
        val videoInfo: VideoInfo,
    )

    var renderCalls = mutableListOf<RenderCall>()
    var getBitrateCalls = mutableListOf<String>()

    // Delay simulation
    var renderDelayMs: Long = 0

    fun setRenderResponse(result: TranslationResult) {
        _renderResult = result
    }

    fun setAvailableEncodersResponse(encoders: List<HardwareEncoder>) {
        _availableEncoders = encoders
    }

    override fun render(
        videoPath: String,
        subtitles: Subtitles,
        outputOptions: OutputOptions,
        videoInfo: VideoInfo,
    ): Flow<StageProgress> = flow {
        renderCalls.add(RenderCall(videoPath, subtitles, outputOptions, videoInfo))
        renderError?.let { throw it }

        for (progress in renderProgress) {
            if (renderDelayMs > 0) delay(renderDelayMs)
            emit(
                StageProgress(
                    percentage = progress,
                    message = "Rendering ${(progress * 100).toInt()}%"
                )
            )
        }
    }

    override fun getRenderResult(): TranslationResult = _renderResult

    override suspend fun getAvailableEncoders(): List<HardwareEncoder> = _availableEncoders

    override suspend fun isEncoderAvailable(encoder: HardwareEncoder): Boolean =
        encoderAvailability[encoder] ?: false

    override suspend fun getVideoBitrate(videoPath: String): Int? {
        getBitrateCalls.add(videoPath)
        getBitrateError?.let { throw it }
        return videoBitrateResponse
    }

    fun reset() {
        renderCalls.clear()
        getBitrateCalls.clear()
        renderError = null
        getBitrateError = null
    }
}

/**
 * Container for all mock services, convenient for pipeline testing.
 */
data class MockServices(
    val videoDownloader: MockVideoDownloadService = MockVideoDownloadService(),
    val transcriptionService: MockTranscriptionService = MockTranscriptionService(),
    val translationService: MockTranslationService = MockTranslationService(),
    val renderingService: MockRenderingService = MockRenderingService(),
) {
    fun resetAll() {
        videoDownloader.reset()
        transcriptionService.reset()
        translationService.reset()
        renderingService.reset()
    }
}
