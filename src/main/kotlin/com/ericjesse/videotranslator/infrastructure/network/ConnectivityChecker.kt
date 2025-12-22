package com.ericjesse.videotranslator.infrastructure.network

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Represents the overall connectivity state.
 */
enum class ConnectivityState {
    /** Connected to the internet with all services available */
    CONNECTED,
    /** Connected to the internet but with slow connection */
    SLOW_CONNECTION,
    /** Connected to the internet but some services are unavailable */
    PARTIAL,
    /** No internet connection */
    DISCONNECTED,
    /** Checking connectivity */
    CHECKING,
    /** Initial state before first check */
    UNKNOWN
}

/**
 * Represents the status of a specific service.
 */
data class ServiceStatus(
    val serviceName: String,
    val isAvailable: Boolean,
    val responseTimeMs: Long? = null,
    val lastChecked: Instant = Instant.now(),
    val errorMessage: String? = null
) {
    val isHealthy: Boolean get() = isAvailable && (responseTimeMs ?: 0) < SLOW_THRESHOLD_MS

    companion object {
        const val SLOW_THRESHOLD_MS = 5000L
    }
}

/**
 * Represents the complete connectivity status.
 */
data class ConnectivityStatus(
    val state: ConnectivityState,
    val internetAvailable: Boolean,
    val services: Map<String, ServiceStatus> = emptyMap(),
    val lastChecked: Instant = Instant.now(),
    val averageLatencyMs: Long? = null
) {
    /**
     * Gets the status for a specific service.
     */
    fun getServiceStatus(serviceName: String): ServiceStatus? = services[serviceName]

    /**
     * Checks if a specific service is available.
     */
    fun isServiceAvailable(serviceName: String): Boolean =
        services[serviceName]?.isAvailable ?: false

    /**
     * Gets a user-friendly message describing the current state.
     */
    fun getMessage(): String = when (state) {
        ConnectivityState.CONNECTED -> "Connected"
        ConnectivityState.SLOW_CONNECTION -> "Slow connection detected"
        ConnectivityState.PARTIAL -> {
            val unavailable = services.filter { !it.value.isAvailable }.keys
            "Some services unavailable: ${unavailable.joinToString(", ")}"
        }
        ConnectivityState.DISCONNECTED -> "No internet connection"
        ConnectivityState.CHECKING -> "Checking connectivity..."
        ConnectivityState.UNKNOWN -> "Connectivity unknown"
    }
}

/**
 * Known services that can be checked for availability.
 */
object KnownServices {
    const val LIBRE_TRANSLATE = "LibreTranslate"
    const val DEEPL = "DeepL"
    const val OPENAI = "OpenAI"
    const val YOUTUBE = "YouTube"
    const val HUGGING_FACE = "HuggingFace"

    /**
     * Default endpoints for connectivity checks.
     */
    val defaultEndpoints = mapOf(
        LIBRE_TRANSLATE to "https://libretranslate.com/languages",
        DEEPL to "https://api.deepl.com/v2/languages",
        OPENAI to "https://api.openai.com/v1/models",
        YOUTUBE to "https://www.youtube.com",
        HUGGING_FACE to "https://huggingface.co"
    )
}

/**
 * Configuration for the connectivity checker.
 */
data class ConnectivityConfig(
    /** Interval between automatic connectivity checks */
    val checkIntervalMs: Long = 30_000L,
    /** Timeout for individual service checks */
    val serviceTimeoutMs: Long = 10_000L,
    /** Timeout for basic internet check */
    val internetCheckTimeoutMs: Long = 5_000L,
    /** Whether to automatically check connectivity periodically */
    val autoCheck: Boolean = true,
    /** Hosts to use for basic internet connectivity check */
    val internetCheckHosts: List<String> = listOf(
        "8.8.8.8",        // Google DNS
        "1.1.1.1",        // Cloudflare DNS
        "208.67.222.222"  // OpenDNS
    ),
    /** Port to use for basic connectivity check */
    val internetCheckPort: Int = 53,
    /** Threshold in ms above which connection is considered slow */
    val slowConnectionThresholdMs: Long = 5000L
)

/**
 * Checks and monitors network connectivity.
 *
 * Features:
 * - Basic internet connectivity check
 * - Service-specific availability checks
 * - Connection speed estimation
 * - Automatic periodic checking
 * - State change notifications via Flow
 *
 * @param httpClient HTTP client for making requests.
 * @param config Configuration for the checker.
 */
