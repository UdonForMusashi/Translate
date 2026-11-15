package dev.translate.installer.data.remote

import dev.translate.installer.domain.GameProfile
import dev.translate.installer.security.BundleFailureCode
import dev.translate.installer.security.BundleValidationException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val body = "abcdefghij".toByteArray()
    private val asset = ReleaseAsset(11, "bundle.zip", 10, "release-1")
    private val progress = DownloadProgressSink { _, _ -> }

    @Test
    fun `network interruption retains prefix and resumes with validator and full hash`() =
        runBlocking {
            val checkpoint = ResumeCheckpoint(temporary.root, GameProfile.NA)
            val first = Stub(200, body, mapOf("ETag" to "\"one\""), failAt = 4)
            val second =
                Stub(
                    206,
                    body.copyOfRange(4, 10),
                    mapOf("ETag" to "\"one\"", "Content-Range" to "bytes 4-9/10"),
                )
            val replies = ArrayDeque(listOf(first, second))
            val client = GitHubReleaseClient(connectionFactory = { replies.removeFirst() })
            try {
                client.download(GameProfile.NA, asset, checkpoint.partial, progress, checkpoint)
                fail("Expected network failure")
            } catch (e: BundleValidationException) {
                assertEquals(BundleFailureCode.NETWORK_UNAVAILABLE, e.code)
            }
            assertEquals(4L, checkpoint.partial.length())
            val restored = ResumeCheckpoint(temporary.root, GameProfile.NA)
            val result =
                client.download(GameProfile.NA, asset, restored.partial, progress, restored)
            assertEquals("bytes=4-", second.requested["Range"])
            assertEquals("\"one\"", second.requested["If-Range"])
            assertArrayEquals(body, checkpoint.partial.readBytes())
            assertEquals(hash(body), result.sha256)
        }

    @Test
    fun `server ignoring range replaces the prefix instead of appending`() = runBlocking {
        val checkpoint = prefix()
        val response = Stub(200, body, mapOf("ETag" to "\"two\""))
        GitHubReleaseClient(connectionFactory = { response })
            .download(GameProfile.NA, asset, checkpoint.partial, progress, checkpoint)
        assertArrayEquals(body, checkpoint.partial.readBytes())
    }

    @Test
    fun `changed release identity discards old bytes before requesting`() = runBlocking {
        val checkpoint = prefix()
        val response = Stub(200, body, mapOf("ETag" to "\"new\""))
        GitHubReleaseClient(connectionFactory = { response })
            .download(GameProfile.NA, asset.copy(id = 12), checkpoint.partial, progress, checkpoint)
        assertNull(response.requested["Range"])
        assertArrayEquals(body, checkpoint.partial.readBytes())
    }

    @Test
    fun `malformed content range clears transport cache and is rejected`() = runBlocking {
        val checkpoint = prefix()
        val response =
            Stub(
                206,
                body.copyOfRange(4, 10),
                mapOf("ETag" to "\"one\"", "Content-Range" to "bytes 3-8/10"),
            )
        try {
            GitHubReleaseClient(connectionFactory = { response })
                .download(GameProfile.NA, asset, checkpoint.partial, progress, checkpoint)
            fail("Invalid range accepted")
        } catch (e: BundleValidationException) {
            assertEquals(BundleFailureCode.DOWNLOAD_SIZE_MISMATCH, e.code)
        }
        assertFalse(checkpoint.partial.exists())
    }

    @Test
    fun `changed validator in partial response is rejected`() = runBlocking {
        val checkpoint = prefix()
        val response =
            Stub(
                206,
                body.copyOfRange(4, 10),
                mapOf("ETag" to "\"two\"", "Content-Range" to "bytes 4-9/10"),
            )
        try {
            GitHubReleaseClient(connectionFactory = { response })
                .download(GameProfile.NA, asset, checkpoint.partial, progress, checkpoint)
            fail("Changed range accepted")
        } catch (e: BundleValidationException) {
            assertEquals(BundleFailureCode.DOWNLOAD_SIZE_MISMATCH, e.code)
        }
        assertFalse(checkpoint.partial.exists())
    }

    @Test
    fun `weak validator restarts an incomplete download`() = runBlocking {
        val checkpoint = prefix()
        checkpoint.record(asset, "W/\"one\"")
        val response = Stub(200, body, emptyMap())
        GitHubReleaseClient(connectionFactory = { response })
            .download(GameProfile.NA, asset, checkpoint.partial, progress, checkpoint)
        assertNull(response.requested["Range"])
        assertArrayEquals(body, checkpoint.partial.readBytes())
    }

    @Test
    fun `complete cache is rehashed and reused without another transfer`() = runBlocking {
        val checkpoint = prefix()
        checkpoint.partial.writeBytes(body)
        val client = GitHubReleaseClient(connectionFactory = { error("Unexpected HTTP transfer") })
        assertEquals(
            hash(body),
            client.download(GameProfile.NA, asset, checkpoint.partial, progress, checkpoint).sha256,
        )
    }

    @Test
    fun `416 clears prefix so next attempt requests complete resource`() = runBlocking {
        val checkpoint = prefix()
        try {
            GitHubReleaseClient(connectionFactory = { Stub(416, byteArrayOf(), emptyMap()) })
                .download(GameProfile.NA, asset, checkpoint.partial, progress, checkpoint)
            fail("416 accepted")
        } catch (e: BundleValidationException) {
            assertEquals(BundleFailureCode.DOWNLOAD_FAILED, e.code)
        }
        assertFalse(checkpoint.partial.exists())
    }

    @Test
    fun `redirect to untrusted host never receives request`() = runBlocking {
        val checkpoint = prefix()
        var calls = 0
        val client =
            GitHubReleaseClient(
                connectionFactory = {
                    calls++
                    Stub(302, byteArrayOf(), mapOf("Location" to "https://example.org/bundle"))
                }
            )
        try {
            client.download(GameProfile.NA, asset, checkpoint.partial, progress, checkpoint)
            fail("Unsafe redirect accepted")
        } catch (e: BundleValidationException) {
            assertEquals(BundleFailureCode.DOWNLOAD_REDIRECT_INVALID, e.code)
        }
        assertEquals(1, calls)
    }

    private fun prefix(): ResumeCheckpoint =
        ResumeCheckpoint(temporary.root, GameProfile.NA).also {
            it.partial.writeBytes(body.copyOfRange(0, 4))
            it.record(asset, "\"one\"")
        }

    @Test
    fun `explicit cancellation propagates without becoming a network failure`() = runBlocking {
        val checkpoint = prefix()
        val response = Stub(200, body, mapOf("ETag" to "\"new\""))
        try {
            GitHubReleaseClient(connectionFactory = { response })
                .download(
                    GameProfile.NA,
                    asset,
                    checkpoint.partial,
                    DownloadProgressSink { _, _ ->
                        throw kotlinx.coroutines.CancellationException("User cancelled")
                    },
                    checkpoint,
                )
            fail("Cancellation ignored")
        } catch (_: kotlinx.coroutines.CancellationException) {}
    }

    private fun hash(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }

    private class Stub(
        private val status: Int,
        private val bytes: ByteArray,
        private val headers: Map<String, String>,
        private val failAt: Int? = null,
    ) : HttpURLConnection(URL("https://api.github.com/test")) {
        val requested = mutableMapOf<String, String>()

        override fun setRequestProperty(key: String, value: String) {
            requested[key] = value
        }

        override fun getResponseCode() = status

        override fun getContentLengthLong() = bytes.size.toLong()

        override fun getHeaderField(name: String) = headers[name]

        override fun connect() {}

        override fun disconnect() {}

        override fun usingProxy() = false

        override fun getInputStream(): InputStream {
            val input = ByteArrayInputStream(bytes)
            var read = 0
            return object : InputStream() {
                override fun read(): Int {
                    if (failAt != null && read >= failAt) throw IOException("Disconnected")
                    return input.read().also { if (it >= 0) read++ }
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (failAt != null && read >= failAt) throw IOException("Disconnected")
                    val count =
                        input.read(
                            buffer,
                            offset,
                            if (failAt != null) minOf(length, failAt - read) else length,
                        )
                    if (count > 0) read += count
                    return count
                }
            }
        }
    }
}
