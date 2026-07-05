package com.example.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.extraction.LocalDocumentExtractor
import com.example.core.ocr.LocalOcrEngine
import com.example.core.search.SearchPipeline
import com.example.core.search.SearchResult
import com.example.data.database.DocDao
import com.example.data.database.DocDatabase
import com.example.data.database.DocumentEntity
import com.example.data.database.DocumentUsageEntity
import com.example.data.database.SearchHistoryEntity
import com.example.data.repository.IndexingProgress
import com.example.data.repository.IndexingRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardStats(
    val documentsIndexed: Int = 0,
    val totalChunks: Int = 0,
    val embeddingsGenerated: Int = 0,
    val ocrCachedPages: Int = 0,
    val storageUsageBytes: Long = 0,
    val lastIndexingTime: Long = 0
)

data class SettingsState(
    val enableAutoIndexing: Boolean = true,
    val enableOcr: Boolean = true,
    val enableSemanticSearch: Boolean = true,
    val isDarkMode: Boolean? = null, // null means system preference
    val selectedEngine: String = "gemma"
)

@OptIn(FlowPreview::class)
class MainViewModel(private val context: Context) : ViewModel() {

    private val db = DocDatabase.getDatabase(context)
    val docDao: DocDao = db.docDao()

    private val ocrEngine = LocalOcrEngine()
    private val docExtractor = LocalDocumentExtractor(context, ocrEngine)
    val embeddingEngine = com.example.core.embeddings.DynamicEmbeddingEngine(context)

    private val searchPipeline = SearchPipeline(docDao, embeddingEngine)
    private val indexRepository = IndexingRepository.getInstance(context)

    // Indexing progress
    val indexingProgress: StateFlow<IndexingProgress> = indexRepository.indexingProgress

    // Document list
    val documents: StateFlow<List<DocumentEntity>> = docDao.getAllDocuments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Search parameters
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    // History
    val searchHistory: StateFlow<List<SearchHistoryEntity>> = docDao.getSearchHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Shared preferences for settings persistence
    private val prefs = context.getSharedPreferences("findmydoc_settings", Context.MODE_PRIVATE)

    // Settings
    private val _settingsState = MutableStateFlow(SettingsState())
    val settingsState = _settingsState.asStateFlow()

