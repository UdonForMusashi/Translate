package dev.translate.installer.security

import dev.translate.installer.domain.GameProfile
import dev.translate.installer.domain.ProfileContentPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProductionReleaseCompatibilityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test fun `provided public FGO releases pass the production verifier`() {
        val releases = listOf(
            GameProfile.NA to System.getenv(NA_RELEASE_ENV),
            GameProfile.JP to System.getenv(JP_RELEASE_ENV),
        )
        if (releases.all { it.second.isNullOrBlank() }) {
            assumeTrue("Public release ZIPs were not provided", false)
        }
        assertTrue("Both regional release ZIPs must be provided", releases.all {
            !it.second.isNullOrBlank()
        })

        val verifier = BundleVerifier(
            signatureVerifier = ManifestSignatureVerifier(ReleaseKeyProvider),
        )
        releases.forEach { (profile, path) ->
            val archive = File(requireNotNull(path)).absoluteFile
            assertTrue("Release ZIP is missing: $archive", archive.isFile)
            val verified = verifier.verifyAndExtract(
                archiveFile = archive,
                extractionDirectory = File(
                    temporaryFolder.root,
                    "extract-${profile.name}",
                ),
                selectedProfile = profile,
            )

            assertEquals(ManifestPolicy.SCHEMA_VERSION, verified.manifest.schemaVersion)
            assertEquals(profile.name, verified.manifest.profile)
            assertEquals(profile.profileId, verified.manifest.profileId)
            assertEquals(profile.packageName, verified.manifest.packageName)
            val names = verified.manifest.files.mapTo(mutableSetOf()) { it.name }
            assertTrue(profile.contentPolicy.hasCompleteServantData(names))
            verified.manifest.files.forEach { file ->
                assertEquals(
                    profile.contentPolicy.destinationDirectoryFor(file.name),
                    file.destinationDirectory,
                )
            }
            ProfileContentPolicy.FGO_SERVANT_FILE_NAMES.forEach { name ->
                assertTrue(File(verified.extractedDirectory, "files/$name").isFile)
            }
        }
    }

    private companion object {
        const val NA_RELEASE_ENV = "FGO_NA_RELEASE_ZIP"
        const val JP_RELEASE_ENV = "FGO_JP_RELEASE_ZIP"
    }
}
