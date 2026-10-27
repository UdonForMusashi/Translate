package dev.translate.installer.privileged

import dev.translate.installer.domain.GameProfile
import dev.translate.installer.domain.ProfileContentPolicy
import dev.translate.installer.domain.decodeTechnical
import dev.translate.installer.io.moveReplacingCompatible
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

internal data class PrivilegedProfile(
    val code: Int,
    val packageName: String,
    val destinationRelativeDirectory: String,
    val contentPolicy: ProfileContentPolicy,
)

internal object PrivilegedProfiles {
    val JP = GameProfile.JP.toPrivilegedProfile(1)
    val NA = GameProfile.NA.toPrivilegedProfile(2)
    val BA = GameProfile.BA.toPrivilegedProfile(3)

    fun fromCode(code: Int): PrivilegedProfile = when (code) {
        JP.code -> JP
        NA.code -> NA
        BA.code -> BA
        else -> throw InstallEngineException(InstallError.INVALID_PROFILE)
    }

    private fun GameProfile.toPrivilegedProfile(code: Int) = PrivilegedProfile(
        code = code,
        packageName = packageName,
        destinationRelativeDirectory = destinationRelativeDirectory,
        contentPolicy = contentPolicy,
    )
}

internal enum class InstallError(val serviceCode: Int) {
    INVALID_CALLER(1),
    INVALID_PROFILE(2),
    INVALID_FILE_NAME(3),
    TARGET_UNAVAILABLE(4),
    TARGET_UNSAFE(5),
    TARGET_NOT_WRITABLE(6),
    SOURCE_SIZE_MISMATCH(7),
    SOURCE_HASH_MISMATCH(8),
    IO_FAILURE(9),
    INSUFFICIENT_STORAGE(10),
    TARGET_FILE_MISSING(11),
    TARGET_CHANGED(12),
    RECEIPT_INVALID(13),
}

internal class InstallEngineException(
    val error: InstallError,
    cause: Throwable? = null,
) : Exception(error.name, cause)

internal fun interface InstallProgress {
    fun report(phase: Int, fileName: String, processed: Long, total: Long)
}

internal data class PrivilegedInstalledFile(
    val name: String,
    val size: Long,
    val sha256: String,
)

