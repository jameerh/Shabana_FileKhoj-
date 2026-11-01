package com.shabana.filekhoj.pdf

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shabana.filekhoj.data.DocumentEntity
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfEditorSessionInstrumentedTest {
    @Test
    fun exportCopyReopensWithRequestedPagesAndNeverChangesSource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File(context.cacheDir, "pdf-editor-source-${System.nanoTime()}.pdf")
        var output: File? = null
        var session: PdfEditorSession? = null
        try {
            PDDocument().use { sourceDocument ->
                repeat(3) { sourceDocument.addPage(PDPage()) }
                sourceDocument.save(source)
            }
            val sourceHashBefore = sha256(source)
            val entity = DocumentEntity(
                displayName = "test-source.pdf",
                mimeType = "application/pdf",
                sizeBytes = source.length(),
                sourceUri = Uri.fromFile(source).toString(),
                dateAdded = System.currentTimeMillis(),
                sourceLastModified = null,
                isStoredDecrypted = true,
                managedPath = source.absolutePath
            )
            session = PdfEditorSession.open(context, entity)
            output = session!!.exportCopy(
                listOf(PdfEditorPage(sourceIndex = 2), PdfEditorPage(sourceIndex = 0, rotationDelta = 90))
            )

            PDDocument.load(output!!).use { exported ->
                assertEquals(2, exported.numberOfPages)
                assertEquals(0, exported.getPage(0).rotation)
                assertEquals(90, exported.getPage(1).rotation)
            }
            assertEquals(sourceHashBefore, sha256(source))
            assertNotEquals(source.absolutePath, output!!.absolutePath)
            assertTrue(output!!.length() > 5L)
        } finally {
            session?.close()
            output?.delete()
            source.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
