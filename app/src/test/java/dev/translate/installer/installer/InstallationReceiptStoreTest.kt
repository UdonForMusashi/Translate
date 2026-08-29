package dev.translate.installer.installer

import dev.translate.installer.domain.GameProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstallationReceiptStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `persists separate validated receipts per profile`() {
        val store = store()
        val receipt = receipt(GameProfile.NA, ReceiptStatus.PENDING)

        store.save(receipt)
        assertEquals(receipt, store.load(GameProfile.NA))
        assertEquals(null, store.load(GameProfile.JP))

        val installed = receipt.withStatus(ReceiptStatus.INSTALLED)
        store.save(installed)
        assertEquals(installed, store.load(GameProfile.NA))
    }

    @Test fun `rejects corrupt receipt instead of exposing names for deletion`() {
        val root = temporaryFolder.newFolder("receipts")
        File(root, "receipt-${GameProfile.JP.profileId}.bin").writeText("attacker-controlled")
        val store = InstallationReceiptStore(root, testOnly = true)

        assertThrows(ReceiptStoreException::class.java) {
            store.load(GameProfile.JP)
        }
    }

    @Test fun `rejects duplicate unsafe or incomplete file sets`() {
        val store = store()
        val valid = receipt(GameProfile.JP, ReceiptStatus.PENDING)
        listOf(
            valid.copy(files = valid.files + valid.files.first()),
            valid.copy(files = listOf(InstalledFileRecord("../evil.bin", 1, HASH))),
            valid.copy(files = valid.files.filterNot { it.name == SPECIAL }),
        ).forEach { invalid ->
            assertThrows(ReceiptStoreException::class.java) { store.save(invalid) }
        }
    }

    @Test fun `pending receipt accepts two authenticated versions per name only`() {
        val store = store()
        val valid = receipt(GameProfile.JP, ReceiptStatus.PENDING)
        val withPreviousVersion = valid.copy(
            files = valid.files + InstalledFileRecord("translation.bin", 5, OTHER_HASH),
        )

        store.save(withPreviousVersion)
        assertEquals(withPreviousVersion, store.load(GameProfile.JP))

        assertThrows(ReceiptStoreException::class.java) {
            store.save(
                withPreviousVersion.copy(
                    files = withPreviousVersion.files +
                        InstalledFileRecord("translation.bin", 6, THIRD_HASH),
                ),
            )
        }
        assertThrows(ReceiptStoreException::class.java) {
            store.save(withPreviousVersion.copy(status = ReceiptStatus.INSTALLED))
        }
    }

    @Test fun `delete removes receipt and any private partial only`() {
        val root = temporaryFolder.newFolder("delete")
        val store = InstallationReceiptStore(root, testOnly = true)
        store.save(receipt(GameProfile.JP, ReceiptStatus.INSTALLED))
        val unrelated = File(root, "unrelated").apply { writeText("keep") }
        File(root, "receipt-${GameProfile.JP.profileId}.partial").writeText("stale")

        store.delete(GameProfile.JP)

        assertEquals(null, store.load(GameProfile.JP))
        assertTrue(unrelated.exists())
        assertFalse(File(root, "receipt-${GameProfile.JP.profileId}.partial").exists())
    }

    private fun store() = InstallationReceiptStore(
        temporaryFolder.newFolder("store-${System.nanoTime()}"),
        testOnly = true,
    )

    private fun receipt(profile: GameProfile, status: ReceiptStatus) = InstallationReceipt(
        profile = profile,
        bundleVersion = "2026.08.23",
        status = status,
        files = listOf(
            InstalledFileRecord("translation.bin", 3, HASH),
            InstalledFileRecord(SPECIAL, 4, HASH),
        ),
    )

    private companion object {
        const val SPECIAL = "cfb1d36393fd67385e046b084b7cf7ed"
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val THIRD_HASH = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
