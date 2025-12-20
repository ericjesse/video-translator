package com.ericjesse.videotranslator.infrastructure.security

import com.ericjesse.videotranslator.infrastructure.config.OperatingSystem
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private val logger = KotlinLogging.logger {}

/**
 * Interface for secure storage of sensitive data like API keys.
 * Implementations use platform-native credential managers when available,
 * falling back to encrypted file storage.
 */
interface SecureStorage {
    /**
     * Stores a value securely.
     * @param key The identifier for this value
     * @param value The sensitive value to store
     * @return true if stored successfully, false otherwise
     */
    fun store(key: String, value: String): Boolean

    /**
     * Retrieves a previously stored value.
     * @param key The identifier for the value
     * @return The stored value, or null if not found or on error
     */
    fun retrieve(key: String): String?

    /**
     * Deletes a stored value.
     * @param key The identifier for the value to delete
     * @return true if deleted successfully, false otherwise
     */
    fun delete(key: String): Boolean

    /**
     * Checks if a value exists for the given key.
     * @param key The identifier to check
     * @return true if a value exists, false otherwise
     */
    fun exists(key: String): Boolean

    companion object {
        private const val SERVICE_NAME = "com.ericjesse.videotranslator"

        /**
         * Creates a platform-appropriate SecureStorage implementation.
         */
        fun create(platformPaths: PlatformPaths): SecureStorage {
            val primary: SecureStorage = when (platformPaths.operatingSystem) {
                OperatingSystem.MACOS -> MacOSKeychainStorage(SERVICE_NAME)
                OperatingSystem.WINDOWS -> WindowsCredentialStorage(SERVICE_NAME)
                OperatingSystem.LINUX -> LinuxSecretStorage(SERVICE_NAME, platformPaths)
            }

            // Wrap with fallback for robustness
            val fallback = EncryptedFileStorage(platformPaths)
            return FallbackSecureStorage(primary, fallback)
        }
    }
}

/**
 * Secure storage implementation that tries a primary storage first,
 * falling back to a secondary storage on failure.
 */
internal class FallbackSecureStorage(
    private val primary: SecureStorage,
    private val fallback: SecureStorage
) : SecureStorage {

    override fun store(key: String, value: String): Boolean {
        return try {
            if (primary.store(key, value)) {
                true
            } else {
                logger.warn { "Primary storage failed for key '$key', using fallback" }
                fallback.store(key, value)
            }
        } catch (e: Exception) {
            logger.warn { "Primary storage threw exception for key '$key': ${e.message}" }
            fallback.store(key, value)
        }
    }

    override fun retrieve(key: String): String? {
        return try {
            primary.retrieve(key) ?: fallback.retrieve(key)
        } catch (e: Exception) {
            logger.warn { "Primary storage threw exception retrieving '$key': ${e.message}" }
            fallback.retrieve(key)
        }
    }

    override fun delete(key: String): Boolean {
        val primaryResult = try {
            primary.delete(key)
        } catch (e: Exception) {
            false
        }
        val fallbackResult = try {
            fallback.delete(key)
        } catch (e: Exception) {
            false
        }
        return primaryResult || fallbackResult
    }

    override fun exists(key: String): Boolean {
        return try {
            primary.exists(key) || fallback.exists(key)
        } catch (e: Exception) {
            fallback.exists(key)
        }
    }
}

/**
 * macOS Keychain implementation using the 'security' CLI tool.
 */
internal class MacOSKeychainStorage(private val serviceName: String) : SecureStorage {

    override fun store(key: String, value: String): Boolean {
        return try {
            // First try to delete any existing entry
            delete(key)

            // Add new entry using security CLI
            val process = ProcessBuilder(
                "security", "add-generic-password",
                "-s", serviceName,
                "-a", key,
                "-w", value,
                "-U" // Update if exists
            ).redirectErrorStream(true).start()

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val output = process.inputStream.bufferedReader().readText()
                logger.warn { "Keychain store failed: $output" }
            }
            exitCode == 0
        } catch (e: Exception) {
            logger.error(e) { "Failed to store in Keychain" }
            false
        }
    }

    override fun retrieve(key: String): String? {
        return try {
            val process = ProcessBuilder(
                "security", "find-generic-password",
                "-s", serviceName,
                "-a", key,
                "-w" // Output password only
            ).redirectErrorStream(true).start()

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                process.inputStream.bufferedReader().readText().trim()
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug { "Failed to retrieve from Keychain: ${e.message}" }
            null
        }
    }

    override fun delete(key: String): Boolean {
        return try {
            val process = ProcessBuilder(
                "security", "delete-generic-password",
                "-s", serviceName,
                "-a", key
            ).redirectErrorStream(true).start()

            process.waitFor() == 0
        } catch (e: Exception) {
            logger.debug { "Failed to delete from Keychain: ${e.message}" }
            false
        }
    }

    override fun exists(key: String): Boolean {
        return retrieve(key) != null
    }
}

