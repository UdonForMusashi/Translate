package dev.translate.installer.data.importer

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import dev.translate.installer.audit.MessageCode
import dev.translate.installer.audit.OperationPhase
import dev.translate.installer.audit.OperationStatus
import dev.translate.installer.audit.TransactionEventEmitter
import dev.translate.installer.domain.GameProfile
import dev.translate.installer.security.BundleFailureCode
import dev.translate.installer.security.BundleValidationException
import dev.translate.installer.security.ImportLimits
import dev.translate.installer.security.ensureSufficientStorage
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class LocalBundleImporter(
    context: Context,
    private val stager: BundleStager,
    private val limits: ImportLimits = ImportLimits(),
) {
    private val resolver: ContentResolver = context.contentResolver

    suspend fun import(
        source: Uri,
        transactionId: String,
        selectedProfile: GameProfile,
        events: TransactionEventEmitter,
    ): ImportedBundle {
        if (source.scheme != ContentResolver.SCHEME_CONTENT) {
            throw BundleValidationException(BundleFailureCode.SOURCE_UNAVAILABLE)
        }
        val displayName = querySafeDisplayName(source)
        val declaredSourceSize = querySizeForProgress(source)

        return stager.stage(transactionId, selectedProfile, events) { partial ->
            ensureSufficientStorage(
                directory = partial.parentFile ?: partial,
                requiredBytes = declaredSourceSize ?: 0,
                limits = limits,
            )
            events.emit(
                phase = OperationPhase.IMPORT,
                status = OperationStatus.STARTED,
                messageCode = MessageCode.SOURCE_COPY_STARTED,
                fileRelativePath = displayName,
                bytesTotal = declaredSourceSize,
            )
            val archiveHash = copySource(
                source = source,
                destination = partial,
                displayName = displayName,
                declaredSize = declaredSourceSize,
                events = events,
            )
            events.emit(
                phase = OperationPhase.IMPORT,
                status = OperationStatus.SUCCEEDED,
                messageCode = MessageCode.SOURCE_COPY_SUCCEEDED,
                fileRelativePath = displayName,
                bytesProcessed = partial.length(),
                bytesTotal = partial.length(),
            )
            BundleSourceResult(
                sha256 = archiveHash,
                displayName = displayName,
            )
        }
    }

    private suspend fun copySource(
        source: Uri,
        destination: File,
        displayName: String,
        declaredSize: Long?,
        events: TransactionEventEmitter,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var processed = 0L
        var lastProgressAtNanos = 0L
        var lastStorageCheckAt = 0L
        val sourceStream = try {
            resolver.openInputStream(source)
        } catch (exception: SecurityException) {
            throw BundleValidationException(
                BundleFailureCode.SOURCE_PERMISSION_DENIED,
                exception,
            )
        } catch (exception: IOException) {
            throw BundleValidationException(BundleFailureCode.SOURCE_READ_FAILED, exception)
        } catch (exception: RuntimeException) {
            throw BundleValidationException(BundleFailureCode.SOURCE_READ_FAILED, exception)
        } ?: throw BundleValidationException(BundleFailureCode.SOURCE_UNAVAILABLE)

        BufferedInputStream(sourceStream).use { input ->
            FileOutputStream(destination).use { fileOutput ->
                val output = BufferedOutputStream(fileOutput)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = try {
                        input.read(buffer)
                    } catch (exception: SecurityException) {
                        throw BundleValidationException(
                            BundleFailureCode.SOURCE_PERMISSION_DENIED,
                            exception,
                        )
                    } catch (exception: IOException) {
                        throw BundleValidationException(
                            BundleFailureCode.SOURCE_READ_FAILED,
                            exception,
                        )
                    }
                    if (read < 0) break
                    if (read == 0) continue
                    processed += read
                    if (processed > limits.maxArchiveBytes) {
                        throw BundleValidationException(BundleFailureCode.ARCHIVE_TOO_LARGE)
                    }
                    if (processed - lastStorageCheckAt >= limits.storageCheckIntervalBytes) {
                        ensureSufficientStorage(
                            directory = destination.parentFile ?: destination,
                            requiredBytes = 0,
                            limits = limits,
                        )
                        lastStorageCheckAt = processed
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)

                    val now = System.nanoTime()
                    if (now - lastProgressAtNanos >= PROGRESS_INTERVAL_NANOS) {
                        lastProgressAtNanos = now
                        events.emit(
                            phase = OperationPhase.IMPORT,
                            status = OperationStatus.PROGRESS,
                            messageCode = MessageCode.SOURCE_COPY_PROGRESS,
                            fileRelativePath = displayName,
                            bytesProcessed = processed,
                            bytesTotal = declaredSize,
                        )
                    }
                }
                output.flush()
                fileOutput.fd.sync()
            }
        }
        return digest.digest().toHex()
    }

    private fun querySafeDisplayName(source: Uri): String {
        val raw = resolver.querySingle(source, OpenableColumns.DISPLAY_NAME) { cursor, index ->
            cursor.getString(index)
        } ?: "pacote.zip"
        val sanitized = raw
            .take(MAX_DISPLAY_NAME_LENGTH)
            .map { character ->
                if (character in 'A'..'Z' ||
                    character in 'a'..'z' ||
                    character in '0'..'9' ||
                    character in "._-"
                ) {
                    character
                } else {
                    '_'
                }
            }
            .joinToString("")
        return sanitized.ifBlank { "pacote.zip" }
    }

    private fun querySizeForProgress(source: Uri): Long? {
        val size = resolver.querySingle(source, OpenableColumns.SIZE) { cursor, index ->
            if (cursor.isNull(index)) null else cursor.getLong(index)
        }
        return size?.takeIf { it in 1..limits.maxArchiveBytes }
    }

    private fun <T> ContentResolver.querySingle(
        uri: Uri,
        column: String,
        read: (Cursor, Int) -> T?,
    ): T? = try {
        query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(column)
            if (index >= 0 && cursor.moveToFirst()) read(cursor, index) else null
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 128
        const val PROGRESS_INTERVAL_NANOS = 250_000_000L
    }
}
