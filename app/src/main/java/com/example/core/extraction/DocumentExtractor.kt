package com.example.core.extraction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.core.ocr.OcrEngine
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
     * PDF Text extraction:
     * 1. Attempt basic PDF text parsing (checking for textual strings or utilizing PdfRenderer page info)
     * 2. If extracted text is low/empty, render pages to Bitmaps and run OCR Engine!
     */
    private suspend fun extractPdfText(uri: Uri): ExtractedDocument {
        val stringBuilder = java.lang.StringBuilder()
        var textLengthThreshold = 30 // Minimum characters to consider a PDF text-rich

        // Try standard text decoding from PDF streams (simple text streams exist in many PDFs)
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var lineCount = 0
                    var line = reader.readLine()
                    while (line != null && lineCount < 200) { // check first few lines
                        val textPart = line.replace(Regex("[^a-zA-Z0-9\\s]"), " ")
                        if (textPart.trim().isNotEmpty()) {
                            stringBuilder.append(textPart).append(" ")
                        }
                        line = reader.readLine()
                        lineCount++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed standard PDF text read", e)
        }

        val parsedText = stringBuilder.toString().trim()
        if (parsedText.length >= textLengthThreshold) {
            return ExtractedDocument(parsedText, mapOf("source" to "PDF Text Decoder"))
        }

        // --- OCR Fallback Pipeline ---
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
            ExtractedDocument(parsedText, mapOf("source" to "PDF Fallback (No Pages Rendered)"))
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
