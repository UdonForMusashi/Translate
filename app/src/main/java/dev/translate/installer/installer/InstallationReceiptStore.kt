package dev.translate.installer.installer

import android.content.Context
import android.util.AtomicFile
import dev.translate.installer.domain.GameProfile
import dev.translate.installer.io.moveReplacingCompatible
import dev.translate.installer.security.LocalBundleManifest
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

enum class ReceiptStatus { PENDING, INSTALLED }

data class InstallationReceipt(
    val profile: GameProfile,
    val bundleVersion: String,
    val status: ReceiptStatus,
    val files: List<InstalledFileRecord>,
) {
    fun withStatus(newStatus: ReceiptStatus) = copy(status = newStatus)

    companion object {
        fun fromManifest(
            profile: GameProfile,
            manifest: LocalBundleManifest,
            status: ReceiptStatus = ReceiptStatus.PENDING,
        ) = InstallationReceipt(
            profile = profile,
            bundleVersion = manifest.version,
            status = status,
            files = manifest.files.map { InstalledFileRecord(it.name, it.size, it.sha256) },
        )

        fun pendingUpdate(
            profile: GameProfile,
            manifest: LocalBundleManifest,
            previous: InstallationReceipt?,
        ): InstallationReceipt {
            val currentFiles = manifest.files.map {
                InstalledFileRecord(it.name, it.size, it.sha256)
            }
            return InstallationReceipt(
                profile = profile,
                bundleVersion = manifest.version,
                status = ReceiptStatus.PENDING,
                files = (currentFiles + previous?.files.orEmpty()).distinct(),
            )
        }

        fun installedUpdate(
            profile: GameProfile,
            manifest: LocalBundleManifest,
            previous: InstallationReceipt?,
        ): InstallationReceipt {
            val currentFiles = manifest.files.map {
                InstalledFileRecord(it.name, it.size, it.sha256)
            }
            val currentNames = currentFiles.mapTo(hashSetOf()) { it.name }
            return InstallationReceipt(
                profile = profile,
                bundleVersion = manifest.version,
                status = ReceiptStatus.INSTALLED,
                files = currentFiles + previous?.files.orEmpty()
                    .filterNot { it.name in currentNames },
            )
        }
    }
}

class ReceiptStoreException(cause: Throwable? = null) : IOException("RECEIPT_INVALID", cause)

