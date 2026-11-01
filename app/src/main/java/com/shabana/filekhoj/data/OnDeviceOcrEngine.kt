package com.shabana.filekhoj.data

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class OcrRecognitionResult(
    val text: String,
    /** Mean ML Kit line confidence, in the range 0..1. */
    val confidence: Float
)

/**
 * Offline OCR using bundled Latin and Devanagari models.
 * The selected recognizer's line confidence is retained so indexing can reject low-confidence OCR.
 */
class OnDeviceOcrEngine : AutoCloseable {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagari = TextRecognition.getClient(
        DevanagariTextRecognizerOptions.Builder().build()
    )

    fun recognize(bitmap: Bitmap): OcrRecognitionResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        val latinResult = runCatching { Tasks.await(latin.process(image)) }.getOrNull()
        val devanagariResult = runCatching { Tasks.await(devanagari.process(image)) }.getOrNull()
        val selected = listOfNotNull(latinResult, devanagariResult)
            .maxWithOrNull(compareBy<Text> { contentScore(it.text) }.thenBy { confidence(it) })

        if (selected == null) return OcrRecognitionResult("", 0f)
        return OcrRecognitionResult(selected.text.trim(), confidence(selected))
    }

    private fun contentScore(text: String): Int =
        text.count { it.isLetterOrDigit() }

    private fun confidence(result: Text): Float {
        val lineConfidences = result.textBlocks
            .flatMap { it.lines }
            .map { it.confidence }
            .filter { it in 0f..1f }
        return if (lineConfidences.isEmpty()) 0f else lineConfidences.average().toFloat()
    }

    override fun close() {
        latin.close()
        devanagari.close()
    }
}
