package com.ericjesse.videotranslator.infrastructure.archive

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ArchiveExtractorTest {

    private val extractor = ArchiveExtractor()

    // ==================== Archive Type Detection Tests ====================

    @Test
    fun `detectArchiveType identifies ZIP files`() {
        assertEquals(ArchiveType.ZIP, ArchiveExtractor.detectArchiveType(Path.of("file.zip")))
        assertEquals(ArchiveType.ZIP, ArchiveExtractor.detectArchiveType(Path.of("FILE.ZIP")))
        assertEquals(ArchiveType.ZIP, ArchiveExtractor.detectArchiveType(Path.of("/path/to/archive.zip")))
    }

    @Test
    fun `detectArchiveType identifies TAR_XZ files`() {
        assertEquals(ArchiveType.TAR_XZ, ArchiveExtractor.detectArchiveType(Path.of("file.tar.xz")))
        assertEquals(ArchiveType.TAR_XZ, ArchiveExtractor.detectArchiveType(Path.of("file.txz")))
        assertEquals(ArchiveType.TAR_XZ, ArchiveExtractor.detectArchiveType(Path.of("FILE.TAR.XZ")))
    }

    @Test
    fun `detectArchiveType identifies TAR_GZ files`() {
        assertEquals(ArchiveType.TAR_GZ, ArchiveExtractor.detectArchiveType(Path.of("file.tar.gz")))
        assertEquals(ArchiveType.TAR_GZ, ArchiveExtractor.detectArchiveType(Path.of("file.tgz")))
    }

    @Test
    fun `detectArchiveType identifies 7z files`() {
        assertEquals(ArchiveType.SEVEN_ZIP, ArchiveExtractor.detectArchiveType(Path.of("file.7z")))
        assertEquals(ArchiveType.SEVEN_ZIP, ArchiveExtractor.detectArchiveType(Path.of("FILE.7Z")))
    }

    @Test
    fun `detectArchiveType returns null for unknown formats`() {
        assertNull(ArchiveExtractor.detectArchiveType(Path.of("file.txt")))
        assertNull(ArchiveExtractor.detectArchiveType(Path.of("file.rar")))
        assertNull(ArchiveExtractor.detectArchiveType(Path.of("file")))
    }

    // ==================== ZIP Extraction Tests ====================

    @Test
    fun `extract ZIP extracts all files correctly`(@TempDir tempDir: Path) = runTest {
        // Create a test ZIP file
        val zipFile = tempDir.resolve("test.zip")
        createTestZip(zipFile, mapOf(
            "file1.txt" to "content1",
            "file2.txt" to "content2",
            "subdir/file3.txt" to "content3"
        ))

        val destDir = tempDir.resolve("extracted")

        // Extract
        val progressList = extractor.extract(zipFile, destDir).toList()

        // Verify extraction
        assertTrue(Files.exists(destDir.resolve("file1.txt")))
        assertTrue(Files.exists(destDir.resolve("file2.txt")))
        assertTrue(Files.exists(destDir.resolve("subdir/file3.txt")))

        assertEquals("content1", Files.readString(destDir.resolve("file1.txt")))
        assertEquals("content2", Files.readString(destDir.resolve("file2.txt")))
        assertEquals("content3", Files.readString(destDir.resolve("subdir/file3.txt")))

        // Verify progress was reported
        assertTrue(progressList.isNotEmpty(), "Should emit progress events")
        assertEquals(3, progressList.last().filesExtracted)
    }

    @Test
    fun `extract ZIP creates destination directory if not exists`(@TempDir tempDir: Path) = runTest {
        val zipFile = tempDir.resolve("test.zip")
        createTestZip(zipFile, mapOf("file.txt" to "content"))

        val destDir = tempDir.resolve("non/existent/path")
        assertFalse(Files.exists(destDir))

        extractor.extract(zipFile, destDir).toList()

        assertTrue(Files.exists(destDir))
        assertTrue(Files.exists(destDir.resolve("file.txt")))
    }

    @Test
    fun `extract ZIP with overwriteExisting true overwrites files`(@TempDir tempDir: Path) = runTest {
        val zipFile = tempDir.resolve("test.zip")
        createTestZip(zipFile, mapOf("file.txt" to "new content"))

        val destDir = tempDir.resolve("extracted")
        Files.createDirectories(destDir)
        Files.writeString(destDir.resolve("file.txt"), "old content")

        extractor.extract(zipFile, destDir, ExtractionConfig(overwriteExisting = true)).toList()

        assertEquals("new content", Files.readString(destDir.resolve("file.txt")))
    }

    @Test
    fun `extract ZIP with overwriteExisting false skips existing files`(@TempDir tempDir: Path) = runTest {
        val zipFile = tempDir.resolve("test.zip")
        createTestZip(zipFile, mapOf("file.txt" to "new content"))

        val destDir = tempDir.resolve("extracted")
        Files.createDirectories(destDir)
        Files.writeString(destDir.resolve("file.txt"), "old content")

        extractor.extract(zipFile, destDir, ExtractionConfig(overwriteExisting = false)).toList()

        assertEquals("old content", Files.readString(destDir.resolve("file.txt")))
    }

    @Test
    fun `extract ZIP handles empty directories`(@TempDir tempDir: Path) = runTest {
        val zipFile = tempDir.resolve("test.zip")
        createTestZipWithEmptyDir(zipFile)

        val destDir = tempDir.resolve("extracted")
        extractor.extract(zipFile, destDir).toList()

        assertTrue(Files.exists(destDir.resolve("emptydir")))
        assertTrue(Files.isDirectory(destDir.resolve("emptydir")))
    }

    // ==================== TAR.GZ Extraction Tests ====================

    @Test
    fun `extract TAR_GZ extracts all files correctly`(@TempDir tempDir: Path) = runTest {
        val tarGzFile = tempDir.resolve("test.tar.gz")
        createTestTarGz(tarGzFile, mapOf(
            "file1.txt" to "content1",
            "dir/file2.txt" to "content2"
        ))

        val destDir = tempDir.resolve("extracted")
        val progressList = extractor.extract(tarGzFile, destDir).toList()

        assertTrue(Files.exists(destDir.resolve("file1.txt")))
        assertTrue(Files.exists(destDir.resolve("dir/file2.txt")))
        assertEquals("content1", Files.readString(destDir.resolve("file1.txt")))
        assertEquals("content2", Files.readString(destDir.resolve("dir/file2.txt")))

        assertTrue(progressList.isNotEmpty())
        assertEquals(2, progressList.last().filesExtracted)
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `extract TAR_GZ preserves Unix permissions`(@TempDir tempDir: Path) = runTest {
        val tarGzFile = tempDir.resolve("test.tar.gz")
        createTestTarGzWithPermissions(tarGzFile, "script.sh", "#!/bin/bash\necho hello", 0b111_101_101) // 755

        val destDir = tempDir.resolve("extracted")
        extractor.extract(tarGzFile, destDir, ExtractionConfig(preservePermissions = true)).toList()

        val scriptFile = destDir.resolve("script.sh").toFile()
        assertTrue(scriptFile.canExecute(), "File should be executable")
    }

    // ==================== TAR.XZ Extraction Tests ====================

    @Test
    fun `extract TAR_XZ extracts all files correctly`(@TempDir tempDir: Path) = runTest {
        val tarXzFile = tempDir.resolve("test.tar.xz")
        createTestTarXz(tarXzFile, mapOf(
            "readme.txt" to "Hello World",
            "bin/tool" to "binary content"
        ))

        val destDir = tempDir.resolve("extracted")
        val progressList = extractor.extract(tarXzFile, destDir).toList()

        assertTrue(Files.exists(destDir.resolve("readme.txt")))
        assertTrue(Files.exists(destDir.resolve("bin/tool")))
        assertEquals("Hello World", Files.readString(destDir.resolve("readme.txt")))

        assertTrue(progressList.isNotEmpty())
        assertEquals(2, progressList.last().filesExtracted)
    }

    // ==================== 7z Extraction Tests ====================

    @Test
    fun `extract 7z extracts all files correctly`(@TempDir tempDir: Path) = runTest {
        val sevenZFile = tempDir.resolve("test.7z")
        createTest7z(sevenZFile, mapOf(
            "doc.txt" to "document content",
            "data/file.dat" to "data content"
        ))

        val destDir = tempDir.resolve("extracted")
        val progressList = extractor.extract(sevenZFile, destDir).toList()

        assertTrue(Files.exists(destDir.resolve("doc.txt")))
        assertTrue(Files.exists(destDir.resolve("data/file.dat")))
        assertEquals("document content", Files.readString(destDir.resolve("doc.txt")))
        assertEquals("data content", Files.readString(destDir.resolve("data/file.dat")))

        // 7z provides total file count
        assertTrue(progressList.isNotEmpty())
        assertEquals(2, progressList.last().filesExtracted)
        assertEquals(2, progressList.last().totalFiles)
    }

    // ==================== extractBlocking Tests ====================

    @Test
    fun `extractBlocking returns ExtractionResult with correct stats`(@TempDir tempDir: Path) = runTest {
        val zipFile = tempDir.resolve("test.zip")
        createTestZip(zipFile, mapOf(
            "a.txt" to "aaa",
            "b.txt" to "bbbbb"
        ))

        val destDir = tempDir.resolve("extracted")
        val result = extractor.extractBlocking(zipFile, destDir)

        assertEquals(2, result.filesExtracted)
        assertTrue(result.totalBytes > 0)
        assertEquals(destDir, result.extractedPath)
    }

    @Test
    fun `extractBlocking invokes progress callback`(@TempDir tempDir: Path) = runTest {
        val zipFile = tempDir.resolve("test.zip")
        createTestZip(zipFile, mapOf("file.txt" to "content"))

        val destDir = tempDir.resolve("extracted")
        val progressUpdates = mutableListOf<ExtractionProgress>()

        extractor.extractBlocking(zipFile, destDir) { progress ->
            progressUpdates.add(progress)
        }

        assertTrue(progressUpdates.isNotEmpty())
        assertEquals("file.txt", progressUpdates.last().currentFile)
    }

    // ==================== Progress Reporting Tests ====================

    @Test
    fun `progress reports correct file names during extraction`(@TempDir tempDir: Path) = runTest {
        val zipFile = tempDir.resolve("test.zip")
        createTestZip(zipFile, mapOf(
            "alpha.txt" to "a",
            "beta.txt" to "b",
            "gamma.txt" to "g"
        ))

        val destDir = tempDir.resolve("extracted")
        val fileNames = extractor.extract(zipFile, destDir).toList().map { it.currentFile }

        assertTrue(fileNames.contains("alpha.txt"))
        assertTrue(fileNames.contains("beta.txt"))
        assertTrue(fileNames.contains("gamma.txt"))
    }

    @Test
    fun `progress filesExtracted increments correctly`(@TempDir tempDir: Path) = runTest {
        val zipFile = tempDir.resolve("test.zip")
        createTestZip(zipFile, mapOf(
            "1.txt" to "one",
            "2.txt" to "two",
            "3.txt" to "three"
        ))

        val destDir = tempDir.resolve("extracted")
        val progressList = extractor.extract(zipFile, destDir).toList()

        // Each progress event should show incrementing file count
        val fileCounts = progressList.map { it.filesExtracted }
        assertEquals(listOf(1, 2, 3), fileCounts)
    }

    @Test
    fun `ExtractionProgress percentage calculation works correctly`() {
        // With known file counts
        val progress1 = ExtractionProgress(
            bytesExtracted = 50,
            totalBytes = 100,
            currentFile = "test.txt",
            filesExtracted = 5,
            totalFiles = 10
        )
        assertEquals(0.5f, progress1.percentage, 0.001f)

        // With unknown total files but known bytes
        val progress2 = ExtractionProgress(
            bytesExtracted = 75,
            totalBytes = 100,
            currentFile = "test.txt",
            filesExtracted = 3,
            totalFiles = -1
        )
        assertEquals(0.75f, progress2.percentage, 0.001f)

        // With unknown totals
        val progress3 = ExtractionProgress(
            bytesExtracted = 100,
            totalBytes = -1,
            currentFile = "test.txt",
            filesExtracted = 5,
            totalFiles = -1
        )
        assertEquals(-1f, progress3.percentage)
    }

    // ==================== Binary Finding Tests ====================

    @Test
    fun `findBinary finds file by exact name`(@TempDir tempDir: Path) = runTest {
        // Create directory structure with binary
        val binDir = tempDir.resolve("app/bin")
        Files.createDirectories(binDir)
        val binaryFile = binDir.resolve("ffmpeg")
        Files.writeString(binaryFile, "binary")

        val found = extractor.findBinary(tempDir, "ffmpeg")

        assertNotNull(found)
        assertEquals(binaryFile, found)
    }

    @Test
    fun `findBinary finds file with lowercase search`(@TempDir tempDir: Path) = runTest {
        val binDir = tempDir.resolve("bin")
        Files.createDirectories(binDir)
        // Create file with lowercase name that matches the search
        Files.writeString(binDir.resolve("ffmpeg"), "binary")

        val found = extractor.findBinary(tempDir, "ffmpeg")

        assertNotNull(found)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `findBinary adds exe extension on Windows`(@TempDir tempDir: Path) = runTest {
        val binDir = tempDir.resolve("bin")
        Files.createDirectories(binDir)
        Files.writeString(binDir.resolve("ffmpeg.exe"), "binary")

        val found = extractor.findBinary(tempDir, "ffmpeg")

        assertNotNull(found)
        assertTrue(found.toString().endsWith("ffmpeg.exe"))
    }

    @Test
    fun `findBinary returns null when not found`(@TempDir tempDir: Path) = runTest {
        Files.createDirectories(tempDir.resolve("empty"))

        val found = extractor.findBinary(tempDir, "nonexistent")

        assertNull(found)
    }

    @Test
    fun `findBinary respects maxDepth`(@TempDir tempDir: Path) = runTest {
        // Create binary at depth 4
        val deepDir = tempDir.resolve("a/b/c/d")
        Files.createDirectories(deepDir)
        Files.writeString(deepDir.resolve("binary"), "content")

        // Should not find at depth 2
        val notFound = extractor.findBinary(tempDir, "binary", maxDepth = 2)
        assertNull(notFound)

        // Should find at depth 5
        val found = extractor.findBinary(tempDir, "binary", maxDepth = 5)
        assertNotNull(found)
    }

    @Test
    fun `findAllBinaries finds multiple binaries`(@TempDir tempDir: Path) = runTest {
        val binDir = tempDir.resolve("bin")
        Files.createDirectories(binDir)
        Files.writeString(binDir.resolve("ffmpeg"), "binary1")
        Files.writeString(binDir.resolve("ffprobe"), "binary2")

        // Make them executable on Unix
        binDir.resolve("ffmpeg").toFile().setExecutable(true)
        binDir.resolve("ffprobe").toFile().setExecutable(true)

        val binaries = extractor.findAllBinaries(tempDir)

        assertTrue(binaries.containsKey("ffmpeg"))
        assertTrue(binaries.containsKey("ffprobe"))
    }

    @Test
    fun `findFfmpegBinaries returns both ffmpeg and ffprobe`(@TempDir tempDir: Path) = runTest {
        val binDir = tempDir.resolve("ffmpeg-build/bin")
        Files.createDirectories(binDir)
        Files.writeString(binDir.resolve("ffmpeg"), "ffmpeg binary")
        Files.writeString(binDir.resolve("ffprobe"), "ffprobe binary")

        val (ffmpeg, ffprobe) = extractor.findFfmpegBinaries(tempDir)

        assertNotNull(ffmpeg)
        assertNotNull(ffprobe)
        assertTrue(ffmpeg.toString().contains("ffmpeg"))
        assertTrue(ffprobe.toString().contains("ffprobe"))
    }

    @Test
    fun `findFfmpegBinaries returns nulls when not found`(@TempDir tempDir: Path) = runTest {
        Files.createDirectories(tempDir.resolve("empty"))

        val (ffmpeg, ffprobe) = extractor.findFfmpegBinaries(tempDir)

        assertNull(ffmpeg)
        assertNull(ffprobe)
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `extract throws ArchiveException for unsupported format`(@TempDir tempDir: Path) = runTest {
        val unknownFile = tempDir.resolve("file.rar")
        Files.writeString(unknownFile, "not a real archive")

        val exception = assertFailsWith<ArchiveException> {
            extractor.extract(unknownFile, tempDir.resolve("dest")).toList()
        }

        assertTrue(exception.message.contains("Unsupported"))
        assertEquals(unknownFile.toString(), exception.archiveName)
    }

    @Test
    fun `extract handles invalid ZIP gracefully by extracting zero files`(@TempDir tempDir: Path) = runTest {
        // ZipInputStream doesn't validate upfront - it just fails to find entries
        val invalidZip = tempDir.resolve("invalid.zip")
        Files.writeString(invalidZip, "this is not a valid zip file")

        val progressList = extractor.extract(invalidZip, tempDir.resolve("dest")).toList()

        // No files extracted from invalid archive
        assertTrue(progressList.isEmpty() || progressList.last().filesExtracted == 0)
    }

    @Test
    fun `extract throws exception for non-existent file`(@TempDir tempDir: Path) = runTest {
        val nonExistent = tempDir.resolve("does-not-exist.zip")

        assertFailsWith<Exception> {
            extractor.extract(nonExistent, tempDir.resolve("dest")).toList()
        }
    }

    // ==================== ExtractionConfig Tests ====================

    @Test
    fun `ExtractionConfig has correct defaults`() {
        val config = ExtractionConfig()

        assertTrue(config.overwriteExisting)
        assertTrue(config.preservePermissions)
        assertTrue(config.flattenSingleRoot)
    }

    @Test
    fun `ExtractionConfig can be customized`() {
        val config = ExtractionConfig(
            overwriteExisting = false,
            preservePermissions = false,
            flattenSingleRoot = false
        )

        assertFalse(config.overwriteExisting)
        assertFalse(config.preservePermissions)
        assertFalse(config.flattenSingleRoot)
    }

    // ==================== ExtractionResult Tests ====================

    @Test
    fun `ExtractionResult contains correct data`() {
        val result = ExtractionResult(
            extractedPath = Path.of("/tmp/extracted"),
            filesExtracted = 10,
            totalBytes = 1024
        )

        assertEquals(Path.of("/tmp/extracted"), result.extractedPath)
        assertEquals(10, result.filesExtracted)
        assertEquals(1024, result.totalBytes)
    }

    // ==================== ArchiveException Tests ====================

    @Test
    fun `ArchiveException contains archive name and message`() {
        val exception = ArchiveException("test.zip", "File not found")

        assertEquals("test.zip", exception.archiveName)
        assertEquals("File not found", exception.message)
        assertTrue(exception is Exception)
    }

    // ==================== Helper Methods for Creating Test Archives ====================

    private fun createTestZip(path: Path, files: Map<String, String>) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(path.toFile()))).use { zos ->
            files.forEach { (name, content) ->
                // Create parent directories if needed
                val parts = name.split("/")
                if (parts.size > 1) {
                    var dirPath = ""
                    for (i in 0 until parts.size - 1) {
                        dirPath += parts[i] + "/"
                        val dirEntry = ZipEntry(dirPath)
                        try {
                            zos.putNextEntry(dirEntry)
                            zos.closeEntry()
                        } catch (e: Exception) {
                            // Directory might already exist
                        }
                    }
                }

                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
    }

    private fun createTestZipWithEmptyDir(path: Path) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(path.toFile()))).use { zos ->
            val dirEntry = ZipEntry("emptydir/")
            zos.putNextEntry(dirEntry)
            zos.closeEntry()
        }
    }

    private fun createTestTarGz(path: Path, files: Map<String, String>) {
        FileOutputStream(path.toFile()).use { fos ->
            GZIPOutputStream(fos).use { gzos ->
                TarArchiveOutputStream(gzos).use { tos ->
                    tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)

                    files.forEach { (name, content) ->
                        val entry = TarArchiveEntry(name)
                        entry.size = content.length.toLong()
                        tos.putArchiveEntry(entry)
                        tos.write(content.toByteArray())
                        tos.closeArchiveEntry()
                    }
                }
            }
        }
    }

    private fun createTestTarGzWithPermissions(path: Path, fileName: String, content: String, mode: Int) {
        FileOutputStream(path.toFile()).use { fos ->
            GZIPOutputStream(fos).use { gzos ->
                TarArchiveOutputStream(gzos).use { tos ->
                    val entry = TarArchiveEntry(fileName)
                    entry.size = content.length.toLong()
                    entry.mode = mode
                    tos.putArchiveEntry(entry)
                    tos.write(content.toByteArray())
                    tos.closeArchiveEntry()
                }
            }
        }
    }

    private fun createTestTarXz(path: Path, files: Map<String, String>) {
        FileOutputStream(path.toFile()).use { fos ->
            XZCompressorOutputStream(fos).use { xzos ->
                TarArchiveOutputStream(xzos).use { tos ->
                    tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)

                    files.forEach { (name, content) ->
                        val entry = TarArchiveEntry(name)
                        entry.size = content.length.toLong()
                        tos.putArchiveEntry(entry)
                        tos.write(content.toByteArray())
                        tos.closeArchiveEntry()
                    }
                }
            }
        }
    }

    private fun createTest7z(path: Path, files: Map<String, String>) {
        SevenZOutputFile(path.toFile()).use { szof ->
            files.forEach { (name, content) ->
                val entry = szof.createArchiveEntry(File(name), name) as SevenZArchiveEntry
                szof.putArchiveEntry(entry)
                szof.write(content.toByteArray())
                szof.closeArchiveEntry()
            }
        }
    }
}