    // Dashboard calculations
    val dashboardStats: StateFlow<DashboardStats> = combine(
        documents,
        // Calculate statistics directly from the database
        _searchQuery // dummy state flow to trigger update on recalculation request
    ) { docs, _ ->
        computeDashboardStats(docs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardStats())

    init {
        // Load settings from persistent preferences
        val autoIdx = prefs.getBoolean("enable_auto_indexing", true)
        val ocr = prefs.getBoolean("enable_ocr", true)
        val sem = prefs.getBoolean("enable_semantic", true)
        val dark = if (prefs.contains("is_dark_mode")) prefs.getBoolean("is_dark_mode", false) else null
        val engine = prefs.getString("selected_embedding_engine", "gemma") ?: "gemma"
        
        _settingsState.value = SettingsState(
            enableAutoIndexing = autoIdx,
            enableOcr = ocr,
            enableSemanticSearch = sem,
            isDarkMode = dark,
            selectedEngine = engine
        )

        // Monitor search query changes and trigger search with debounce for efficiency
        viewModelScope.launch {
            _searchQuery
                .debounce(300)
                .collect { query ->
                    executeSearch(query)
                }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private fun executeSearch(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }

        viewModelScope.launch {
            _isSearching.value = true
            try {
                val settings = _settingsState.value
                val results = searchPipeline.search(
                    query = query,
                    enableSemantic = settings.enableSemanticSearch,
                    enableOcr = settings.enableOcr
                )
                _searchResults.value = results

                // Save search query to history if results exist
                if (results.isNotEmpty()) {
                    docDao.insertSearchHistory(SearchHistoryEntity(query = query))
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Search execution error", e)
            } finally {
                _isSearching.value = false
            }
        }
    }

    // --- Search interactions ---
    fun deleteSearchHistoryItem(query: String) {
        viewModelScope.launch {
            docDao.deleteSearchHistoryQuery(query)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            docDao.clearSearchHistory()
        }
    }

    // --- Index management ---
    fun startIndexing(forceRescan: Boolean = false) {
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.example.core.indexing.OnDemandIndexingWorker>()
            .setInputData(androidx.work.workDataOf(
                "action" to "index",
                "force_rescan" to forceRescan
            ))
            .build()
        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
            "OnDemandIndexingWork",
            androidx.work.ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun injectSandboxDocuments() {
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.example.core.indexing.OnDemandIndexingWorker>()
            .setInputData(androidx.work.workDataOf(
                "action" to "sandbox"
            ))
            .build()
        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
            "OnDemandIndexingWork",
            androidx.work.ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun clearAllIndexes() {
        viewModelScope.launch {
            indexRepository.clearAllIndexes()
            _searchResults.value = emptyList()
            _searchQuery.value = ""
        }
    }

    fun deleteDocumentIndex(docId: Long) {
        viewModelScope.launch {
            indexRepository.deleteDocumentIndex(docId)
            // Trigger refresh
            executeSearch(_searchQuery.value)
        }
    }

    fun rebuildIndex() {
        viewModelScope.launch {
            indexRepository.clearAllIndexes()
            startIndexing(forceRescan = true)
        }
    }

    private fun getShareableUri(context: Context, uriString: String): Uri {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val filePath = uri.path ?: return uri
            val file = java.io.File(filePath)
            if (file.exists()) {
                return try {
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to generate content URI via FileProvider", e)
                    uri
                }
            }
        }
        return uri
    }

    // --- Document Interactions ---
    fun openDocument(context: Context, doc: DocumentEntity) {
        viewModelScope.launch {
            // Priority/Lazy Indexing: index this document immediately in a non-blocking background coroutine
            try {
                indexRepository.indexDocumentImmediately(doc.uri)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error indexing prioritized doc", e)
            }

            // Track usage analytics
            try {
                val currentUsage = docDao.getDocumentUsage(doc.id)
                val newUsage = DocumentUsageEntity(
                    documentId = doc.id,
                    openCount = (currentUsage?.openCount ?: 0) + 1,
                    lastOpened = System.currentTimeMillis()
                )
                docDao.insertDocumentUsage(newUsage)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error updating usage log", e)
            }

            // Launch view intent
            try {
                val shareableUri = getShareableUri(context, doc.uri)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(shareableUri, doc.mimeType)
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // If SAF viewer fails because it's a simulated sandbox URI, mock it with a helpful toast
                if (doc.uri.startsWith("content://com.findmydoc.sandbox")) {
                    Toast.makeText(
                        context,
                        "Opening Sandbox File: ${doc.fileName} (${doc.documentType})\nEverything runs secure, offline!",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(context, "No suitable reader found for ${doc.fileName}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun revealLocation(context: Context, doc: DocumentEntity) {
        Toast.makeText(
            context,
            "File path: ${doc.uri}\nOffline Location Secured.",
            Toast.LENGTH_LONG
        ).show()
    }

    fun shareDocument(context: Context, doc: DocumentEntity) {
        try {
            val shareableUri = getShareableUri(context, doc.uri)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = doc.mimeType
                putExtra(Intent.EXTRA_STREAM, shareableUri)
                putExtra(Intent.EXTRA_SUBJECT, "Sharing: ${doc.fileName}")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(Intent.createChooser(intent, "Share Document"))
        } catch (e: Exception) {
            if (doc.uri.startsWith("content://com.findmydoc.sandbox")) {
                Toast.makeText(
                    context,
                    "Sharing sandbox placeholder file: ${doc.fileName}",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(context, "Failed to share ${doc.fileName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Settings management ---
    fun updateAutoIndexing(enabled: Boolean) {
        _settingsState.value = _settingsState.value.copy(enableAutoIndexing = enabled)
        prefs.edit().putBoolean("enable_auto_indexing", enabled).apply()
    }

    fun updateOcr(enabled: Boolean) {
        _settingsState.value = _settingsState.value.copy(enableOcr = enabled)
        prefs.edit().putBoolean("enable_ocr", enabled).apply()
    }

    fun updateSemanticSearch(enabled: Boolean) {
        _settingsState.value = _settingsState.value.copy(enableSemanticSearch = enabled)
        prefs.edit().putBoolean("enable_semantic", enabled).apply()
    }

    fun updateDarkMode(enabled: Boolean?) {
        _settingsState.value = _settingsState.value.copy(isDarkMode = enabled)
        if (enabled == null) {
            prefs.edit().remove("is_dark_mode").apply()
        } else {
            prefs.edit().putBoolean("is_dark_mode", enabled).apply()
        }
    }

    fun updateEmbeddingEngine(engine: String) {
        _settingsState.value = _settingsState.value.copy(selectedEngine = engine)
        prefs.edit().putString("selected_embedding_engine", engine).apply()
    }

    // --- Stat computation helper ---
    private suspend fun computeDashboardStats(docs: List<DocumentEntity>): DashboardStats {
        var chunkCount = 0
        var embeddingCount = 0
        var ocrCount = 0
        var storageUsed = 0L
        var lastTime = 0L

        for (doc in docs) {
            storageUsed += doc.size
            if (doc.indexedAt > lastTime) {
                lastTime = doc.indexedAt
            }
            
            val docChunks = docDao.getChunksForDocument(doc.id)
            chunkCount += docChunks.size
            
            val ocrItems = docDao.getOcrForDocument(doc.id)
            ocrCount += ocrItems.size
        }

        // We can query all chunks with embeddings
        val chunksWithEmbeds = docDao.getAllChunksWithEmbeddings()
        embeddingCount = chunksWithEmbeds.size

        return DashboardStats(
            documentsIndexed = docs.size,
            totalChunks = chunkCount,
            embeddingsGenerated = embeddingCount,
            ocrCachedPages = ocrCount,
            storageUsageBytes = storageUsed,
            lastIndexingTime = lastTime
        )
    }
}
