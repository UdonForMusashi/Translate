package dev.translate.installer.security

import android.annotation.SuppressLint
import java.io.File

@SuppressLint("UsableSpace")
internal fun ensureSufficientStorage(
    directory: File,
    requiredBytes: Long,
    limits: ImportLimits,
) {
    if (!directory.isDirectory || requiredBytes < 0) {
        fail(BundleFailureCode.IO_ERROR)
    }
    val usableBytes = directory.usableSpace
    if (usableBytes <= 0 ||
        usableBytes < limits.minimumFreeBytes ||
        requiredBytes > usableBytes - limits.minimumFreeBytes
    ) {
        fail(BundleFailureCode.INSUFFICIENT_STORAGE)
    }
}
