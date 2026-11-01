package com.shabana.filekhoj.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexQualityPolicyTest {
    @Test
    fun rejectsBlankAndTinyText() {
        assertFalse(IndexQualityPolicy.isUsableText(""))
        assertFalse(IndexQualityPolicy.isUsableText("123"))
    }

    @Test
    fun acceptsPlausibleEnglishAndDevanagariText() {
        assertTrue(IndexQualityPolicy.isUsableText("Aadhaar Card Name: Ramesh Kumar 1234"))
        assertTrue(IndexQualityPolicy.isUsableText("आधार कार्ड नाव रमेश कुमार १२३४"))
    }

    @Test
    fun requiresAtLeastSeventyFivePercentOcrConfidence() {
        val text = "Aadhaar Card Name Ramesh Kumar 1234"
        assertFalse(IndexQualityPolicy.isUsableOcr(text, 0.74f))
        assertTrue(IndexQualityPolicy.isUsableOcr(text, 0.75f))
    }

    @Test
    fun requiresAtLeastSeventyFivePercentPageCoverage() {
        assertFalse(IndexQualityPolicy.hasEnoughPageCoverage(74, 100))
        assertTrue(IndexQualityPolicy.hasEnoughPageCoverage(75, 100))
        assertFalse(IndexQualityPolicy.hasEnoughPageCoverage(0, 0))
    }

    @Test
    fun preservesUsableTextWhenPdfCoverageIsPartial() {
        val text = "Aadhaar card आधार कार्ड अर्जदार नाव"
        assertEquals(
            DocumentEntity.INDEX_STATUS_PARTIALLY_INDEXED,
            IndexQualityPolicy.pdfIndexStatus(text, 100, 200)
        )
    }

    @Test
    fun marksPdfPartialWhenPageProcessingLimitLeavesUnvisitedPages() {
        val text = "Aadhaar card आधार कार्ड अर्जदार नाव"
        assertEquals(
            DocumentEntity.INDEX_STATUS_PARTIALLY_INDEXED,
            IndexQualityPolicy.pdfIndexStatus(
                text = text,
                usablePages = 2_000,
                totalPages = 2_200,
                processedPages = 2_000
            )
        )
    }

    @Test
    fun onlyMarksPdfIndexedWhenCoverageGatePasses() {
        val text = "Aadhaar card आधार कार्ड अर्जदार नाव"
        assertEquals(
            DocumentEntity.INDEX_STATUS_INDEXED,
            IndexQualityPolicy.pdfIndexStatus(text, 75, 100)
        )
        assertEquals(
            DocumentEntity.INDEX_STATUS_OCR_REQUIRED,
            IndexQualityPolicy.pdfIndexStatus("", 0, 100)
        )
    }
}
