package dev.translate.installer.ui

import dev.translate.installer.data.remote.ReleaseAsset
import dev.translate.installer.data.remote.TransferMeter
import dev.translate.installer.installer.ReceiptStatus
import org.junit.Assert.*
import org.junit.Test

class TransferAndCatalogTest {
    @Test
    fun `resumed bytes do not inflate speed or ETA`() {
        val meter = TransferMeter()
        assertNull(meter.sample(4_000_000, 10_000_000, 1_000_000_000).first)
        val (speed, eta) = meter.sample(5_000_000, 10_000_000, 2_000_000_000)
        assertEquals(1_000_000.0, speed!!, .01)
        assertEquals(5L, eta)
    }

    @Test
    fun `update uses immutable asset identity instead of manifest versus tag`() {
        val asset = ReleaseAsset(2, "bundle.zip", 100, "tag-different-from-manifest")
        val summary =
            ProfileSummary(
                installedVersion = "signed-version",
                receiptStatus = ReceiptStatus.INSTALLED,
                latest = asset,
                installedAssetId = 2,
            )
        assertFalse(summary.updateAvailable)
        assertTrue(summary.copy(installedAssetId = 1).updateAvailable)
        assertFalse(summary.copy(installedAssetId = null).updateAvailable)
        assertFalse(
            summary
                .copy(receiptStatus = ReceiptStatus.PENDING, installedAssetId = 1)
                .updateAvailable
        )
    }

    @Test
    fun `rate resets when response restarts from zero`() {
        val meter = TransferMeter()
        meter.sample(1000, 10000, 1_000_000_000)
        meter.sample(2000, 10000, 2_000_000_000)
        val (speed, eta) = meter.sample(0, 10000, 3_000_000_000)
        assertNull(speed)
        assertNull(eta)
    }
}
