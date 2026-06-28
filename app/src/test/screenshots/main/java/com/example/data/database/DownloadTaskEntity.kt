package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.DownloadTask

@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val type: String,
    val status: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val speedBytesPerSec: Long,
    val progress: Float,
    val peersCount: Int,
    val isStreamingExtraction: Boolean,
    val outputDirectory: String,
    val error: String?,
    val createdAt: Long,
    val priority: Int,
    val isLowStorageMode: Boolean,
    val speedLimitBytesPerSec: Long,
    val httpMirrorUrl: String?
) {
    fun toDomain(): DownloadTask {
        return DownloadTask(
            id = id,
            name = name,
            url = url,
            type = type,
            status = status,
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            speedBytesPerSec = speedBytesPerSec,
            progress = progress,
            peersCount = peersCount,
            isStreamingExtraction = isStreamingExtraction,
            outputDirectory = outputDirectory,
            error = error,
            createdAt = createdAt,
            priority = priority,
            isLowStorageMode = isLowStorageMode,
            speedLimitBytesPerSec = speedLimitBytesPerSec,
            httpMirrorUrl = httpMirrorUrl
        )
    }

    companion object {
        fun fromDomain(task: DownloadTask): DownloadTaskEntity {
            return DownloadTaskEntity(
                id = task.id,
                name = task.name,
                url = task.url,
                type = task.type,
                status = task.status,
                totalBytes = task.totalBytes,
                downloadedBytes = task.downloadedBytes,
                speedBytesPerSec = task.speedBytesPerSec,
                progress = task.progress,
                peersCount = task.peersCount,
                isStreamingExtraction = task.isStreamingExtraction,
                outputDirectory = task.outputDirectory,
                error = task.error,
                createdAt = task.createdAt,
                priority = task.priority,
                isLowStorageMode = task.isLowStorageMode,
                speedLimitBytesPerSec = task.speedLimitBytesPerSec,
                httpMirrorUrl = task.httpMirrorUrl
            )
        }
    }
}
