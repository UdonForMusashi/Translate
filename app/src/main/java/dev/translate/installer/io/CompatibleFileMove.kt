package dev.translate.installer.io

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
) {
    try {
        atomicMove(source, destination)
    } catch (exception: AtomicMoveNotSupportedException) {
        retryReplacementMove(source, destination, exception, replacementMove)
    } catch (exception: FileAlreadyExistsException) {
        retryReplacementMove(source, destination, exception, replacementMove)
    } catch (exception: UnsupportedOperationException) {
        retryReplacementMove(source, destination, exception, replacementMove)
    }
}

private fun retryReplacementMove(
    source: Path,
    destination: Path,
    atomicFailure: Exception,
    replacementMove: (Path, Path) -> Unit,
) {
    try {
        replacementMove(source, destination)
    } catch (exception: Exception) {
        exception.addSuppressed(atomicFailure)
        throw exception
    }
}
