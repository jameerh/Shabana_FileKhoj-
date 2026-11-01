package com.shabana.filekhoj.data

import java.io.File

/**
 * Promotes a fully written backup without deleting the last known-good archive first.
 * Both files must live in the same directory so rename operations stay on one filesystem.
 */
internal object BackupFilePromotion {
    fun promote(temporary: File, destination: File): Boolean {
        val parent = destination.parentFile ?: return false
        if (temporary.canonicalFile.parentFile != parent.canonicalFile) return false

        val previous = File(parent, "${destination.name}.previous")

        // Recover the last good archive even if the replacement temp file is missing or invalid.
        if (!destination.exists() && previous.isFile) {
            if (!previous.renameTo(destination)) return false
        }
        if (!temporary.isFile || temporary.length() <= 0L) return false
        // A previous copy is only stale when the canonical destination is already present.
        if (destination.exists() && previous.exists() && !previous.delete()) return false

        val hadDestination = destination.isFile
        if (hadDestination && !destination.renameTo(previous)) return false

        if (temporary.renameTo(destination)) {
            // Failure to remove this extra copy is not a backup failure; the new backup is valid.
            previous.delete()
            return true
        }

        if (hadDestination && previous.exists()) {
            if (!previous.renameTo(destination)) {
                throw IllegalStateException(
                    "Could not publish the new backup; the previous backup remains at ${previous.absolutePath}."
                )
            }
        }
        return false
    }
}
