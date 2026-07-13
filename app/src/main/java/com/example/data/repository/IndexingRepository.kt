package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.example.core.extraction.DocumentExtractor
import com.example.core.indexing.Chunker
import com.example.data.database.ChunkEntity
import com.example.data.database.DocDao
import com.example.data.database.DocumentEntity
import com.example.data.database.EmbeddingEntity
import com.example.data.database.OcrEntity
import com.example.data.database.IndexingLedgerEntity
import com.example.data.database.LedgerStatus
import com.example.core.embeddings.EmbeddingEngine
import com.example.core.embeddings.ArcticEmbedder
import com.example.core.extraction.LocalDocumentExtractor
import com.example.core.ocr.PaddleOcrEngine
import com.example.data.database.DocDatabase
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class IndexingProgress(
    val status: String, // "Idle", "Scanning", "Processing", "Completed", "Error", "Permission Denied"
    val totalFiles: Int = 0,
    val processedFiles: Int = 0,
    val currentFileName: String = ""
)

class IndexingRepository(
    private val context: Context,
    private val docDao: DocDao,
    private val documentExtractor: DocumentExtractor,
    private val embeddingEngine: EmbeddingEngine
) {

    private val tag = "IndexingRepository"

    private val _indexingProgress = MutableStateFlow(IndexingProgress("Idle"))
    val indexingProgress: StateFlow<IndexingProgress> = _indexingProgress.asStateFlow()

    private fun hasStoragePermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Scans device directories and MediaStore files for supported document types.
     */
    suspend fun discoverAndIndexFiles(forceRescan: Boolean = false, priorityUris: List<String> = emptyList()) = withContext(Dispatchers.IO) {
        try {
            if (!hasStoragePermission()) {
                _indexingProgress.value = IndexingProgress("Permission Denied")
                throw SecurityException("Read/Write Storage Permission is not granted. Please allow storage access first.")
            }

            _indexingProgress.value = IndexingProgress("Scanning")
            val discoveredFiles = mutableListOf<DocumentMetadata>()

            // 1. Direct directory scan (very reliable fallback for documents in standard locations)
            val directoriesToScan = mutableListOf<File>()
            try {
                directoriesToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
                directoriesToScan.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
                context.getExternalFilesDir(null)?.let { directoriesToScan.add(it) }
                if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                    directoriesToScan.add(Environment.getExternalStorageDirectory())
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to resolve some standard directories", e)
            }

            for (dir in directoriesToScan) {
                scanDirectoryRecursively(dir, discoveredFiles)
            }

            // 2. Query MediaStore
            try {
                val externalUri = MediaStore.Files.getContentUri("external")
                val projection = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_MODIFIED
                )

                // Filter for supported extensions
                val mimeTypes = listOf(
                    "application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "text/plain",
                    "text/comma-separated-values",
                    "text/csv",
                    "text/markdown",
                    "application/json",
                    "text/xml",
                    "application/epub+zip",
                    "application/vnd.oasis.opendocument.text",
                    "application/vnd.oasis.opendocument.spreadsheet",
                    "application/vnd.oasis.opendocument.presentation"
                )

                val selectionBuilder = StringBuilder()
                selectionBuilder.append("(${MediaStore.Files.FileColumns.MIME_TYPE} IN (${mimeTypes.joinToString { "?" }}))")
                
                // Also match by file name extension as mimeType mapping isn't always reliable
                val extensions = listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "md", "json", "xml", "epub", "odt", "ods", "odp")
                for (ext in extensions) {
                    selectionBuilder.append(" OR (${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.${ext}')")
                }

                val selection = selectionBuilder.toString()
                val selectionArgs = mimeTypes.toTypedArray()

                context.contentResolver.query(
                    externalUri,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "unknown"
                        val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                        val size = cursor.getLong(sizeCol)
                        val dateModified = cursor.getLong(dateCol) * 1000 // Convert sec to ms
                        val fileUri = Uri.withAppendedPath(externalUri, id.toString())

                        val documentType = getDocumentTypeFromExtension(name)
                        val hash = md5(name + size + dateModified)

                        // Avoid duplicate URIs if already scanned directly
                        val uriStr = fileUri.toString()
                        if (discoveredFiles.none { it.uri == uriStr || (it.fileName == name && it.size == size) }) {
                            discoveredFiles.add(
                                DocumentMetadata(
                                    uri = uriStr,
                                    fileName = name,
                                    mimeType = mime,
                                    size = size,
                                    modifiedAt = dateModified,
                                    hash = hash,
                                    documentType = documentType
                                )
                            )
                        }
                    }
                }
            } catch (securityException: SecurityException) {
                Log.e(tag, "Read storage permission required for MediaStore; falling back to direct storage", securityException)
            } catch (e: Exception) {
                Log.e(tag, "MediaStore query failed, fallback to direct directory scanner", e)
            }

            val sortedDiscovered = if (priorityUris.isNotEmpty()) {
                discoveredFiles.sortedBy { if (priorityUris.contains(it.uri)) 0 else 1 }
            } else {
                discoveredFiles
            }

            processDiscoveryList(sortedDiscovered, forceRescan)

        } catch (securityException: SecurityException) {
            Log.e(tag, "Read storage permission required", securityException)
            _indexingProgress.value = IndexingProgress("Permission Denied: ${securityException.localizedMessage}")
        } catch (e: Exception) {
            Log.e(tag, "Discovery failed", e)
            _indexingProgress.value = IndexingProgress("Error: ${e.localizedMessage ?: e.message ?: "Unknown error"}")
        }
    }

    private class PreparedDoc(
        val metadata: DocumentMetadata,
        val newDocEntity: DocumentEntity,
        val docId: Long,
        val chunks: List<ChunkEntity>,
        val chunkTexts: List<String>,
        val wasOcrUsed: Boolean,
        val extractedText: String,
        val isSkip: Boolean
    )

    private fun ledgerEntityFor(
        metadata: DocumentMetadata,
        status: String,
        failedStage: String? = null,
        failureReason: String? = null,
        attemptCount: Int = 1
    ) = IndexingLedgerEntity(
        uri = metadata.uri,
        fileName = metadata.fileName,
        mimeType = metadata.mimeType,
        size = metadata.size,
        modifiedAt = metadata.modifiedAt,
        hash = metadata.hash,
        documentType = metadata.documentType,
        status = status,
        failedStage = failedStage,
        failureReason = failureReason,
        attemptCount = attemptCount
    )

    private suspend fun writeLedger(
        metadata: DocumentMetadata,
        status: String,
        failedStage: String? = null,
        failureReason: String? = null,
        attemptCount: Int = 1
    ) {
        docDao.upsertLedgerEntry(ledgerEntityFor(metadata, status, failedStage, failureReason, attemptCount))
    }

    private suspend fun processDiscoveryList(discovered: List<DocumentMetadata>, forceRescan: Boolean) = withContext(Dispatchers.Default) {
        val total = discovered.size
        if (total == 0) {
            _indexingProgress.value = IndexingProgress("Completed", totalFiles = 0, processedFiles = 0)
            return@withContext
        }

        _indexingProgress.value = IndexingProgress("Processing", totalFiles = total, processedFiles = 0)

        // Part 2 requirement: every discovered file gets a ledger row up front, before any
        // processing begins, so a file can never silently disappear from the pipeline - it must
        // end this run in COMMITTED or FAILED (with a reason).
        withContext(Dispatchers.IO) {
            docDao.upsertLedgerEntries(discovered.map { ledgerEntityFor(it, LedgerStatus.DISCOVERED) })
        }

        // Channel for passing prepared docs from parser/chunker thread pool to the embedder/writer
        val preparedChannel = Channel<PreparedDoc>(capacity = 10)

        // Embedding inference is CPU-bound; a small pool of independent ONNX Runtime sessions -
        // each with its own session/arena - lets several documents embed concurrently instead of
        // serializing through one. Kept modest (not = core count) since the device was already
        // under real memory pressure (multi-GB swap in use) even before adding more sessions.
        val embedWorkerCount = 3
        val embedEngines = List(embedWorkerCount) { index ->
            if (index == 0) embeddingEngine else ArcticEmbedder(context)
        }
        val processedCount = java.util.concurrent.atomic.AtomicInteger(0)

        // Spawn a pool of coroutines for embedding generation & database insertion (consumers).
        // Channel consumption fans out automatically: each PreparedDoc is delivered to exactly one
        // worker, so N workers embedding different documents run truly in parallel.
        val consumerJobs = List(embedWorkerCount) { workerIndex ->
            val workerEngine = embedEngines[workerIndex]
            launch(Dispatchers.IO) {
            for (prepared in preparedChannel) {
                try {
                    _indexingProgress.value = IndexingProgress(
                        status = "Processing",
                        totalFiles = total,
                        processedFiles = processedCount.get(),
                        currentFileName = prepared.metadata.fileName
                    )

                    if (prepared.isSkip) {
                        continue
                    }

                    // Write OCR result cache if OCR was used
                    if (prepared.wasOcrUsed) {
                        val ocrEntity = OcrEntity(
                            documentId = prepared.docId,
                            pageNumber = 1,
                            text = prepared.extractedText,
                            confidence = 0.92f
                        )
                        docDao.insertOcr(ocrEntity)
                    }

                    // Insert chunks to DB
                    if (prepared.chunks.isNotEmpty()) {
                        val chunkIds = docDao.insertChunks(prepared.chunks)

                        // Generate Embeddings for chunks using batching!
                        // This deduplicates identical chunks in the corpus before running through the embedder (Requirement 6)
                        val uniqueTextsMap = mutableMapOf<String, FloatArray>()
                        val uniqueTextsList = prepared.chunkTexts.distinct()
                        
                        if (uniqueTextsList.isNotEmpty()) {
                            // Run batch embedding inference!
                            val vectors = workerEngine.embedBatch(uniqueTextsList)
                            for (i in uniqueTextsList.indices) {
                                uniqueTextsMap[uniqueTextsList[i]] = vectors.getOrElse(i) { FloatArray(ArcticEmbedder.EMBEDDING_DIM) }
                            }
                        }
                        writeLedger(prepared.metadata, LedgerStatus.EMBEDDED)

                        val embeddingEntities = chunkIds.mapIndexed { index, chunkId ->
                            val chunkText = prepared.chunkTexts[index]
                            val vector = uniqueTextsMap[chunkText] ?: FloatArray(ArcticEmbedder.EMBEDDING_DIM)
                            EmbeddingEntity(
                                chunkId = chunkId,
                                vector = vector
                            )
                        }

                        docDao.insertEmbeddings(embeddingEntities)
                        docDao.insertDocument(prepared.newDocEntity.copy(
                            id = prepared.docId,
                            ocrCompleted = prepared.wasOcrUsed,
                            embeddingCompleted = true
                        ))
                        writeLedger(prepared.metadata, LedgerStatus.COMMITTED)
                    } else {
                        // Mark completed even if empty chunks
                        docDao.insertDocument(prepared.newDocEntity.copy(
                            id = prepared.docId,
                            ocrCompleted = prepared.wasOcrUsed,
                            embeddingCompleted = true
                        ))
                        writeLedger(prepared.metadata, LedgerStatus.COMMITTED)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Failed to consumer-embed doc ${prepared.metadata.fileName}", e)
                    writeLedger(
                        prepared.metadata, LedgerStatus.FAILED,
                        failedStage = "EMBED_OR_COMMIT",
                        failureReason = e.message ?: e.toString()
                    )
                } finally {
                    val done = processedCount.incrementAndGet()
                    _indexingProgress.value = IndexingProgress(
                        status = "Processing",
                        totalFiles = total,
                        processedFiles = done,
                        currentFileName = prepared.metadata.fileName
                    )
                }
            }
            }
        }

        // Spawn multiple parser workers to process documents in parallel (producers)
        val numWorkers = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1).coerceAtMost(4)
        
        val docChannel = Channel<DocumentMetadata>(capacity = Channel.UNLIMITED)
        discovered.forEach { docChannel.send(it) }
        docChannel.close()

        val producerJobs = List(numWorkers) {
            launch(Dispatchers.Default) {
                for (metadata in docChannel) {
                    try {
                        // Check if file is already indexed and unmodified (Requirement 5: Incremental indexing)
                        val existingDoc = withContext(Dispatchers.IO) { docDao.getDocumentByUri(metadata.uri) }
                        if (existingDoc != null && !forceRescan && existingDoc.hash == metadata.hash) {
                            // Skip indexing unmodified file but report progress count
                            preparedChannel.send(PreparedDoc(
                                metadata = metadata,
                                newDocEntity = existingDoc,
                                docId = existingDoc.id,
                                chunks = emptyList(),
                                chunkTexts = emptyList(),
                                wasOcrUsed = existingDoc.ocrCompleted,
                                extractedText = "",
                                isSkip = true
                            ))
                            writeLedger(metadata, LedgerStatus.COMMITTED)
                            continue
                        }

                        // If file changed or re-indexing forced, clear existing document indices first
                        if (existingDoc != null) {
                            withContext(Dispatchers.IO) { deleteDocumentIndexSync(existingDoc.id) }
                        }

                        // Duplicate-content shortcut: many real-world corpora carry the same file
                        // in multiple places (Downloads + WhatsApp Media + backups). If a
                        // byte-identical copy has already been fully embedded elsewhere, clone its
                        // chunks/embeddings instead of re-running extraction (incl. OCR) and
                        // inference for content we've already processed.
                        val duplicateSource = withContext(Dispatchers.IO) {
                            docDao.getCompletedDocumentByHash(metadata.hash)
                        }
                        if (duplicateSource != null) {
                            val cloned = withContext(Dispatchers.IO) {
                                cloneIndexedDocument(duplicateSource, metadata)
                            }
                            preparedChannel.send(PreparedDoc(
                                metadata = metadata,
                                newDocEntity = cloned,
                                docId = cloned.id,
                                chunks = emptyList(),
                                chunkTexts = emptyList(),
                                wasOcrUsed = cloned.ocrCompleted,
                                extractedText = "",
                                isSkip = true
                            ))
                            writeLedger(metadata, LedgerStatus.COMMITTED)
                            continue
                        }

                        // Create new document entity
                        val newDocEntity = DocumentEntity(
                            uri = metadata.uri,
                            fileName = metadata.fileName,
                            mimeType = metadata.mimeType,
                            size = metadata.size,
                            modifiedAt = metadata.modifiedAt,
                            indexedAt = System.currentTimeMillis(),
                            hash = metadata.hash,
                            documentType = metadata.documentType,
                            ocrCompleted = false,
                            embeddingCompleted = false
                        )

                        val docId = withContext(Dispatchers.IO) { docDao.insertDocument(newDocEntity) }

                        // 2. Extract Text (highly parallel CPU-bound task across workers)
                        val extracted = documentExtractor.extract(Uri.parse(metadata.uri))
                        writeLedger(metadata, LedgerStatus.EXTRACTED)

                        // 3. Chunk Text
                        val chunks = Chunker.chunk(extracted.text)
                        writeLedger(metadata, LedgerStatus.CHUNKED)

                        val chunkEntities = chunks.map { chunk ->
                            // Cache tokenizer output separately from embeddings (Requirement 8)
                            val tokenIdsList = try {
                                embeddingEngine.tokenizeToIds(chunk.text)
                            } catch (e: Exception) {
                                emptyList()
                            }
                            val tokenIdsStr = if (tokenIdsList.isNotEmpty()) tokenIdsList.joinToString(",") else null

                            ChunkEntity(
                                documentId = docId,
                                text = chunk.text,
                                order = chunk.order,
                                tokenIds = tokenIdsStr
                            )
                        }

                        preparedChannel.send(PreparedDoc(
                            metadata = metadata,
                            newDocEntity = newDocEntity,
                            docId = docId,
                            chunks = chunkEntities,
                            chunkTexts = chunks.map { it.text },
                            wasOcrUsed = extracted.wasOcrUsed,
                            extractedText = extracted.text,
                            isSkip = false
                        ))

                    } catch (e: Exception) {
                        Log.e(tag, "Failed to parse document ${metadata.fileName} in worker", e)
                        writeLedger(
                            metadata, LedgerStatus.FAILED,
                            failedStage = "EXTRACT_OR_CHUNK",
                            failureReason = e.message ?: e.toString()
                        )
                        // Send dummy skip task to consumer so the progress bar increments correctly
                        preparedChannel.send(PreparedDoc(
                            metadata = metadata,
                            newDocEntity = DocumentEntity(uri = metadata.uri, fileName = metadata.fileName, mimeType = metadata.mimeType, size = metadata.size, modifiedAt = metadata.modifiedAt, indexedAt = 0L, hash = "", documentType = metadata.documentType),
                            docId = 0L,
                            chunks = emptyList(),
                            chunkTexts = emptyList(),
                            wasOcrUsed = false,
                            extractedText = "",
                            isSkip = true
                        ))
                    }
                }
            }
        }

        // Wait for all producer workers to finish
        producerJobs.forEach { it.join() }
        preparedChannel.close()

        // Wait for all embedding consumer workers to finish
        consumerJobs.forEach { it.join() }

        // Part 2 requirement: retry any file that didn't reach COMMITTED once, in case the
        // failure was transient (e.g. a memory spike under load). Sequential, not parallel -
        // this is a small cleanup pass over what should be a minority of files, and running it
        // sequentially avoids re-creating the same contention that may have caused the failure.
        val uncommitted = withContext(Dispatchers.IO) { docDao.getUncommittedLedgerEntries() }
        if (uncommitted.isNotEmpty()) {
            Log.d(tag, "Retry pass: ${uncommitted.size} file(s) not yet committed")
            for (entry in uncommitted) {
                retryLedgerEntry(entry)
            }
        }

        _indexingProgress.value = IndexingProgress("Completed", totalFiles = total, processedFiles = total)
    }

    /**
     * Re-attempts the full extract -> chunk -> embed -> commit sequence for a single file that
     * didn't reach COMMITTED in the main run. Sequential and self-contained (no channels/worker
     * pool) since this only runs over the presumably-small set of files that failed once.
     */
    private suspend fun retryLedgerEntry(entry: IndexingLedgerEntity) = withContext(Dispatchers.IO) {
        val metadata = DocumentMetadata(
            uri = entry.uri,
            fileName = entry.fileName,
            mimeType = entry.mimeType,
            size = entry.size,
            modifiedAt = entry.modifiedAt,
            hash = entry.hash,
            documentType = entry.documentType
        )
        val nextAttempt = entry.attemptCount + 1
        try {
            val existingDoc = docDao.getDocumentByUri(metadata.uri)
            if (existingDoc != null) {
                deleteDocumentIndexSync(existingDoc.id)
            }

            val freshEntity = DocumentEntity(
                uri = metadata.uri,
                fileName = metadata.fileName,
                mimeType = metadata.mimeType,
                size = metadata.size,
                modifiedAt = metadata.modifiedAt,
                indexedAt = System.currentTimeMillis(),
                hash = metadata.hash,
                documentType = metadata.documentType,
                ocrCompleted = false,
                embeddingCompleted = false
            )
            val docId = docDao.insertDocument(freshEntity)

            val extracted = documentExtractor.extract(Uri.parse(metadata.uri))
            writeLedger(metadata, LedgerStatus.EXTRACTED, attemptCount = nextAttempt)

            val chunks = Chunker.chunk(extracted.text)
            val chunkEntities = chunks.map { chunk ->
                ChunkEntity(documentId = docId, text = chunk.text, order = chunk.order)
            }
            writeLedger(metadata, LedgerStatus.CHUNKED, attemptCount = nextAttempt)

            if (extracted.wasOcrUsed) {
                docDao.insertOcr(OcrEntity(documentId = docId, pageNumber = 1, text = extracted.text, confidence = 0.92f))
            }

            if (chunkEntities.isNotEmpty()) {
                val chunkIds = docDao.insertChunks(chunkEntities)
                val uniqueTexts = chunks.map { it.text }.distinct()
                val vectors = embeddingEngine.embedBatch(uniqueTexts)
                val vectorByText = uniqueTexts.zip(vectors).toMap()
                writeLedger(metadata, LedgerStatus.EMBEDDED, attemptCount = nextAttempt)

                val embeddingEntities = chunkIds.mapIndexed { index, chunkId ->
                    val text = chunks[index].text
                    EmbeddingEntity(chunkId = chunkId, vector = vectorByText[text] ?: FloatArray(ArcticEmbedder.EMBEDDING_DIM))
                }
                docDao.insertEmbeddings(embeddingEntities)
            }

            docDao.insertDocument(
                freshEntity.copy(id = docId, ocrCompleted = extracted.wasOcrUsed, embeddingCompleted = true)
            )
            writeLedger(metadata, LedgerStatus.COMMITTED, attemptCount = nextAttempt)
            Log.d(tag, "Retry succeeded for ${metadata.fileName}")
        } catch (e: Exception) {
            Log.e(tag, "Retry failed for ${entry.fileName}", e)
            writeLedger(
                metadata, LedgerStatus.FAILED,
                failedStage = "RETRY",
                failureReason = e.message ?: e.toString(),
                attemptCount = nextAttempt
            )
        }
    }

    /**
     * Prioritizes and indexes a specific document immediately in a non-blocking foreground thread.
     */
    suspend fun indexDocumentImmediately(uriStr: String) = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "indexDocumentImmediately started for: $uriStr")
            val fileUri = Uri.parse(uriStr)
            val name = getFileNameFromUri(context, fileUri)
            val size = getFileSizeFromUri(context, fileUri)
            val modified = getFileLastModifiedFromUri(context, fileUri)
            val docType = getDocumentTypeFromExtension(name)
            val hash = md5(name + size + modified)

            val metadata = DocumentMetadata(
                uri = uriStr,
                fileName = name,
                mimeType = getMimeTypeFromUri(context, fileUri),
                size = size,
                modifiedAt = modified,
                hash = hash,
                documentType = docType
            )

            processDiscoveryList(listOf(metadata), forceRescan = true)
        } catch (e: Exception) {
            Log.e(tag, "Failed to index single document immediately: $uriStr", e)
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name = "unknown"
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex) ?: "unknown"
                }
            }
        } else if (uri.scheme == "file") {
            name = uri.lastPathSegment ?: "unknown"
        }
        return name
    }

    private fun getFileSizeFromUri(context: Context, uri: Uri): Long {
        var size = 0L
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        } else if (uri.scheme == "file") {
            val path = uri.path
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    size = file.length()
                }
            }
        }
        return size
    }

    private fun getFileLastModifiedFromUri(context: Context, uri: Uri): Long {
        var modified = System.currentTimeMillis()
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val dateIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                if (dateIndex != -1 && cursor.moveToFirst()) {
                    modified = cursor.getLong(dateIndex) * 1000
                }
            }
        } else if (uri.scheme == "file") {
            val path = uri.path
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    modified = file.lastModified()
                }
            }
        }
        return modified
    }

    private fun getMimeTypeFromUri(context: Context, uri: Uri): String {
        return context.contentResolver.getType(uri) ?: "application/octet-stream"
    }

    /**
     * Generates a realistic sandbox repository on emulator to demonstrate hybrid/vector indexing instantly.
     */
    suspend fun createSandboxFiles() = withContext(Dispatchers.IO) {
        try {
            if (!hasStoragePermission()) {
                _indexingProgress.value = IndexingProgress("Permission Denied")
                throw SecurityException("Read/Write Storage Permission is not granted. Please allow storage access first.")
            }

            _indexingProgress.value = IndexingProgress("Scanning")
        val sandboxDocuments = listOf(
            DocumentSandboxTemplate(
                name = "Arjun_Resume_2026.pdf",
                mime = "application/pdf",
                size = 145000,
                type = "PDF",
                text = "Arjun T K - Senior Software Engineer. Experience: 4 years developing native Android apps with Kotlin and Jetpack Compose. Skills: SQLite, Room, Coroutines, Flow, WorkManager, Dagger Hilt, MVVM, Clean Architecture. Projects: Implemented on-device vector search algorithms for documents. Education: Bachelor of Technology in Computer Science."
            ),
            DocumentSandboxTemplate(
                name = "Train_Ticket_Ernakulam_Bangalore.pdf",
                mime = "application/pdf",
                size = 289000,
                type = "PDF",
                text = "IRCTC E-Ticketing Passenger Reservation Association. PNR: 423-1082390. Train: 16525 island Express from Ernakulam Jn (ERS) to KSR Bengaluru (SBC). Date of Journey: March 15, 2026. Class: 2AC Tier AC sleeper. Passenger Name: Arjun TK, Age: 26, Status: Confirmed."
            ),
            DocumentSandboxTemplate(
                name = "Machine_Learning_Overview.pptx",
                mime = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                size = 4200000,
                type = "PowerPoint",
                text = "Introduction to Machine Learning. Slide 1: Welcome. Slide 2: Supervised Learning vs Unsupervised Learning. Classification, regression, clustering, K-Means, random forests, and deep neural networks. Slide 3: Neural network embeddings map words or objects to dense vectors. Vector spaces capture semantic relations: King minus Man plus Woman equals Queen."
            ),
            DocumentSandboxTemplate(
                name = "Semester_6_Timetable.xlsx",
                mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                size = 54000,
                type = "Excel",
                text = "Academic Calendar and Course Timetable. Monday 9:00 AM: Advanced Database Management Systems, 11:00 AM: Neural Networks Lab. Tuesday 10:00 AM: Machine Learning Algorithms. Wednesday 2:00 PM: Compiler Design. Semester 6 Examination begins June 10, 2026."
            ),
            DocumentSandboxTemplate(
                name = "KSEB_Electricity_Bill_March.pdf",
                mime = "application/pdf",
                size = 92000,
                type = "PDF",
                text = "Kerala State Electricity Board. Bill Date: March 12, 2026. Consumer Number: 10423901923. Billing Period: February to March 2026. Units Consumed: 240 kWh. Total Amount Due: INR 1850. Due Date: March 28, 2026."
            ),
            DocumentSandboxTemplate(
                name = "Firebase_Integration_Guide.md",
                mime = "text/markdown",
                size = 12000,
                type = "Text",
                text = "# Firebase Integration Steps\nFollow these guidelines to setup Firebase in your Android projects:\n1. Register app in Firebase Console.\n2. Download google-services.json file.\n3. Add Firestore or Firebase AI SDK to Gradle dependencies.\n4. Ensure internet permissions are configured in AndroidManifest.xml."
            )
        )

        _indexingProgress.value = IndexingProgress("Processing", totalFiles = sandboxDocuments.size, processedFiles = 0)
        var count = 0

        for (template in sandboxDocuments) {
            _indexingProgress.value = IndexingProgress("Processing", sandboxDocuments.size, count, template.name)

            // Setup a fake local file Uri or standard content scheme for index identity
            val fakeUri = "content://com.seekmydocs.sandbox/${template.name.hashCode()}"
            val docHash = md5(template.name + template.size + System.currentTimeMillis())

            val existing = docDao.getDocumentByUri(fakeUri)
            if (existing != null) {
                deleteDocumentIndexSync(existing.id)
            }

            val docEntity = DocumentEntity(
                uri = fakeUri,
                fileName = template.name,
                mimeType = template.mime,
                size = template.size,
                modifiedAt = System.currentTimeMillis() - 86400000 * count, // mock dates
                indexedAt = System.currentTimeMillis(),
                hash = docHash,
                documentType = template.type,
                ocrCompleted = template.name.endsWith(".pdf"), // PDFs can simulate OCR
                embeddingCompleted = false
            )

            val docId = docDao.insertDocument(docEntity)

            if (template.name.endsWith(".pdf")) {
                val ocr = OcrEntity(
                    documentId = docId,
                    pageNumber = 1,
                    text = template.text,
                    confidence = 0.95f
                )
                docDao.insertOcr(ocr)
            }

            val chunks = Chunker.chunk(template.text)
            if (chunks.isNotEmpty()) {
                val chunkEntities = chunks.map {
                    ChunkEntity(documentId = docId, text = it.text, order = it.order)
                }
                val chunkIds = docDao.insertChunks(chunkEntities)

                try {
                    // Semantic search is an optional enhancement — an embedding failure for
                    // this document must not abort indexing of the remaining sandbox files.
                    val embEntities = chunkIds.mapIndexed { idx, cid ->
                        EmbeddingEntity(chunkId = cid, vector = embeddingEngine.embed(chunks[idx].text))
                    }
                    docDao.insertEmbeddings(embEntities)
                    docDao.insertDocument(docEntity.copy(id = docId, embeddingCompleted = true))
                } catch (e: Exception) {
                    Log.e(tag, "Embedding failed for sandbox doc ${template.name}. Continuing without semantic search.", e)
                    docDao.insertDocument(docEntity.copy(id = docId, embeddingCompleted = false))
                }
            }

            count++
        }

        _indexingProgress.value = IndexingProgress("Completed", sandboxDocuments.size, count)
        } catch (securityException: SecurityException) {
            Log.e(tag, "Read storage permission required", securityException)
            _indexingProgress.value = IndexingProgress("Permission Denied: ${securityException.localizedMessage}")
        } catch (e: Exception) {
            Log.e(tag, "Sandbox generation failed", e)
            _indexingProgress.value = IndexingProgress("Error: ${e.localizedMessage ?: e.message ?: "Unknown error"}")
        }
    }

    /**
     * Copies an already-embedded document's chunks/embeddings/OCR onto a new document row for a
     * different URI with byte-identical content, so duplicate files skip re-extraction and
     * re-inference entirely.
     */
    private suspend fun cloneIndexedDocument(source: DocumentEntity, metadata: DocumentMetadata): DocumentEntity {
        val newDocEntity = DocumentEntity(
            uri = metadata.uri,
            fileName = metadata.fileName,
            mimeType = metadata.mimeType,
            size = metadata.size,
            modifiedAt = metadata.modifiedAt,
            indexedAt = System.currentTimeMillis(),
            hash = metadata.hash,
            documentType = metadata.documentType,
            ocrCompleted = source.ocrCompleted,
            embeddingCompleted = true
        )
        val newDocId = docDao.insertDocument(newDocEntity)

        val sourceChunks = docDao.getChunksWithEmbeddingsForDocument(source.id)
        if (sourceChunks.isNotEmpty()) {
            val newChunkIds = docDao.insertChunks(sourceChunks.map { chunk ->
                ChunkEntity(documentId = newDocId, text = chunk.text, order = chunk.order)
            })
            val newEmbeddings = newChunkIds.mapIndexed { index, chunkId ->
                EmbeddingEntity(chunkId = chunkId, vector = sourceChunks[index].vector)
            }
            docDao.insertEmbeddings(newEmbeddings)
        }

        if (source.ocrCompleted) {
            val sourceOcr = docDao.getOcrForDocument(source.id)
            docDao.insertOcrs(sourceOcr.map { it.copy(id = 0, documentId = newDocId) })
        }

        return newDocEntity.copy(id = newDocId)
    }

    private suspend fun deleteDocumentIndexSync(docId: Long) {
        docDao.deleteEmbeddingsByDocumentId(docId)
        docDao.deleteChunksByDocumentId(docId)
        docDao.deleteOcrByDocumentId(docId)
        docDao.deleteDocumentById(docId)
    }

    suspend fun deleteDocumentIndex(docId: Long) = withContext(Dispatchers.IO) {
        deleteDocumentIndexSync(docId)
    }

    suspend fun clearAllIndexes() = withContext(Dispatchers.IO) {
        docDao.deleteAllEmbeddings()
        docDao.deleteAllChunks()
        docDao.deleteAllOcrCache()
        docDao.deleteAllDocuments()
        docDao.deleteAllLedgerEntries()
    }

    private fun scanDirectoryRecursively(directory: File, discoveredFiles: MutableList<DocumentMetadata>) {
        if (!directory.exists() || !directory.isDirectory) return
        val files = directory.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                if (!file.name.startsWith(".")) {
                    scanDirectoryRecursively(file, discoveredFiles)
                }
            } else if (file.isFile) {
                val name = file.name
                val ext = name.substringAfterLast('.', "").lowercase()
                val extensions = listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "md", "json", "xml", "epub", "odt", "ods", "odp")
                if (ext in extensions) {
                    val uri = Uri.fromFile(file).toString()
                    val mime = getMimeTypeFromExtension(ext)
                    val size = file.length()
                    val dateModified = file.lastModified()
                    val documentType = getDocumentTypeFromExtension(name)
                    val hash = md5(name + size + dateModified)
                    
                    if (discoveredFiles.none { it.uri == uri || (it.fileName == name && it.size == size) }) {
                        discoveredFiles.add(
                            DocumentMetadata(
                                uri = uri,
                                fileName = name,
                                mimeType = mime,
                                size = size,
                                modifiedAt = dateModified,
                                hash = hash,
                                documentType = documentType
                            )
                        )
                    }
                }
            }
        }
    }

    private fun getMimeTypeFromExtension(ext: String): String {
        return when (ext) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            "md" -> "text/markdown"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "epub" -> "application/epub+zip"
            "odt" -> "application/vnd.oasis.opendocument.text"
            "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
            "odp" -> "application/vnd.oasis.opendocument.presentation"
            else -> "application/octet-stream"
        }
    }

    private fun getDocumentTypeFromExtension(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "PDF"
            "doc", "docx" -> "Word"
            "xls", "xlsx" -> "Excel"
            "ppt", "pptx" -> "PowerPoint"
            "odt", "ods", "odp" -> "OpenDocument"
            "epub" -> "EPUB"
            "md" -> "Markdown"
            "txt" -> "Text"
            "csv" -> "CSV"
            "json" -> "JSON"
            "xml" -> "XML"
            else -> "Document"
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private data class DocumentMetadata(
        val uri: String,
        val fileName: String,
        val mimeType: String,
        val size: Long,
        val modifiedAt: Long,
        val hash: String,
        val documentType: String
    )

    private data class DocumentSandboxTemplate(
        val name: String,
        val mime: String,
        val size: Long,
        val type: String,
        val text: String
    )

    companion object {
        @Volatile
        private var INSTANCE: IndexingRepository? = null

        fun getInstance(context: Context): IndexingRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = INSTANCE ?: run {
                    val appContext = context.applicationContext
                    val db = DocDatabase.getDatabase(appContext)
                    val docDao = db.docDao()
                    val ocrEngine = PaddleOcrEngine(appContext)
                    val docExtractor = LocalDocumentExtractor(appContext, ocrEngine)
                    val embeddingEngine = com.example.core.embeddings.DynamicEmbeddingEngine(appContext)
                    IndexingRepository(appContext, docDao, docExtractor, embeddingEngine)
                }
                INSTANCE = instance
                instance
            }
        }
    }
}
