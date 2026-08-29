package dev.translate.installer.security

import dev.translate.installer.domain.GameProfile
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundleVerifierTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
    }

    @Test fun `authenticates inspects and extracts a valid schema 2 bundle`() {
        val fixture = fixture(GameProfile.JP)
        val extraction = File(temporaryFolder.root, "valid")
        val result = verify(fixture, extraction, GameProfile.JP)
        assertEquals("fixture-1", result.manifest.version)
        assertEquals(2L, result.manifest.fileCount)
        assertArrayEquals(fixture.bin, File(extraction, "files/123abc.bin").readBytes())
        assertArrayEquals(fixture.special, File(extraction, "files/$SPECIAL").readBytes())
    }

    @Test fun `accepts different signed versions without a hardcoded zip name`() {
        val first = fixture(GameProfile.NA, version = "20260823-010101", binName = "a.bin")
        val second = fixture(GameProfile.NA, version = "20260824-020202", binName = "b.bin")
        assertEquals("20260823-010101", verify(first, File(temporaryFolder.root, "a"), GameProfile.NA).manifest.version)
        assertEquals("20260824-020202", verify(second, File(temporaryFolder.root, "b"), GameProfile.NA).manifest.version)
    }

    @Test fun `rejects a bundle selected for the other profile`() {
        assertCode(BundleFailureCode.PROFILE_MISMATCH, fixture(GameProfile.JP), "profile", GameProfile.NA)
    }

    @Test fun `rejects an unexpected archive entry`() {
        assertCode(BundleFailureCode.UNEXPECTED_ENTRY, fixture(GameProfile.NA, extra = "files/extra.bin"), "extra", GameProfile.NA)
    }

    @Test fun `rejects a signed manifest containing path traversal`() {
        assertCode(BundleFailureCode.UNSAFE_ENTRY, fixture(GameProfile.JP, entryOverride = "files/../123abc.bin"), "traversal", GameProfile.JP)
    }

    @Test fun `rejects a Unix symlink and removes extraction`() {
        val extraction = File(temporaryFolder.root, "symlink")
        val error = assertThrows(BundleValidationException::class.java) {
            verify(fixture(GameProfile.JP, symlink = true), extraction, GameProfile.JP)
        }
        assertEquals(BundleFailureCode.UNSAFE_ENTRY, error.code)
        assertFalse(extraction.exists())
    }

    @Test fun `rejects an altered signature before extraction`() {
        val extraction = File(temporaryFolder.root, "signature")
        val error = assertThrows(BundleValidationException::class.java) {
            verify(fixture(GameProfile.JP, alterSignature = true), extraction, GameProfile.JP)
        }
        assertEquals(BundleFailureCode.SIGNATURE_INVALID, error.code)
        assertFalse(extraction.exists())
    }

    @Test fun `rejects an altered payload and removes partial extraction`() {
        val extraction = File(temporaryFolder.root, "payload")
        val error = assertThrows(BundleValidationException::class.java) {
            verify(fixture(GameProfile.NA, alterPayload = true), extraction, GameProfile.NA)
        }
        assertEquals(BundleFailureCode.FILE_HASH_MISMATCH, error.code)
        assertFalse(extraction.exists())
    }

    @Test fun `rejects an excessive compression ratio`() {
        val f = fixture(GameProfile.JP, bin = ByteArray(4_096))
        val error = assertThrows(BundleValidationException::class.java) {
            verifier(ImportLimits(compressionRatioCheckThreshold = 1, maxCompressionRatio = 2))
                .verifyAndExtract(f.archive, File(temporaryFolder.root, "ratio"), GameProfile.JP)
        }
        assertEquals(BundleFailureCode.COMPRESSION_RATIO_EXCEEDED, error.code)
    }

    @Test fun `rejects extraction without the storage safety reserve`() {
        val extraction = File(temporaryFolder.root, "storage")
        val error = assertThrows(BundleValidationException::class.java) {
            verifier(ImportLimits(minimumFreeBytes = Long.MAX_VALUE)).verifyAndExtract(
                fixture(GameProfile.JP).archive, extraction, GameProfile.JP,
            )
        }
        assertEquals(BundleFailureCode.INSUFFICIENT_STORAGE, error.code)
        assertFalse(extraction.exists())
    }

    @Test fun `rejects too many central directory entries`() {
        val f = fixture(GameProfile.JP, extra = "extra")
        val error = assertThrows(BundleValidationException::class.java) {
            verifier(ImportLimits(maxArchiveEntries = 4)).verifyAndExtract(
                f.archive, File(temporaryFolder.root, "entries"), GameProfile.JP,
            )
        }
        assertEquals(BundleFailureCode.TOO_MANY_ENTRIES, error.code)
    }

    private fun verify(f: Fixture, dir: File, profile: GameProfile) =
        verifier().verifyAndExtract(f.archive, dir, profile)

    private fun assertCode(code: BundleFailureCode, f: Fixture, dir: String, profile: GameProfile) {
        val error = assertThrows(BundleValidationException::class.java) {
            verify(f, File(temporaryFolder.root, dir), profile)
        }
        assertEquals(code, error.code)
    }

    private fun verifier(limits: ImportLimits = ImportLimits()) = BundleVerifier(
        ManifestSignatureVerifier(PublicKeyProvider { keyId ->
            keyPair.public.takeIf { keyId == ManifestPolicy.RELEASE_KEY_ID }
        }),
        limits = limits,
    )

    private fun fixture(
        profile: GameProfile,
        extra: String? = null,
        entryOverride: String? = null,
        symlink: Boolean = false,
        alterSignature: Boolean = false,
        alterPayload: Boolean = false,
        bin: ByteArray = "translated-bin-content".toByteArray(),
        version: String = "fixture-1",
        binName: String = "123abc.bin",
    ): Fixture {
        val archivedBin = bin.clone().also { if (alterPayload) it[0] = (it[0].toInt() xor 1).toByte() }
        val special = "translated-special-content".toByteArray()
        val binEntry = entryOverride ?: "files/$binName"
        val manifest = """
            {"schemaVersion":2,"keyId":"${ManifestPolicy.RELEASE_KEY_ID}","profile":"${profile.name}","profileId":"${profile.profileId}","packageName":"${profile.packageName}","destinationDirectory":"${profile.destinationRelativeDirectory}","version":"$version","createdAt":"2026-08-23T02:49:28Z","fileCount":2,"uncompressedSize":${bin.size + special.size},"files":[{"name":"$binName","archiveEntry":"$binEntry","size":${bin.size},"sha256":"${sha256(bin)}"},{"name":"$SPECIAL","archiveEntry":"files/$SPECIAL","size":${special.size},"sha256":"${sha256(special)}"}]}
        """.trimIndent().toByteArray()
        val signature = Signature.getInstance(ManifestPolicy.SUPPORTED_ALGORITHM).run {
            initSign(keyPair.private); update(manifest); sign()
        }.also { if (alterSignature) it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val envelope = """
            {"schemaVersion":2,"keyId":"${ManifestPolicy.RELEASE_KEY_ID}","algorithm":"${ManifestPolicy.SUPPORTED_ALGORITHM}","signature":"${Base64.getEncoder().encodeToString(signature)}"}
        """.trimIndent().toByteArray()
        val archive = temporaryFolder.newFile("bundle-${System.nanoTime()}.zip")
        if (symlink) {
            ZipArchiveOutputStream(archive).use { zip ->
                zip.entry("manifest.json", manifest); zip.entry("manifest.sig", envelope)
                zip.putArchiveEntry(ZipArchiveEntry(binEntry).apply { unixMode = UnixStat.LINK_FLAG or 0b111_101_101 })
                zip.write(archivedBin); zip.closeArchiveEntry(); zip.entry("files/$SPECIAL", special)
            }
        } else {
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                zip.entry("manifest.json", manifest); zip.entry("manifest.sig", envelope)
                zip.entry(binEntry, archivedBin); zip.entry("files/$SPECIAL", special)
                extra?.let { zip.entry(it, byteArrayOf(1)) }
            }
        }
        return Fixture(archive, bin, special)
    }

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name)); write(bytes); closeEntry()
    }
    private fun ZipArchiveOutputStream.entry(name: String, bytes: ByteArray) {
        putArchiveEntry(ZipArchiveEntry(name)); write(bytes); closeArchiveEntry()
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class Fixture(val archive: File, val bin: ByteArray, val special: ByteArray)
    private companion object {
        const val SPECIAL = "cfb1d36393fd67385e046b084b7cf7ed"
    }
}
