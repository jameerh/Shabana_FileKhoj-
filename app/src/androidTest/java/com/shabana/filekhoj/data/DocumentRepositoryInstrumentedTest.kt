package com.shabana.filekhoj.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentRepositoryInstrumentedTest {
    @Test
    fun indexedEnglishAndMarathiTextCanBeFoundThroughFts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = DocumentDatabase.getInstance(context).documentDao()
        val token = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "search-test-$token.txt")
        file.writeText("test")
        var id = -1L
        try {
            id = dao.insert(
                DocumentEntity(
                    displayName = "search-test-$token.txt",
                    mimeType = "text/plain",
                    sizeBytes = file.length(),
                    sourceUri = "content://filekhoj-search-test/$token",
                    dateAdded = System.currentTimeMillis(),
                    sourceLastModified = null,
                    managedPath = file.absolutePath
                )
            )
            assertTrue(id > 0L)
            dao.saveExtractedContent(
                id,
                "search-test-$token.txt",
                "Aadhaar card आधार कार्ड अर्जदार नाव",
                1
            )

            val english = dao.searchFullText(SearchQueryNormalizer.toFtsQuery("Aadhaar")!!)
            val marathi = dao.searchFullText(SearchQueryNormalizer.toFtsQuery("आधार")!!)
            assertTrue("English text should be searchable.", english.any { it.id == id })
            assertTrue("Marathi Devanagari text should be searchable.", marathi.any { it.id == id })
            assertEquals("Aadhaar card आधार कार्ड अर्जदार नाव", dao.findExtractedText(id)?.extractedText)
        } finally {
            if (id > 0L) dao.deleteDocumentCompletely(id)
            file.delete()
        }
    }

    @Test
    fun deletingManagedDocumentRemovesItsLibraryRowAndPrivateCopy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = DocumentDatabase.getInstance(context).documentDao()
        val repository = DocumentRepository(context)
        val token = UUID.randomUUID().toString()
        val file = File(context.filesDir, "documents/delete-test-$token.txt")
        check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
        file.writeText("private managed test copy")
        var id = -1L
        try {
            id = dao.insert(
                DocumentEntity(
                    displayName = "delete-test-$token.txt",
                    mimeType = "text/plain",
                    sizeBytes = file.length(),
                    sourceUri = "content://filekhoj-delete-test/$token",
                    dateAdded = System.currentTimeMillis(),
                    sourceLastModified = null,
                    managedPath = file.absolutePath
                )
            )
            assertTrue(id > 0L)

            val result = repository.deleteDocuments(listOf(id))
            assertEquals(1, result.deleted)
            assertEquals(0, result.failed)
            assertTrue("Managed private copy should be deleted.", !file.exists())
            assertEquals(null, dao.findById(id))
        } finally {
            if (id > 0L && dao.findById(id) != null) dao.deleteDocumentCompletely(id)
            file.delete()
        }
    }
}
