package dev.translate.installer.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption

class CompatibleFileMoveTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `falls back when Android refuses atomic replacement`() {
        listOf(
            FileAlreadyExistsException("receipt.bin"),
            AtomicMoveNotSupportedException("receipt.partial", "receipt.bin", "unsupported"),
            UnsupportedOperationException("ATOMIC_MOVE"),
        ).forEachIndexed { index, atomicFailure ->
            val root = temporaryFolder.newFolder("move-fallback-$index")
            val source = root.resolve("receipt.partial").apply { writeText("novo") }
            val destination = root.resolve("receipt.bin").apply { writeText("anterior") }
            var fallbackUsed = false

            moveReplacingCompatible(
                source = source.toPath(),
                destination = destination.toPath(),
                atomicMove = { _, _ -> throw atomicFailure },
                replacementMove = { from, to ->
                    fallbackUsed = true
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
                },
            )

            assertTrue(fallbackUsed)
            assertEquals("novo", destination.readText())
            assertFalse(source.exists())
        }
    }

    @Test fun `keeps previous file when both replacement attempts fail`() {
        val root = temporaryFolder.newFolder("move-double-failure")
        val source = root.resolve("receipt.partial").apply { writeText("novo") }
        val destination = root.resolve("receipt.bin").apply { writeText("anterior") }

        assertThrows(IOException::class.java) {
            moveReplacingCompatible(
                source = source.toPath(),
                destination = destination.toPath(),
                atomicMove = { _, _ -> throw FileAlreadyExistsException("receipt.bin") },
                replacementMove = { _, _ -> throw IOException("falha de escrita") },
            )
        }

        assertEquals("anterior", destination.readText())
        assertEquals("novo", source.readText())
    }

    @Test fun `retries a regular move after a generic atomic IOException`() {
        val root = temporaryFolder.newFolder("move-generic-io")
        val source = root.resolve("translation.partial").apply { writeText("novo") }
        val destination = root.resolve("translation.bin").apply { writeText("anterior") }

        val mode = moveReplacingCompatible(
            source = source.toPath(),
            destination = destination.toPath(),
            atomicMove = { _, _ -> throw IOException("atomic rename failed") },
        )

        assertEquals(FileReplacementMode.REPLACEMENT_MOVE, mode)
        assertEquals("novo", destination.readText())
        assertFalse(source.exists())
    }

    @Test fun `uses a copy fallback for an unsupported regular move`() {
        val root = temporaryFolder.newFolder("copy-fallback")
        val source = root.resolve("translation.partial").apply { writeText("novo") }
        val destination = root.resolve("translation.bin").apply { writeText("anterior") }

        val mode = moveReplacingCompatible(
            source = source.toPath(),
            destination = destination.toPath(),
            atomicMove = { _, _ -> throw IOException("atomic rename failed") },
            replacementMove = { from, to ->
                throw FileSystemException(from.toString(), to.toString(), "Invalid argument")
            },
            replacementCopy = { from, to ->
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
            },
        )

        assertEquals(FileReplacementMode.REPLACEMENT_COPY, mode)
        assertEquals("novo", destination.readText())
        assertTrue(source.exists())
    }

    @Test fun `does not turn a permission failure into a copy fallback`() {
        val root = temporaryFolder.newFolder("copy-permission-denied")
        val source = root.resolve("translation.partial").apply { writeText("novo") }
        val destination = root.resolve("translation.bin").apply { writeText("anterior") }
        var copyAttempted = false

        assertThrows(AccessDeniedException::class.java) {
            moveReplacingCompatible(
                source = source.toPath(),
                destination = destination.toPath(),
                atomicMove = { _, _ -> throw IOException("atomic rename failed") },
                replacementMove = { _, _ -> throw AccessDeniedException("translation.bin") },
                replacementCopy = { _, _ -> copyAttempted = true },
            )
        }

        assertFalse(copyAttempted)
        assertEquals("anterior", destination.readText())
        assertEquals("novo", source.readText())
    }

    @Test fun `continues after unsupported sync but propagates real IO failures`() {
        val file = temporaryFolder.newFile("sync.bin")
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
            assertFalse(
                syncFileCompatible(channel) {
                    throw FileSystemException(file.path, null, "Invalid argument")
                },
            )
            assertThrows(IOException::class.java) {
                syncFileCompatible(channel) { throw IOException("disk failure") }
            }
        }
    }
}