class ConnectivityChecker(
    private val httpClient: HttpClient,
    private val config: ConnectivityConfig = ConnectivityConfig()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Observable state
    private val _status = MutableStateFlow(
        ConnectivityStatus(
            state = ConnectivityState.UNKNOWN,
            internetAvailable = false
        )
    )
    val status: StateFlow<ConnectivityStatus> = _status.asStateFlow()

    // Compose-observable state
    var currentStatus by mutableStateOf(_status.value)
        private set

    // Custom service endpoints (overrides defaults)
    private val customEndpoints = mutableMapOf<String, String>()

    // Monitoring job
    private var monitoringJob: Job? = null

    init {
        // Sync StateFlow to Compose state
        scope.launch {
            _status.collect { newStatus ->
                currentStatus = newStatus
            }
        }
    }

    /**
     * Sets a custom endpoint for a service.
     */
    fun setServiceEndpoint(serviceName: String, endpoint: String) {
        customEndpoints[serviceName] = endpoint
    }

    /**
     * Gets the endpoint for a service.
     */
    fun getServiceEndpoint(serviceName: String): String? =
        customEndpoints[serviceName] ?: KnownServices.defaultEndpoints[serviceName]

    /**
     * Starts automatic connectivity monitoring.
     */
    fun startMonitoring() {
        if (monitoringJob?.isActive == true) return

        logger.info { "Starting connectivity monitoring" }

        monitoringJob = scope.launch {
            while (isActive) {
                checkConnectivity()
                delay(config.checkIntervalMs)
            }
        }
    }

    /**
     * Stops automatic connectivity monitoring.
     */
    fun stopMonitoring() {
        logger.info { "Stopping connectivity monitoring" }
        monitoringJob?.cancel()
        monitoringJob = null
    }

    /**
     * Performs a full connectivity check.
     *
     * @param services List of services to check. If empty, checks all known services.
     * @return The connectivity status.
     */
    suspend fun checkConnectivity(
        services: List<String> = emptyList()
    ): ConnectivityStatus = withContext(Dispatchers.IO) {
        _status.value = _status.value.copy(state = ConnectivityState.CHECKING)

        val startTime = System.currentTimeMillis()

        // Check basic internet connectivity
        val internetAvailable = checkInternetConnectivity()

        if (!internetAvailable) {
            val status = ConnectivityStatus(
                state = ConnectivityState.DISCONNECTED,
                internetAvailable = false,
                lastChecked = Instant.now()
            )
            _status.value = status
            return@withContext status
        }

        // Check services
        val servicesToCheck = services.ifEmpty {
            KnownServices.defaultEndpoints.keys.toList()
        }

        val serviceStatuses = servicesToCheck.mapNotNull { serviceName ->
            getServiceEndpoint(serviceName)?.let { endpoint ->
                serviceName to checkService(serviceName, endpoint)
            }
        }.toMap()

        val endTime = System.currentTimeMillis()
        val totalTime = endTime - startTime

        // Calculate average latency from successful checks
        val latencies = serviceStatuses.values
            .filter { it.isAvailable && it.responseTimeMs != null }
            .mapNotNull { it.responseTimeMs }

        val averageLatency = if (latencies.isNotEmpty()) {
            latencies.average().toLong()
        } else null

        // Determine overall state
        val state = determineState(internetAvailable, serviceStatuses, averageLatency)

        val status = ConnectivityStatus(
            state = state,
            internetAvailable = internetAvailable,
            services = serviceStatuses,
            lastChecked = Instant.now(),
            averageLatencyMs = averageLatency
        )

        _status.value = status
        logger.debug { "Connectivity check completed: $state (${totalTime}ms)" }

        status
    }

    /**
     * Checks internet connectivity using TCP connection to DNS servers.
     */
    suspend fun checkInternetConnectivity(): Boolean = withContext(Dispatchers.IO) {
        for (host in config.internetCheckHosts) {
            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(host, config.internetCheckPort),
                        config.internetCheckTimeoutMs.toInt()
                    )
                    return@withContext true
                }
            } catch (e: Exception) {
                logger.debug { "Internet check to $host failed: ${e.message}" }
                continue
            }
        }
        false
    }

    /**
     * Checks if a specific service is available.
     */
    suspend fun checkService(
        serviceName: String,
        endpoint: String
    ): ServiceStatus = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val response = withTimeout(config.serviceTimeoutMs) {
                httpClient.get(endpoint)
            }

            val responseTime = System.currentTimeMillis() - startTime

            val isAvailable = response.status.isSuccess() ||
                    response.status == HttpStatusCode.Unauthorized || // API key required but service is up
                    response.status == HttpStatusCode.Forbidden

            ServiceStatus(
                serviceName = serviceName,
                isAvailable = isAvailable,
                responseTimeMs = responseTime,
                lastChecked = Instant.now(),
                errorMessage = if (!isAvailable) "HTTP ${response.status.value}" else null
            )
        } catch (e: Exception) {
            val responseTime = System.currentTimeMillis() - startTime

            logger.debug { "Service check for $serviceName failed: ${e.message}" }

            ServiceStatus(
                serviceName = serviceName,
                isAvailable = false,
                responseTimeMs = responseTime,
                lastChecked = Instant.now(),
                errorMessage = e.message ?: "Connection failed"
            )
        }
    }

    /**
     * Checks if a specific service is available (convenience method).
     */
    suspend fun isServiceAvailable(serviceName: String): Boolean {
        val endpoint = getServiceEndpoint(serviceName) ?: return false
        return checkService(serviceName, endpoint).isAvailable
    }

    /**
     * Checks connectivity before starting a translation.
     *
     * @param translationService The translation service to use.
     * @return Result with connectivity status or error.
     */
    suspend fun checkBeforeTranslation(
        translationService: String
    ): ConnectivityCheckResult = withContext(Dispatchers.IO) {
        // First check basic internet
        if (!checkInternetConnectivity()) {
            return@withContext ConnectivityCheckResult.NoInternet
        }

        // Check YouTube (needed for downloading)
        val youtubeStatus = checkService(
            KnownServices.YOUTUBE,
            KnownServices.defaultEndpoints[KnownServices.YOUTUBE]!!
        )
        if (!youtubeStatus.isAvailable) {
            return@withContext ConnectivityCheckResult.ServiceUnavailable(
                KnownServices.YOUTUBE,
                youtubeStatus.errorMessage ?: "Service unavailable"
            )
        }

        // Check translation service
        val translationEndpoint = getServiceEndpoint(translationService)
        if (translationEndpoint != null) {
            val translationStatus = checkService(translationService, translationEndpoint)
            if (!translationStatus.isAvailable) {
                return@withContext ConnectivityCheckResult.ServiceUnavailable(
                    translationService,
                    translationStatus.errorMessage ?: "Service unavailable"
                )
            }

            // Check for slow connection
            translationStatus.responseTimeMs?.let { latency ->
                if (latency > config.slowConnectionThresholdMs) {
                    return@withContext ConnectivityCheckResult.SlowConnection(latency)
                }
            }
        }

        ConnectivityCheckResult.Ready
    }

    /**
     * Waits for connectivity to be restored.
     *
     * @param timeout Maximum time to wait.
     * @param checkInterval Interval between checks.
     * @return true if connectivity was restored, false if timeout.
     */
    suspend fun waitForConnectivity(
        timeout: Duration = Duration.ofMinutes(5),
        checkInterval: Duration = Duration.ofSeconds(5)
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = Instant.now().plus(timeout)

        while (Instant.now().isBefore(deadline)) {
            if (checkInternetConnectivity()) {
                return@withContext true
            }
            delay(checkInterval.toMillis())
        }

        false
    }

    /**
     * Determines the overall connectivity state.
     */
    private fun determineState(
        internetAvailable: Boolean,
        services: Map<String, ServiceStatus>,
        averageLatency: Long?
    ): ConnectivityState {
        if (!internetAvailable) return ConnectivityState.DISCONNECTED

        val availableServices = services.values.count { it.isAvailable }
        val totalServices = services.size

        return when {
            totalServices == 0 -> ConnectivityState.CONNECTED
            availableServices == 0 -> ConnectivityState.PARTIAL
            availableServices < totalServices -> ConnectivityState.PARTIAL
            averageLatency != null && averageLatency > config.slowConnectionThresholdMs -> {
                ConnectivityState.SLOW_CONNECTION
            }
            else -> ConnectivityState.CONNECTED
        }
    }

    /**
     * Closes the connectivity checker and releases resources.
     */
    fun close() {
        stopMonitoring()
        scope.cancel()
    }
}

