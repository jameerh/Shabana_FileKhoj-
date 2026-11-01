package com.shabana.filekhoj.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryNormalizerTest {
    @Test
    fun blankAndPunctuationOnlyQueriesReturnNull() {
        assertNull(SearchQueryNormalizer.toFtsQuery("   \n\t"))
        assertNull(SearchQueryNormalizer.toFtsQuery("\"\"\" --- !!!"))
    }

    @Test
    fun termsAreQuotedAndJoinedWithAnd() {
        assertEquals("\"aadhaar\" AND \"card\"", SearchQueryNormalizer.toFtsQuery("aadhaar card"))
    }

    @Test
    fun ftsOperatorsRemainLiteralSearchTerms() {
        assertEquals("\"OR\" AND \"pan\"", SearchQueryNormalizer.toFtsQuery("OR pan"))
    }

    @Test
    fun preservesMarathiDevanagariSearchTerms() {
        val query = SearchQueryNormalizer.toFtsQuery("आधार   कार्ड")
        assertEquals("\"आधार\" AND \"कार्ड\"", query)
    }

    @Test
    fun normalizesUnicodeAndControlCharacters() {
        val query = SearchQueryNormalizer.toFtsQuery("pan\u0000 card")
        assertTrue(query!!.contains("\"pan\""))
        assertTrue(query.contains("\"card\""))
    }

    @Test
    fun embeddedQuotesAreEscapedInsideLiteralTerm() {
        assertEquals("\"pan\"\"\" AND \"card\"", SearchQueryNormalizer.toFtsQuery("pan\" card"))
    }
}
