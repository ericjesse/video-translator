package com.ericjesse.videotranslator.infrastructure.resources

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.ericjesse.videotranslator.infrastructure.config.ConfigManager
import com.ericjesse.videotranslator.infrastructure.config.ResourceSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.lang.management.MemoryUsage
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Represents the current memory state.
 */
data class MemoryState(
    /** Heap memory used in bytes */
    val heapUsed: Long,
    /** Heap memory max in bytes */
    val heapMax: Long,
    /** Non-heap memory used in bytes */
    val nonHeapUsed: Long,
    /** Total JVM memory usage in bytes */
    val totalUsed: Long,
    /** System total physical memory in bytes */
    val systemTotal: Long,
    /** System available physical memory in bytes */
    val systemAvailable: Long,
    /** Usage percentage (0-100) */
    val usagePercent: Int,
    /** Timestamp of this measurement */
    val timestamp: Instant = Instant.now()
) {
    val heapUsedMB: Long get() = heapUsed / (1024 * 1024)
    val heapMaxMB: Long get() = heapMax / (1024 * 1024)
    val totalUsedMB: Long get() = totalUsed / (1024 * 1024)
    val systemTotalMB: Long get() = systemTotal / (1024 * 1024)
    val systemAvailableMB: Long get() = systemAvailable / (1024 * 1024)

    /**
     * Checks if memory usage is critical (above 90%).
     */
    val isCritical: Boolean get() = usagePercent > 90

    /**
     * Checks if memory usage is high (above 75%).
     */
    val isHigh: Boolean get() = usagePercent > 75

    /**
     * Gets a human-readable summary.
     */
    fun getSummary(): String = buildString {
        append("Heap: ${heapUsedMB}MB / ${heapMaxMB}MB (${usagePercent}%)")
        append(" | System: ${systemAvailableMB}MB available of ${systemTotalMB}MB")
    }
}

/**
 * Result of a resource check.
 */
sealed class ResourceCheckResult {
    /** Resources are sufficient */
    data object Sufficient : ResourceCheckResult()

    /** Memory is low, suggest using smaller model */
    data class LowMemory(
        val currentUsageMB: Long,
        val limitMB: Long,
        val suggestedModel: String?
    ) : ResourceCheckResult()

    /** Memory limit would be exceeded */
    data class MemoryLimitExceeded(
        val currentUsageMB: Long,
        val limitMB: Long,
        val requiredMB: Long
    ) : ResourceCheckResult()

    /** Disk space is low */
    data class LowDiskSpace(
        val availableMB: Long,
        val requiredMB: Long
    ) : ResourceCheckResult()

    fun isOk(): Boolean = this is Sufficient

    fun getMessage(): String = when (this) {
        is Sufficient -> "Resources are sufficient"
        is LowMemory -> "Memory usage is high (${currentUsageMB}MB / ${limitMB}MB). " +
                suggestedModel?.let { "Consider using $it model." } ?: "Consider reducing workload."
        is MemoryLimitExceeded -> "Memory limit would be exceeded. " +
                "Current: ${currentUsageMB}MB, Limit: ${limitMB}MB, Required: ${requiredMB}MB"
        is LowDiskSpace -> "Insufficient disk space. " +
                "Available: ${availableMB}MB, Required: ${requiredMB}MB"
    }
}

/**
 * Whisper model memory requirements (approximate).
 */
object WhisperModelRequirements {
    val modelSizes = mapOf(
        "tiny" to 75L,      // ~75MB
        "base" to 150L,     // ~150MB
        "small" to 500L,    // ~500MB
        "medium" to 1500L,  // ~1.5GB
        "large" to 3000L    // ~3GB
    )

    /**
     * Gets the suggested model based on available memory.
     */
    fun getSuggestedModel(availableMemoryMB: Long): String = when {
        availableMemoryMB >= 4000 -> "large"
        availableMemoryMB >= 2000 -> "medium"
        availableMemoryMB >= 800 -> "small"
        availableMemoryMB >= 300 -> "base"
        else -> "tiny"
    }

    /**
     * Gets a smaller fallback model.
     */
    fun getSmallerModel(currentModel: String): String? = when (currentModel) {
        "large" -> "medium"
        "medium" -> "small"
        "small" -> "base"
        "base" -> "tiny"
        else -> null
    }

    /**
     * Gets the memory requirement for a model.
     */
    fun getMemoryRequirement(model: String): Long = modelSizes[model] ?: 150L
}

/**
 * Manages system resources including memory tracking and limits.
 *
 * Features:
 * - Real-time memory monitoring
 * - Configurable memory limits (MB or percentage)
 * - Pre-operation resource checks
 * - Graceful degradation suggestions
 * - Observable state for UI updates
 *
 * @param configManager Configuration manager for resource settings.
 */