internal class TransactionalFileEngine(
    private val rootResolver: (PrivilegedProfile) -> File,
    private val usableSpaceProvider: (File) -> Long = { it.usableSpace },
) {
    @Synchronized
    fun probe(profile: PrivilegedProfile, requiredBytes: Long) = guarded {
        val root = requireRoot(profile)
        cleanupManagedArtifacts(profile, root)
        ensureSpace(root, requiredBytes, reclaimableBytes = 0)
    }

    @Synchronized
    fun installFile(
        profile: PrivilegedProfile,
        fileName: String,
        input: FileInputStream,
        expectedSize: Long,
        expectedSha256: String,
        progress: InstallProgress,
    ) = guarded {
        val record = validateRecord(profile, fileName, expectedSize, expectedSha256)
        val root = requireRoot(profile)
        cleanupManagedArtifacts(profile, root)
        val target = safeChild(root, record.name)
        val targetExisted = Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (targetExisted) {
            requireExistingRegularFile(target)
        } else if (!profile.contentPolicy.mayCreateMissingFile(record.name)) {
            throw InstallEngineException(InstallError.TARGET_FILE_MISSING)
        }
        ensureSpace(root, record.size, reclaimableBytes = 0)

        verifySource(input, record)
        input.channel.position(0)

        val temporary = safeChild(root, atomicTemporaryName(record.name))
        deleteManagedTemporary(temporary)
        val targetPermissions = if (targetExisted) readPosixPermissions(target) else null
        try {
            FileChannel.open(
                temporary.toPath(),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW,
                LinkOption.NOFOLLOW_LINKS,
            ).use { output ->
                copyVerifiedSource(input.channel, output, record, progress)
                output.force(true)
            }
            verifyTarget(temporary, record)
            targetPermissions?.let { applyPosixPermissions(temporary, it) }

            if (targetExisted) {
                requireExistingRegularFile(target)
            } else if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                unsafe()
            }
            moveReplacingCompatible(temporary.toPath(), target.toPath())
            verifyTarget(target, record)
        } catch (exception: Exception) {
            deleteManagedTemporary(temporary)
            throw exception
        }
    }

    @Synchronized
    fun verifyInstalled(profile: PrivilegedProfile, records: List<PrivilegedInstalledFile>) = guarded {
        validateRecords(profile, records)
        val root = requireRoot(profile)
        cleanupManagedArtifacts(profile, root)
        records.forEach { record ->
            val target = safeChild(root, record.name)
            requireExistingRegularFile(target)
            verifyTarget(target, record)
        }
    }

    @Synchronized
    fun uninstall(
        profile: PrivilegedProfile,
        records: List<PrivilegedInstalledFile>,
        progress: InstallProgress,
    ) = guarded {
        validateRecords(profile, records, allowAlternatives = true)
        val root = requireRoot(profile)
        cleanupManagedArtifacts(profile, root)

        val recordsByName = records.groupBy { it.name }

        val removableNames = recordsByName.filter { (name, alternatives) ->
            val target = safeChild(root, name)
            if (target.exists()) {
                requireExistingRegularFile(target)
                alternatives.any { targetMatches(target, it) }
            } else {
                false
            }
        }.keys

        recordsByName.forEach { (name, alternatives) ->
            val target = safeChild(root, name)
            if (name in removableNames && target.exists()) {
                requireExistingRegularFile(target)
                if (alternatives.any { targetMatches(target, it) } && !target.delete()) io()
            }
            val progressSize = alternatives.maxOf { it.size }
            progress.report(PHASE_UNINSTALL, name, progressSize, progressSize)
        }
    }

    private fun verifySource(input: FileInputStream, record: PrivilegedInstalledFile) {
        val channel = input.channel
        channel.position(0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
        var processed = 0L
        while (true) {
            buffer.clear()
            val read = channel.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            processed += read
            if (processed > record.size || processed > MAX_FILE_BYTES) {
                throw InstallEngineException(InstallError.SOURCE_SIZE_MISMATCH)
            }
            buffer.flip()
            digest.update(buffer)
        }
        if (processed != record.size) {
            throw InstallEngineException(InstallError.SOURCE_SIZE_MISMATCH)
        }
        if (digest.digest().toHex() != record.sha256) {
            throw InstallEngineException(InstallError.SOURCE_HASH_MISMATCH)
        }
    }

    private fun copyVerifiedSource(
        source: FileChannel,
        destination: FileChannel,
        record: PrivilegedInstalledFile,
        progress: InstallProgress,
    ) {
        val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
        var processed = 0L
        var lastReported = 0L
        while (true) {
            buffer.clear()
            val read = source.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            processed += read
            if (processed > record.size) {
                throw InstallEngineException(InstallError.SOURCE_SIZE_MISMATCH)
            }
            buffer.flip()
            while (buffer.hasRemaining()) destination.write(buffer)
            if (processed == record.size || processed - lastReported >= PROGRESS_STEP) {
                lastReported = processed
                progress.report(PHASE_INSTALL, record.name, processed, record.size)
            }
        }
        if (processed != record.size) {
            throw InstallEngineException(InstallError.SOURCE_SIZE_MISMATCH)
        }
    }

    private fun validateRecords(
        profile: PrivilegedProfile,
        records: List<PrivilegedInstalledFile>,
        allowAlternatives: Boolean = false,
    ) {
        val recordsByName = records.groupBy { it.name }
        val maximumPerName = if (allowAlternatives) MAX_HASHES_PER_NAME else 1
        if (records.isEmpty() || records.size > MAX_FILES ||
            records.toSet().size != records.size ||
            recordsByName.values.any { it.size > maximumPerName }
        ) receiptInvalid()
        records.forEach { validateRecord(profile, it.name, it.size, it.sha256) }
        if (!profile.contentPolicy.isValidFileNameSet(recordsByName.keys)) receiptInvalid()
    }

    private fun validateRecord(
        profile: PrivilegedProfile,
        name: String,
        size: Long,
        hash: String,
    ): PrivilegedInstalledFile {
        requireFileName(profile, name)
        if (size < 0 || size > MAX_FILE_BYTES || !SHA_256.matches(hash)) receiptInvalid()
        return PrivilegedInstalledFile(name, size, hash)
    }

    private fun requireRoot(profile: PrivilegedProfile): File {
        val root = rootResolver(profile).absoluteFile
        if (!root.exists() || !root.isDirectory) {
            throw InstallEngineException(InstallError.TARGET_UNAVAILABLE)
        }
        if (Files.isSymbolicLink(root.toPath()) || root.canonicalFile != root) unsafe()
        if (!Files.isWritable(root.toPath())) {
            throw InstallEngineException(InstallError.TARGET_NOT_WRITABLE)
        }
        return root
    }

    private fun requireExistingRegularFile(file: File) {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw InstallEngineException(InstallError.TARGET_FILE_MISSING)
        }
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) unsafe()
    }

    private fun verifyTarget(file: File, record: PrivilegedInstalledFile) {
        if (file.length() != record.size || sha256(file) != record.sha256) {
            throw InstallEngineException(InstallError.SOURCE_HASH_MISMATCH)
        }
    }

    private fun targetMatches(file: File, record: PrivilegedInstalledFile): Boolean =
        file.length() == record.size && sha256(file) == record.sha256

    private fun readPosixPermissions(file: File): Set<PosixFilePermission>? = try {
        Files.getFileAttributeView(
            file.toPath(),
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )?.readAttributes()?.permissions()
    } catch (_: UnsupportedOperationException) {
        null
    }

    private fun applyPosixPermissions(file: File, permissions: Set<PosixFilePermission>) {
        Files.getFileAttributeView(
            file.toPath(),
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )?.setPermissions(permissions)
    }

    private fun ensureSpace(root: File, requiredBytes: Long, reclaimableBytes: Long) {
        if (requiredBytes < 0 || requiredBytes > MAX_TOTAL_BYTES || reclaimableBytes < 0) {
            receiptInvalid()
        }
        val usable = usableSpaceProvider(root)
        val effective = if (Long.MAX_VALUE - usable < reclaimableBytes) {
            Long.MAX_VALUE
        } else {
            usable + reclaimableBytes
        }
        if (usable <= 0 || effective < requiredBytes ||
            effective - requiredBytes < MINIMUM_FREE_BYTES
        ) {
            throw InstallEngineException(InstallError.INSUFFICIENT_STORAGE)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun safeChild(parent: File, name: String): File {
        if (name.contains('/') || name.contains('\\') || name.indexOf('\u0000') >= 0) unsafe()
        val child = File(parent, name)
        if (child.canonicalFile.parentFile != parent.canonicalFile) unsafe()
        return child
    }

    private fun requireFileName(profile: PrivilegedProfile, name: String) {
        if (!profile.contentPolicy.isAllowedFileName(name)) {
            throw InstallEngineException(InstallError.INVALID_FILE_NAME)
        }
    }

    private fun cleanupManagedArtifacts(profile: PrivilegedProfile, root: File) {
        val children = root.listFiles() ?: io()
        children.forEach { child ->
            val prefix = ATOMIC_TEMPORARY_PREFIXES.firstOrNull {
                child.name.startsWith(it) && child.name.endsWith(ATOMIC_TEMPORARY_SUFFIX)
            }
            val originalName = prefix?.let {
                child.name.removePrefix(it).removeSuffix(ATOMIC_TEMPORARY_SUFFIX)
            }
            if (originalName != null && profile.contentPolicy.isAllowedFileName(originalName)) {
                deleteManagedTemporary(child)
            }
        }
        if (profile.contentPolicy != ProfileContentPolicy.FGO_TRANSLATION) return
        children.forEach { child ->
            if (child.name in LEGACY_FIXED_NAMES || LEGACY_TRANSACTION.matches(child.name)) {
                safeDeleteLegacyTree(child)
            }
        }
    }

    private fun atomicTemporaryName(fileName: String) =
        "$ATOMIC_TEMPORARY_PREFIX$fileName$ATOMIC_TEMPORARY_SUFFIX"

    private fun deleteManagedTemporary(file: File) {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) unsafe()
        Files.delete(path)
    }

    private fun safeDeleteLegacyTree(root: File) {
        if (!root.exists()) return
        if (Files.isSymbolicLink(root.toPath())) {
            if (!root.delete()) io()
            return
        }
        if (root.isDirectory) {
            val children = root.listFiles() ?: io()
            children.forEach(::safeDeleteLegacyTree)
        }
        if (!root.delete()) io()
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (exception: InstallEngineException) {
        throw exception
    } catch (exception: SecurityException) {
        throw InstallEngineException(InstallError.TARGET_NOT_WRITABLE, exception)
    } catch (exception: Exception) {
        throw InstallEngineException(InstallError.IO_FAILURE, exception)
    }

    private fun unsafe(): Nothing = throw InstallEngineException(InstallError.TARGET_UNSAFE)
    private fun receiptInvalid(): Nothing = throw InstallEngineException(InstallError.RECEIPT_INVALID)
    private fun io(): Nothing = throw InstallEngineException(InstallError.IO_FAILURE)

    companion object {
        const val PHASE_INSTALL = 1
        const val PHASE_UNINSTALL = 2
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP = 1024L * 1024L
        private const val MAX_FILES = 4_094
        private const val MAX_HASHES_PER_NAME = 2
        private const val MAX_FILE_BYTES = 1024L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 4L * 1024L * 1024L * 1024L
        private const val MINIMUM_FREE_BYTES = 64L * 1024L * 1024L
        private const val ATOMIC_TEMPORARY_PREFIX = ".fate-cafe-install-"
        private val ATOMIC_TEMPORARY_PREFIXES = listOf(
            ATOMIC_TEMPORARY_PREFIX,
            ".barista-latte-install-",
        )
        private const val ATOMIC_TEMPORARY_SUFFIX = ".partial"
        private val SHA_256 = Regex("[0-9a-f]{64}")
        private val LEGACY_TRANSACTION = Regex(
            Regex.escape(decodeTechnical("LnRyYW5zbGF0ZWZnby10eG4t")) +
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
        )
        private val LEGACY_FIXED_NAMES = setOf(
            decodeTechnical("LnRyYW5zbGF0ZWZnby1sYXN0LWJhY2t1cA=="),
            decodeTechnical("LnRyYW5zbGF0ZWZnby1vYnNvbGV0ZS1iYWNrdXA="),
            decodeTechnical("LnRyYW5zbGF0ZWZnby1wcm9iZQ=="),
        )
    }
}

private fun ByteArray.toHex(): String = joinToString("") {
    "%02x".format(it.toInt() and 0xff)
}
