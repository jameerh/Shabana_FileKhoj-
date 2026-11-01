package com.shabana.filekhoj.data

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
}
