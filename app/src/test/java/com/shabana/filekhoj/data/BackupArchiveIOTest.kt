package com.shabana.filekhoj.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupArchiveIOTest {
    @Test
    fun copiesArchiveWithinLimit() {
        val output = ByteArrayOutputStream()
        val copied = BackupArchiveIO.copyBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output, 3)
        assertEquals(3L, copied)
        assertEquals(3, output.size())
    }

    @Test
    fun rejectsArchiveThatExceedsLimitBeforeWritingTheOversizedChunk() {
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            BackupArchiveIO.copyBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), output, 3)
        }
        assertEquals(0, output.size())
    }
}
