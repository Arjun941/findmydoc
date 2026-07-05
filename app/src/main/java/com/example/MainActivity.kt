package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.indexing.BackgroundSyncWorker
import com.example.core.indexing.MediaStoreObserver
import com.example.presentation.MainViewModel
import com.example.presentation.ui.MainScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel
    private var mediaStoreObserver: MediaStoreObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Initialize ViewModel
        viewModel = MainViewModel(applicationContext)

        // 2. Schedule WorkManager Background Sync (undercharging, idle, battery constraints)
        try {
            BackgroundSyncWorker.schedule(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Initialize real-time MediaStore Content Observer
        mediaStoreObserver = MediaStoreObserver(applicationContext) {
            // Trigger incremental synchronization on device storage updates
            viewModel.startIndexing(forceRescan = false)
        }

        // 4. Trigger standard startup sync on launch
        viewModel.startIndexing(forceRescan = false)

        setContent {
            val settingsState by viewModel.settingsState.collectAsStateWithLifecycle()
            val useDarkTheme = settingsState.isDarkMode ?: androidx.compose.foundation.isSystemInDarkTheme()
            
            MyApplicationTheme(darkTheme = useDarkTheme) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Register ContentObserver on active foreground use
        mediaStoreObserver?.register()
    }

    override fun onStop() {
        super.onStop()
        // Unregister ContentObserver when app is backgrounded to preserve battery
        mediaStoreObserver?.unregister()
    }
}
