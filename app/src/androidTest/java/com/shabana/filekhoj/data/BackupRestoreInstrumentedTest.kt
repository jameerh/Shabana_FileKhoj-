package com.shabana.filekhoj.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRestoreInstrumentedTest {
    @Test
    fun validBackupRestoresPayloadAndMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = DocumentDatabase.getInstance(context).documentDao()
        val manager = BackupRestoreManager(context)
        val token = UUID.randomUUID().toString()
        val source = File(context.filesDir, "documents/backup-test-$token.txt")
        val archive = File(context.cacheDir, "backup-test-$token.zip")
        val beforeIds = dao.getAllForBackup().map { it.id }.toSet()
        try {
            check(source.parentFile?.mkdirs() == true || source.parentFile?.isDirectory == true)
            source.writeText("Shabana FileKhoj backup restore test")
            val sourceId = dao.insert(
                DocumentEntity(
                    displayName = "backup-test-$token.txt",
                    mimeType = "text/plain",
                    sizeBytes = source.length(),
                    sourceUri = "content://filekhoj-test/$token",
                    dateAdded = System.currentTimeMillis(),
                    sourceLastModified = null,
                    category = "test",
                    managedPath = source.absolutePath,
                    isFavourite = true
                )
            )
            assertTrue(sourceId > 0L)
            manager.createBackup(archive)

            val result = manager.restoreBackup(archive)
            assertEquals(1, result.restored)
            assertEquals(0, result.skipped)
            val added = dao.getAllForBackup().filter { it.id !in beforeIds && it.id != sourceId }
            assertEquals(1, added.size)
            assertEquals("backup-test-$token.txt", added.single().displayName)
            assertEquals("test", added.single().category)
            assertTrue(added.single().isFavourite)
            assertEquals("Shabana FileKhoj backup restore test", File(added.single().managedPath!!).readText())
        } finally {
            cleanup(context, dao, beforeIds)
            archive.delete()
            source.delete()
        }
    }

    @Test
    fun tamperedPayloadIsRejectedBeforeAnyRestoreRowsAreCommitted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = DocumentDatabase.getInstance(context).documentDao()
        val manager = BackupRestoreManager(context)
        val token = UUID.randomUUID().toString()
        val source = File(context.filesDir, "documents/backup-tamper-$token.txt")
        val archive = File(context.cacheDir, "backup-tamper-$token.zip")
        val tampered = File(context.cacheDir, "backup-tamper-modified-$token.zip")
        val beforeIds = dao.getAllForBackup().map { it.id }.toSet()
        try {
            check(source.parentFile?.mkdirs() == true || source.parentFile?.isDirectory == true)
            source.writeText("Original payload used to verify SHA-256 integrity")
            val sourceId = dao.insert(
                DocumentEntity(
                    displayName = "backup-tamper-$token.txt",
                    mimeType = "text/plain",
                    sizeBytes = source.length(),
                    sourceUri = "content://filekhoj-test/$token",
                    dateAdded = System.currentTimeMillis(),
                    sourceLastModified = null,
                    managedPath = source.absolutePath
                )
            )
            assertTrue(sourceId > 0L)
            manager.createBackup(archive)
            rewriteWithTamperedPayload(archive, tampered)

            val idsBeforeRestore = dao.getAllForBackup().map { it.id }.toSet()
            val failure = runCatching { manager.restoreBackup(tampered) }.exceptionOrNull()
            assertTrue("Tampered payload must be rejected.", failure is IllegalArgumentException)
            assertEquals(idsBeforeRestore, dao.getAllForBackup().map { it.id }.toSet())
        } finally {
            cleanup(context, dao, beforeIds)
            archive.delete()
            tampered.delete()
            source.delete()
        }
    }

    private fun rewriteWithTamperedPayload(source: File, destination: File) {
        ZipInputStream(source.inputStream().buffered()).use { input ->
            ZipOutputStream(destination.outputStream().buffered()).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    output.putNextEntry(ZipEntry(entry.name))
                    val bytes = input.readBytes()
                    if (entry.name.startsWith("documents/") && bytes.isNotEmpty()) {
                        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
                    }
                    output.write(bytes)
                    output.closeEntry()
                    input.closeEntry()
                }
            }
        }
    }

    private suspend fun cleanup(
        context: Context,
        dao: DocumentDao,
        originalIds: Set<Long>
    ) {
        dao.getAllForBackup().filter { it.id !in originalIds }.forEach { document ->
            WorkManager.getInstance(context).cancelUniqueWork("filekhoj-index-${document.id}")
            dao.deleteDocumentCompletely(document.id)
            document.managedPath?.let(::File)?.delete()
        }
    }
}