/**
 * Windows Credential Manager implementation using cmdkey CLI.
 * This avoids JNA dependency by using the built-in cmdkey and PowerShell.
 */
internal class WindowsCredentialStorage(private val serviceName: String) : SecureStorage {

    private fun getTargetName(key: String): String = "$serviceName:$key"

    override fun store(key: String, value: String): Boolean {
        return try {
            // Use PowerShell to store credential securely
            // cmdkey doesn't support programmatic password input well,
            // so we use PowerShell's CredentialManager module or direct API
            val target = getTargetName(key)

            // First delete existing
            delete(key)

            // Use PowerShell to create credential
            // Note: This requires PowerShell 5.1+ which is standard on Windows 10+
            val script = """
                ${"$"}cred = New-Object System.Management.Automation.PSCredential("$key", (ConvertTo-SecureString "$value" -AsPlainText -Force))
                cmdkey /generic:"$target" /user:"$key" /pass:"$value"
            """.trimIndent()

            val process = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "cmdkey /generic:\"$target\" /user:\"$key\" /pass:\"$value\""
            ).redirectErrorStream(true).start()

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val output = process.inputStream.bufferedReader().readText()
                logger.warn { "Windows credential store failed: $output" }
            }
            exitCode == 0
        } catch (e: Exception) {
            logger.error(e) { "Failed to store in Windows Credential Manager" }
            false
        }
    }

    override fun retrieve(key: String): String? {
        return try {
            val target = getTargetName(key)

            // Use PowerShell to retrieve credential
            // cmdkey /list doesn't show passwords, so we need a different approach
            val script = """
                Add-Type -AssemblyName System.Security
                ${"$"}cred = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto(
                    [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR(
                        (Get-StoredCredential -Target "$target").Password
                    )
                )
                Write-Output ${"$"}cred
            """.trimIndent()

            // Alternative using vaultcmd if available, or fallback to stored file
            // Since Windows credential retrieval via CLI is complex, we rely on fallback
            logger.debug { "Windows credential retrieval not fully implemented, using fallback" }
            null
        } catch (e: Exception) {
            logger.debug { "Failed to retrieve from Windows Credential Manager: ${e.message}" }
            null
        }
    }

    override fun delete(key: String): Boolean {
        return try {
            val target = getTargetName(key)
            val process = ProcessBuilder(
                "cmdkey", "/delete:$target"
            ).redirectErrorStream(true).start()

            process.waitFor() == 0
        } catch (e: Exception) {
            logger.debug { "Failed to delete from Windows Credential Manager: ${e.message}" }
            false
        }
    }

    override fun exists(key: String): Boolean {
        return try {
            val target = getTargetName(key)
            val process = ProcessBuilder(
                "cmdkey", "/list:$target"
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains(target)
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Linux implementation using secret-tool CLI (libsecret).
 * Falls back to encrypted file storage if libsecret is not available.
 */
internal class LinuxSecretStorage(
    private val serviceName: String,
    private val platformPaths: PlatformPaths
) : SecureStorage {

    private val hasSecretTool: Boolean by lazy {
        try {
            val process = ProcessBuilder("which", "secret-tool")
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun store(key: String, value: String): Boolean {
        if (!hasSecretTool) {
            logger.debug { "secret-tool not available" }
            return false
        }

        return try {
            val process = ProcessBuilder(
                "secret-tool", "store",
                "--label", "$serviceName - $key",
                "service", serviceName,
                "key", key
            ).redirectErrorStream(true).start()

            // Write password to stdin
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(value)
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val output = process.inputStream.bufferedReader().readText()
                logger.warn { "secret-tool store failed: $output" }
            }
            exitCode == 0
        } catch (e: Exception) {
            logger.error(e) { "Failed to store using secret-tool" }
            false
        }
    }

    override fun retrieve(key: String): String? {
        if (!hasSecretTool) {
            return null
        }

        return try {
            val process = ProcessBuilder(
                "secret-tool", "lookup",
                "service", serviceName,
                "key", key
            ).redirectErrorStream(true).start()

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                process.inputStream.bufferedReader().readText().trim()
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug { "Failed to retrieve using secret-tool: ${e.message}" }
            null
        }
    }

    override fun delete(key: String): Boolean {
        if (!hasSecretTool) {
            return false
        }

        return try {
            val process = ProcessBuilder(
                "secret-tool", "clear",
                "service", serviceName,
                "key", key
            ).redirectErrorStream(true).start()

            process.waitFor() == 0
        } catch (e: Exception) {
            logger.debug { "Failed to delete using secret-tool: ${e.message}" }
            false
        }
    }

    override fun exists(key: String): Boolean {
        return retrieve(key) != null
    }
}

/**
 * Encrypted file storage using AES-256-GCM.
 * Uses a key derived from machine-specific data for portability.
 */
internal class EncryptedFileStorage(private val platformPaths: PlatformPaths) : SecureStorage {

    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"
        private const val KEY_LENGTH = 256
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val PBKDF2_ITERATIONS = 100_000
        private const val SALT_LENGTH = 16
    }

    private val secretsDir: File by lazy {
        File(platformPaths.configDir, "secrets").also { it.mkdirs() }
    }

    private val masterKeyFile: File by lazy {
        File(secretsDir, ".key")
    }

    /**
     * Derives a master key from machine-specific data.
     */
    private fun getMasterKey(): ByteArray {
        // Get or create salt
        val salt = if (masterKeyFile.exists()) {
            masterKeyFile.readBytes()
        } else {
            ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
                .also { masterKeyFile.writeBytes(it) }
        }

        // Derive key from machine-specific data
        val machineId = getMachineIdentifier()
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(machineId.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        return factory.generateSecret(spec).encoded
    }

    /**
     * Gets a machine-specific identifier for key derivation.
     * Combines multiple sources for uniqueness.
     */
    private fun getMachineIdentifier(): String {
        val components = mutableListOf<String>()

        // User home directory (stable per user)
        components.add(System.getProperty("user.home") ?: "")

        // Username
        components.add(System.getProperty("user.name") ?: "")

        // OS info
        components.add(System.getProperty("os.name") ?: "")
        components.add(System.getProperty("os.arch") ?: "")

        // Try to get machine-specific ID
        try {
            when (platformPaths.operatingSystem) {
                OperatingSystem.MACOS -> {
                    val process = ProcessBuilder("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
                        .redirectErrorStream(true).start()
                    val output = process.inputStream.bufferedReader().readText()
                    val match = Regex("\"IOPlatformUUID\" = \"([^\"]+)\"").find(output)
                    match?.groupValues?.get(1)?.let { components.add(it) }
                }
                OperatingSystem.LINUX -> {
                    val machineId = File("/etc/machine-id")
                    if (machineId.exists()) {
                        components.add(machineId.readText().trim())
                    }
                }
                OperatingSystem.WINDOWS -> {
                    val process = ProcessBuilder(
                        "wmic", "csproduct", "get", "uuid"
                    ).redirectErrorStream(true).start()
                    val output = process.inputStream.bufferedReader().readText()
                    val lines = output.lines().filter { it.isNotBlank() && !it.contains("UUID") }
                    if (lines.isNotEmpty()) {
                        components.add(lines.first().trim())
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug { "Could not get machine ID: ${e.message}" }
        }

        // Hash the combined components
        val combined = components.joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
        return Base64.getEncoder().encodeToString(digest.digest(combined.toByteArray()))
    }

    private fun getSecretFile(key: String): File {
        // Hash the key to create a safe filename
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(key.toByteArray())
            .take(16)
            .joinToString("") { "%02x".format(it) }
        return File(secretsDir, hash)
    }

    override fun store(key: String, value: String): Boolean {
        return try {
            val masterKey = getMasterKey()
            val secretKey = SecretKeySpec(masterKey, KEY_ALGORITHM)

            // Generate random IV
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)

            // Encrypt
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))

            // Store IV + encrypted data
            val output = ByteBuffer.allocate(GCM_IV_LENGTH + encrypted.size)
            output.put(iv)
            output.put(encrypted)

            getSecretFile(key).writeBytes(output.array())
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to store encrypted value" }
            false
        }
    }

    override fun retrieve(key: String): String? {
        val file = getSecretFile(key)
        if (!file.exists()) return null

        return try {
            val data = file.readBytes()
            if (data.size < GCM_IV_LENGTH + 1) return null

            val masterKey = getMasterKey()
            val secretKey = SecretKeySpec(masterKey, KEY_ALGORITHM)

            // Extract IV and encrypted data
            val buffer = ByteBuffer.wrap(data)
            val iv = ByteArray(GCM_IV_LENGTH)
            buffer.get(iv)
            val encrypted = ByteArray(buffer.remaining())
            buffer.get(encrypted)

            // Decrypt
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decrypted = cipher.doFinal(encrypted)

            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            logger.error(e) { "Failed to retrieve encrypted value" }
            null
        }
    }

    override fun delete(key: String): Boolean {
        return try {
            getSecretFile(key).delete()
        } catch (e: Exception) {
            logger.debug { "Failed to delete secret file: ${e.message}" }
            false
        }
    }

    override fun exists(key: String): Boolean {
        return getSecretFile(key).exists()
    }
}