/**
 * Result of a pre-translation connectivity check.
 */
sealed class ConnectivityCheckResult {
    /** All services are available and ready */
    data object Ready : ConnectivityCheckResult()

    /** No internet connection */
    data object NoInternet : ConnectivityCheckResult()

    /** A specific service is unavailable */
    data class ServiceUnavailable(
        val serviceName: String,
        val reason: String
    ) : ConnectivityCheckResult()

    /** Connection is slow */
    data class SlowConnection(val latencyMs: Long) : ConnectivityCheckResult()

    /**
     * Returns true if the result indicates connectivity is ready.
     */
    fun isReady(): Boolean = this is Ready

    /**
     * Returns a user-friendly message for this result.
     */
    fun getMessage(): String = when (this) {
        is Ready -> "Ready to translate"
        is NoInternet -> "No internet connection. Please check your network settings."
        is ServiceUnavailable -> "$serviceName is currently unavailable: $reason"
        is SlowConnection -> "Slow connection detected (${latencyMs}ms latency). Translation may take longer."
    }
}

/**
 * Extension to check connectivity and report errors through ErrorHandler.
 */
suspend fun ConnectivityChecker.checkWithErrorReporting(
    translationService: String,
    onRetry: (() -> Unit)? = null
): Boolean {
    val result = checkBeforeTranslation(translationService)

    when (result) {
        is ConnectivityCheckResult.Ready -> return true

        is ConnectivityCheckResult.NoInternet -> {
            com.ericjesse.videotranslator.ui.error.ErrorHandler.reportError(
                title = "No Internet Connection",
                message = result.getMessage(),
                category = com.ericjesse.videotranslator.ui.error.ErrorCategory.NetworkError(),
                retryAction = onRetry
            )
        }

        is ConnectivityCheckResult.ServiceUnavailable -> {
            com.ericjesse.videotranslator.ui.error.ErrorHandler.reportError(
                title = "${result.serviceName} Unavailable",
                message = result.getMessage(),
                category = com.ericjesse.videotranslator.ui.error.ErrorCategory.NetworkError(
                    endpoint = result.serviceName
                ),
                retryAction = onRetry
            )
        }

        is ConnectivityCheckResult.SlowConnection -> {
            // Slow connection is a warning, not a blocker
            return true
        }
    }

    return false
}
