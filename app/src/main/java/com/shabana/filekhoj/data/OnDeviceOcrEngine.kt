package com.shabana.filekhoj.data

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Offline OCR using bundled Latin and Devanagari models. The two recognizers are run
 * locally; the best single-script result is chosen to avoid duplicating every line.
 */
class OnDeviceOcrEngine : AutoCloseable {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagari = TextRecognition.getClient(
        DevanagariTextRecognizerOptions.Builder().build()
    )

    fun recognize(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val latinText = runCatching { Tasks.await(latin.process(image)).text.trim() }.getOrDefault("")
        val devanagariText = runCatching {
            Tasks.await(devanagari.process(image)).text.trim()
        }.getOrDefault("")

        return when {
            latinText.isBlank() -> devanagariText
            devanagariText.isBlank() -> latinText
            score(devanagariText) > score(latinText) -> devanagariText
            else -> latinText
        }
    }

    private fun score(text: String): Int = text.count { it.isLetterOrDigit() }

    override fun close() {
        latin.close()
        devanagari.close()
    }
}
