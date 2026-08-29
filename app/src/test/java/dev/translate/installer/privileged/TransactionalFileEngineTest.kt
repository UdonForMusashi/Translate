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
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

class TransactionalFileEngineTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var jpRoot: File
    private lateinit var naRoot: File
    private lateinit var engine: TransactionalFileEngine

    @Before fun setUp() {
        jpRoot = temporaryFolder.newFolder("jp")
        naRoot = temporaryFolder.newFolder("na")
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

    private fun engine(usableSpace: Long? = null) = TransactionalFileEngine(
        rootResolver = { profile -> if (profile == PrivilegedProfiles.JP) jpRoot else naRoot },
        usableSpaceProvider = { usableSpace ?: it.usableSpace },
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
        val NO_PROGRESS = InstallProgress { _, _, _, _ -> }
    }
}
