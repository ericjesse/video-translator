package com.ericjesse.videotranslator.infrastructure.translation

import com.ericjesse.videotranslator.infrastructure.config.OperatingSystem
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.io.File
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val logger = KotlinLogging.logger {}

/**
 * Manages the local LibreTranslate server lifecycle.
 *
 * Features:
 * - Start/stop the server on demand
 * - Health check monitoring
 * - Auto-restart on crash
 * - Graceful shutdown
 *
 * @property platformPaths Platform-specific paths for finding the virtual environment.
 * @property httpClient HTTP client for health checks.
 */
class LibreTranslateService(
    private val platformPaths: PlatformPaths,
    private val httpClient: HttpClient
) {
    companion object {
        const val DEFAULT_PORT = 5000
        const val DEFAULT_HOST = "127.0.0.1"
        const val HEALTH_CHECK_INTERVAL_MS = 5000L
        const val STARTUP_TIMEOUT_MS = 60000L
        const val SHUTDOWN_TIMEOUT_MS = 10000L
    }

    private var serverProcess: Process? = null
    private var healthCheckJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _status = MutableStateFlow(ServerStatus.STOPPED)
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private val _port = MutableStateFlow(DEFAULT_PORT)
    val port: StateFlow<Int> = _port.asStateFlow()

    /**
     * The URL of the local LibreTranslate server.
     */
    val serverUrl: String
        get() = "http://$DEFAULT_HOST:${_port.value}"

    /**
     * Starts the local LibreTranslate server.
     *
     * @param loadOnly If true, only load models without starting the server.
     * @return True if the server started successfully.
     */
    suspend fun start(loadOnly: Boolean = false): Boolean {
        if (_status.value == ServerStatus.RUNNING) {
            logger.info { "LibreTranslate server is already running" }
            return true
        }

        _status.value = ServerStatus.STARTING

        // Find an available port
        val port = findAvailablePort()
        _port.value = port
        logger.info { "Using port $port for LibreTranslate server" }

        val venvDir = File(platformPaths.libreTranslateDir, "venv")
        val libreTranslatePath = getVenvLibreTranslatePath(venvDir)

        if (!File(libreTranslatePath).exists()) {
            logger.error { "LibreTranslate executable not found at: $libreTranslatePath" }
            _status.value = ServerStatus.ERROR
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                // Get Python path for running the wrapper script
                val pythonPath = when (platformPaths.operatingSystem) {
                    OperatingSystem.WINDOWS -> "${venvDir.absolutePath}\\Scripts\\python.exe"
                    else -> "${venvDir.absolutePath}/bin/python"
                }

                // Create a wrapper script that patches SSL before running LibreTranslate
                val wrapperScript = createSslPatchedStartupScript(venvDir, port, loadOnly)

                // Write script to a temp file instead of using -c (which has issues on Windows with quotes)
                val scriptFile = File(platformPaths.libreTranslateDir, "start_server.py")
                scriptFile.writeText(wrapperScript)

                val command = listOf(pythonPath, scriptFile.absolutePath)

                logger.info { "Starting LibreTranslate with SSL fix on port $port" }

                val processBuilder = ProcessBuilder(command)
                    .directory(File(platformPaths.libreTranslateDir))
                    .redirectErrorStream(true)

                // Set environment for the virtual environment
                val env = processBuilder.environment()
                env["VIRTUAL_ENV"] = venvDir.absolutePath
                env["PATH"] = "${venvDir.absolutePath}/bin:${env["PATH"]}"

                serverProcess = processBuilder.start()

                // Start output reader
                scope.launch {
                    try {
                        serverProcess?.inputStream?.bufferedReader()?.use { reader ->
                            reader.lineSequence().forEach { line ->
                                logger.debug { "[LibreTranslate] $line" }

                                // Detect when server is ready
                                if (line.contains("Running on") || line.contains("Uvicorn running")) {
                                    _status.value = ServerStatus.RUNNING
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.debug { "Output reader stopped: ${e.message}" }
                    }
                }

                // Wait for server to start with health checks
                val started = waitForServerStart(port)

                if (started) {
                    _status.value = ServerStatus.RUNNING
                    startHealthCheck()
                    logger.info { "LibreTranslate server started on port $port" }

                    // Log available languages for debugging
                    val languages = getLanguages()
                    if (languages != null) {
                        val languageCodes = languages.map { it.code }.sorted()
                        logger.info { "Available LibreTranslate languages: ${languageCodes.joinToString(", ")}" }
                    } else {
                        logger.warn { "Could not fetch available languages from LibreTranslate" }
                    }

                    true
                } else {
                    logger.error { "LibreTranslate server failed to start within timeout" }
                    stop()
                    _status.value = ServerStatus.ERROR
                    false
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to start LibreTranslate server" }
                _status.value = ServerStatus.ERROR
                false
            }
        }
    }

    /**
     * Stops the local LibreTranslate server.
     */
    suspend fun stop() {
        if (_status.value == ServerStatus.STOPPED) {
            return
        }

        _status.value = ServerStatus.STOPPING
        healthCheckJob?.cancel()

        withContext(Dispatchers.IO) {
            try {
                serverProcess?.let { process ->
                    // Try graceful shutdown first
                    process.destroy()

                    // Wait for graceful shutdown
                    val terminated = withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                        while (process.isAlive) {
                            delay(100)
                        }
                        true
                    }

                    if (terminated != true) {
                        logger.warn { "Force killing LibreTranslate server" }
                        process.destroyForcibly()
                    }
                }

                serverProcess = null
                _status.value = ServerStatus.STOPPED
                logger.info { "LibreTranslate server stopped" }
            } catch (e: Exception) {
                logger.error(e) { "Error stopping LibreTranslate server" }
                _status.value = ServerStatus.ERROR
            }
        }
    }

    /**
     * Restarts the server.
     */
    suspend fun restart(): Boolean {
        stop()
        delay(1000) // Brief pause
        return start()
    }

    /**
     * Finds an available TCP port.
     */
    private fun findAvailablePort(): Int {
        return ServerSocket(0).use { socket ->
            socket.localPort
        }
    }

    /**
     * Checks if the server is healthy.
     */
    suspend fun isHealthy(): Boolean {
        return try {
            val response = httpClient.get("$serverUrl/languages")
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Gets the list of available languages from the server.
     */
    suspend fun getLanguages(): List<LanguageInfo>? {
        return try {
            val response = httpClient.get("$serverUrl/languages")
            if (response.status == HttpStatusCode.OK) {
                // Parse response - LibreTranslate returns array of {code, name}
                kotlinx.serialization.json.Json {
                    ignoreUnknownKeys = true
                }.decodeFromString<List<LanguageInfo>>(response.bodyAsText())
            } else null
        } catch (e: Exception) {
            logger.debug { "Failed to get languages: ${e.message}" }
            null
        }
    }

    /**
     * Waits for the server to start responding.
     */
    private suspend fun waitForServerStart(port: Int): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < STARTUP_TIMEOUT_MS) {
            if (serverProcess?.isAlive != true) {
                logger.error { "LibreTranslate process died during startup" }
                return false
            }

            try {
                val response = httpClient.get("http://$DEFAULT_HOST:$port/languages")
                if (response.status == HttpStatusCode.OK) {
                    return true
                }
            } catch (e: Exception) {
                // Server not ready yet
            }

            delay(1000)
        }

        return false
    }

    /**
     * Starts periodic health checks.
     */
    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)

                if (_status.value == ServerStatus.RUNNING) {
                    val healthy = isHealthy()
                    if (!healthy) {
                        logger.warn { "LibreTranslate health check failed" }

                        // Check if process is still alive
                        if (serverProcess?.isAlive != true) {
                            logger.error { "LibreTranslate process died unexpectedly" }
                            _status.value = ServerStatus.ERROR
                        }
                    }
                }
            }
        }
    }

    /**
     * Gets the libretranslate executable path within the virtual environment.
     */
    private fun getVenvLibreTranslatePath(venvDir: File): String {
        return when (platformPaths.operatingSystem) {
            OperatingSystem.WINDOWS -> "${venvDir.absolutePath}\\Scripts\\libretranslate.exe"
            else -> "${venvDir.absolutePath}/bin/libretranslate"
        }
    }

    /**
     * Creates a Python script that disables SSL verification before running LibreTranslate.
     * This is needed because LibreTranslate downloads models on first run and macOS
     * has SSL certificate issues with Python's default configuration.
     *
     * On Windows, this script also sets up DLL search paths for PyTorch dependencies
     * before any imports that might trigger torch loading.
     */
    private fun createSslPatchedStartupScript(venvDir: File, port: Int, loadOnly: Boolean): String {
        val loadOnlyArg = if (loadOnly) ", '--load-only'" else ""
        return """
import os
import sys

# On Windows, we need to add DLL directories BEFORE importing torch or anything that imports it
# This fixes WinError 1114 "DLL initialization routine failed" for c10.dll
if sys.platform == 'win32':
    # Find site-packages directory
    site_packages = None
    for path in sys.path:
        if 'site-packages' in path and os.path.isdir(path):
            site_packages = path
            break

    if site_packages:
        # Add Intel OpenMP DLL directory (if installed via pip)
        intel_openmp_dirs = [
            os.path.join(site_packages, 'intel_openmp', 'bin'),
            os.path.join(site_packages, 'intel_openmp', 'Library', 'bin'),
        ]
        for dll_dir in intel_openmp_dirs:
            if os.path.isdir(dll_dir):
                try:
                    os.add_dll_directory(dll_dir)
                except Exception:
                    pass

        # Add PyTorch lib directory
        torch_lib_dir = os.path.join(site_packages, 'torch', 'lib')
        if os.path.isdir(torch_lib_dir):
            try:
                os.add_dll_directory(torch_lib_dir)
            except Exception:
                pass

        # Add ctranslate2 lib directory if it exists
        ct2_lib_dir = os.path.join(site_packages, 'ctranslate2')
        if os.path.isdir(ct2_lib_dir):
            try:
                os.add_dll_directory(ct2_lib_dir)
            except Exception:
                pass

        # Also add to PATH as a fallback for older Python or DLL loading mechanisms
        dll_paths = []
        for dll_dir in intel_openmp_dirs + [torch_lib_dir, ct2_lib_dir]:
            if os.path.isdir(dll_dir):
                dll_paths.append(dll_dir)
        if dll_paths:
            os.environ['PATH'] = ';'.join(dll_paths) + ';' + os.environ.get('PATH', '')

import ssl
import urllib.request

# Disable SSL verification globally (needed for downloading language models on macOS)
ssl._create_default_https_context = ssl._create_unverified_context

# Set environment variables to disable SSL verification for various libraries
os.environ['PYTHONHTTPSVERIFY'] = '0'
os.environ['CURL_CA_BUNDLE'] = ''
os.environ['REQUESTS_CA_BUNDLE'] = ''

# Patch urllib to use unverified context
try:
    urllib.request.urlopen.__globals__['_opener'] = urllib.request.build_opener(
        urllib.request.HTTPSHandler(context=ssl._create_unverified_context())
    )
except Exception:
    pass

# Patch requests library if available (used by argos-translate for model downloads)
try:
    import requests
    from requests.adapters import HTTPAdapter
    from urllib3.util.ssl_ import create_urllib3_context

    class SSLAdapter(HTTPAdapter):
        def init_poolmanager(self, *args, **kwargs):
            ctx = create_urllib3_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            kwargs['ssl_context'] = ctx
            return super().init_poolmanager(*args, **kwargs)

    # Patch default session
    original_session = requests.Session
    def patched_session(*args, **kwargs):
        session = original_session(*args, **kwargs)
        session.verify = False
        session.mount('https://', SSLAdapter())
        return session
    requests.Session = patched_session

    # Also disable warnings
    import urllib3
    urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
except ImportError:
    pass

# Download required language models if not already installed
try:
    import argostranslate.package
    import argostranslate.translate

    print("Checking and downloading required language models...")

    # Update package index
    argostranslate.package.update_package_index()
    available_packages = argostranslate.package.get_available_packages()
    installed_packages = argostranslate.package.get_installed_packages()
    installed_codes = {(p.from_code, p.to_code) for p in installed_packages}

    # Required language pairs for Video Translator
    required_pairs = [
        ("en", "fr"), ("fr", "en"),  # English <-> French
        ("en", "de"), ("de", "en"),  # English <-> German
    ]

    for from_code, to_code in required_pairs:
        if (from_code, to_code) not in installed_codes:
            # Find and install the package
            for pkg in available_packages:
                if pkg.from_code == from_code and pkg.to_code == to_code:
                    print(f"Downloading {from_code} -> {to_code} language model...")
                    download_path = pkg.download()
                    argostranslate.package.install_from_path(download_path)
                    print(f"Installed {from_code} -> {to_code}")
                    break
        else:
            print(f"Language model {from_code} -> {to_code} already installed")

    print("Language models ready")
except Exception as e:
    print(f"Warning: Could not download language models: {e}")
    print("Translation may fail for some language pairs")

# Now run LibreTranslate
import sys
sys.argv = ['libretranslate', '--host', '$DEFAULT_HOST', '--port', '$port'$loadOnlyArg]

from libretranslate.main import main
main()
""".trimIndent()
    }

    /**
     * Finds the certifi CA bundle by running Python in the virtual environment.
     * This is needed to fix SSL certificate verification on macOS.
     */
    private fun findCertifiCaBundle(venvDir: File): String? {
        val pythonPath = when (platformPaths.operatingSystem) {
            OperatingSystem.WINDOWS -> "${venvDir.absolutePath}\\Scripts\\python.exe"
            else -> "${venvDir.absolutePath}/bin/python"
        }

        if (!File(pythonPath).exists()) {
            logger.debug { "Python not found at: $pythonPath" }
            return null
        }

        return try {
            val process = ProcessBuilder(
                pythonPath, "-c", "import certifi; print(certifi.where())"
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0 && output.isNotEmpty() && File(output).exists()) {
                logger.info { "Found certifi CA bundle at: $output" }
                output
            } else {
                logger.debug { "certifi not available or CA bundle not found: $output" }
                null
            }
        } catch (e: Exception) {
            logger.debug { "Failed to get certifi path: ${e.message}" }
            null
        }
    }

    /**
     * Cleans up resources.
     */
    fun dispose() {
        scope.cancel()
        runBlocking { stop() }
    }
}

/**
 * Server status.
 */
enum class ServerStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

/**
 * Language information from LibreTranslate.
 */
@kotlinx.serialization.Serializable
data class LanguageInfo(
    val code: String,
    val name: String
)
