package dev.translate.installer.installer

import android.content.Context
import android.system.Os
import android.system.OsConstants
import dev.translate.installer.domain.GameProfile
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
import java.nio.file.StandardCopyOption

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
    private val directorySync: (File) -> Unit,
) {
    constructor(context: Context) : this(
        File(context.filesDir, RECEIPT_DIRECTORY),
        ::syncDirectory,
    )

    internal constructor(root: File, testOnly: Boolean) : this(root, directorySync = {}) {
        check(testOnly)
    }

    @Synchronized
    fun load(profile: GameProfile): InstallationReceipt? {
        ensureRoot()
        val file = receiptFile(profile)
        if (!file.exists()) return null
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() !in 1..MAX_RECEIPT_BYTES) {
            throw ReceiptStoreException()
        }
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
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
            FileOutputStream(partial).use { fileOutput ->
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
            moveReplacing(partial, destination)
            directorySync(root)
        } catch (exception: Exception) {
            partial.delete()
            throw ReceiptStoreException(exception)
        }
    }

    @Synchronized
    fun delete(profile: GameProfile) {
        ensureRoot()
        var deleted = false
        listOf(receiptFile(profile), partialFile(profile)).forEach { file ->
            if (file.exists()) {
                if (!file.isFile || Files.isSymbolicLink(file.toPath()) || !file.delete()) {
                    throw ReceiptStoreException()
                }
                deleted = true
            }
        }
        if (deleted) directorySync(root)
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
            SPECIAL_FILE !in recordsByName ||
            receipt.files.none { BIN_NAME.matches(it.name) }
        ) invalid()
        receipt.files.forEach { file ->
            if ((file.name != SPECIAL_FILE && !BIN_NAME.matches(file.name)) ||
                file.size < 0 || file.size > MAX_FILE_BYTES || !SHA_256.matches(file.sha256)
            ) invalid()
        }
    }

    private fun ensureRoot() {
        if (!root.exists()) {
            if (!root.mkdirs()) throw ReceiptStoreException()
            root.parentFile?.let(directorySync)
        }
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath()) ||
            root.canonicalFile != root.absoluteFile
        ) throw ReceiptStoreException()
    }

    private fun receiptFile(profile: GameProfile) = safeChild("receipt-${profile.profileId}.bin")
    private fun partialFile(profile: GameProfile) = safeChild("receipt-${profile.profileId}.partial")

    private fun safeChild(name: String): File {
        val child = File(root, name)
        if (child.canonicalFile.parentFile != root.canonicalFile) invalid()
        return child
    }

    private fun moveReplacing(source: File, destination: File) {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
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
        const val SPECIAL_FILE = "cfb1d36393fd67385e046b084b7cf7ed"
        val SAFE_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val BIN_NAME = Regex("[A-Za-z0-9_-]{1,128}\\.bin")
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

private fun syncDirectory(directory: File) {
    val descriptor = Os.open(
        directory.absolutePath,
        OsConstants.O_RDONLY,
        0,
    )
    try {
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}
