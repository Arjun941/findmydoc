package com.example.core.extraction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.core.ocr.OcrEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

data class ExtractedDocument(
    val text: String,
    val metadata: Map<String, String> = emptyMap(),
    val wasOcrUsed: Boolean = false
)

interface DocumentExtractor {
    suspend fun extract(uri: Uri): ExtractedDocument
}

class LocalDocumentExtractor(
    private val context: Context,
    private val ocrEngine: OcrEngine
) : DocumentExtractor {

    companion object {
        // Below this, treat the "text layer" as noise (e.g. a single stray watermark text object
        // on an otherwise scanned page) and fall back to OCR instead of indexing near-nothing.
        private const val MIN_DIGITAL_TEXT_LENGTH = 40
    }

    private val tag = "LocalDocumentExtractor"

    override suspend fun extract(uri: Uri): ExtractedDocument {
        val fileName = getFileName(uri)
        val extension = fileName.substringAfterLast('.', "").lowercase()

        return try {
            when (extension) {
                "txt", "csv", "md", "json", "xml" -> {
                    extractPlainPlainText(uri)
                }
                "docx", "xlsx", "pptx" -> {
                    extractOfficeOpenXmlText(uri)
                }
                "odt", "ods", "odp" -> {
                    extractOpenDocumentText(uri)
                }
                "epub" -> {
                    extractEpubText(uri)
                }
                "pdf" -> {
                    extractPdfText(uri)
                }
                else -> {
                    // Generic fallback
                    extractPlainPlainText(uri)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to extract text from $uri", e)
            ExtractedDocument("", mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    private fun extractPlainPlainText(uri: Uri): ExtractedDocument {
        val stringBuilder = java.lang.StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    stringBuilder.append(line).append("\n")
                    line = reader.readLine()
                }
            }
        }
        return ExtractedDocument(stringBuilder.toString(), mapOf("source" to "Plain Text Parser"))
    }

    /**
     * Parse office files (.docx, .xlsx, .pptx) which are zipped XML archives.
     * We scan the ZIP entries for main content text components.
     */
    private fun extractOfficeOpenXmlText(uri: Uri): ExtractedDocument {
        val stringBuilder = java.lang.StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    // DOCX: word/document.xml
                    // XLSX: xl/sharedStrings.xml
                    // PPTX: ppt/slides/slide*.xml
                    if (name == "word/document.xml" || name == "xl/sharedStrings.xml" || name.startsWith("ppt/slides/slide")) {
                        BufferedReader(InputStreamReader(zip)).use { reader ->
                            var line: String? = reader.readLine()
                            while (line != null) {
                                // Simple regex to strip XML tags and extract raw text tokens
                                val cleanLine = line.replace(Regex("<[^>]*>"), " ").trim()
                                if (cleanLine.isNotEmpty()) {
                                    stringBuilder.append(cleanLine).append("\n")
                                }
                                line = reader.readLine()
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return ExtractedDocument(stringBuilder.toString(), mapOf("source" to "Office OpenXML Parser"))
    }

    /**
     * Parse OpenDocument (.odt, .ods, .odp) which contain content.xml.
     */
    private fun extractOpenDocumentText(uri: Uri): ExtractedDocument {
        val stringBuilder = java.lang.StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "content.xml") {
                        BufferedReader(InputStreamReader(zip)).use { reader ->
                            var line: String? = reader.readLine()
                            while (line != null) {
                                val cleanLine = line.replace(Regex("<[^>]*>"), " ").trim()
                                if (cleanLine.isNotEmpty()) {
                                    stringBuilder.append(cleanLine).append("\n")
                                }
                                line = reader.readLine()
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return ExtractedDocument(stringBuilder.toString(), mapOf("source" to "OpenDocument XML Parser"))
    }

    /**
     * Parse EPUB files which contain XHTML content items.
     */
    private fun extractEpubText(uri: Uri): ExtractedDocument {
        val stringBuilder = java.lang.StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase()
                    if (name.endsWith(".html") || name.endsWith(".xhtml")) {
                        BufferedReader(InputStreamReader(zip)).use { reader ->
                            var line: String? = reader.readLine()
                            while (line != null) {
                                val cleanLine = line.replace(Regex("<[^>]*>"), " ").trim()
                                if (cleanLine.isNotEmpty()) {
                                    stringBuilder.append(cleanLine).append(" ")
                                }
                                line = reader.readLine()
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return ExtractedDocument(stringBuilder.toString(), mapOf("source" to "EPUB HTML Parser"))
    }

    /**
     * PDF text extraction: real text layer first, OCR only as a fallback.
     *
     * An earlier version tried a lightweight pass first - reading the file's raw bytes and
     * stripping non-alphanumeric characters, on the assumption some PDFs have plain-readable text
     * streams. That's not true for real-world PDFs: content streams are almost always compressed
     * (FlateDecode for text, DCTDecode/JPXDecode for scanned-page images), so raw-byte reading
     * only ever recovered the PDF container header/object syntax, never real content - it was
     * removed entirely and every PDF went through OCR unconditionally. That was correct as a fix
     * (no more binary noise) but wasteful for genuinely digital PDFs, which have a perfectly good
     * text layer sitting right there.
     *
     * PDFBox-Android does real PDF parsing (content-stream decompression, text operators, font/
     * ToUnicode decoding) rather than raw-byte reading, so "did it find real text" is a reliable,
     * non-heuristic signal - a scanned/image-only PDF has no text objects at all and PDFBox
     * correctly returns empty, it never leaks binary structure the way the old approach did.
     */
    private suspend fun extractPdfText(uri: Uri): ExtractedDocument {
        val digitalText = tryExtractDigitalPdfText(uri)
        if (digitalText != null && digitalText.trim().length >= MIN_DIGITAL_TEXT_LENGTH) {
            return ExtractedDocument(digitalText, mapOf("source" to "PDF Text Layer (PDFBox)"), wasOcrUsed = false)
        }

        // --- OCR fallback: no usable text layer (scanned/image-only PDF) ---
        // Render PDF pages using Android's native PdfRenderer
        val bitmaps = mutableListOf<Bitmap>()
        try {
            val parcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            if (parcelFileDescriptor != null) {
                val renderer = PdfRenderer(parcelFileDescriptor)
                // OCR only first 5 pages for efficiency and battery preservation (as per requirements for large PDFs)
                val pageCount = renderer.pageCount.coerceAtMost(5)
                for (i in 0 until pageCount) {
                    val page = renderer.openPage(i)
                    // Scale down slightly to preserve memory
                    val bitmap = Bitmap.createBitmap(page.width / 2, page.height / 2, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmaps.add(bitmap)
                    page.close()
                }
                renderer.close()
                parcelFileDescriptor.close()
            }
        } catch (e: Exception) {
            Log.e(tag, "PdfRenderer failed or file has restricted access", e)
        }

        return if (bitmaps.isNotEmpty()) {
            val ocrResult = ocrEngine.extractText(bitmaps)
            // Recycle bitmaps to free memory immediately!
            for (bmp in bitmaps) {
                if (!bmp.isRecycled) bmp.recycle()
            }
            ExtractedDocument(ocrResult.text, mapOf("source" to "PDF OCR Engine"), wasOcrUsed = true)
        } else {
            ExtractedDocument("", mapOf("source" to "PDF Fallback (No Pages Rendered)", "error" to "PdfRenderer produced no pages"))
        }
    }

    /** Returns the PDF's real extracted text via PDFBox, or null if parsing failed outright
     * (encrypted/corrupt file) - a genuinely scanned/image-only PDF parses fine and just yields
     * an empty/near-empty string, which the caller's length check catches. */
    private fun tryExtractDigitalPdfText(uri: Uri): String? {
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context)
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    PDFTextStripper().getText(document)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "PDFBox text-layer extraction failed, falling back to OCR", e)
            null
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (columnIndex != -1) {
                        result = cursor.getString(columnIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "unknown_document"
    }
}
