package dev.translate.installer.data.remote

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.translate.installer.domain.GameProfile
import dev.translate.installer.security.BundleFailureCode
import dev.translate.installer.security.BundleValidationException
import dev.translate.installer.security.ImportLimits
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class ReleaseAsset(
    val id: Long,
    val name: String,
    val size: Long,
    val tagName: String,
)

data class DownloadResult(
    val sha256: String,
    val bytes: Long,
)

fun interface DownloadProgressSink {
    fun onProgress(processed: Long, total: Long)
}

class GitHubReleaseClient(
    private val limits: ImportLimits = ImportLimits(),
    private val parser: ReleaseMetadataParser = ReleaseMetadataParser(),
) {
    suspend fun latest(profile: GameProfile): ReleaseAsset {
        val repository = RemoteRepositoryCatalog.repository(profile)
        val endpoint = URL("https://api.github.com/repos/$OWNER/$repository/releases/latest")
        val connection = openConnection(endpoint, JSON_ACCEPT)
        return try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val bytes = readBounded(connection, MAX_RELEASE_JSON_BYTES)
                    parser.parse(bytes, limits.maxArchiveBytes)
                }
                HttpURLConnection.HTTP_NOT_FOUND -> fail(BundleFailureCode.RELEASE_NOT_FOUND)
                HTTP_RATE_LIMITED, HTTP_TOO_MANY_REQUESTS ->
                    fail(BundleFailureCode.RELEASE_RATE_LIMITED)
                else -> fail(BundleFailureCode.RELEASE_REQUEST_FAILED)
            }
        } catch (exception: BundleValidationException) {
            throw exception
        } catch (exception: IOException) {
            fail(BundleFailureCode.NETWORK_UNAVAILABLE, exception)
        } catch (exception: RuntimeException) {
            fail(BundleFailureCode.RELEASE_RESPONSE_INVALID, exception)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(
        profile: GameProfile,
        asset: ReleaseAsset,
        destination: File,
        progress: DownloadProgressSink,
    ): DownloadResult {
        if (asset.id <= 0 || asset.size !in 1..limits.maxArchiveBytes) {
            fail(BundleFailureCode.RELEASE_ASSET_INVALID)
        }
        val repository = RemoteRepositoryCatalog.repository(profile)
        val endpoint = URL("https://api.github.com/repos/$OWNER/$repository/releases/assets/${asset.id}")
        val connection = try {
            openAssetFollowingRedirects(endpoint)
        } catch (exception: BundleValidationException) {
            throw exception
        } catch (exception: IOException) {
            fail(BundleFailureCode.NETWORK_UNAVAILABLE, exception)
        } catch (exception: RuntimeException) {
            fail(BundleFailureCode.DOWNLOAD_FAILED, exception)
        }
        return try {
            val contentLength = connection.contentLengthLong
            if (contentLength > limits.maxArchiveBytes ||
                contentLength >= 0 && contentLength != asset.size
            ) {
                fail(BundleFailureCode.DOWNLOAD_SIZE_MISMATCH)
            }
            downloadBody(connection, destination, asset.size, progress)
        } catch (exception: BundleValidationException) {
            throw exception
        } catch (exception: IOException) {
            fail(BundleFailureCode.NETWORK_UNAVAILABLE, exception)
        } catch (exception: RuntimeException) {
            fail(BundleFailureCode.DOWNLOAD_FAILED, exception)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun openAssetFollowingRedirects(initial: URL): HttpURLConnection {
        var current = initial
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            coroutineContext.ensureActive()
            val connection = openConnection(current, BINARY_ACCEPT)
            val status = try {
                connection.responseCode
            } catch (exception: IOException) {
                connection.disconnect()
                throw exception
            }
            when (status) {
                HttpURLConnection.HTTP_OK -> return connection
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HTTP_SEE_OTHER,
                HTTP_TEMPORARY_REDIRECT,
                HTTP_PERMANENT_REDIRECT,
                -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (redirectCount >= MAX_REDIRECTS || location.isNullOrBlank()) {
                        fail(BundleFailureCode.DOWNLOAD_REDIRECT_INVALID)
                    }
                    current = URL(current, location)
                    validateRemoteUrl(current)
                }
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    connection.disconnect()
                    fail(BundleFailureCode.RELEASE_ASSET_INVALID)
                }
                HTTP_RATE_LIMITED, HTTP_TOO_MANY_REQUESTS -> {
                    connection.disconnect()
                    fail(BundleFailureCode.RELEASE_RATE_LIMITED)
                }
                else -> {
                    connection.disconnect()
                    fail(BundleFailureCode.DOWNLOAD_FAILED)
                }
            }
        }
        fail(BundleFailureCode.DOWNLOAD_REDIRECT_INVALID)
    }

    private suspend fun downloadBody(
        connection: HttpURLConnection,
        destination: File,
        expectedSize: Long,
        progress: DownloadProgressSink,
    ): DownloadResult {
        val digest = MessageDigest.getInstance("SHA-256")
        var processed = 0L
        var lastProgressAtNanos = 0L
        BufferedInputStream(connection.inputStream).use { input ->
            FileOutputStream(destination).use { fileOutput ->
                BufferedOutputStream(fileOutput).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        processed += read
                        if (processed > expectedSize || processed > limits.maxArchiveBytes) {
                            fail(BundleFailureCode.DOWNLOAD_SIZE_MISMATCH)
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        val now = System.nanoTime()
                        if (processed == expectedSize ||
                            now - lastProgressAtNanos >= PROGRESS_INTERVAL_NANOS
                        ) {
                            lastProgressAtNanos = now
                            progress.onProgress(processed, expectedSize)
                        }
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
        }
        if (processed != expectedSize) fail(BundleFailureCode.DOWNLOAD_SIZE_MISMATCH)
        return DownloadResult(digest.digest().toHex(), processed)
    }

    private fun openConnection(url: URL, accept: String): HttpURLConnection {
        validateRemoteUrl(url)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            doInput = true
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            setRequestProperty("User-Agent", USER_AGENT)
        }
    }

    private suspend fun readBounded(connection: HttpURLConnection, maximum: Long): ByteArray {
        val declared = connection.contentLengthLong
        if (declared > maximum) fail(BundleFailureCode.RELEASE_RESPONSE_INVALID)
        val output = ByteArrayOutputStream()
        BufferedInputStream(connection.inputStream).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > maximum) fail(BundleFailureCode.RELEASE_RESPONSE_INVALID)
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private companion object {
        const val OWNER = "UdonForMusashi"
        const val API_VERSION = "2022-11-28"
        const val USER_AGENT = "TranslationInstaller/0.2.0"
        const val JSON_ACCEPT = "application/vnd.github+json"
        const val BINARY_ACCEPT = "application/octet-stream"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_RELEASE_JSON_BYTES = 1L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val PROGRESS_INTERVAL_NANOS = 250_000_000L
        const val HTTP_SEE_OTHER = 303
        const val HTTP_TEMPORARY_REDIRECT = 307
        const val HTTP_PERMANENT_REDIRECT = 308
        const val HTTP_RATE_LIMITED = 403
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}

class ReleaseMetadataParser {
    fun parse(bytes: ByteArray, maxArchiveBytes: Long): ReleaseAsset {
        if (bytes.isEmpty() || bytes.size > MAX_JSON_BYTES) invalid()
        return try {
            JsonReader(
                InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8),
            ).use { reader ->
                reader.setStrictness(Strictness.STRICT)
                var tagName: String? = null
                var assets: List<ParsedAsset>? = null
                reader.readObject { name ->
                    when (name) {
                        "tag_name" -> tagName = reader.readLimitedString(MAX_TAG_LENGTH)
                        "assets" -> assets = reader.readAssets()
                        else -> reader.skipValue()
                    }
                }
                if (reader.peek() != JsonToken.END_DOCUMENT) invalid()
                val tag = tagName ?: invalid()
                if (!SAFE_TAG_NAME.matches(tag)) invalid()
                val releaseAssets = assets ?: invalid()
                if (releaseAssets.size != 1) invalidAsset()
                val asset = releaseAssets.single()
                if (asset.id <= 0 || asset.size !in 1..maxArchiveBytes ||
                    asset.state != "uploaded" || !SAFE_ZIP_NAME.matches(asset.name)
                ) {
                    invalidAsset()
                }
                ReleaseAsset(asset.id, asset.name, asset.size, tag)
            }
        } catch (exception: BundleValidationException) {
            throw exception
        } catch (exception: Exception) {
            fail(BundleFailureCode.RELEASE_RESPONSE_INVALID, exception)
        }
    }

    private fun JsonReader.readAssets(): List<ParsedAsset> {
        if (peek() != JsonToken.BEGIN_ARRAY) invalid()
        beginArray()
        val assets = mutableListOf<ParsedAsset>()
        while (hasNext()) {
            if (assets.size >= MAX_ASSETS) invalidAsset()
            assets += readAsset()
        }
        endArray()
        return assets
    }

    private fun JsonReader.readAsset(): ParsedAsset {
        var id: Long? = null
        var name: String? = null
        var size: Long? = null
        var state: String? = null
        readObject { field ->
            when (field) {
                "id" -> id = readUnsignedLong()
                "name" -> name = readLimitedString(MAX_ASSET_NAME_LENGTH)
                "size" -> size = readUnsignedLong()
                "state" -> state = readLimitedString(MAX_STATE_LENGTH)
                else -> skipValue()
            }
        }
        return ParsedAsset(
            id = id ?: invalid(),
            name = name ?: invalid(),
            size = size ?: invalid(),
            state = state ?: invalid(),
        )
    }

    private inline fun JsonReader.readObject(readField: (String) -> Unit) {
        if (peek() != JsonToken.BEGIN_OBJECT) invalid()
        beginObject()
        val seen = mutableSetOf<String>()
        while (hasNext()) {
            val name = nextName()
            if (!seen.add(name)) invalid()
            readField(name)
        }
        endObject()
    }

    private fun JsonReader.readLimitedString(maxLength: Int): String {
        if (peek() != JsonToken.STRING) invalid()
        val value = nextString()
        if (value.isEmpty() || value.length > maxLength || value.indexOf('\u0000') >= 0) invalid()
        return value
    }

    private fun JsonReader.readUnsignedLong(): Long {
        if (peek() != JsonToken.NUMBER) invalid()
        val value = nextString()
        if (!UNSIGNED_INTEGER.matches(value)) invalid()
        return value.toLongOrNull() ?: invalid()
    }

    private fun invalid(): Nothing = fail(BundleFailureCode.RELEASE_RESPONSE_INVALID)

    private fun invalidAsset(): Nothing = fail(BundleFailureCode.RELEASE_ASSET_INVALID)

    private data class ParsedAsset(
        val id: Long,
        val name: String,
        val size: Long,
        val state: String,
    )

    private companion object {
        const val MAX_JSON_BYTES = 1 * 1024 * 1024
        const val MAX_ASSETS = 32
        const val MAX_TAG_LENGTH = 128
        const val MAX_ASSET_NAME_LENGTH = 128
        const val MAX_STATE_LENGTH = 32
        val UNSIGNED_INTEGER = Regex("0|[1-9][0-9]*")
        val SAFE_TAG_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SAFE_ZIP_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,123}\\.zip", RegexOption.IGNORE_CASE)
    }
}

object RemoteRepositoryCatalog {
    fun repository(profile: GameProfile): String = when (profile) {
        GameProfile.NA -> "content-na"
        GameProfile.JP -> "content-jp"
    }
}

internal fun validateRemoteUrl(url: URL) {
    val uri = try {
        URI(url.toString())
    } catch (exception: Exception) {
        fail(BundleFailureCode.DOWNLOAD_REDIRECT_INVALID, exception)
    }
    val host = uri.host?.lowercase() ?: fail(BundleFailureCode.DOWNLOAD_REDIRECT_INVALID)
    if (uri.scheme != "https" || uri.userInfo != null || uri.fragment != null ||
        uri.port !in listOf(-1, 443) || host !in ALLOWED_REMOTE_HOSTS
    ) {
        fail(BundleFailureCode.DOWNLOAD_REDIRECT_INVALID)
    }
}

private fun fail(code: BundleFailureCode, cause: Throwable? = null): Nothing =
    throw BundleValidationException(code, cause)

private val ALLOWED_REMOTE_HOSTS = setOf(
    "api.github.com",
    "release-assets.githubusercontent.com",
)
