package dev.translate.installer.io

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.io.IOException

internal enum class FileReplacementMode {
    ATOMIC_MOVE,
    REPLACEMENT_MOVE,
    REPLACEMENT_COPY,
}

internal fun moveReplacingCompatible(
    source: Path,
    destination: Path,
    atomicMove: (Path, Path) -> Unit = { from, to ->
        Files.move(
            from,
            to,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    },
    replacementMove: (Path, Path) -> Unit = { from, to ->
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
    },
    replacementCopy: ((Path, Path) -> Unit)? = null,
): FileReplacementMode {
    try {
        atomicMove(source, destination)
        return FileReplacementMode.ATOMIC_MOVE
    } catch (exception: Exception) {
        if (!canRetryAtomicMove(exception)) throw exception
        return retryReplacementMove(
            source = source,
            destination = destination,
            atomicFailure = exception,
            replacementMove = replacementMove,
            replacementCopy = replacementCopy,
        )
    }
}

private fun retryReplacementMove(
    source: Path,
    destination: Path,
    atomicFailure: Exception,
    replacementMove: (Path, Path) -> Unit,
    replacementCopy: ((Path, Path) -> Unit)?,
): FileReplacementMode {
    try {
        replacementMove(source, destination)
        return FileReplacementMode.REPLACEMENT_MOVE
    } catch (exception: Exception) {
        exception.addSuppressed(atomicFailure)
        if (replacementCopy == null || !isStorageFeatureUnsupported(exception)) {
            throw exception
        }
        try {
            replacementCopy(source, destination)
            return FileReplacementMode.REPLACEMENT_COPY
        } catch (copyFailure: Exception) {
            copyFailure.addSuppressed(exception)
            throw copyFailure
        }
    }
}

internal fun syncFileCompatible(
    channel: FileChannel,
    sync: (FileChannel) -> Unit = { it.force(true) },
): Boolean = try {
    sync(channel)
    true
} catch (exception: Exception) {
    if (!isStorageFeatureUnsupported(exception)) throw exception
    false
}

internal fun isStorageFeatureUnsupported(exception: Throwable): Boolean {
    if (exception is UnsupportedOperationException ||
        exception is AtomicMoveNotSupportedException ||
        exception is FileAlreadyExistsException
    ) {
        return true
    }
    if (exception !is FileSystemException && exception !is IOException) return false
    val details = buildString {
        append(exception.message.orEmpty())
        if (exception is FileSystemException) {
            append(' ')
            append(exception.reason.orEmpty())
        }
    }.lowercase()
    return UNSUPPORTED_STORAGE_MESSAGES.any(details::contains)
}

private fun canRetryAtomicMove(exception: Exception): Boolean =
    exception is IOException || isStorageFeatureUnsupported(exception)

private val UNSUPPORTED_STORAGE_MESSAGES = listOf(
    "atomic move not supported",
    "cross-device",
    "exdev",
    "function not implemented",
    "invalid argument",
    "not supported",
    "operation unsupported",
    "unsupported operation",
)
