package com.ericjesse.videotranslator.infrastructure.process

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Token for cancelling running processes.
 * Thread-safe and can be shared across coroutines.
 */
class CancellationToken {
    private val cancelled = AtomicBoolean(false)
    private val processRef = AtomicReference<Process?>(null)

    /** Whether cancellation has been requested. */
    val isCancelled: Boolean get() = cancelled.get()

    /**
     * Cancels the process if running.
     * Safe to call multiple times.
     */
    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            logger.info { "Cancellation requested" }
            processRef.get()?.let { process ->
                if (process.isAlive) {
                    logger.info { "Destroying process due to cancellation" }
                    process.destroyForcibly()
                }
            }
        }
    }

    internal fun attachProcess(process: Process) {
        processRef.set(process)
        // If already cancelled before process started, kill immediately
        if (cancelled.get() && process.isAlive) {
            process.destroyForcibly()
        }
    }

    internal fun detachProcess() {
        processRef.set(null)
    }
}

/**
 * Configuration for process execution.
 *
 * @property workingDir The working directory for the process. If null, uses the current directory.
 * @property timeoutMinutes Maximum time to wait for process completion. Default is 60 minutes.
 * @property environment Additional environment variables to set for the process.
 * @property memoryLimitMb Memory limit in MB for Java subprocesses. Adds -Xmx flag automatically.
 * @property inheritIO If true, inherits the parent process's stdin/stdout/stderr.
 */
data class ProcessConfig(
    val workingDir: String? = null,
    val timeoutMinutes: Long = 60,
    val environment: Map<String, String> = emptyMap(),
    val memoryLimitMb: Int? = null,
    val inheritIO: Boolean = false
)

/**
 * Executes external processes (yt-dlp, FFmpeg, Whisper).
 * Provides streaming output, cancellation support, and proper process management.
 *
 * Features:
 * - Cross-platform command execution (Windows/Unix)
 * - Process cancellation via CancellationToken
 * - UTF-8 encoding on all platforms
 * - Memory limit enforcement for Java subprocesses
 * - Real-time line-by-line output streaming
 */
class ProcessExecutor {

    companion object {
        private val IS_WINDOWS = System.getProperty("os.name").lowercase().contains("win")

        /**
         * Wraps a command for shell execution if needed.
         * On Windows, uses cmd.exe for commands that need shell features.
         */
        fun shellCommand(command: String): List<String> {
            return if (IS_WINDOWS) {
                listOf("cmd.exe", "/c", command)
            } else {
                listOf("/bin/sh", "-c", command)
            }
        }
    }

