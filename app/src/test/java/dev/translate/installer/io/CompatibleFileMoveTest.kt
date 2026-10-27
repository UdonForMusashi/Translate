package dev.translate.installer.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
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
}
