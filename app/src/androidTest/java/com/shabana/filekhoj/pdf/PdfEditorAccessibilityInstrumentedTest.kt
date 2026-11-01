package com.shabana.filekhoj.pdf

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shabana.filekhoj.data.DocumentEntity
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfEditorAccessibilityInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pageSelectionAndReorderControlsHaveMeaningfulTalkBackLabels() {
        val context = composeRule.activity
        PDFBoxResourceLoader.init(context)
        val source = File(context.cacheDir, "pdf-a11y-source-${System.nanoTime()}.pdf")
        try {
            PDDocument().use { pdf ->
                repeat(2) { pdf.addPage(PDPage()) }
                pdf.save(source)
            }
            val document = DocumentEntity(
                displayName = "accessibility-test.pdf",
                mimeType = "application/pdf",
                sizeBytes = source.length(),
                sourceUri = Uri.fromFile(source).toString(),
                dateAdded = System.currentTimeMillis(),
                sourceLastModified = null,
                isStoredDecrypted = true,
                managedPath = source.absolutePath
            )
            composeRule.setContent {
                PdfEditorScreen(
                    context = context,
                    document = document,
                    onClose = {},
                    onSaveCopy = { _, _, callback -> callback(false, "Test does not save files.") }
                )
            }
            composeRule.waitUntil(timeoutMillis = 20_000) {
                try {
                    composeRule.onNodeWithContentDescription("Select source page 1").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            composeRule.onNodeWithContentDescription("Select source page 1").assertExists()
            composeRule.onNodeWithContentDescription("Move page 1 up").assertExists()
            composeRule.onNodeWithContentDescription("Move page 1 down").assertExists()
        } finally {
            source.delete()
        }
    }
}