    /**
     * Executes a command and streams output lines to the callback.
     * Supports cancellation via CancellationToken.
     *
     * @param command The command and arguments to execute
     * @param config Process configuration options
     * @param cancellationToken Optional token for cancelling the process
     * @param onOutput Callback for each line of output (stdout + stderr merged)
     * @return Exit code of the process
     * @throws ProcessException if the process exits with non-zero code
     * @throws ProcessCancelledException if cancelled via token
     */
    suspend fun execute(
        command: List<String>,
        config: ProcessConfig = ProcessConfig(),
        cancellationToken: CancellationToken? = null,
        onOutput: suspend (String) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val commandStr = command.joinToString(" ")

        logger.info { "Executing command: $commandStr" }
        logger.debug { "Working directory: ${config.workingDir ?: "default"}" }
        logger.debug { "Timeout: ${config.timeoutMinutes} minutes" }
        config.memoryLimitMb?.let { logger.debug { "Memory limit: ${it}MB" } }

        // Check for pre-cancellation
        if (cancellationToken?.isCancelled == true) {
            throw ProcessCancelledException(command.first())
        }

        val processBuilder = createProcessBuilder(command, config)
        val process = processBuilder.start()

        cancellationToken?.attachProcess(process)

        try {
            // Stream output in real-time with UTF-8 encoding
            val reader = BufferedReader(
                InputStreamReader(process.inputStream, StandardCharsets.UTF_8),
                1 // Small buffer for real-time streaming
            )

            var lineCount = 0
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                // Check cancellation between lines
                if (cancellationToken?.isCancelled == true) {
                    process.destroyForcibly()
                    throw ProcessCancelledException(command.first())
                }

                line?.let {
                    lineCount++
                    logger.trace { "[$lineCount] $it" }
                    onOutput(it)
                }

                // Yield to allow cancellation checks
                yield()
            }

            // Wait for process completion
            val completed = process.waitFor(config.timeoutMinutes, TimeUnit.MINUTES)
            val elapsed = System.currentTimeMillis() - startTime

            if (!completed) {
                logger.error { "Process timed out after ${config.timeoutMinutes} minutes" }
                process.destroyForcibly()
                throw ProcessException(
                    command = command.first(),
                    exitCode = -1,
                    message = "Process timed out after ${config.timeoutMinutes} minutes"
                )
            }

            val exitCode = process.exitValue()

            if (exitCode != 0) {
                // Try to capture any remaining error output
                val errorOutput = try {
                    process.errorStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
                } catch (e: Exception) {
                    ""
                }

                logger.error { "Process failed with exit code $exitCode after ${elapsed}ms" }
                if (errorOutput.isNotEmpty()) {
                    logger.error { "Error output: $errorOutput" }
                }

                val errorMessage = if (errorOutput.isNotEmpty()) {
                    "Process exited with code $exitCode: $errorOutput"
                } else {
                    "Process exited with code $exitCode"
                }

                throw ProcessException(
                    command = command.first(),
                    exitCode = exitCode,
                    message = errorMessage
                )
            }

            logger.info { "Process completed successfully in ${elapsed}ms ($lineCount lines of output)" }
            exitCode

        } finally {
            cancellationToken?.detachProcess()
            if (process.isAlive) {
                logger.debug { "Cleaning up: destroying process" }
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    /**
     * Executes a command and returns output as a Flow for reactive processing.
     * Each emission is a line of output.
     */
    fun executeAsFlow(
        command: List<String>,
        config: ProcessConfig = ProcessConfig(),
        cancellationToken: CancellationToken? = null
    ): Flow<String> = flow {
        execute(command, config, cancellationToken) { line ->
            emit(line)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Executes a command and returns the combined output.
     * Captures stdout and stderr separately.
     */
    suspend fun executeAndCapture(
        command: List<String>,
        config: ProcessConfig = ProcessConfig(),
        cancellationToken: CancellationToken? = null
    ): ProcessResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val output = StringBuilder()
        val errors = StringBuilder()
        val commandStr = command.joinToString(" ")

        logger.info { "Executing (capture mode): $commandStr" }

        if (cancellationToken?.isCancelled == true) {
            throw ProcessCancelledException(command.first())
        }

        val processBuilder = createProcessBuilder(command, config, mergeErrorStream = false)
        val process = processBuilder.start()

        cancellationToken?.attachProcess(process)

        try {
            // Read both streams concurrently with UTF-8
            val stdoutJob = CoroutineScope(Dispatchers.IO).launch {
                BufferedReader(
                    InputStreamReader(process.inputStream, StandardCharsets.UTF_8)
                ).use { reader ->
                    reader.forEachLine { line ->
                        output.appendLine(line)
                        logger.trace { "[stdout] $line" }
                    }
                }
            }

            val stderrJob = CoroutineScope(Dispatchers.IO).launch {
                BufferedReader(
                    InputStreamReader(process.errorStream, StandardCharsets.UTF_8)
                ).use { reader ->
                    reader.forEachLine { line ->
                        errors.appendLine(line)
                        logger.trace { "[stderr] $line" }
                    }
                }
            }

            // Wait with timeout
            val completed = process.waitFor(config.timeoutMinutes, TimeUnit.MINUTES)

            // Give streams time to finish
            withTimeoutOrNull(2000) {
                stdoutJob.join()
                stderrJob.join()
            }

            val elapsed = System.currentTimeMillis() - startTime

            if (!completed) {
                logger.error { "Process timed out after ${config.timeoutMinutes} minutes" }
                process.destroyForcibly()
                return@withContext ProcessResult(
                    exitCode = -1,
                    stdout = output.toString(),
                    stderr = "Process timed out after ${config.timeoutMinutes} minutes"
                )
            }

            val exitCode = process.exitValue()
            logger.info { "Process completed with exit code $exitCode in ${elapsed}ms" }

            ProcessResult(
                exitCode = exitCode,
                stdout = output.toString().trim(),
                stderr = errors.toString().trim()
            )

        } finally {
            cancellationToken?.detachProcess()
            if (process.isAlive) {
                logger.debug { "Cleaning up: destroying process" }
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    /**
     * Checks if a command is available (exists in PATH or at given path).
     */
    suspend fun isAvailable(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            logger.debug { "Checking availability of: $command" }

            val checkCommand = if (IS_WINDOWS && !command.contains("\\") && !command.contains("/")) {
                listOf("where", command)
            } else {
                listOf(command, "--version")
            }

            val process = ProcessBuilder(checkCommand)
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(10, TimeUnit.SECONDS)
            val available = completed && process.exitValue() == 0

            logger.debug { "$command available: $available" }
            available
        } catch (e: Exception) {
            logger.debug { "$command not available: ${e.message}" }
            false
        }
    }

    /**
     * Gets the version of a command if available.
     */
    suspend fun getVersion(command: String): String? = withContext(Dispatchers.IO) {
        try {
            logger.debug { "Getting version of: $command" }
            val result = executeAndCapture(
                listOf(command, "--version"),
                ProcessConfig(timeoutMinutes = 1)
            )
            if (result.exitCode == 0) {
                result.stdout.lines().firstOrNull()?.trim()
            } else null
        } catch (e: Exception) {
            logger.debug { "Could not get version of $command: ${e.message}" }
            null
        }
    }

    /**
     * Creates a ProcessBuilder with proper configuration.
     */
    private fun createProcessBuilder(
        command: List<String>,
        config: ProcessConfig,
        mergeErrorStream: Boolean = true
    ): ProcessBuilder {
        val finalCommand = applyMemoryLimit(command, config.memoryLimitMb)

        logger.debug { "Final command: ${finalCommand.joinToString(" ")}" }
        logger.debug { "Command arguments (${finalCommand.size} total):" }
        finalCommand.forEachIndexed { index, arg ->
            logger.debug { "  [$index] = '$arg'" }
        }

        return ProcessBuilder(finalCommand).apply {
            if (mergeErrorStream) {
                redirectErrorStream(true)
            }

            config.workingDir?.let {
                directory(File(it))
                logger.debug { "Set working directory: $it" }
            }

            // Set environment for unbuffered output and UTF-8
            environment().apply {
                // Python unbuffered output
                put("PYTHONUNBUFFERED", "1")
                put("PYTHONIOENCODING", "utf-8")

                // Force UTF-8 on Windows
                if (IS_WINDOWS) {
                    put("PYTHONUTF8", "1")
                }

                // FFmpeg UTF-8 support
                put("FFMPEG_FORCE_UTF8", "1")

                // Add custom environment variables
                putAll(config.environment)
            }

            if (config.inheritIO) {
                inheritIO()
            }
        }
    }

    /**
     * Applies memory limit to Java commands.
     */
    private fun applyMemoryLimit(command: List<String>, memoryLimitMb: Int?): List<String> {
        if (memoryLimitMb == null) return command

        val executable = command.firstOrNull()?.lowercase() ?: return command

        // Check if this is a Java command
        if (executable == "java" || executable.endsWith("java.exe")) {
            val mutableCommand = command.toMutableList()

            // Check if -Xmx is already set
            val hasXmx = command.any { it.startsWith("-Xmx") }

            if (!hasXmx) {
                // Insert -Xmx after "java" command
                mutableCommand.add(1, "-Xmx${memoryLimitMb}m")
                logger.debug { "Applied memory limit: -Xmx${memoryLimitMb}m" }
            }

            return mutableCommand
        }

        return command
    }
}

/**
 * Result of a process execution.
 *
 * @property exitCode The exit code returned by the process. 0 typically indicates success.
 * @property stdout The captured standard output of the process.
 * @property stderr The captured standard error of the process.
 */
data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    /** Whether the process completed successfully (exit code 0). */
    val isSuccess: Boolean get() = exitCode == 0

    /**
     * Returns stdout if successful, otherwise throws ProcessException.
     */
    fun getOrThrow(command: String = "process"): String {
        if (!isSuccess) {
            throw ProcessException(
                command = command,
                exitCode = exitCode,
                message = stderr.ifBlank { "Process failed" }
            )
        }
        return stdout
    }
}

/**
 * Exception thrown when a process fails.
 *
 * @property command The command that failed (typically the executable name).
 * @property exitCode The exit code returned by the failed process.
 * @property message A description of the failure.
 */
class ProcessException(
    val command: String,
    val exitCode: Int,
    override val message: String
) : Exception("$command failed: $message (exit code: $exitCode)")

/**
 * Exception thrown when a process is cancelled via [CancellationToken].
 *
 * @property command The command that was cancelled.
 */
class ProcessCancelledException(
    val command: String
) : Exception("Process '$command' was cancelled")
