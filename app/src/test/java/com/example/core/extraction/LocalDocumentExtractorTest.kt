package com.example.core.extraction

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.core.ocr.OcrEngine
import com.example.core.ocr.OcrResult
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A digitally-authored PDF (a real text layer, not a scanned image) must be extracted via its
 * text layer, never routed through OCR - this is the "digital docs shouldn't need OCR" behavior
 * that a much earlier version of this extractor got wrong (raw-byte reading a PDF's compressed
 * content stream can never yield readable text, so every PDF used to fall through to OCR
 * unconditionally). An [OcrEngine] that throws if invoked lets this test fail loudly if that
 * regresses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalDocumentExtractorTest {

    private class FailingOcrEngine : OcrEngine {
        override suspend fun extractText(images: List<Bitmap>): OcrResult {
            throw AssertionError("OCR must not run for a digitally-authored PDF with a real text layer")
        }
    }

    private fun authorPdf(context: Context, text: String): File {
        val file = File.createTempFile("digital", ".pdf", context.cacheDir)
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(50f, 700f)
                stream.showText(text)
                stream.endText()
            }
            document.save(file)
        }
        return file
    }

    @Test
    fun extract_digitalPdf_usesTextLayer_notOcr() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val expectedText = "This is a digitally authored PDF with a real embedded text layer."
        val file = authorPdf(context, expectedText)

        val extractor = LocalDocumentExtractor(context, FailingOcrEngine())
        val result = extractor.extract(Uri.fromFile(file))

        assertFalse("Digital PDF must not be marked as OCR'd", result.wasOcrUsed)
        assertTrue(result.text.contains("digitally authored"))
    }
}
