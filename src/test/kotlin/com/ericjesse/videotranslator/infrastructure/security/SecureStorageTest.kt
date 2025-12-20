package com.ericjesse.videotranslator.infrastructure.security

import com.ericjesse.videotranslator.infrastructure.config.OperatingSystem
import com.ericjesse.videotranslator.infrastructure.config.PlatformPaths
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class SecureStorageTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var platformPaths: PlatformPaths

    @BeforeEach
    fun setup() {
        platformPaths = mockk<PlatformPaths>()
        every { platformPaths.configDir } returns tempDir.resolve("config").toString().also {
            File(it).mkdirs()
        }
        every { platformPaths.operatingSystem } returns OperatingSystem.MACOS
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // ==================== EncryptedFileStorage Tests ====================

    @Test
    fun `EncryptedFileStorage store and retrieve works correctly`() {
        val storage = EncryptedFileStorage(platformPaths)

        val result = storage.store("test-key", "secret-value")

        assertTrue(result, "Store should succeed")
        assertEquals("secret-value", storage.retrieve("test-key"))
    }

    @Test
    fun `EncryptedFileStorage returns null for non-existent key`() {
        val storage = EncryptedFileStorage(platformPaths)

        assertNull(storage.retrieve("non-existent-key"))
    }

    @Test
    fun `EncryptedFileStorage exists returns true for stored key`() {
        val storage = EncryptedFileStorage(platformPaths)
        storage.store("existing-key", "value")

        assertTrue(storage.exists("existing-key"))
        assertFalse(storage.exists("non-existing-key"))
    }

    @Test
    fun `EncryptedFileStorage delete removes stored value`() {
        val storage = EncryptedFileStorage(platformPaths)
        storage.store("to-delete", "value")
        assertTrue(storage.exists("to-delete"))

        val deleted = storage.delete("to-delete")

        assertTrue(deleted)
        assertFalse(storage.exists("to-delete"))
        assertNull(storage.retrieve("to-delete"))
    }

    @Test
    fun `EncryptedFileStorage delete returns false for non-existent key`() {
        val storage = EncryptedFileStorage(platformPaths)

        assertFalse(storage.delete("non-existent"))
    }

    @Test
    fun `EncryptedFileStorage handles empty value`() {
        val storage = EncryptedFileStorage(platformPaths)

        assertTrue(storage.store("empty-key", ""))
        assertEquals("", storage.retrieve("empty-key"))
    }

    @Test
    fun `EncryptedFileStorage handles unicode values`() {
        val storage = EncryptedFileStorage(platformPaths)
        val unicodeValue = "日本語テスト 🔐 émojis"

        assertTrue(storage.store("unicode-key", unicodeValue))
        assertEquals(unicodeValue, storage.retrieve("unicode-key"))
    }

    @Test
    fun `EncryptedFileStorage handles special characters in value`() {
        val storage = EncryptedFileStorage(platformPaths)
        val specialValue = "!@#\$%^&*()_+-={}[]|\\:\";<>?,./~`\n\t\r"

        assertTrue(storage.store("special-key", specialValue))
        assertEquals(specialValue, storage.retrieve("special-key"))
    }

    @Test
    fun `EncryptedFileStorage handles long values`() {
        val storage = EncryptedFileStorage(platformPaths)
        val longValue = "a".repeat(10000)

        assertTrue(storage.store("long-key", longValue))
        assertEquals(longValue, storage.retrieve("long-key"))
    }

    @Test
    fun `EncryptedFileStorage overwrites existing value`() {
        val storage = EncryptedFileStorage(platformPaths)

        storage.store("key", "first-value")
        assertEquals("first-value", storage.retrieve("key"))

        storage.store("key", "second-value")
        assertEquals("second-value", storage.retrieve("key"))
    }

    @Test
    fun `EncryptedFileStorage creates secrets directory`() {
        val storage = EncryptedFileStorage(platformPaths)
        storage.store("test", "value")

        val secretsDir = File(platformPaths.configDir, "secrets")
        assertTrue(secretsDir.exists())
        assertTrue(secretsDir.isDirectory)
    }

    @Test
    fun `EncryptedFileStorage creates salt file`() {
        val storage = EncryptedFileStorage(platformPaths)
        storage.store("test", "value")

        val keyFile = File(platformPaths.configDir, "secrets/.key")
        assertTrue(keyFile.exists())
        assertEquals(16, keyFile.readBytes().size) // SALT_LENGTH = 16
    }

    @Test
    fun `EncryptedFileStorage uses consistent salt across operations`() {
        val storage = EncryptedFileStorage(platformPaths)
        storage.store("key1", "value1")

        val keyFile = File(platformPaths.configDir, "secrets/.key")
        val saltBefore = keyFile.readBytes().toList()

        storage.store("key2", "value2")
        val saltAfter = keyFile.readBytes().toList()

        assertEquals(saltBefore, saltAfter, "Salt should remain constant")
    }

    @Test
    fun `EncryptedFileStorage handles corrupted file gracefully`() {
        val storage = EncryptedFileStorage(platformPaths)
        storage.store("corrupt-key", "value")

        // Corrupt the file
        val secretsDir = File(platformPaths.configDir, "secrets")
        val files = secretsDir.listFiles()?.filter { it.name != ".key" } ?: emptyList()
        assertTrue(files.isNotEmpty())
        files.first().writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        // Should return null instead of throwing
        assertNull(storage.retrieve("corrupt-key"))
    }

    @Test
    fun `EncryptedFileStorage handles file too small gracefully`() {
        val storage = EncryptedFileStorage(platformPaths)
        storage.store("small-file-key", "value")

        // Write a file that's too small (less than IV length)
        val secretsDir = File(platformPaths.configDir, "secrets")
        val files = secretsDir.listFiles()?.filter { it.name != ".key" } ?: emptyList()
        files.first().writeBytes(byteArrayOf(1, 2, 3))

        assertNull(storage.retrieve("small-file-key"))
    }

    @Test
    fun `EncryptedFileStorage multiple keys stored independently`() {
        val storage = EncryptedFileStorage(platformPaths)

        storage.store("key1", "value1")
        storage.store("key2", "value2")
        storage.store("key3", "value3")

        assertEquals("value1", storage.retrieve("key1"))
        assertEquals("value2", storage.retrieve("key2"))
        assertEquals("value3", storage.retrieve("key3"))

        storage.delete("key2")

        assertEquals("value1", storage.retrieve("key1"))
        assertNull(storage.retrieve("key2"))
        assertEquals("value3", storage.retrieve("key3"))
    }

    // ==================== FallbackSecureStorage Tests ====================

    @Test
    fun `FallbackSecureStorage uses primary when successful`() {
        val primary = mockk<SecureStorage>()
        val fallback = mockk<SecureStorage>()

        every { primary.store("key", "value") } returns true
        every { primary.retrieve("key") } returns "value"

        val storage = FallbackSecureStorage(primary, fallback)

        assertTrue(storage.store("key", "value"))
        assertEquals("value", storage.retrieve("key"))

        verify { primary.store("key", "value") }
        verify { primary.retrieve("key") }
        verify(exactly = 0) { fallback.store(any(), any()) }
        verify(exactly = 0) { fallback.retrieve(any()) }
    }

    @Test
    fun `FallbackSecureStorage uses fallback when primary store fails`() {
        val primary = mockk<SecureStorage>()
        val fallback = mockk<SecureStorage>()

        every { primary.store("key", "value") } returns false
        every { fallback.store("key", "value") } returns true

        val storage = FallbackSecureStorage(primary, fallback)

        assertTrue(storage.store("key", "value"))

        verify { primary.store("key", "value") }
        verify { fallback.store("key", "value") }
    }

    @Test
    fun `FallbackSecureStorage uses fallback when primary store throws`() {
        val primary = mockk<SecureStorage>()
        val fallback = mockk<SecureStorage>()

        every { primary.store("key", "value") } throws RuntimeException("Primary failed")
        every { fallback.store("key", "value") } returns true

        val storage = FallbackSecureStorage(primary, fallback)

        assertTrue(storage.store("key", "value"))

        verify { primary.store("key", "value") }
        verify { fallback.store("key", "value") }
    }

    @Test
    fun `FallbackSecureStorage retrieve checks fallback when primary returns null`() {
        val primary = mockk<SecureStorage>()
        val fallback = mockk<SecureStorage>()

        every { primary.retrieve("key") } returns null
        every { fallback.retrieve("key") } returns "fallback-value"

        val storage = FallbackSecureStorage(primary, fallback)

        assertEquals("fallback-value", storage.retrieve("key"))

        verify { primary.retrieve("key") }
        verify { fallback.retrieve("key") }
    }

    @Test
    fun `FallbackSecureStorage retrieve uses fallback when primary throws`() {
        val primary = mockk<SecureStorage>()
        val fallback = mockk<SecureStorage>()

        every { primary.retrieve("key") } throws RuntimeException("Primary failed")
        every { fallback.retrieve("key") } returns "fallback-value"

        val storage = FallbackSecureStorage(primary, fallback)

        assertEquals("fallback-value", storage.retrieve("key"))
    }

    @Test
    fun `FallbackSecureStorage delete tries both storages`() {
        val primary = mockk<SecureStorage>()
        val fallback = mockk<SecureStorage>()

        every { primary.delete("key") } returns true
        every { fallback.delete("key") } returns false

        val storage = FallbackSecureStorage(primary, fallback)

        assertTrue(storage.delete("key"))

        verify { primary.delete("key") }
        verify { fallback.delete("key") }
    }

    @Test
    fun `FallbackSecureStorage delete returns true if either succeeds`() {
        val primary = mockk<SecureStorage>()
        val fallback = mockk<SecureStorage>()

        every { primary.delete("key") } returns false
        every { fallback.delete("key") } returns true

        val storage = FallbackSecureStorage(primary, fallback)

        assertTrue(storage.delete("key"))
    }

    @Test
    fun `FallbackSecureStorage delete handles exceptions`() {
        val primary = mockk<SecureStorage>()
        val fallback = mockk<SecureStorage>()

        every { primary.delete("key") } throws RuntimeException("Error")
        every { fallback.delete("key") } returns true

        val storage = FallbackSecureStorage(primary, fallback)

        assertTrue(storage.delete("key"))
    }

    @Test
    fun `FallbackSecureStorage exists checks both storages`() {
        val primary = mockk<SecureStorage>()
        val fallback = mockk<SecureStorage>()

        every { primary.exists("key") } returns false
        every { fallback.exists("key") } returns true

        val storage = FallbackSecureStorage(primary, fallback)

        assertTrue(storage.exists("key"))
    }

    @Test
    fun `FallbackSecureStorage exists returns true if primary has key`() {
        val primary = mockk<SecureStorage>()
        val fallback = mockk<SecureStorage>()

        every { primary.exists("key") } returns true

        val storage = FallbackSecureStorage(primary, fallback)

        assertTrue(storage.exists("key"))
        verify(exactly = 0) { fallback.exists(any()) }
    }

    @Test
    fun `FallbackSecureStorage exists handles primary exception`() {
        val primary = mockk<SecureStorage>()
        val fallback = mockk<SecureStorage>()

        every { primary.exists("key") } throws RuntimeException("Error")
        every { fallback.exists("key") } returns true

        val storage = FallbackSecureStorage(primary, fallback)

        assertTrue(storage.exists("key"))
    }

    // ==================== SecureStorage Factory Tests ====================

    @Test
    fun `SecureStorage factory creates FallbackSecureStorage`() {
        val storage = SecureStorage.create(platformPaths)

        // Factory should wrap with FallbackSecureStorage
        assertIs<FallbackSecureStorage>(storage)
    }

    @Test
    fun `SecureStorage factory works for macOS`() {
        every { platformPaths.operatingSystem } returns OperatingSystem.MACOS

        val storage = SecureStorage.create(platformPaths)
        assertNotNull(storage)
    }

    @Test
    fun `SecureStorage factory works for Windows`() {
        every { platformPaths.operatingSystem } returns OperatingSystem.WINDOWS

        val storage = SecureStorage.create(platformPaths)
        assertNotNull(storage)
    }

    @Test
    fun `SecureStorage factory works for Linux`() {
        every { platformPaths.operatingSystem } returns OperatingSystem.LINUX

        val storage = SecureStorage.create(platformPaths)
        assertNotNull(storage)
    }

    // ==================== Integration Tests ====================

    @Test
    fun `Full round-trip with factory-created storage`() {
        val storage = SecureStorage.create(platformPaths)

        // The macOS keychain may not be available in test environment,
        // but the fallback (EncryptedFileStorage) should work
        val stored = storage.store("integration-test-key", "integration-test-value")
        assertTrue(stored, "Store should succeed (possibly via fallback)")

        val retrieved = storage.retrieve("integration-test-key")
        assertEquals("integration-test-value", retrieved)

        assertTrue(storage.exists("integration-test-key"))

        assertTrue(storage.delete("integration-test-key"))
        assertFalse(storage.exists("integration-test-key"))
    }

    @Test
    fun `Factory-created storage handles API key format values`() {
        val storage = SecureStorage.create(platformPaths)

        val apiKey = "sk-proj-abc123XYZ789_very-long-api-key-format"
        storage.store("openai_api_key", apiKey)

        assertEquals(apiKey, storage.retrieve("openai_api_key"))
    }

    // ==================== Mock SecureStorage for Unit Tests ====================

    @Test
    fun `InMemorySecureStorage can be used for testing`() {
        val storage = InMemorySecureStorage()

        storage.store("key", "value")
        assertEquals("value", storage.retrieve("key"))
        assertTrue(storage.exists("key"))

        storage.delete("key")
        assertNull(storage.retrieve("key"))
        assertFalse(storage.exists("key"))
    }
}

/**
 * Simple in-memory implementation for testing purposes.
 */
class InMemorySecureStorage : SecureStorage {
    private val storage = mutableMapOf<String, String>()

    override fun store(key: String, value: String): Boolean {
        storage[key] = value
        return true
    }

    override fun retrieve(key: String): String? = storage[key]

    override fun delete(key: String): Boolean {
        return storage.remove(key) != null
    }

    override fun exists(key: String): Boolean = storage.containsKey(key)
}
