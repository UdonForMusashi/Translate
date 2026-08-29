package dev.translate.installer.security

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

internal fun preflightZipCentralDirectory(
    archiveFile: File,
    limits: ImportLimits,
) {
    try {
        RandomAccessFile(archiveFile, "r").use { archive ->
            val archiveLength = archive.length()
            if (archiveLength < END_OF_CENTRAL_DIRECTORY_MIN_SIZE) {
                fail(BundleFailureCode.ARCHIVE_INVALID)
            }
            archive.seek(0)
            if (archive.readLittleEndianUnsignedInt() != LOCAL_FILE_HEADER_SIGNATURE) {
                fail(BundleFailureCode.ARCHIVE_INVALID)
            }

            val tailSize = min(archiveLength, MAX_END_SEARCH_BYTES).toInt()
            val tail = ByteArray(tailSize)
            val tailOffset = archiveLength - tailSize
            archive.seek(tailOffset)
            archive.readFully(tail)

            val eocdIndex = findEndOfCentralDirectory(tail)
            val diskNumber = tail.unsignedShort(eocdIndex + 4)
            val centralDirectoryDisk = tail.unsignedShort(eocdIndex + 6)
            val entriesOnDisk = tail.unsignedShort(eocdIndex + 8)
            val totalEntries = tail.unsignedShort(eocdIndex + 10)
            val centralDirectorySize = tail.unsignedInt(eocdIndex + 12)
            val centralDirectoryOffset = tail.unsignedInt(eocdIndex + 16)
            val commentLength = tail.unsignedShort(eocdIndex + 20)
            val eocdAbsoluteOffset = tailOffset + eocdIndex

            if (diskNumber != 0 ||
                centralDirectoryDisk != 0 ||
                entriesOnDisk != totalEntries ||
                totalEntries == ZIP64_UNSIGNED_SHORT_SENTINEL ||
                centralDirectorySize == ZIP64_UNSIGNED_INT_SENTINEL ||
                centralDirectoryOffset == ZIP64_UNSIGNED_INT_SENTINEL ||
                commentLength != 0
            ) {
                fail(BundleFailureCode.ARCHIVE_INVALID)
            }
            if (totalEntries > limits.maxArchiveEntries) {
                fail(BundleFailureCode.TOO_MANY_ENTRIES)
            }
            if (centralDirectorySize > limits.maxCentralDirectoryBytes ||
                centralDirectoryOffset > eocdAbsoluteOffset ||
                centralDirectorySize > eocdAbsoluteOffset - centralDirectoryOffset ||
                centralDirectoryOffset + centralDirectorySize != eocdAbsoluteOffset
            ) {
                fail(BundleFailureCode.ARCHIVE_INVALID)
            }
        }
    } catch (exception: BundleValidationException) {
        throw exception
    } catch (exception: Exception) {
        fail(BundleFailureCode.ARCHIVE_INVALID, exception)
    }
}

private fun findEndOfCentralDirectory(tail: ByteArray): Int {
    for (index in tail.size - END_OF_CENTRAL_DIRECTORY_MIN_SIZE downTo 0) {
        if (tail.unsignedInt(index) != END_OF_CENTRAL_DIRECTORY_SIGNATURE) continue
        val commentLength = tail.unsignedShort(index + 20)
        if (index + END_OF_CENTRAL_DIRECTORY_MIN_SIZE + commentLength == tail.size) {
            return index
        }
    }
    fail(BundleFailureCode.ARCHIVE_INVALID)
}

private fun RandomAccessFile.readLittleEndianUnsignedInt(): Long {
    val bytes = ByteArray(4)
    readFully(bytes)
    return bytes.unsignedInt(0)
}

private fun ByteArray.unsignedShort(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.unsignedInt(offset: Int): Long =
    unsignedShort(offset).toLong() or
        (unsignedShort(offset + 2).toLong() shl 16)

private const val END_OF_CENTRAL_DIRECTORY_MIN_SIZE = 22
private const val MAX_END_SEARCH_BYTES = 65_557L
private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50L
private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50L
private const val ZIP64_UNSIGNED_SHORT_SENTINEL = 0xffff
private const val ZIP64_UNSIGNED_INT_SENTINEL = 0xffffffffL
