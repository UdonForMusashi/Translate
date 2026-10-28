package dev.translate.installer.privileged

import dev.translate.installer.domain.decodeTechnical
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

class TransactionalFileEngineTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var jpRoot: File
    private lateinit var naRoot: File
    private lateinit var baRoot: File
    private lateinit var engine: TransactionalFileEngine

    @Test fun `BetterFGO profiles use independent fixed packages and service codes`() {
        assertEquals(4, PrivilegedProfiles.BETTER_JP.code)
        assertEquals("io.rayshift.betterfgo", PrivilegedProfiles.BETTER_JP.packageName)
        assertEquals(5, PrivilegedProfiles.BETTER_NA.code)
        assertEquals("io.rayshift.betterfgo.en", PrivilegedProfiles.BETTER_NA.packageName)
        assertEquals(PrivilegedProfiles.BETTER_JP, PrivilegedProfiles.fromCode(4))
        assertEquals(PrivilegedProfiles.BETTER_NA, PrivilegedProfiles.fromCode(5))
        assertEquals("files/data/d713/", PrivilegedProfiles.BETTER_JP.destinationRelativeDirectory)
        assertEquals("files/data/d713/", PrivilegedProfiles.BETTER_NA.destinationRelativeDirectory)
    }

    @Before fun setUp() {
        jpRoot = temporaryFolder.newFolder("jp")
        naRoot = temporaryFolder.newFolder("na")
        baRoot = temporaryFolder.newFolder("ba")
        engine = engine()
    }

    @Test fun `overwrites existing targets without creating artifacts and preserves mode`() {
        val target = File(jpRoot, BIN).apply { writeText("original") }
        File(jpRoot, SPECIAL).writeText("original-special")
        val expectedPermissions = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
        )
        Files.setPosixFilePermissions(target.toPath(), expectedPermissions)
        val translated = "translated".toByteArray()

        install(PrivilegedProfiles.JP, BIN, translated)

        assertArrayEquals(translated, target.readBytes())
        assertEquals(expectedPermissions, Files.getPosixFilePermissions(target.toPath()))
        assertEquals(setOf(BIN, SPECIAL), jpRoot.list()!!.toSet())
    }

    @Test fun `BetterFGO falls back to a verified copy on emulator storage`() {
        val original = "original-better-fgo".toByteArray()
        val translated = "translated-better-fgo".toByteArray()
        val target = File(naRoot, BIN).apply { writeBytes(original) }
        engine = engine(fileOperations = emulatorFallbackOperations())

        install(PrivilegedProfiles.BETTER_NA, BIN, translated)

        assertArrayEquals(translated, target.readBytes())
        assertEquals(setOf(BIN), naRoot.list()!!.toSet())
    }

    @Test fun `copy fallback restores the previous BetterFGO file after interruption`() {
        val original = "original-better-fgo".toByteArray()
        val translated = "translated-better-fgo".toByteArray()
        val target = File(naRoot, BIN).apply { writeBytes(original) }
        val operations = emulatorFallbackOperations { source, destination ->
            if (source.fileName.toString().startsWith(".fate-cafe-install-") &&
                destination.fileName.toString() == BIN
            ) {
                Files.write(
                    destination,
                    "partial".toByteArray(),
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                throw IOException("copy interrupted")
            }
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        engine = engine(fileOperations = operations)

        val error = assertThrows(InstallEngineException::class.java) {
            install(PrivilegedProfiles.BETTER_NA, BIN, translated)
        }

        assertEquals(InstallError.IO_FAILURE, error.error)
        assertArrayEquals(original, target.readBytes())
        assertEquals(setOf(BIN), naRoot.list()!!.toSet())
    }

    @Test fun `probe restores an interrupted BetterFGO rollback before continuing`() {
        val original = "original-better-fgo".toByteArray()
        File(naRoot, BIN).writeText("partial-new-file")
        val rollback = File(
            naRoot,
            ".fate-cafe-rollback-${original.size}-${sha256(original)}-$BIN.partial",
        )
            .apply { writeBytes(original) }

        engine.probe(PrivilegedProfiles.BETTER_NA, 0)

        assertArrayEquals(original, File(naRoot, BIN).readBytes())
        assertFalse(rollback.exists())
        assertEquals(setOf(BIN), naRoot.list()!!.toSet())
    }

    @Test fun `probe discards an incomplete rollback only while original is intact`() {
        val original = "original-better-fgo".toByteArray()
        val target = File(naRoot, BIN).apply { writeBytes(original) }
        val rollbackName =
            ".fate-cafe-rollback-${original.size}-${sha256(original)}-$BIN.partial"
        val rollback = File(naRoot, rollbackName).apply { writeText("partial") }

        engine.probe(PrivilegedProfiles.BETTER_NA, 0)

        assertArrayEquals(original, target.readBytes())
        assertFalse(rollback.exists())
        assertEquals(setOf(BIN), naRoot.list()!!.toSet())
    }

    @Test fun `probe refuses an incomplete rollback when destination also changed`() {
        val original = "original-better-fgo".toByteArray()
        val target = File(naRoot, BIN).apply { writeText("partial-target") }
        val rollback = File(
            naRoot,
            ".fate-cafe-rollback-${original.size}-${sha256(original)}-$BIN.partial",
        ).apply { writeText("partial-rollback") }

        val error = assertThrows(InstallEngineException::class.java) {
            engine.probe(PrivilegedProfiles.BETTER_NA, 0)
        }

        assertEquals(InstallError.IO_FAILURE, error.error)
        assertEquals("partial-target", target.readText())
        assertEquals("partial-rollback", rollback.readText())
    }

    @Test fun `allows authenticated bin to be added but special file must already exist`() {
        val added = "new-bin".toByteArray()
        install(PrivilegedProfiles.JP, "added.bin", added)
        assertArrayEquals(added, File(jpRoot, "added.bin").readBytes())

        val special = "special".toByteArray()
        val error = assertThrows(InstallEngineException::class.java) {
            install(PrivilegedProfiles.JP, SPECIAL, special)
        }
        assertEquals(InstallError.TARGET_FILE_MISSING, error.error)
        assertFalse(File(jpRoot, SPECIAL).exists())
    }

    @Test fun `rejects traversal absolute paths separators and unlisted extensions`() {
        File(jpRoot, SPECIAL).writeText("special")
        listOf("../evil.bin", "/evil.bin", "dir/evil.bin", "dir\\evil.bin", "evil.txt")
            .forEach { unsafeName ->
                val error = assertThrows(InstallEngineException::class.java) {
                    install(PrivilegedProfiles.JP, unsafeName, byteArrayOf(1))
                }
                assertEquals(InstallError.INVALID_FILE_NAME, error.error)
            }
    }

    @Test fun `rejects changed source before touching destination`() {
        val original = "original".toByteArray()
        val target = File(jpRoot, BIN).apply { writeBytes(original) }
        val source = temporaryFolder.newFile("source.bin").apply { writeText("changed") }

        val error = assertThrows(InstallEngineException::class.java) {
            FileInputStream(source).use { input ->
                engine.installFile(
                    PrivilegedProfiles.JP,
                    BIN,
                    input,
                    source.length(),
                    sha256("expected".toByteArray()),
                    NO_PROGRESS,
                )
            }
        }

        assertEquals(InstallError.SOURCE_HASH_MISMATCH, error.error)
        assertArrayEquals(original, target.readBytes())
    }

    @Test fun `uninstall deletes only exact installed content including added bins`() {
        val bin = "translated-bin".toByteArray()
        val special = "translated-special".toByteArray()
        File(jpRoot, BIN).writeBytes(bin)
        File(jpRoot, SPECIAL).writeBytes(special)
        val records = listOf(record(BIN, bin), record(SPECIAL, special))

        engine.uninstall(PrivilegedProfiles.JP, records, NO_PROGRESS)

        assertFalse(File(jpRoot, BIN).exists())
        assertFalse(File(jpRoot, SPECIAL).exists())
    }

    @Test fun `uninstall deletes exact content and preserves targets changed by game`() {
        val bin = "translated-bin".toByteArray()
        val special = "translated-special".toByteArray()
        File(jpRoot, BIN).writeBytes(bin)
        File(jpRoot, SPECIAL).writeText("game-updated-this-file")
        val records = listOf(record(BIN, bin), record(SPECIAL, special))

        engine.uninstall(PrivilegedProfiles.JP, records, NO_PROGRESS)

        assertFalse(File(jpRoot, BIN).exists())
        assertTrue(File(jpRoot, SPECIAL).exists())
    }

    @Test fun `uninstall treats already missing targets as removed`() {
        val bin = "translated-bin".toByteArray()
        val special = "translated-special".toByteArray()
        File(jpRoot, SPECIAL).writeBytes(special)

        engine.uninstall(
            PrivilegedProfiles.JP,
            listOf(record(BIN, bin), record(SPECIAL, special)),
            NO_PROGRESS,
        )

        assertFalse(File(jpRoot, SPECIAL).exists())
    }

    @Test fun `uninstall recognizes either authenticated version after interrupted update`() {
        val oldBin = "old-translated-bin".toByteArray()
        val newBin = "new-translated-bin".toByteArray()
        val oldSpecial = "old-translated-special".toByteArray()
        val newSpecial = "new-translated-special".toByteArray()
        File(jpRoot, BIN).writeBytes(oldBin)
        File(jpRoot, SPECIAL).writeBytes(newSpecial)

        engine.uninstall(
            PrivilegedProfiles.JP,
            listOf(
                record(BIN, newBin),
                record(SPECIAL, newSpecial),
                record(BIN, oldBin),
                record(SPECIAL, oldSpecial),
            ),
            NO_PROGRESS,
        )

        assertFalse(File(jpRoot, BIN).exists())
        assertFalse(File(jpRoot, SPECIAL).exists())
    }

    @Test fun `uninstall rejects more than two alternatives for one name before deletion`() {
        val bin = "translated-bin".toByteArray()
        val special = "translated-special".toByteArray()
        File(jpRoot, BIN).writeBytes(bin)
        File(jpRoot, SPECIAL).writeBytes(special)

        val error = assertThrows(InstallEngineException::class.java) {
            engine.uninstall(
                PrivilegedProfiles.JP,
                listOf(
                    record(BIN, bin),
                    record(BIN, "other".toByteArray()),
                    record(BIN, "third".toByteArray()),
                    record(SPECIAL, special),
                ),
                NO_PROGRESS,
            )
        }

        assertEquals(InstallError.RECEIPT_INVALID, error.error)
        assertTrue(File(jpRoot, BIN).exists())
        assertTrue(File(jpRoot, SPECIAL).exists())
    }

    @Test fun `probe removes only strictly named legacy app artifacts`() {
        val legacyBackup = decodeTechnical("LnRyYW5zbGF0ZWZnby1sYXN0LWJhY2t1cA==")
        val legacyTransaction = decodeTechnical(
            "LnRyYW5zbGF0ZWZnby10eG4tMTIzZTQ1NjctZTg5Yi00MmQzLWE0NTYtNDI2NjE0MTc0MDAw",
        )
        val similarName = decodeTechnical(
            "LnRyYW5zbGF0ZWZnby1zaW1pbGFyLWJ1dC1ub3Qtb3Vycw==",
        )
        File(jpRoot, legacyBackup).mkdir().also {
            File(File(jpRoot, legacyBackup), "old.bin").writeText("backup")
        }
        File(jpRoot, legacyTransaction).mkdir()
        File(jpRoot, similarName).writeText("keep")

        engine.probe(PrivilegedProfiles.JP, 0)

        assertFalse(File(jpRoot, legacyBackup).exists())
        assertFalse(File(jpRoot, legacyTransaction).exists())
        assertTrue(File(jpRoot, similarName).exists())
    }

    @Test fun `preflight rejects installation when safety margin would be violated`() {
        val lowSpaceEngine = engine(usableSpace = 65L * 1024L * 1024L)

        val error = assertThrows(InstallEngineException::class.java) {
            lowSpaceEngine.probe(PrivilegedProfiles.JP, 2L * 1024L * 1024L)
        }

        assertEquals(InstallError.INSUFFICIENT_STORAGE, error.error)
    }

    @Test fun `Blue Archive overwrites only an existing ExcelDB database`() {
        val translated = "translated-database".toByteArray()
        val target = File(baRoot, EXCEL_DB).apply { writeText("original-database") }

        install(PrivilegedProfiles.BA, EXCEL_DB, translated)

        assertArrayEquals(translated, target.readBytes())
        assertEquals(setOf(EXCEL_DB), baRoot.list()!!.toSet())
    }

    @Test fun `Blue Archive preserves original database when atomic staging has no space`() {
        val original = "original-database".toByteArray()
        val translated = "translated-database".toByteArray()
        val target = File(baRoot, EXCEL_DB).apply { writeBytes(original) }
        engine = engine(usableSpace = MINIMUM_FREE_BYTES + translated.size - 1L)

        val error = assertThrows(InstallEngineException::class.java) {
            install(PrivilegedProfiles.BA, EXCEL_DB, translated)
        }

        assertEquals(InstallError.INSUFFICIENT_STORAGE, error.error)
        assertArrayEquals(original, target.readBytes())
        assertEquals(setOf(EXCEL_DB), baRoot.list()!!.toSet())
    }

    @Test fun `Blue Archive never uses the non-atomic copy fallback`() {
        val original = "original-database".toByteArray()
        val target = File(baRoot, EXCEL_DB).apply { writeBytes(original) }
        var copyAttempted = false
        engine = engine(
            fileOperations = emulatorFallbackOperations { _, _ -> copyAttempted = true },
        )

        val error = assertThrows(InstallEngineException::class.java) {
            install(PrivilegedProfiles.BA, EXCEL_DB, "translated-database".toByteArray())
        }

        assertEquals(InstallError.IO_FAILURE, error.error)
        assertFalse(copyAttempted)
        assertArrayEquals(original, target.readBytes())
        assertEquals(setOf(EXCEL_DB), baRoot.list()!!.toSet())
    }

    @Test fun `probe removes only the exact interrupted atomic staging file`() {
        val interrupted = File(baRoot, ".fate-cafe-install-ExcelDB.db.partial")
            .apply { writeText("partial") }
        val legacyInterrupted = File(baRoot, ".barista-latte-install-ExcelDB.db.partial")
            .apply { writeText("legacy-partial") }
        val unrelated = File(baRoot, ".barista-latte-install-outro.db.partial")
            .apply { writeText("keep") }

        engine.probe(PrivilegedProfiles.BA, 0)

        assertFalse(interrupted.exists())
        assertFalse(legacyInterrupted.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun `Blue Archive rejects a staging symlink without touching either database`() {
        val original = "original-database".toByteArray()
        val outside = temporaryFolder.newFile("outside.db").apply { writeText("outside") }
        val target = File(baRoot, EXCEL_DB).apply { writeBytes(original) }
        Files.createSymbolicLink(
            File(baRoot, ".fate-cafe-install-ExcelDB.db.partial").toPath(),
            outside.toPath(),
        )

        val error = assertThrows(InstallEngineException::class.java) {
            install(PrivilegedProfiles.BA, EXCEL_DB, "translated".toByteArray())
        }

        assertEquals(InstallError.TARGET_UNSAFE, error.error)
        assertArrayEquals(original, target.readBytes())
        assertEquals("outside", outside.readText())
    }

    @Test fun `Blue Archive rejects missing database and other file names`() {
        val missing = assertThrows(InstallEngineException::class.java) {
            install(PrivilegedProfiles.BA, EXCEL_DB, "translated".toByteArray())
        }
        assertEquals(InstallError.TARGET_FILE_MISSING, missing.error)

        File(baRoot, EXCEL_DB).writeText("original")
        val invalid = assertThrows(InstallEngineException::class.java) {
            install(PrivilegedProfiles.BA, "outro.db", "translated".toByteArray())
        }
        assertEquals(InstallError.INVALID_FILE_NAME, invalid.error)
        assertTrue(File(baRoot, EXCEL_DB).exists())
    }

    private fun engine(
        usableSpace: Long? = null,
        fileOperations: TransactionalFileOperations = TransactionalFileOperations(),
    ) = TransactionalFileEngine(
        rootResolver = { profile ->
            when (profile) {
                PrivilegedProfiles.JP, PrivilegedProfiles.BETTER_JP -> jpRoot
                PrivilegedProfiles.NA, PrivilegedProfiles.BETTER_NA -> naRoot
                PrivilegedProfiles.BA -> baRoot
                else -> error("perfil inesperado")
            }
        },
        usableSpaceProvider = { usableSpace ?: it.usableSpace },
        fileOperations = fileOperations,
    )

    private fun emulatorFallbackOperations(
        replacementCopy: (java.nio.file.Path, java.nio.file.Path) -> Unit = { source, destination ->
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        },
    ) = TransactionalFileOperations(
        sync = { throw FileSystemException("translation.partial", null, "Invalid argument") },
        atomicMove = { _, _ -> throw IOException("atomic rename failed") },
        replacementMove = { source, destination ->
            throw FileSystemException(source.toString(), destination.toString(), "Invalid argument")
        },
        replacementCopy = replacementCopy,
    )

    private fun install(profile: PrivilegedProfile, name: String, bytes: ByteArray) {
        val source = temporaryFolder.newFile("source-${System.nanoTime()}.bin")
        source.writeBytes(bytes)
        FileInputStream(source).use { input ->
            engine.installFile(profile, name, input, bytes.size.toLong(), sha256(bytes), NO_PROGRESS)
        }
    }

    private fun record(name: String, bytes: ByteArray) =
        PrivilegedInstalledFile(name, bytes.size.toLong(), sha256(bytes))

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val BIN = "translation.bin"
        const val SPECIAL = "cfb1d36393fd67385e046b084b7cf7ed"
        const val EXCEL_DB = "ExcelDB.db"
        const val MINIMUM_FREE_BYTES = 64L * 1024L * 1024L
        val NO_PROGRESS = InstallProgress { _, _, _, _ -> }
    }
}
