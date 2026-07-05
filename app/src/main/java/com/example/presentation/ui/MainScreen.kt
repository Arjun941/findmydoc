package com.example.presentation.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.search.SearchResult
import com.example.data.database.DocumentEntity
import com.example.data.database.SearchHistoryEntity
import com.example.data.repository.IndexingProgress
import com.example.presentation.DashboardStats
import com.example.presentation.MainViewModel
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

enum class NavigationTab(val route: String, val label: String, val icon: ImageVector) {
    SEARCH("search", "Search", Icons.Default.Search),
    DASHBOARD("dashboard", "Dashboard", Icons.Default.Analytics),
    SETTINGS("settings", "Settings", Icons.Default.Settings)
}

data class ConfirmationDialogState(
    val title: String,
    val message: String,
    val confirmText: String = "Confirm",
    val dismissText: String = "Cancel",
    val isDestructive: Boolean = false,
    val onConfirm: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(NavigationTab.SEARCH) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var confirmationState by remember { mutableStateOf<ConfirmationDialogState?>(null) }
    val context = LocalContext.current

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                viewModel.startIndexing()
            }
        }
    )

    val manageFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    viewModel.startIndexing()
                }
            }
        }
    )

    val triggerScan = { forceRescan: Boolean ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                if (forceRescan) {
                    viewModel.rebuildIndex()
                } else {
                    viewModel.startIndexing()
                }
            } else {
                showPermissionDialog = true
            }
        } else {
            val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                if (forceRescan) {
                    viewModel.rebuildIndex()
                } else {
                    viewModel.startIndexing()
                }
            } else {
                requestPermissionLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Storage Permission Required") },
            text = { 
                Text("FindMyDoc needs \"All Files Access\" to scan and index document files (PDF, Excel, Word, Text, EPUB) on your device storage. All processing is kept offline and secure on your device.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionDialog = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                manageFilesLauncher.launch(intent)
                            } catch (e: Exception) {
                                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                manageFilesLauncher.launch(intent)
                            }
                        }
                    }
                ) {
                    Text("Grant Permission")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                NavigationTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, fontSize = 11.sp) },
                        modifier = Modifier.testTag("nav_${tab.route}")
                    )
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            when (selectedTab) {
                NavigationTab.SEARCH -> SearchTabContent(
                    viewModel = viewModel,
                    onScanRequest = triggerScan,
                    onConfirmAction = { confirmationState = it }
                )
                NavigationTab.DASHBOARD -> DashboardTabContent(viewModel = viewModel, onScanRequest = triggerScan)
                NavigationTab.SETTINGS -> SettingsTabContent(
                    viewModel = viewModel,
                    onScanRequest = triggerScan,
                    onConfirmAction = { confirmationState = it }
                )
            }
        }
    }

    confirmationState?.let { state ->
        AlertDialog(
            onDismissRequest = { confirmationState = null },
            title = { Text(state.title, fontWeight = FontWeight.Bold) },
            text = { Text(state.message) },
            confirmButton = {
                Button(
                    onClick = {
                        state.onConfirm()
                        confirmationState = null
                    },
                    colors = if (state.isDestructive) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(state.confirmText)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmationState = null }) {
                    Text(state.dismissText)
                }
            },
            modifier = Modifier.testTag("confirmation_modal")
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTabContent(
    viewModel: MainViewModel,
    onScanRequest: (Boolean) -> Unit,
    onConfirmAction: (ConfirmationDialogState) -> Unit
) {
    val context = LocalContext.current
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val indexingProgress by viewModel.indexingProgress.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()

    var selectedResultForActions by remember { mutableStateOf<SearchResult?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Title Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.FindInPage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "FindMyDoc",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1.0f))
            Badge(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Text("Offline Secure", modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 10.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Search text field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            placeholder = { Text("Search your offline files...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search Icon") },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear Search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_bar_input")
        )

        Spacer(modifier = Modifier.height(12.dp))

        // State switcher
        if (searchQuery.isBlank()) {
            // Indexing progress card
            if (indexingProgress.status != "Idle" && indexingProgress.status != "Completed") {
                IndexingProgressCard(indexingProgress)
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Sandbox Generator Hero Banner
            if (documents.isEmpty()) {
                NoDocumentsHeroCard(
                    onIndexRequest = { onScanRequest(false) },
                    onSandboxRequest = {
                        onConfirmAction(
                            ConfirmationDialogState(
                                title = "Load Sandbox Files",
                                message = "Would you like to inject simulated sample documents into your offline index? This lets you test FindMyDoc's hybrid keyword and vector semantic search instantly, without needing actual files on your storage.",
                                confirmText = "Load Sandbox",
                                onConfirm = { viewModel.injectSandboxDocuments() }
                            )
                        )
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1.0f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (searchHistory.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Recent Searches",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                TextButton(onClick = { viewModel.clearSearchHistory() }) {
                                    Text("Clear All", fontSize = 12.sp)
                                }
                            }
                        }

                        items(searchHistory.take(5)) { item ->
                            RecentQueryItem(
                                item = item,
                                onClick = { viewModel.updateSearchQuery(item.query) },
                                onDelete = { viewModel.deleteSearchHistoryItem(item.query) }
                            )
                        }
                    }

                    item {
                        Text(
                            text = "Recently Indexed Files (${documents.size})",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    items(documents.take(15)) { doc ->
                        DocumentListRow(
                            document = doc,
                            onClick = { viewModel.openDocument(context, doc) },
                            onLongClick = {
                                selectedResultForActions = SearchResult(
                                    document = doc,
                                    score = 100f,
                                    snippet = "",
                                    matchedQueryTerms = emptyList(),
                                    isSemanticMatch = false,
                                    isFilenameMatch = false,
                                    isOcrMatch = false
                                )
                            }
                        )
                    }
                }
            }
        } else {
            // Search Results display
            if (isSearching) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.0f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (searchResults.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.0f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FindInPage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "No matching documents found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Try different keywords or check Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                Text(
                    text = "${searchResults.size} matches found",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                LazyColumn(
                    modifier = Modifier.weight(1.0f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(searchResults) { result ->
                        SearchResultItemCard(
                            result = result,
                            onClick = { viewModel.openDocument(context, result.document) },
                            onLongClick = { selectedResultForActions = result }
                        )
                    }
                }
            }
        }
    }

    selectedResultForActions?.let { result ->
        DocumentActionDialog(
            result = result,
            onDismiss = { selectedResultForActions = null },
            onOpen = {
                viewModel.openDocument(context, result.document)
                selectedResultForActions = null
            },
            onShare = {
                viewModel.shareDocument(context, result.document)
                selectedResultForActions = null
            },
            onRevealLocation = {
                viewModel.revealLocation(context, result.document)
                selectedResultForActions = null
            },
            onDeleteIndex = {
                onConfirmAction(
                    ConfirmationDialogState(
                        title = "Remove from Index",
                        message = "Are you sure you want to remove '${result.document.fileName}' from the offline search engine index? The actual physical file on your device storage will not be affected, but you won't be able to search its text or contents until the next rescan.",
                        confirmText = "Remove Index",
                        isDestructive = true,
                        onConfirm = {
                            viewModel.deleteDocumentIndex(result.document.id)
                        }
                    )
                )
                selectedResultForActions = null
            }
        )
    }
}

@Composable
fun IndexingProgressCard(progress: IndexingProgress) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Sync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Indexing engine is active",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Status: ${progress.status}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )
            if (progress.totalFiles > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress.processedFiles.toFloat() / progress.totalFiles },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Processed: ${progress.processedFiles}/${progress.totalFiles}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = progress.currentFileName,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.widthIn(max = 180.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun NoDocumentsHeroCard(
    onIndexRequest: () -> Unit,
    onSandboxRequest: () -> Unit
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Privacy First Document Search",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "FindMyDoc scans your device locally to enable instant keyword and semantic vector search on PDFs, Excel spreadsheets, text docs, and PowerPoint presentation notes. No file contents ever leave your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 4.dp),
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onIndexRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("start_indexing_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.FindInPage, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan Storage for Documents")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onSandboxRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("inject_sandbox_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CardTravel, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Load Sandbox Sample Files")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentQueryItem(
    item: SearchHistoryEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClick = onClick
            )
            .padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = item.query,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.0f)
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Delete from history",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentListRow(
    document: DocumentEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = getFileTypeColor(document.documentType).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = getFileTypeIcon(document.documentType),
                contentDescription = null,
                tint = getFileTypeColor(document.documentType),
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1.0f)) {
            Text(
                text = document.fileName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = document.documentType,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = getFileTypeColor(document.documentType)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatFileSize(document.size),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(onClick = onLongClick) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Document options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchResultItemCard(
    result: SearchResult,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val document = result.document

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = getFileTypeColor(document.documentType).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getFileTypeIcon(document.documentType),
                        contentDescription = null,
                        tint = getFileTypeColor(document.documentType),
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1.0f)) {
                    Text(
                        text = document.fileName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = document.documentType,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = getFileTypeColor(document.documentType)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatFileSize(document.size),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                if (result.isSemanticMatch) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                            Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(10.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("Semantic Match", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (result.isOcrMatch) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                            Icon(Icons.Default.DocumentScanner, contentDescription = null, modifier = Modifier.size(10.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("OCR Scan Match", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (result.snippet.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = buildHighlightedSnippet(result.snippet, result.matchedQueryTerms),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val formattedDate = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(document.modifiedAt))
                Text(
                    text = "Modified: $formattedDate",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )

                if (result.openCount > 0) {
                    Text(
                        text = "Opened ${result.openCount} times",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
fun buildHighlightedSnippet(snippet: String, queryTerms: List<String>): AnnotatedString {
    return remember(snippet, queryTerms) {
        buildAnnotatedString {
            if (queryTerms.isEmpty()) {
                append(snippet)
                return@buildAnnotatedString
            }

            val lowerSnippet = snippet.lowercase(Locale.ROOT)
            var currentIndex = 0

            while (currentIndex < snippet.length) {
                var closestMatchIndex = -1
                var closestTermLength = 0

                for (term in queryTerms) {
                    val idx = lowerSnippet.indexOf(term, currentIndex)
                    if (idx != -1) {
                        if (closestMatchIndex == -1 || idx < closestMatchIndex) {
                            closestMatchIndex = idx
                            closestTermLength = term.length
                        }
                    }
                }

                if (closestMatchIndex == -1) {
                    append(snippet.substring(currentIndex))
                    break
                }

                if (closestMatchIndex > currentIndex) {
                    append(snippet.substring(currentIndex, closestMatchIndex))
                }

                val matchString = snippet.substring(closestMatchIndex, closestMatchIndex + closestTermLength)
                withStyle(
                    style = SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD48A00),
                    )
                ) {
                    append(matchString)
                }

                currentIndex = closestMatchIndex + closestTermLength
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardTabContent(viewModel: MainViewModel, onScanRequest: (Boolean) -> Unit) {
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val stats by viewModel.dashboardStats.collectAsStateWithLifecycle()
    val indexingProgress by viewModel.indexingProgress.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Indexing Dashboard",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Secure local knowledge base engine",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) {
                StatCard(
                    title = "Documents Indexed",
                    value = stats.documentsIndexed.toString(),
                    icon = Icons.Default.Description,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f)) {
                StatCard(
                    title = "Text Chunks",
                    value = stats.totalChunks.toString(),
                    icon = Icons.Default.Segment,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) {
                StatCard(
                    title = "Vectors Generated",
                    value = stats.embeddingsGenerated.toString(),
                    icon = Icons.Default.Psychology,
                    color = Color(0xFF8E24AA)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f)) {
                StatCard(
                    title = "OCR Scanned Pages",
                    value = stats.ocrCachedPages.toString(),
                    icon = Icons.Default.DocumentScanner,
                    color = Color(0xFF00ACC1)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Engine Storage Metrics",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total Indexed Payload Size:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatFileSize(stats.storageUsageBytes), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Last Database Sync:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val timeString = if (stats.lastIndexingTime > 0) {
                        SimpleDateFormat("MMM d, yyyy HH:mm:ss", Locale.getDefault()).format(Date(stats.lastIndexingTime))
                    } else "Never"
                    Text(timeString, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Knowledge Base Types Distribution",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(vertical = 4.dp)
        )

        if (documents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.0f),
                contentAlignment = Alignment.Center
            ) {
                Text("No data to report. Load sandbox files to start.", fontSize = 12.sp)
            }
        } else {
            val typeBreakdown = remember(documents) {
                documents.groupBy { it.documentType }.mapValues { it.value.size }
            }

            LazyColumn(
                modifier = Modifier.weight(1.0f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 8.dp)
            ) {
                typeBreakdown.forEach { (type, count) ->
                    item {
                        FileDistributionRow(
                            type = type,
                            count = count,
                            total = documents.size
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onScanRequest(false) },
                modifier = Modifier.weight(1.0f),
                enabled = indexingProgress.status == "Idle" || indexingProgress.status == "Completed"
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Incremental Sync", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = { viewModel.clearAllIndexes() },
                modifier = Modifier.weight(1.0f)
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Wipe Engine Index", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    icon: ImageVector,
    color: Color
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun FileDistributionRow(
    type: String,
    count: Int,
    total: Int
) {
    val percentage = (count.toFloat() / total)
    val color = getFileTypeColor(type)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(type, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text("$count files (${(percentage * 100).toInt()}%)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { percentage },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTabContent(
    viewModel: MainViewModel,
    onScanRequest: (Boolean) -> Unit,
    onConfirmAction: (ConfirmationDialogState) -> Unit
) {
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val stats by viewModel.dashboardStats.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Engine Settings",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Configure your offline discovery behavior",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatic Sync & Watcher", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Watch background folder alterations via MediaStore observers and periodic background tasks.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings.enableAutoIndexing,
                            onCheckedChange = { viewModel.updateAutoIndexing(it) },
                            modifier = Modifier.testTag("setting_auto_sync")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Optical Character Recognition (OCR)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Use ML Kit to render and extract readable texts from image-only PDFs and scanned paper docs.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings.enableOcr,
                            onCheckedChange = { viewModel.updateOcr(it) },
                            modifier = Modifier.testTag("setting_ocr")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Semantic Vector Search", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Generate on-device neural embeddings for document chunks to enable conceptual natural language query matches.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings.enableSemanticSearch,
                            onCheckedChange = { viewModel.updateSemanticSearch(it) },
                            modifier = Modifier.testTag("setting_semantic")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Application Theme", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            val themeLabel = when (settings.isDarkMode) {
                                true -> "Dark theme manually active"
                                false -> "Light theme manually active"
                                null -> "Match system theme preference"
                            }
                            Text(themeLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(null to "Auto", false to "Light", true to "Dark").forEach { (value, label) ->
                                val isSelected = settings.isDarkMode == value
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.updateDarkMode(value) },
                                    label = { Text(label, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                    modifier = Modifier.testTag("theme_chip_$label")
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Active Embedding Model", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Gemma-300M (144d - SentencePiece)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            text = "Fixed Default",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("engine_fixed_label")
                        )
                    }
                }
            }
        }

        item {
            Text("Engine Operations", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onConfirmAction(
                            ConfirmationDialogState(
                                title = "Rebuild Document Index",
                                message = "Are you sure you want to rebuild your search index from scratch? This will clear all existing document mappings and perform a full storage rescan to re-extract texts and generate neural embeddings.",
                                confirmText = "Rebuild Index",
                                isDestructive = true,
                                onConfirm = { onScanRequest(true) }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Rebuild Document Index from Scratch")
                }

                OutlinedButton(
                    onClick = {
                        onConfirmAction(
                            ConfirmationDialogState(
                                title = "Clear Search History",
                                message = "Are you sure you want to clear your local search query history? This cannot be undone.",
                                confirmText = "Clear History",
                                isDestructive = true,
                                onConfirm = { viewModel.clearSearchHistory() }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.History, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear Local Search Query History")
                }

                OutlinedButton(
                    onClick = {
                        onConfirmAction(
                            ConfirmationDialogState(
                                title = "Delete All Embeddings & Cache",
                                message = "Are you sure you want to delete all offline embeddings and cached OCR text? This will completely empty your database. You will need to trigger a full storage scan to discover and index files again.",
                                confirmText = "Delete All",
                                isDestructive = true,
                                onConfirm = { viewModel.clearAllIndexes() }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ClearAll, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete Offline Embeddings & Cache")
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Privacy Manifest", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "FindMyDoc functions 100% offline. No telemetry APIs, remote servers, cloud networks, or accounts are configured. Your document structures, extracted phrases, and mathematical query vectors remain strictly localized in SQLite on your device.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
fun DocumentActionDialog(
    result: SearchResult,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRevealLocation: () -> Unit,
    onDeleteIndex: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                result.document.fileName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onOpen,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Document File", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share/Send File", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onRevealLocation,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Show Secured Location Path", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onDeleteIndex,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Remove from Engine Index Only", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        },
        modifier = Modifier.testTag("doc_action_dialog")
    )
}

fun getFileTypeIcon(type: String): ImageVector {
    return when (type) {
        "PDF" -> Icons.Outlined.PictureAsPdf
        "Word" -> Icons.Outlined.Article
        "Excel" -> Icons.Outlined.GridOn
        "PowerPoint" -> Icons.Outlined.Slideshow
        "OpenDocument" -> Icons.Outlined.SnippetFolder
        "EPUB" -> Icons.Outlined.Book
        "Text", "Markdown" -> Icons.Outlined.StickyNote2
        "CSV" -> Icons.Outlined.ViewWeek
        "JSON", "XML" -> Icons.Outlined.Code
        else -> Icons.Outlined.InsertDriveFile
    }
}

fun getFileTypeColor(type: String): Color {
    return when (type) {
        "PDF" -> Color(0xFFE53935)
        "Word" -> Color(0xFF1E88E5)
        "Excel" -> Color(0xFF43A047)
        "PowerPoint" -> Color(0xFFF4511E)
        "OpenDocument" -> Color(0xFF3949AB)
        "EPUB" -> Color(0xFF5E35B1)
        "Text", "Markdown" -> Color(0xFF757575)
        "CSV" -> Color(0xFF00897B)
        "JSON", "XML" -> Color(0xFFD81B60)
        else -> Color(0xFF5D4037)
    }
}

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}
