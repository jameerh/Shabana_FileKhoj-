package com.shabana.filekhoj.data

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Bounds compressed archive copies before untrusted ZIP bytes can exhaust app storage.
 * Restore also enforces limits on expanded entries independently.
 */
internal object BackupArchiveIO {
    const val MAX_ARCHIVE_BYTES = 1088L * 1024L * 1024L

    @Throws(IOException::class)
    fun copyBounded(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = MAX_ARCHIVE_BYTES
    ): Long {
        require(maxBytes >= 0L) { "Archive size limit cannot be negative." }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (total > maxBytes - count) {
                throw IOException("Backup archive exceeds the allowed file size.")
            }
            output.write(buffer, 0, count)
            total += count
        }
        return total
    }
}
