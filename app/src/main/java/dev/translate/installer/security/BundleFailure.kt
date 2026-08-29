package dev.translate.installer.security

import java.io.IOException

enum class BundleFailureCode {
    SOURCE_UNAVAILABLE,
    SOURCE_PERMISSION_DENIED,
    SOURCE_READ_FAILED,
    ARCHIVE_TOO_LARGE,
    ARCHIVE_INVALID,
    TOO_MANY_ENTRIES,
    REQUIRED_ENTRY_MISSING,
    DUPLICATE_ENTRY,
    UNEXPECTED_ENTRY,
    UNSAFE_ENTRY,
    UNSUPPORTED_COMPRESSION,
    COMPRESSION_RATIO_EXCEEDED,
    MANIFEST_TOO_LARGE,
    SIGNATURE_ENVELOPE_TOO_LARGE,
    JSON_INVALID,
    SIGNATURE_ENVELOPE_INVALID,
    SIGNING_KEY_UNKNOWN,
    SIGNATURE_INVALID,
    MANIFEST_INVALID,
    PROFILE_MISMATCH,
    PACKAGE_MISMATCH,
    FILE_COUNT_MISMATCH,
    UNCOMPRESSED_SIZE_MISMATCH,
    FILE_SIZE_MISMATCH,
    FILE_HASH_MISMATCH,
    INSUFFICIENT_STORAGE,
    IO_ERROR,
}

class BundleValidationException(
    val code: BundleFailureCode,
    cause: Throwable? = null,
) : IOException(code.name, cause)

internal fun fail(
    code: BundleFailureCode,
    cause: Throwable? = null,
): Nothing = throw BundleValidationException(code, cause)
