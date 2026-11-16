package dev.translate.installer.ui

import dev.translate.installer.data.remote.ReleaseAsset
import dev.translate.installer.installer.ReceiptStatus
import dev.translate.installer.security.BundleFailureCode

data class ProfileSummary(
    val installedVersion: String? = null,
    val receiptStatus: ReceiptStatus? = null,
    val latest: ReleaseAsset? = null,
    val checking: Boolean = false,
    val lookupFailure: BundleFailureCode? = null,
    val receiptInvalid: Boolean = false,
    val installedAssetId: Long? = null,
) {
    val updateAvailable: Boolean
        get() =
            receiptStatus == ReceiptStatus.INSTALLED &&
                installedVersion != null &&
                latest != null &&
                lookupFailure == null &&
                installedAssetId != null &&
                installedAssetId != latest.id
}
