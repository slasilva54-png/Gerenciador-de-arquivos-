package com.example.domain.model

data class DownloadTask(
    val id: String,
    val name: String,
    val url: String,
    val type: String, // "HTTP", "TORRENT", "HYBRID"
    val status: String, // "PENDING", "DOWNLOADING", "PAUSED", "EXTRACTING", "COMPLETED", "ERROR"
    val totalBytes: Long,
    val downloadedBytes: Long,
    val speedBytesPerSec: Long,
    val progress: Float, // 0.0 to 100.0
    val peersCount: Int,
    val isStreamingExtraction: Boolean,
    val outputDirectory: String,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val priority: Int = 2, // 1 = Low, 2 = Medium, 3 = High
    val isLowStorageMode: Boolean = false,
    val speedLimitBytesPerSec: Long = 0, // 0 means unlimited
    val httpMirrorUrl: String? = null
) {
    val etaSeconds: Long
        get() {
            if (speedBytesPerSec <= 0 || totalBytes <= 0) return -1
            val remainingBytes = totalBytes - downloadedBytes
            if (remainingBytes <= 0) return 0
            return remainingBytes / speedBytesPerSec
        }
}
