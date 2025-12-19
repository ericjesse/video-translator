package com.ericjesse.videotranslator.infrastructure.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Executes external processes (yt-dlp, FFmpeg, Whisper).
 * Provides streaming output and proper process management.
 */
class ProcessExecutor {
    
    /**
     * Executes a command and streams output lines to the callback.
     * Throws ProcessException if the process exits with non-zero code.
     */
    suspend fun execute(
        command: List<String>,
        workingDir: String? = null,
        timeoutMinutes: Long = 60,
        onOutput: suspend (String) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        logger.debug { "Executing: ${command.joinToString(" ")}" }
        
        val processBuilder = ProcessBuilder(command).apply {
            redirectErrorStream(true) // Merge stderr into stdout
            workingDir?.let { directory(java.io.File(it)) }
            
            // Ensure we can read output
            environment()["PYTHONUNBUFFERED"] = "1"
        }
        
        val process = processBuilder.start()
        
        try {
            // Read output stream
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            
            while (reader.readLine().also { line = it } != null) {
                line?.let { 
                    logger.trace { it }
                    onOutput(it)
                }
            }
            
            // Wait for process to complete
            val completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
            
            if (!completed) {
                process.destroyForcibly()
                throw ProcessException(
                    command = command.first(),
                    exitCode = -1,
                    message = "Process timed out after $timeoutMinutes minutes"
                )
            }
            
            val exitCode = process.exitValue()
            
            if (exitCode != 0) {
                throw ProcessException(
                    command = command.first(),
                    exitCode = exitCode,
                    message = "Process exited with code $exitCode"
                )
            }
            
            exitCode
            
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }
    
    /**
     * Executes a command and returns the combined output.
     */
    suspend fun executeAndCapture(
        command: List<String>,
        workingDir: String? = null,
        timeoutMinutes: Long = 5
    ): ProcessResult = withContext(Dispatchers.IO) {
        val output = StringBuilder()
        val errors = StringBuilder()
        
        logger.debug { "Executing: ${command.joinToString(" ")}" }
        
        val processBuilder = ProcessBuilder(command).apply {
            workingDir?.let { directory(java.io.File(it)) }
        }
        
        val process = processBuilder.start()
        
        try {
            // Read stdout
            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))
            
            // Read both streams
            val stdoutThread = Thread {
                stdoutReader.forEachLine { output.appendLine(it) }
            }
            val stderrThread = Thread {
                stderrReader.forEachLine { errors.appendLine(it) }
            }
            
            stdoutThread.start()
            stderrThread.start()
            
            val completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
            
            stdoutThread.join(1000)
            stderrThread.join(1000)
            
            if (!completed) {
                process.destroyForcibly()
                return@withContext ProcessResult(
                    exitCode = -1,
                    stdout = output.toString(),
                    stderr = "Process timed out"
                )
            }
            
            ProcessResult(
                exitCode = process.exitValue(),
                stdout = output.toString().trim(),
                stderr = errors.toString().trim()
            )
            
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }
    
    /**
     * Checks if a command is available (exists in PATH or at given path).
     */
    suspend fun isAvailable(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(listOf(command, "--version"))
                .redirectErrorStream(true)
                .start()
            
            process.waitFor(5, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Gets the version of a command if available.
     */
    suspend fun getVersion(command: String): String? = withContext(Dispatchers.IO) {
        try {
            val result = executeAndCapture(listOf(command, "--version"), timeoutMinutes = 1)
            if (result.exitCode == 0) {
                // Extract version from first line
                result.stdout.lines().firstOrNull()?.trim()
            } else null
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Result of a process execution.
 */
data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * Exception thrown when a process fails.
 */
class ProcessException(
    val command: String,
    val exitCode: Int,
    override val message: String
) : Exception("$command failed: $message (exit code: $exitCode)")
