package com.example.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.SmartDownloadApp
import com.example.domain.model.DownloadTask
import com.example.engine.torrent.TorrentClient
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class DownloadViewModel : ViewModel() {
    private val repository = SmartDownloadApp.instance.repository
    private val manager = SmartDownloadApp.instance.downloadManager

    val tasks: StateFlow<List<DownloadTask>> = repository.allTasks
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val activeSpeeds: StateFlow<Map<String, Long>> = manager.activeSpeeds
    val activePeers: StateFlow<Map<String, List<TorrentClient.SwarmPeer>>> = manager.activePeers

    fun addTask(
        name: String,
        url: String,
        type: String,
        isStreamingExtraction: Boolean,
        priority: Int,
        isLowStorageMode: Boolean,
        httpMirrorUrl: String? = null
    ) {
        viewModelScope.launch {
            val task = DownloadTask(
                id = UUID.randomUUID().toString(),
                name = name,
                url = url,
                type = type,
                status = "PENDING",
                totalBytes = 0,
                downloadedBytes = 0,
                speedBytesPerSec = 0,
                progress = 0f,
                peersCount = 0,
                isStreamingExtraction = isStreamingExtraction,
                outputDirectory = "",
                priority = priority,
                isLowStorageMode = isLowStorageMode,
                httpMirrorUrl = httpMirrorUrl
            )
            repository.insertOrUpdate(task)
            manager.startTask(task.id)
        }
    }

    fun startTask(taskId: String) {
        manager.startTask(taskId)
    }

    fun pauseTask(taskId: String) {
        manager.pauseTask(taskId)
    }

    fun removeTask(taskId: String) {
        manager.removeTask(taskId)
    }

    fun updateSpeedLimit(taskId: String, limit: Long) {
        manager.updateSpeedLimit(taskId, limit)
    }

    fun updateLowStorageMode(taskId: String, enable: Boolean) {
        manager.updateLowStorageMode(taskId, enable)
    }

    fun changePriority(taskId: String, priority: Int) {
        manager.changePriority(taskId, priority)
    }
}
