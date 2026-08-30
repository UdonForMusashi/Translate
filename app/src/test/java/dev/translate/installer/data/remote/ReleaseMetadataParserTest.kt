package dev.translate.installer.data.remote

import dev.translate.installer.domain.GameProfile
import dev.translate.installer.security.BundleFailureCode
import dev.translate.installer.security.BundleValidationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URL

class ReleaseMetadataParserTest {
    private val parser = ReleaseMetadataParser()

    @Test
    fun `accepts one uploaded zip asset`() {
        val result = parser.parse(
            releaseJson(
                """{"id":42,"name":"bundle-2026.08.zip","size":1234,"state":"uploaded"}""",
            ),
            2_000,
        )

        assertEquals(42, result.id)
        assertEquals("bundle-2026.08.zip", result.name)
        assertEquals(1234, result.size)
        assertEquals("v2026.08", result.tagName)
    }

    @Test
    fun `rejects multiple assets`() {
        assertCode(BundleFailureCode.RELEASE_ASSET_INVALID) {
            parser.parse(
                releaseJson(
                    """{"id":1,"name":"a.zip","size":1,"state":"uploaded"},{"id":2,"name":"b.zip","size":1,"state":"uploaded"}""",
                ),
                100,
            )
        }
    }

    @Test
    fun `rejects a non zip asset`() {
        assertCode(BundleFailureCode.RELEASE_ASSET_INVALID) {
            parser.parse(
                releaseJson("""{"id":1,"name":"bundle.bin","size":1,"state":"uploaded"}"""),
                100,
            )
        }
    }

    @Test
    fun `rejects an oversized asset`() {
        assertCode(BundleFailureCode.RELEASE_ASSET_INVALID) {
            parser.parse(
                releaseJson("""{"id":1,"name":"bundle.zip","size":101,"state":"uploaded"}"""),
                100,
            )
        }
    }

    @Test
    fun `rejects duplicate security fields`() {
        assertCode(BundleFailureCode.RELEASE_RESPONSE_INVALID) {
            parser.parse(
                """{"tag_name":"v1","tag_name":"v2","assets":[]}""".toByteArray(),
                100,
            )
        }
    }

    @Test
    fun `rejects unsafe tag text`() {
        assertCode(BundleFailureCode.RELEASE_RESPONSE_INVALID) {
            parser.parse(
                """{"tag_name":"v1\ntexto","assets":[{"id":1,"name":"a.zip","size":1,"state":"uploaded"}]}"""
                    .toByteArray(),
                100,
            )
        }
    }

    @Test
    fun `allows only approved https hosts`() {
        validateRemoteUrl(URL("https://api.github.com/repos/account/content-na/releases/latest"))
        validateRemoteUrl(URL("https://release-assets.githubusercontent.com/file.zip?token=value"))

        assertCode(BundleFailureCode.DOWNLOAD_REDIRECT_INVALID) {
            validateRemoteUrl(URL("http://api.github.com/repos/account/content-na/releases/latest"))
        }
        assertCode(BundleFailureCode.DOWNLOAD_REDIRECT_INVALID) {
            validateRemoteUrl(URL("https://example.org/file.zip"))
        }
        assertCode(BundleFailureCode.DOWNLOAD_REDIRECT_INVALID) {
            validateRemoteUrl(URL("https://api.github.com.evil.example/file.zip"))
        }
    }

    @Test
    fun `maps each profile to an independent repository`() {
        assertEquals("content-na", RemoteRepositoryCatalog.repository(GameProfile.NA))
        assertEquals("content-jp", RemoteRepositoryCatalog.repository(GameProfile.JP))
    }

    private fun releaseJson(assets: String): ByteArray =
        """{"tag_name":"v2026.08","assets":[$assets],"ignored":{"value":true}}"""
            .toByteArray()

    private fun assertCode(code: BundleFailureCode, action: () -> Unit) {
        val exception = assertThrows(BundleValidationException::class.java, action)
        assertEquals(code, exception.code)
    }
}