class ResourceManager(
    private val configManager: ConfigManager? = null
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val memoryMXBean: MemoryMXBean = ManagementFactory.getMemoryMXBean()
    private val osMXBean = ManagementFactory.getOperatingSystemMXBean()

    // Observable state
    private val _memoryState = MutableStateFlow(fetchMemoryState())
    val memoryState: StateFlow<MemoryState> = _memoryState.asStateFlow()

    // Compose-observable state
    var currentMemoryState by mutableStateOf(_memoryState.value)
        private set

    // Resource settings
    private var settings: ResourceSettings = configManager?.getSettings()?.resources
        ?: ResourceSettings()

    // Monitoring
    private var monitoringJob: Job? = null

    // Memory tracking for operations
    private val operationMemoryBaselines = mutableMapOf<String, Long>()

    init {
        // Sync StateFlow to Compose state
        scope.launch {
            _memoryState.collect { newState ->
                currentMemoryState = newState
            }
        }
    }

    /**
     * Reloads settings from config.
     */
    fun reloadSettings() {
        settings = configManager?.getSettings()?.resources ?: ResourceSettings()
        logger.debug { "Resource settings reloaded: maxMB=${settings.maxMemoryMB}, maxPercent=${settings.maxMemoryPercent}" }
    }

    /**
     * Fetches and returns the current memory state.
     */
    fun fetchMemoryState(): MemoryState {
        val heapUsage = memoryMXBean.heapMemoryUsage
        val nonHeapUsage = memoryMXBean.nonHeapMemoryUsage

        val systemTotal = getSystemTotalMemory()
        val systemAvailable = getSystemAvailableMemory()

        val totalUsed = heapUsage.used + nonHeapUsage.used
        val usagePercent = if (heapUsage.max > 0) {
            ((heapUsage.used.toDouble() / heapUsage.max) * 100).toInt()
        } else {
            0
        }

        return MemoryState(
            heapUsed = heapUsage.used,
            heapMax = heapUsage.max,
            nonHeapUsed = nonHeapUsage.used,
            totalUsed = totalUsed,
            systemTotal = systemTotal,
            systemAvailable = systemAvailable,
            usagePercent = usagePercent
        )
    }

    /**
     * Gets the effective memory limit based on settings.
     */
    fun getEffectiveMemoryLimitMB(): Long {
        val absoluteLimit = settings.maxMemoryMB.toLong()
        val percentageLimit = (getSystemTotalMemory() * settings.maxMemoryPercent / 100) / (1024 * 1024)
        return minOf(absoluteLimit, percentageLimit)
    }

    /**
     * Checks if an operation can be started with the given memory requirement.
     *
     * @param requiredMemoryMB Additional memory required for the operation.
     * @param operationType Type of operation (for logging).
     * @return ResourceCheckResult indicating if operation can proceed.
     */
    fun checkResourcesForOperation(
        requiredMemoryMB: Long,
        operationType: String
    ): ResourceCheckResult {
        val state = fetchMemoryState()
        val limitMB = getEffectiveMemoryLimitMB()
        val currentUsageMB = state.totalUsedMB
        val projectedUsageMB = currentUsageMB + requiredMemoryMB

        logger.debug {
            "Resource check for $operationType: " +
                    "current=${currentUsageMB}MB, required=${requiredMemoryMB}MB, " +
                    "projected=${projectedUsageMB}MB, limit=${limitMB}MB"
        }

        return when {
            projectedUsageMB > limitMB -> {
                ResourceCheckResult.MemoryLimitExceeded(
                    currentUsageMB = currentUsageMB,
                    limitMB = limitMB,
                    requiredMB = requiredMemoryMB
                )
            }
            projectedUsageMB > limitMB * 0.8 -> {
                // Warn if we'd use more than 80% of limit
                val suggestedModel = WhisperModelRequirements.getSuggestedModel(
                    limitMB - currentUsageMB
                )
                ResourceCheckResult.LowMemory(
                    currentUsageMB = currentUsageMB,
                    limitMB = limitMB,
                    suggestedModel = suggestedModel
                )
            }
            else -> ResourceCheckResult.Sufficient
        }
    }

    /**
     * Checks if a Whisper model can be loaded given current resources.
     *
     * @param modelName The Whisper model name (tiny, base, small, medium, large).
     * @return ResourceCheckResult with suggestion for alternative model if needed.
     */
    fun checkResourcesForWhisperModel(modelName: String): ResourceCheckResult {
        val requiredMB = WhisperModelRequirements.getMemoryRequirement(modelName)
        val result = checkResourcesForOperation(requiredMB, "Whisper $modelName")

        // If memory is an issue, suggest a smaller model
        if (result is ResourceCheckResult.MemoryLimitExceeded ||
            result is ResourceCheckResult.LowMemory) {
            val suggestedModel = WhisperModelRequirements.getSmallerModel(modelName)
            if (suggestedModel != null) {
                val state = fetchMemoryState()
                return ResourceCheckResult.LowMemory(
                    currentUsageMB = state.totalUsedMB,
                    limitMB = getEffectiveMemoryLimitMB(),
                    suggestedModel = suggestedModel
                )
            }
        }

        return result
    }

    /**
     * Gets the best available Whisper model given current resources.
     *
     * @param preferredModel The user's preferred model.
     * @return The best model that can be used, or the preferred if resources allow.
     */
    fun getBestAvailableWhisperModel(preferredModel: String): String {
        var currentModel = preferredModel

        while (true) {
            val result = checkResourcesForWhisperModel(currentModel)
            if (result.isOk()) {
                if (currentModel != preferredModel) {
                    logger.info { "Degraded from $preferredModel to $currentModel due to memory constraints" }
                }
                return currentModel
            }

            val smallerModel = WhisperModelRequirements.getSmallerModel(currentModel)
            if (smallerModel == null) {
                logger.warn { "No smaller model available, using $currentModel anyway" }
                return currentModel
            }
            currentModel = smallerModel
        }
    }

    /**
     * Starts tracking memory for an operation.
     *
     * @param operationId Unique identifier for the operation.
     */
    fun startOperationTracking(operationId: String) {
        val state = fetchMemoryState()
        operationMemoryBaselines[operationId] = state.totalUsed
        logger.debug { "Started tracking operation $operationId at ${state.totalUsedMB}MB" }
    }

    /**
     * Checks if an operation has exceeded its memory budget.
     *
     * @param operationId The operation to check.
     * @param maxIncreaseMB Maximum allowed memory increase.
     * @return true if limit exceeded.
     */
    fun checkOperationMemoryLimit(operationId: String, maxIncreaseMB: Long): Boolean {
        val baseline = operationMemoryBaselines[operationId] ?: return false
        val current = fetchMemoryState().totalUsed
        val increaseMB = (current - baseline) / (1024 * 1024)

        if (increaseMB > maxIncreaseMB) {
            logger.warn { "Operation $operationId exceeded memory limit: ${increaseMB}MB > ${maxIncreaseMB}MB" }
            return true
        }
        return false
    }

    /**
     * Stops tracking an operation.
     */
    fun stopOperationTracking(operationId: String) {
        val baseline = operationMemoryBaselines.remove(operationId)
        if (baseline != null) {
            val current = fetchMemoryState().totalUsed
            val usedMB = (current - baseline) / (1024 * 1024)
            logger.debug { "Operation $operationId completed, used ${usedMB}MB" }
        }
    }

    /**
     * Starts continuous memory monitoring.
     *
     * @param intervalMs Monitoring interval in milliseconds.
     * @param onHighMemory Callback when memory usage is high.
     * @param onCriticalMemory Callback when memory usage is critical.
     */
    fun startMonitoring(
        intervalMs: Long = 5000L,
        onHighMemory: ((MemoryState) -> Unit)? = null,
        onCriticalMemory: ((MemoryState) -> Unit)? = null
    ) {
        if (monitoringJob?.isActive == true) return

        logger.info { "Starting memory monitoring (interval: ${intervalMs}ms)" }

        monitoringJob = scope.launch {
            while (isActive) {
                val state = fetchMemoryState()
                _memoryState.value = state

                when {
                    state.isCritical -> {
                        logger.warn { "Critical memory: ${state.getSummary()}" }
                        onCriticalMemory?.invoke(state)
                    }
                    state.isHigh -> {
                        logger.debug { "High memory: ${state.getSummary()}" }
                        onHighMemory?.invoke(state)
                    }
                }

                delay(intervalMs)
            }
        }
    }

    /**
     * Stops memory monitoring.
     */
    fun stopMonitoring() {
        logger.info { "Stopping memory monitoring" }
        monitoringJob?.cancel()
        monitoringJob = null
    }

    /**
     * Requests garbage collection.
     * Note: This is a hint to the JVM, not a guarantee.
     */
    fun requestGarbageCollection() {
        logger.debug { "Requesting garbage collection" }
        System.gc()
    }

    /**
     * Gets system total physical memory.
     */
    private fun getSystemTotalMemory(): Long {
        return try {
            val bean = osMXBean as? com.sun.management.OperatingSystemMXBean
            bean?.totalMemorySize ?: Runtime.getRuntime().maxMemory()
        } catch (e: Exception) {
            Runtime.getRuntime().maxMemory()
        }
    }

    /**
     * Gets system available physical memory.
     */
    private fun getSystemAvailableMemory(): Long {
        return try {
            val bean = osMXBean as? com.sun.management.OperatingSystemMXBean
            bean?.freeMemorySize ?: Runtime.getRuntime().freeMemory()
        } catch (e: Exception) {
            Runtime.getRuntime().freeMemory()
        }
    }

    /**
     * Closes the resource manager.
     */
    fun close() {
        stopMonitoring()
        scope.cancel()
    }
}

/**
 * Exception thrown when a memory limit is exceeded.
 */
class MemoryLimitExceededException(
    val currentUsageMB: Long,
    val limitMB: Long,
    val requiredMB: Long
) : Exception(
    "Memory limit exceeded: current ${currentUsageMB}MB + required ${requiredMB}MB > limit ${limitMB}MB"
)

/**
 * Extension to check resources and throw if insufficient.
 */
fun ResourceManager.requireResources(requiredMemoryMB: Long, operationType: String) {
    when (val result = checkResourcesForOperation(requiredMemoryMB, operationType)) {
        is ResourceCheckResult.MemoryLimitExceeded -> {
            throw MemoryLimitExceededException(
                result.currentUsageMB,
                result.limitMB,
                result.requiredMB
            )
        }
        else -> { /* OK */ }
    }
}