class InstallationReceiptStore private constructor(
    private val root: File,
    private val atomicFiles: ReceiptAtomicFiles,
) {
    constructor(context: Context) : this(
        File(context.filesDir, RECEIPT_DIRECTORY),
        AndroidReceiptAtomicFiles,
    )

    internal constructor(root: File, testOnly: Boolean) : this(root, PortableReceiptAtomicFiles) {
        check(testOnly)
    }

    @Synchronized
    fun load(profile: GameProfile): InstallationReceipt? {
        ensureRoot()
        val file = receiptFile(profile)
        val fileInput = try {
            atomicFiles.openRead(file) ?: return null
        } catch (exception: Exception) {
            throw ReceiptStoreException(exception)
        }
        return try {
            if (!file.isFile || Files.isSymbolicLink(file.toPath()) ||
                file.length() !in 1..MAX_RECEIPT_BYTES
            ) invalid()
            DataInputStream(BufferedInputStream(fileInput)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) invalid()
                val storedProfile = GameProfile.fromId(input.readUTF()) ?: invalid()
                if (storedProfile != profile) invalid()
                val status = runCatching { ReceiptStatus.valueOf(input.readUTF()) }.getOrElse { invalid() }
                val bundleVersion = input.readUTF()
                val count = input.readInt()
                if (count !in 1..MAX_FILES) invalid()
                val files = ArrayList<InstalledFileRecord>(count)
                repeat(count) {
                    files += InstalledFileRecord(
                        name = input.readUTF(),
                        size = input.readLong(),
                        sha256 = input.readUTF(),
                    )
                }
                if (input.read() != -1) invalid()
                InstallationReceipt(profile, bundleVersion, status, files).also(::validate)
            }
        } catch (exception: ReceiptStoreException) {
            throw exception
        } catch (exception: Exception) {
            throw ReceiptStoreException(exception)
        }
    }

    @Synchronized
    fun save(receipt: InstallationReceipt) {
        validate(receipt)
        ensureRoot()
        val destination = receiptFile(receipt.profile)
        val partial = partialFile(receipt.profile)
        if (partial.exists() && !partial.delete()) throw ReceiptStoreException()
        try {
            atomicFiles.write(destination) { fileOutput ->
                val buffered = BufferedOutputStream(fileOutput)
                val output = DataOutputStream(buffered)
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeUTF(receipt.profile.profileId)
                output.writeUTF(receipt.status.name)
                output.writeUTF(receipt.bundleVersion)
                output.writeInt(receipt.files.size)
                receipt.files.forEach { file ->
                    output.writeUTF(file.name)
                    output.writeLong(file.size)
                    output.writeUTF(file.sha256)
                }
                output.flush()
                fileOutput.fd.sync()
            }
            if (load(receipt.profile) != receipt) invalid()
        } catch (exception: Exception) {
            partial.delete()
            throw ReceiptStoreException(exception)
        }
    }

    @Synchronized
    fun delete(profile: GameProfile) {
        ensureRoot()
        val destination = receiptFile(profile)
        val partial = partialFile(profile)
        try {
            if (destination.exists() &&
                (!destination.isFile || Files.isSymbolicLink(destination.toPath()))
            ) invalid()
            atomicFiles.delete(destination)
            if (partial.exists() &&
                (!partial.isFile || Files.isSymbolicLink(partial.toPath()) || !partial.delete())
            ) {
                invalid()
            }
        } catch (exception: ReceiptStoreException) {
            throw exception
        } catch (exception: Exception) {
            throw ReceiptStoreException(exception)
        }
    }

    private fun validate(receipt: InstallationReceipt) {
        val recordsByName = receipt.files.groupBy { it.name }
        val allowedHashesPerName = when (receipt.status) {
            ReceiptStatus.PENDING -> MAX_PENDING_HASHES_PER_NAME
            ReceiptStatus.INSTALLED -> 1
        }
        if (!SAFE_VERSION.matches(receipt.bundleVersion) ||
            receipt.files.isEmpty() || receipt.files.size > MAX_FILES ||
            receipt.files.toSet().size != receipt.files.size ||
            recordsByName.values.any { it.size > allowedHashesPerName } ||
            !receipt.profile.contentPolicy.isValidFileNameSet(recordsByName.keys)
        ) invalid()
        receipt.files.forEach { file ->
            if (!receipt.profile.contentPolicy.isAllowedFileName(file.name) ||
                file.size < 0 || file.size > MAX_FILE_BYTES || !SHA_256.matches(file.sha256)
            ) invalid()
        }
    }

    private fun ensureRoot() {
        if (!root.exists()) {
            if (!root.mkdirs()) throw ReceiptStoreException()
        }
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) throw ReceiptStoreException()
    }

    private fun receiptFile(profile: GameProfile) = safeChild("receipt-${profile.profileId}.bin")
    private fun partialFile(profile: GameProfile) = safeChild("receipt-${profile.profileId}.partial")

    private fun safeChild(name: String): File {
        val child = File(root, name)
        if (child.canonicalFile.parentFile != root.canonicalFile) invalid()
        return child
    }

    private fun invalid(): Nothing = throw ReceiptStoreException()

    private companion object {
        const val RECEIPT_DIRECTORY = "installation-receipts"
        const val MAGIC = 0x5446474f
        const val FORMAT_VERSION = 1
        const val MAX_RECEIPT_BYTES = 1024L * 1024L
        const val MAX_FILES = 4_094
        const val MAX_PENDING_HASHES_PER_NAME = 2
        const val MAX_FILE_BYTES = 1024L * 1024L * 1024L
        val SAFE_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

private interface ReceiptAtomicFiles {
    fun openRead(destination: File): FileInputStream?
    fun write(destination: File, content: (FileOutputStream) -> Unit)
    fun delete(destination: File)
}

private object AndroidReceiptAtomicFiles : ReceiptAtomicFiles {
    override fun openRead(destination: File): FileInputStream? {
        val atomicFile = AtomicFile(destination)
        val legacyBackup = File(destination.path + ".bak")
        return if (destination.exists() || legacyBackup.exists()) {
            atomicFile.openRead()
        } else {
            null
        }
    }

    override fun write(destination: File, content: (FileOutputStream) -> Unit) {
        val atomicFile = AtomicFile(destination)
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            content(output)
            atomicFile.finishWrite(output)
            output = null
        } catch (exception: Exception) {
            atomicFile.failWrite(output)
            throw exception
        }
    }

    override fun delete(destination: File) {
        val atomicFile = AtomicFile(destination)
        atomicFile.delete()
        val leftovers = listOf(
            destination,
            File(destination.path + ".new"),
            File(destination.path + ".bak"),
        )
        if (leftovers.any(File::exists)) throw IOException("Atomic receipt deletion failed")
    }
}

private object PortableReceiptAtomicFiles : ReceiptAtomicFiles {
    override fun openRead(destination: File): FileInputStream? =
        destination.takeIf(File::isFile)?.let(::FileInputStream)

    override fun write(destination: File, content: (FileOutputStream) -> Unit) {
        val temporary = File(destination.path + ".new")
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("Temporary receipt deletion failed")
        }
        try {
            FileOutputStream(temporary).use(content)
            moveReplacingCompatible(temporary.toPath(), destination.toPath())
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    override fun delete(destination: File) {
        listOf(destination, File(destination.path + ".new"), File(destination.path + ".bak"))
            .forEach { file ->
                if (file.exists() && !file.delete()) throw IOException("Receipt deletion failed")
            }
    }
}
