package com.shabana.filekhoj.data

import java.util.Locale

data class DefaultDocumentProfile(
    val name: String,
    val icon: String,
    val colorArgb: Int,
    val sortOrder: Int
)

data class ProfileSuggestion(
    val profileName: String,
    val category: String,
    val confidence: Int
)

/** Built-in workspaces are starting points; users may create any additional profile. */
object DocumentProfileDefaults {
    val profiles = listOf(
        DefaultDocumentProfile("Personal", "person", 0xFF07583D.toInt(), 0),
        DefaultDocumentProfile("Shop / Business", "store", 0xFF1769AA.toInt(), 1),
        DefaultDocumentProfile("Medical", "medical", 0xFF9B3A57.toInt(), 2),
        DefaultDocumentProfile("Farm / Agriculture", "farm", 0xFF4F772D.toInt(), 3),
        DefaultDocumentProfile("Property", "home", 0xFF7A5C43.toInt(), 4),
        DefaultDocumentProfile("Family", "family", 0xFF7956A8.toInt(), 5),
        DefaultDocumentProfile("CSC / Digital Services", "service", 0xFF087E8B.toInt(), 6),
        DefaultDocumentProfile("Education", "education", 0xFFB7791F.toInt(), 7),
        DefaultDocumentProfile("Vehicle", "vehicle", 0xFF4B5563.toInt(), 8)
    )

    private data class Rule(val profile: String, val category: String, val keywords: List<String>)

    private val rules = listOf(
        Rule("Shop / Business", "GST & Licences", listOf("gst", "udyam", "shop act", "invoice", "purchase order", "bill", "vendor", "supplier", "व्यापार", "दुकान", "जीएसटी")),
        Rule("Farm / Agriculture", "Land Records", listOf("7/12", "7 12", "8a", "ferfar", "satbara", "land record", "crop insurance", "पीक विमा", "७/१२", "८अ", "शेत", "फेरफार")),
        Rule("Medical", "Reports & Prescriptions", listOf("prescription", "medical", "hospital", "blood report", "lab report", "diagnostic", "आरोग्य", "रुग्णालय", "औषध", "तपासणी")),
        Rule("Property", "Property Records", listOf("sale deed", "property tax", "rent agreement", "7/12", "property", "मालमत्ता", "खरेदीखत")),
        Rule("Education", "Certificates & Marksheets", listOf("marksheet", "degree", "certificate", "transcript", "school", "college", "शाळा", "गुणपत्रक", "प्रमाणपत्र")),
        Rule("Vehicle", "Insurance & Registration", listOf("vehicle", "insurance", "registration certificate", "driving licence", "driving license", "puc", "rc book", "वाहन", "विमा", "परवाना")),
        Rule("CSC / Digital Services", "Applications & Receipts", listOf("application receipt", "acknowledgement", "aaple sarkar", "csc", "maha e seva", "आवेदन", "पावती", "अर्ज"))
    )

    fun suggest(displayName: String, mimeType: String = "", extractedText: String = ""): ProfileSuggestion? {
        val haystack = "$displayName $mimeType $extractedText".lowercase(Locale.ROOT)
        val match = rules.mapNotNull { rule ->
            val matched = rule.keywords.count { haystack.contains(it.lowercase(Locale.ROOT)) }
            if (matched == 0) null else rule to matched
        }.maxByOrNull { it.second } ?: return null
        val confidence = (55 + match.second * 12).coerceAtMost(95)
        return ProfileSuggestion(match.first.profile, match.first.category, confidence)
    }
}
