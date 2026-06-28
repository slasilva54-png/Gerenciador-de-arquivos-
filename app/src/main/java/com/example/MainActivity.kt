package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewmodel.compose.viewModel
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.presentation.DownloadViewModel
import com.example.presentation.ui.DownloadHubApp
import com.example.service.DownloadForegroundService
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    private lateinit var downloadViewModel: DownloadViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request POST_NOTIFICATIONS permission for Android 13+ (API 33+)
        requestNotificationPermission()

        // Start Foreground Service
        DownloadForegroundService.start(this)

        setContent {
            MyApplicationTheme {
                // Instantiating ViewModel
                downloadViewModel = viewModel()

                // Process any starting intent containing magnet links
                processIncomingIntent(intent)

                DownloadHubApp(viewModel = downloadViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::downloadViewModel.isInitialized) {
            processIncomingIntent(intent)
        }
    }

    private fun processIncomingIntent(intent: Intent?) {
        val data = intent?.data?.toString()
        if (data != null && data.startsWith("magnet:")) {
            Log.i(TAG, "Received magnet link share intent: $data")
            Toast.makeText(this, "Link Magnet adicionado com sucesso!", Toast.LENGTH_LONG).show()

            // Extract display name from magnet if available
            val magnetName = data.substringAfter("dn=")
                .substringBefore("&")
                .replace("+", " ")
                .let { java.net.URLDecoder.decode(it, "UTF-8") }
                .ifEmpty { "Torrent Magnet Download" }

            downloadViewModel.addTask(
                name = "$magnetName.torrent",
                url = data,
                type = "TORRENT",
                isStreaming = true,
                priority = 2,
                isLowStorageMode = false
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release resources or stop service on app termination if no active tasks
        // DownloadForegroundService.stop(this)
    }
}
