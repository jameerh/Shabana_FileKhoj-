package com.shabana.filekhoj.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentProfileDefaultsTest {
    @Test
    fun providesExpectedBuiltInWorkspaces() {
        assertEquals(
            listOf("Personal", "Shop / Business", "Medical", "Farm / Agriculture", "Property",
                "Family", "CSC / Digital Services", "Education", "Vehicle"),
            DocumentProfileDefaults.profiles.map { it.name }
        )
        assertEquals(1, DocumentProfileDefaults.profiles.count { it.name == "Personal" })
    }

    @Test
    fun suggestsShopProfileFromInvoiceFilename() {
        val suggestion = DocumentProfileDefaults.suggest("GST_Invoice_October.pdf", "application/pdf")
        assertEquals("Shop / Business", suggestion?.profileName)
        assertEquals("GST & Licences", suggestion?.category)
        assertTrue((suggestion?.confidence ?: 0) >= 55)
    }

    @Test
    fun recognizesMarathiLandRecordTerms() {
        assertEquals("Farm / Agriculture", DocumentProfileDefaults.suggest("७/१२ उतारा.pdf")?.profileName)
    }

    @Test
    fun doesNotGuessWhenThereIsNoUsefulSignal() {
        assertNull(DocumentProfileDefaults.suggest("scan_001.pdf", "application/pdf"))
    }
}
