package com.shabana.filekhoj.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFilePromotionTest {
    @Test
    fun invalidTemporaryArchiveDoesNotReplacePreviousBackup() {
        val directory = createTempDirectory()
        try {
            val destination = File(directory, "latest.zip").apply { writeText("known-good") }
            val temporary = File(directory, "working.zip").apply { writeText("") }

            assertFalse(BackupFilePromotion.promote(temporary, destination))
            assertEquals("known-good", destination.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun validTemporaryArchiveReplacesPreviousBackup() {
        val directory = createTempDirectory()
        try {
            val destination = File(directory, "latest.zip").apply { writeText("old") }
            val temporary = File(directory, "working.zip").apply { writeText("new-valid-archive") }

            assertTrue(BackupFilePromotion.promote(temporary, destination))
            assertEquals("new-valid-archive", destination.readText())
            assertFalse(File(directory, "latest.zip.previous").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun publishesFirstBackupWhenDestinationDoesNotExist() {
        val directory = createTempDirectory()
        try {
            val destination = File(directory, "latest.zip")
            val temporary = File(directory, "working.zip").apply { writeText("first-valid-archive") }

            assertTrue(BackupFilePromotion.promote(temporary, destination))
            assertEquals("first-valid-archive", destination.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createTempDirectory(): File =
        File(System.getProperty("java.io.tmpdir"), "filekhoj-backup-test-${System.nanoTime()}").apply {
            check(mkdirs())
        }
}
