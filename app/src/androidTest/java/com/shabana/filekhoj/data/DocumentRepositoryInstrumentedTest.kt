package com.shabana.filekhoj.data

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun identicalContentImportsOnceAndAddsWorkspaceAssignment() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = DocumentDatabase.getInstance(context).documentDao()
        val repository = DocumentRepository(context)
        val token = UUID.randomUUID().toString()
        val first = File(context.filesDir, "documents/duplicate-a-$token.txt")
        val second = File(context.filesDir, "documents/duplicate-b-$token.txt")
        val beforeIds = dao.getAllForBackup().map { it.id }.toSet()
        var profileId = -1L
        try {
            check(first.parentFile?.mkdirs() == true || first.parentFile?.isDirectory == true)
            first.writeText("same bytes for SHA-256 duplicate test")
            second.writeText(first.readText())
            val firstUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", first)
            val secondUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", second)
            val defaultId = repository.ensureDefaultProfile()
            profileId = repository.createProfile("Duplicate test $token")

            val firstResult = repository.importDocuments(listOf(firstUri), profileId = defaultId)
            val secondResult = repository.importDocuments(listOf(secondUri), profileId = profileId)

            assertEquals(1, firstResult.imported)
            assertEquals(0, secondResult.imported)
            assertEquals(1, secondResult.alreadyPresent)
            val added = dao.getAllForBackup().filter { it.id !in beforeIds }
            assertEquals("Duplicate content must not create a second row.", 1, added.size)
            assertEquals(firstUri.toString(), added.single().sourceUri)
            assertTrue(profileId in dao.getProfileIdsForDocument(added.single().id))
            assertTrue(!added.single().contentHash.isNullOrBlank())
        } finally {
            cleanup(context, dao, beforeIds)
            if (profileId > 0L) dao.getProfile(profileId)?.let { dao.deleteProfile(it) }
            first.delete()
            second.delete()
        }
    }

    @Test
    fun decryptedEncryptedPdfDoesNotInheritSourceSignatureVerdict() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val dao = DocumentDatabase.getInstance(context).documentDao()
        val repository = DocumentRepository(context)
        val token = UUID.randomUUID().toString()
        val source = File(context.filesDir, "documents/encrypted-import-$token.pdf")
        var managedDerivative: File? = null
        var documentId = -1L
        try {
            check(source.parentFile?.mkdirs() == true || source.parentFile?.isDirectory == true)
            PDDocument().use { pdf ->
                pdf.addPage(PDPage())
                val policy = StandardProtectionPolicy(
                    "owner-password-$token",
                    "user-password-$token",
                    AccessPermission()
                ).apply { encryptionKeyLength = 128 }
                pdf.protect(policy)
                pdf.save(source)
            }
            val sourceUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                source
            )
            val result = repository.importDocuments(
                listOf(sourceUri),
                mapOf(sourceUri.toString() to "user-password-$token")
            )
            assertEquals(1, result.imported)
            val row = dao.findBySourceUri(sourceUri.toString())
                ?: throw AssertionError("Imported encrypted PDF should have a library row.")
            documentId = row.id
            val derivativeFile = row.managedPath?.let(::File)
                ?: throw AssertionError("Encrypted source should have a decrypted managed copy.")
            managedDerivative = derivativeFile
            assertTrue("Encrypted source should be represented by a decrypted managed copy.", row.isStoredDecrypted)
            assertEquals(DocumentEntity.SIGNATURE_STATUS_UNKNOWN, row.signatureStatus)
            assertEquals(0, row.signatureCount)
            assertTrue("Managed derivative must exist.", derivativeFile.isFile)
            PDDocument.load(derivativeFile).use { derivative ->
                assertFalse("The managed derivative should no longer be encrypted.", derivative.isEncrypted)
            }
        } finally {
            if (documentId > 0L) {
                androidx.work.WorkManager.getInstance(context)
                    .cancelUniqueWork("filekhoj-index-$documentId")
                dao.deleteDocumentCompletely(documentId)
            }
            managedDerivative?.delete()
            source.delete()
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
