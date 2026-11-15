package dev.translate.installer.data.remote

import dev.translate.installer.domain.GameProfile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files

/** Untrusted transport cache only. These bytes still go through signature and file verification. */
class ResumeCheckpoint(root: File, profile: GameProfile) {
    private val directory = File(root, profile.bundleProfile.profileId)
    val partial = File(directory, "bundle.partial")
    private val metadata = File(directory, "identity")

    init {
        check(root.isDirectory || root.mkdirs() || root.isDirectory)
        check(!Files.isSymbolicLink(root.toPath()))
        check(directory.isDirectory || directory.mkdir() || directory.isDirectory)
        check(!Files.isSymbolicLink(directory.toPath()))
        check(directory.canonicalFile.parentFile == root.canonicalFile)
    }

    fun prepare(asset: ReleaseAsset): String? {
        val existing =
            runCatching {
                    if (
                        !metadata.isFile ||
                            metadata.length() > 2048 ||
                            Files.isSymbolicLink(metadata.toPath()) ||
                            Files.isSymbolicLink(partial.toPath())
                    )
                        return@runCatching null
                    DataInputStream(metadata.inputStream().buffered()).use {
                        val stored =
                            ReleaseAsset(it.readLong(), it.readUTF(), it.readLong(), it.readUTF())
                        val validator = it.readUTF().takeIf(::strongETag)
                        if (
                            stored == asset &&
                                it.read() == -1 &&
                                partial.isFile &&
                                partial.length() <= asset.size
                        )
                            validator
                        else null
                    }
                }
                .getOrNull()
        // A complete file can also be reused after the latest release identity was confirmed.
        val identityMatches =
            runCatching {
                    if (
                        !metadata.isFile ||
                            metadata.length() > 2048 ||
                            Files.isSymbolicLink(metadata.toPath()) ||
                            Files.isSymbolicLink(partial.toPath())
                    )
                        return@runCatching false
                    DataInputStream(metadata.inputStream().buffered()).use {
                        ReleaseAsset(it.readLong(), it.readUTF(), it.readLong(), it.readUTF()) ==
                            asset
                    }
                }
                .getOrDefault(false)
        if (
            !identityMatches ||
                !partial.isFile ||
                partial.length() > asset.size ||
                (partial.length() < asset.size && existing == null)
        )
            clear()
        return existing
    }

    fun record(asset: ReleaseAsset, etag: String?) {
        val temporary = File(directory, "identity.tmp")
        DataOutputStream(temporary.outputStream().buffered()).use {
            it.writeLong(asset.id)
            it.writeUTF(asset.name)
            it.writeLong(asset.size)
            it.writeUTF(asset.tagName)
            it.writeUTF(etag?.takeIf(::strongETag).orEmpty())
        }
        check(!metadata.exists() || metadata.delete())
        check(temporary.renameTo(metadata))
    }

    fun clear() {
        check(!partial.exists() || partial.delete())
        check(!metadata.exists() || metadata.delete())
    }
}

internal fun strongETag(value: String): Boolean =
    value.length in 2..512 &&
        value.startsWith('"') &&
        value.endsWith('"') &&
        value.none { it == '\r' || it == '\n' }

internal fun validContentRange(header: String?, offset: Long, total: Long): Boolean {
    val match =
        Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(header.orEmpty()) ?: return false
    val start = match.groupValues[1].toLongOrNull()
    val end = match.groupValues[2].toLongOrNull()
    val size = match.groupValues[3].toLongOrNull()
    return start == offset && end == total - 1 && size == total
}
