package com.example.engine.http

import android.util.Log
import com.example.engine.buffer.StreamingCircularBuffer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class HttpDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private const val TAG = "HttpDownloader"

    interface ProgressListener {
        fun onProgress(downloaded: Long, total: Long, speedBytesPerSec: Long)
        fun onError(error: String)
        fun onCompleted()
    }

    class SpeedLimiter(private val speedLimitProvider: () -> Long) {
        private var bytesWrittenInSecond = 0L
        private var lastTimeCheck = System.currentTimeMillis()

        @Synchronized
        fun throttle(bytes: Int) {
            val limit = speedLimitProvider()
            if (limit <= 0) return

            val now = System.currentTimeMillis()
            if (now - lastTimeCheck >= 1000) {
                lastTimeCheck = now
                bytesWrittenInSecond = 0
            }

            bytesWrittenInSecond += bytes
            val expectedDuration = (bytesWrittenInSecond.toDouble() / limit) * 1000
            val elapsed = now - lastTimeCheck
            val delay = expectedDuration - elapsed

            if (delay > 5) {
                try {
                    Thread.sleep(delay.toLong())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    suspend fun download(
        url: String,
        outputFile: File,
        numConnections: Int,
        speedLimitProvider: () -> Long,
        isStreaming: Boolean,
        circularBuffer: StreamingCircularBuffer?,
        listener: ProgressListener
    ) = withContext(Dispatchers.IO) {
        val speedLimiter = SpeedLimiter(speedLimitProvider)
        val downloadedBytes = AtomicLong(0)
        var totalBytes = -1L
        var acceptRanges = false

        try {
            // Step 1: Query file metadata
            val headRequest = Request.Builder().url(url).head().build()
            client.newCall(headRequest).execute().use { response ->
                if (response.isSuccessful) {
                    totalBytes = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    val acceptRangesHeader = response.header("Accept-Ranges")
                    acceptRanges = acceptRangesHeader == "bytes" || response.code == 206
                }
            }

            // Fallback to GET if HEAD fails
            if (totalBytes <= 0) {
                val getMetadataRequest = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-0")
                    .build()
                client.newCall(getMetadataRequest).execute().use { response ->
                    if (response.isSuccessful || response.code == 206) {
                        totalBytes = response.header("Content-Range")
                            ?.substringAfterLast("/")
                            ?.toLongOrNull() ?: -1L
                        acceptRanges = response.code == 206
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get headers, will attempt direct GET stream", e)
        }

        if (totalBytes <= 0) {
            // Cannot use multi-thread without size, fallback to single thread
            acceptRanges = false
        }

        val speedTrackerScope = CoroutineScope(Dispatchers.Default + Job())
        var lastDownloadedBytes = 0L
        var lastSpeedCalcTime = System.currentTimeMillis()
        var currentSpeed = 0L

        val speedJob = speedTrackerScope.launch {
            while (isActive) {
                delay(1000)
                val currentDownloaded = downloadedBytes.get()
                val now = System.currentTimeMillis()
                val timeDiff = now - lastSpeedCalcTime
                if (timeDiff > 0) {
                    currentSpeed = ((currentDownloaded - lastDownloadedBytes) * 1000) / timeDiff
                    lastDownloadedBytes = currentDownloaded
                    lastSpeedCalcTime = now
                    listener.onProgress(currentDownloaded, totalBytes, currentSpeed)
                }
            }
        }

        try {
            if (!acceptRanges || isStreaming) {
                // Single stream download, piped to buffer (if streaming extraction) or saved directly
                if (isStreaming && circularBuffer == null) {
                    throw IllegalArgumentException("Circular buffer is required for streaming mode")
                }

                val request = Request.Builder().url(url).build()
                var retryCount = 0
                val maxRetries = 5
                var success = false

                while (retryCount < maxRetries && !success) {
                    try {
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                throw Exception("Http error: ${response.code}")
                            }
                            val body = response.body ?: throw Exception("Empty response body")
                            val inputStream = body.byteStream()
                            val buffer = ByteArray(16384)
                            var read: Int
                            var currentOffset = 0L

                            if (!isStreaming) {
                                outputFile.parentFile?.mkdirs()
                                outputFile.outputStream().use { fos ->
                                    while (inputStream.read(buffer).also { read = it } != -1) {
                                        speedLimiter.throttle(read)
                                        fos.write(buffer, 0, read)
                                        downloadedBytes.addAndGet(read.toLong())
                                        currentOffset += read
                                    }
                                }
                            } else {
                                while (inputStream.read(buffer).also { read = it } != -1) {
                                    speedLimiter.throttle(read)
                                    val chunkData = buffer.copyOf(read)
                                    circularBuffer?.writeChunk(currentOffset, chunkData)
                                    downloadedBytes.addAndGet(read.toLong())
                                    currentOffset += read
                                }
                                circularBuffer?.finish()
                            }
                            success = true
                        }
                    } catch (e: Exception) {
                        retryCount++
                        if (retryCount >= maxRetries) throw e
                        delay(2000L * retryCount) // Backoff
                    }
                }
            } else {
                // Multi-threaded accelerated download using RandomAccessFile
                outputFile.parentFile?.mkdirs()
                val raf = RandomAccessFile(outputFile, "rw")
                raf.setLength(totalBytes)
                raf.close()

                val actualConnections = minOf(numConnections, 32)
                val chunkSize = totalBytes / actualConnections
                val jobs = mutableListOf<Job>()

                for (i in 0 until actualConnections) {
                    val startBytes = i * chunkSize
                    val endBytes = if (i == actualConnections - 1) totalBytes - 1 else (i + 1) * chunkSize - 1

                    val job = launch(Dispatchers.IO) {
                        var threadDownloaded = 0L
                        var retryCount = 0
                        val maxRetries = 5
                        var threadSuccess = false

                        while (retryCount < maxRetries && !threadSuccess) {
                            try {
                                val currentStart = startBytes + threadDownloaded
                                if (currentStart > endBytes) {
                                    threadSuccess = true
                                    break
                                }

                                val rangeHeader = "bytes=$currentStart-$endBytes"
                                val request = Request.Builder()
                                    .url(url)
                                    .header("Range", rangeHeader)
                                    .build()

                                client.newCall(request).execute().use { response ->
                                    if (response.code != 206 && response.code != 200) {
                                        throw Exception("Server did not return partial content (Range). Code: ${response.code}")
                                    }
                                    val body = response.body ?: throw Exception("Empty response body")
                                    val inputStream = body.byteStream()
                                    val buffer = ByteArray(16384)
                                    var read: Int

                                    RandomAccessFile(outputFile, "rw").use { threadRaf ->
                                        threadRaf.seek(currentStart)
                                        while (inputStream.read(buffer).also { read = it } != -1) {
                                            speedLimiter.throttle(read)
                                            threadRaf.write(buffer, 0, read)
                                            threadDownloaded += read
                                            downloadedBytes.addAndGet(read.toLong())
                                        }
                                    }
                                }
                                threadSuccess = true
                            } catch (e: Exception) {
                                Log.e(TAG, "Connection $i error: ${e.message}, retrying...", e)
                                retryCount++
                                if (retryCount >= maxRetries) {
                                    throw e
                                }
                                delay(2000L * retryCount)
                            }
                        }
                    }
                    jobs.add(job)
                }

                jobs.joinAll()
            }

            speedJob.cancel()
            listener.onCompleted()
        } catch (e: Exception) {
            speedJob.cancel()
            Log.e(TAG, "HTTP download error", e)
            circularBuffer?.setError()
            listener.onError(e.message ?: "Erro desconhecido")
        } finally {
            speedTrackerScope.cancel()
        }
    }
}
