package com.hazyoctophant.cardvault.scan

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Runs on-device OCR (ML Kit, bundled model, works offline) on camera frames a few times a second
 * and reports the recognised lines ordered top-to-bottom.
 */
class TextAnalyzer(
    private val intervalMs: Long = 350,
    private val onLines: (List<String>) -> Unit,
) : ImageAnalysis.Analyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var last = 0L

    @Volatile var paused = false
    @Volatile private var closed = false

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val media = image.image
        if (paused || closed || media == null || now - last < intervalMs) {
            image.close()
            return
        }
        last = now
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        recognizer.process(input)
            .addOnSuccessListener { text ->
                val lines = text.textBlocks
                    .flatMap { it.lines }
                    .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                    .map { it.text }
                if (lines.isNotEmpty()) onLines(lines)
            }
            .addOnCompleteListener { image.close() }
    }

    fun close() {
        closed = true
        recognizer.close()
    }
}
