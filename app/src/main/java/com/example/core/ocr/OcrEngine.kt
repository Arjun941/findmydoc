package com.example.core.ocr

import android.graphics.Bitmap

data class OcrResult(
    val text: String,
    val confidence: Float,
    val pagesText: List<String>
)

interface OcrEngine {
    suspend fun extractText(images: List<Bitmap>): OcrResult
}
