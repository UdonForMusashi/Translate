package dev.translate.installer.security

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Base64

class StrictJsonParsers {
    fun parseSignatureEnvelope(bytes: ByteArray): SignatureEnvelope = parse(bytes) { reader ->
        var schemaVersion: Long? = null
        var keyId: String? = null
        var algorithm: String? = null
        var signature: ByteArray? = null

        reader.readObject { name ->
            when (name) {
                "schemaVersion" -> schemaVersion = reader.readUnsignedLong()
                "keyId" -> keyId = reader.readLimitedString(64)
                "algorithm" -> algorithm = reader.readLimitedString(32)
                "signature" -> {
                    val encoded = reader.readLimitedString(256)
                    signature = try {
                        Base64.getDecoder().decode(encoded)
                    } catch (exception: IllegalArgumentException) {
                        fail(BundleFailureCode.SIGNATURE_ENVELOPE_INVALID, exception)
                    }
                }
                else -> fail(BundleFailureCode.SIGNATURE_ENVELOPE_INVALID)
            }
        }

        SignatureEnvelope(
            schemaVersion = schemaVersion.requiredEnvelope(),
            keyId = keyId.requiredEnvelope(),
            algorithm = algorithm.requiredEnvelope(),
            signature = signature.requiredEnvelope(),
        )
    }

    fun parseManifest(bytes: ByteArray): LocalBundleManifest = parse(bytes) { reader ->
        var schemaVersion: Long? = null
        var keyId: String? = null
        var profile: String? = null
        var profileId: String? = null
        var packageName: String? = null
        var destinationDirectory: String? = null
        var version: String? = null
        var createdAt: String? = null
        var fileCount: Long? = null
        var uncompressedSize: Long? = null
        var files: List<ManifestFile>? = null

        reader.readObject { name ->
            when (name) {
                "schemaVersion" -> schemaVersion = reader.readUnsignedLong()
                "keyId" -> keyId = reader.readLimitedString(64)
                "profile" -> profile = reader.readLimitedString(2)
                "profileId" -> profileId = reader.readLimitedString(32)
                "packageName" -> packageName = reader.readLimitedString(128)
                "destinationDirectory" -> destinationDirectory = reader.readLimitedString(64)
                "version" -> version = reader.readLimitedString(128)
                "createdAt" -> createdAt = reader.readLimitedString(64)
                "fileCount" -> fileCount = reader.readUnsignedLong()
                "uncompressedSize" -> uncompressedSize = reader.readUnsignedLong()
                "files" -> files = reader.readFiles()
                else -> fail(BundleFailureCode.MANIFEST_INVALID)
            }
        }

        LocalBundleManifest(
            schemaVersion = schemaVersion.requiredManifest(),
            keyId = keyId.requiredManifest(),
            profile = profile.requiredManifest(),
            profileId = profileId.requiredManifest(),
            packageName = packageName.requiredManifest(),
            destinationDirectory = destinationDirectory.requiredManifest(),
            version = version.requiredManifest(),
            createdAt = createdAt.requiredManifest(),
            fileCount = fileCount.requiredManifest(),
            uncompressedSize = uncompressedSize.requiredManifest(),
            files = files.requiredManifest(),
        )
    }

    private fun <T> parse(bytes: ByteArray, block: (JsonReader) -> T): T {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            fail(BundleFailureCode.JSON_INVALID)
        }

        return try {
            JsonReader(
                InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8),
            ).use { reader ->
                reader.setStrictness(Strictness.STRICT)
                val result = block(reader)
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    fail(BundleFailureCode.JSON_INVALID)
                }
                result
            }
        } catch (exception: BundleValidationException) {
            throw exception
        } catch (exception: Exception) {
            fail(BundleFailureCode.JSON_INVALID, exception)
        }
    }
}

private fun JsonReader.readFiles(): List<ManifestFile> {
    if (peek() != JsonToken.BEGIN_ARRAY) fail(BundleFailureCode.MANIFEST_INVALID)
    beginArray()
    val files = mutableListOf<ManifestFile>()
    while (hasNext()) {
        if (files.size >= 4_096) fail(BundleFailureCode.TOO_MANY_ENTRIES)
        files += readManifestFile()
    }
    endArray()
    return files
}

private fun JsonReader.readManifestFile(): ManifestFile {
    var name: String? = null
    var archiveEntry: String? = null
    var size: Long? = null
    var sha256: String? = null

    readObject { fieldName ->
        when (fieldName) {
            "name" -> name = readLimitedString(128)
            "archiveEntry" -> archiveEntry = readLimitedString(256)
            "size" -> size = readUnsignedLong()
            "sha256" -> sha256 = readLimitedString(64)
            else -> fail(BundleFailureCode.MANIFEST_INVALID)
        }
    }

    return ManifestFile(
        name = name.requiredManifest(),
        archiveEntry = archiveEntry.requiredManifest(),
        size = size.requiredManifest(),
        sha256 = sha256.requiredManifest(),
    )
}

private inline fun JsonReader.readObject(readField: (String) -> Unit) {
    if (peek() != JsonToken.BEGIN_OBJECT) fail(BundleFailureCode.JSON_INVALID)
    beginObject()
    val seen = mutableSetOf<String>()
    while (hasNext()) {
        val name = nextName()
        if (!seen.add(name)) fail(BundleFailureCode.JSON_INVALID)
        readField(name)
    }
    endObject()
}

private fun JsonReader.readLimitedString(maxLength: Int): String {
    if (peek() != JsonToken.STRING) fail(BundleFailureCode.JSON_INVALID)
    val value = nextString()
    if (value.isEmpty() || value.length > maxLength || value.indexOf('\u0000') >= 0) {
        fail(BundleFailureCode.JSON_INVALID)
    }
    return value
}

private fun JsonReader.readUnsignedLong(): Long {
    if (peek() != JsonToken.NUMBER) fail(BundleFailureCode.JSON_INVALID)
    val raw = nextString()
    if (!UNSIGNED_INTEGER.matches(raw)) fail(BundleFailureCode.JSON_INVALID)
    return raw.toLongOrNull() ?: fail(BundleFailureCode.JSON_INVALID)
}

private fun <T> T?.requiredManifest(): T = this ?: fail(BundleFailureCode.MANIFEST_INVALID)

private fun <T> T?.requiredEnvelope(): T =
    this ?: fail(BundleFailureCode.SIGNATURE_ENVELOPE_INVALID)

private val UNSIGNED_INTEGER = Regex("0|[1-9][0-9]*")
