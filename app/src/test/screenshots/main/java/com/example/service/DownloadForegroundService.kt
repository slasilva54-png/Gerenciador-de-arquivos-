package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.SmartDownloadApp
import com.example.domain.model.DownloadTask
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class DownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val isServiceRunning = AtomicBoolean(false)

    companion object {
        private const val CHANNEL_ID = "smart_download_hub_channel"
        private const val NOTIFICATION_ID = 8824

        const val ACTION_START = "com.example.service.START"
        const val ACTION_STOP = "com.example.service.STOP"
        const val ACTION_PAUSE = "com.example.service.PAUSE"
        const val ACTION_RESUME = "com.example.service.RESUME"
        const val EXTRA_TASK_ID = "extra_task_id"

        fun start(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isServiceRunning.getAndSet(true)) {
                    startForegroundWithNotification()
                    observeTasks()
                }
            }
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
            }
            ACTION_PAUSE -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (taskId != null) {
                    SmartDownloadApp.instance.downloadManager.pauseTask(taskId)
                }
            }
            ACTION_RESUME -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (taskId != null) {
                    SmartDownloadApp.instance.downloadManager.startTask(taskId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildProgressNotification("Iniciando SmartDownload Hub...", 0, "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeTasks() {
        serviceScope.launch {
            SmartDownloadApp.instance.repository.allTasks.collect { tasks ->
                val activeTasks = tasks.filter { it.status == "DOWNLOADING" || it.status == "EXTRACTING" }
                if (activeTasks.isEmpty()) {
                    // Update notification to ready state
                    val notification = buildProgressNotification(
                        "SmartDownload Hub pronto",
                        0,
                        "Nenhum download ativo no momento"
                    )
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                    return@collect
                }

                // Compute aggregated metrics
                val totalSpeed = activeTasks.sumOf { it.speedBytesPerSec }
                val avgProgress = activeTasks.map { it.progress }.average().toFloat()
                val speedText = formatSpeed(totalSpeed)

                val message = "${activeTasks.size} download(s) ativos | Velocidade: $speedText"
                val notification = buildProgressNotification(message, avgProgress.toInt(), "Progresso médio: ${avgProgress.toInt()}%")

                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildProgressNotification(title: String, progress: Int, text: String): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setProgress(100, progress, progress == 0 && text.contains("Iniciando"))
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SmartDownload Hub Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Canal de progresso de downloads ativos em segundo plano"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0 B/s"
        val kb = bytesPerSec / 1024f
        if (kb < 1024) return String.format("%.1f KB/s", kb)
        val mb = kb / 1024f
        return String.format("%.1f MB/s", mb)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        isServiceRunning.set(false)
    }
}
