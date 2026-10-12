package dev.translate.installer.data.remote

import dev.translate.installer.data.importer.ImportedBundle
import dev.translate.installer.security.LocalBundleManifest
import dev.translate.installer.security.VerifiedBundle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteImportedBundleTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test fun `cache is current only for the exact release and existing archive`() {
        val archive = temporaryFolder.newFile("bundle.zip").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val release = ReleaseAsset(
            id = 10,
            name = "content-na-20260918.zip",
            size = archive.length(),
            tagName = "v20260918",
        )
        val bundle = RemoteImportedBundle(
            staged = ImportedBundle(
                verifiedBundle = VerifiedBundle(
                    manifest = manifest(),
                    archiveFile = archive,
                    extractedDirectory = temporaryFolder.newFolder("extracted"),
                ),
                archiveSha256 = "0".repeat(64),
                sourceDisplayName = release.name,
            ),
            releaseAsset = release,
        )

        assertTrue(bundle.isCurrent(release))
        assertFalse(bundle.isCurrent(release.copy(id = 11)))
        assertFalse(bundle.isCurrent(release.copy(tagName = "v20260919")))
        assertFalse(bundle.isCurrent(release.copy(name = "content-na-20260919.zip")))
        assertFalse(bundle.isCurrent(release.copy(size = release.size + 1)))

        assertTrue(archive.delete())
        assertFalse(bundle.isCurrent(release))
    }

    private fun manifest() = LocalBundleManifest(
        schemaVersion = 2,
        keyId = "translatefgo-main",
        profile = "NA",
        profileId = "fgo-na",
        packageName = "com.aniplex.fategrandorder.en",
        destinationDirectory = "files/data/d713/",
        version = "20260918",
        createdAt = "2026-09-18T00:00:00Z",
        fileCount = 0,
        uncompressedSize = 0,
        files = emptyList(),
    )
}
