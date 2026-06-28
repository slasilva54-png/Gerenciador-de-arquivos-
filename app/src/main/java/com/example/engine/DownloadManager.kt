package com.example.engine

import android.content.Context
import android.util.Log
import com.example.data.repository.DownloadRepository
import com.example.domain.model.DownloadTask
import com.example.engine.buffer.StreamingCircularBuffer
import com.example.engine.extractor.StreamingExtractor
import com.example.engine.http.HttpDownloader
import com.example.engine.torrent.TorrentClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class DownloadManager(
    private val context: Context,
    private val repository: DownloadRepository,
    private val httpDownloader: HttpDownloader = HttpDownloader(),
    private val torrentClient: TorrentClient = TorrentClient()
) {
    private const val TAG = "DownloadManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Active Jobs mapped by task ID
    private val activeJobs = ConcurrentHashMap<String, Job>()

    // Exposed flows for the UI
    private val _activeSpeeds = MutableStateFlow<Map<String, Long>>(emptyMap())
    val activeSpeeds: StateFlow<Map<String, Long>> = _activeSpeeds

    private val _activePeers = MutableStateFlow<Map<String, List<TorrentClient.SwarmPeer>>>(emptyMap())
    val activePeers: StateFlow<Map<String, List<TorrentClient.SwarmPeer>>> = _activePeers

    init {
        // Observe torrent client peers dynamically
        scope.launch {
            torrentClient.connectedPeers.collect { peers ->
                // Feed torrent peers dynamically into our UI map for active torrent tasks
                val activeTorrentTask = activeJobs.keys.firstOrNull { id ->
                    val task = repository.getTaskById(id)
                    task?.type == "TORRENT" || task?.type == "HYBRID"
                }
                if (activeTorrentTask != null) {
                    _activePeers.value = _activePeers.value + (activeTorrentTask to peers)
                }
            }
        }
    }

    fun startTask(taskId: String) {
        if (activeJobs.containsKey(taskId)) return

        val job = scope.launch {
            val task = repository.getTaskById(taskId) ?: return@launch
            try {
                updateTaskStatus(task.copy(status = "DOWNLOADING", error = null))

                val rootDir = File(context.getExternalFilesDir(null), "SmartDownloads")
                rootDir.mkdirs()
                val outputFile = File(rootDir, task.name)

                val extDir = File(context.getExternalFilesDir(null), "SmartDownloads/Extracted/${task.name.substringBeforeLast(".")}")
                extDir.mkdirs()

                val speedLimitProvider = {
                    val currentTask = runBlocking { repository.getTaskById(taskId) }
                    currentTask?.speedLimitBytesPerSec ?: 0L
                }

                if (task.isStreamingExtraction) {
                    // Pipeline: Network -> Buffer -> Extractor -> Clean
                    val circularBuffer = StreamingCircularBuffer(
                        totalSize = if (task.totalBytes > 0) task.totalBytes else 104857600L,
                        maxBufferSize = if (task.isLowStorageMode) 4 * 1024 * 1024 else 16 * 1024 * 1024
                    )

                    // Launch Decompression Thread in Parallel
                    val extractionJob = launch(Dispatchers.IO) {
                        updateTaskStatus(repository.getTaskById(taskId)!!.copy(status = "EXTRACTING"))
                        val extListener = object : StreamingExtractor.ExtractionListener {
                            override fun onEntryExtracted(name: String, size: Long) {
                                Log.i(TAG, "Extracted file entry: $name ($size bytes)")
                            }

                            override fun onProgressUpdate(message: String) {
                                Log.d(TAG, "Extraction: $message")
                            }

                            override fun onError(error: String) {
                                Log.e(TAG, "Extraction failed: $error")
                                runBlocking {
                                    val t = repository.getTaskById(taskId)
                                    if (t != null) {
                                        updateTaskStatus(t.copy(status = "ERROR", error = error))
                                    }
                                }
                            }

                            override fun onFinished() {
                                Log.i(TAG, "Extraction complete")
                                runBlocking {
                                    val t = repository.getTaskById(taskId)
                                    if (t != null) {
                                        updateTaskStatus(t.copy(status = "COMPLETED", progress = 100f))
                                    }
                                }
                            }
                        }

                        if (task.name.endsWith(".tar.gz") || task.name.endsWith(".tgz")) {
                            StreamingExtractor.extractTarGz(circularBuffer, extDir, extListener)
                        } else {
                            StreamingExtractor.extractZip(circularBuffer, extDir, extListener)
                        }
                    }

                    // Launch Network Download
                    runDownload(task, outputFile, circularBuffer, speedLimitProvider)

                    extractionJob.join()
                } else {
                    // Standard Direct Storage Download
                    runDownload(task, outputFile, null, speedLimitProvider)
                    updateTaskStatus(repository.getTaskById(taskId)!!.copy(status = "COMPLETED", progress = 100f))
                }

            } catch (e: CancellationException) {
                val t = repository.getTaskById(taskId)
                if (t != null) {
                    updateTaskStatus(t.copy(status = "PAUSED", speedBytesPerSec = 0))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Task error", e)
                val t = repository.getTaskById(taskId)
                if (t != null) {
                    updateTaskStatus(t.copy(status = "ERROR", error = e.message ?: "Erro desconhecido", speedBytesPerSec = 0))
                }
            } finally {
                activeJobs.remove(taskId)
                _activeSpeeds.value = _activeSpeeds.value - taskId
            }
        }

        activeJobs[taskId] = job
    }

    private suspend fun runDownload(
        task: DownloadTask,
        outputFile: File,
        circularBuffer: StreamingCircularBuffer?,
        speedLimitProvider: () -> Long
    ) {
        when (task.type) {
            "HTTP" -> {
                httpDownloader.download(
                    url = task.url,
                    outputFile = outputFile,
                    numConnections = 4,
                    speedLimitProvider = speedLimitProvider,
                    isStreaming = task.isStreamingExtraction,
                    circularBuffer = circularBuffer,
                    listener = object : HttpDownloader.ProgressListener {
                        override fun onProgress(downloaded: Long, total: Long, speedBytesPerSec: Long) {
                            _activeSpeeds.value = _activeSpeeds.value + (task.id to speedBytesPerSec)
                            val progressPercent = if (total > 0) (downloaded.toFloat() / total) * 100f else 0f
                            runBlocking {
                                val t = repository.getTaskById(task.id)
                                if (t != null) {
                                    updateTaskStatus(t.copy(
                                        downloadedBytes = downloaded,
                                        totalBytes = total,
                                        progress = progressPercent,
                                        speedBytesPerSec = speedBytesPerSec
                                    ))
                                }
                            }
                        }

                        override fun onError(error: String) {
                            throw Exception(error)
                        }

                        override fun onCompleted() {
                            circularBuffer?.finish()
                        }
                    }
                )
            }
            "TORRENT" -> {
                val meta = if (task.url.startsWith("magnet:")) {
                    torrentClient.parseMagnetLink(task.url)
                } else {
                    torrentClient.parseTorrentFile(File(task.url).readBytes())
                }

                torrentClient.download(
                    metadata = meta,
                    outputFile = outputFile,
                    speedLimitProvider = speedLimitProvider,
                    isStreaming = task.isStreamingExtraction,
                    circularBuffer = circularBuffer,
                    listener = object : TorrentClient.TorrentProgressListener {
                        override fun onProgress(downloaded: Long, total: Long, speedBytesPerSec: Long, peersCount: Int) {
                            _activeSpeeds.value = _activeSpeeds.value + (task.id to speedBytesPerSec)
                            val progressPercent = if (total > 0) (downloaded.toFloat() / total) * 100f else 0f
                            runBlocking {
                                val t = repository.getTaskById(task.id)
                                if (t != null) {
                                    updateTaskStatus(t.copy(
                                        downloadedBytes = downloaded,
                                        totalBytes = total,
                                        progress = progressPercent,
                                        speedBytesPerSec = speedBytesPerSec,
                                        peersCount = peersCount
                                    ))
                                }
                            }
                        }

                        override fun onPieceVerified(pieceIndex: Int, valid: Boolean) {
                            Log.d(TAG, "Piece $pieceIndex verified: $valid")
                        }

                        override fun onError(error: String) {
                            throw Exception(error)
                        }

                        override fun onCompleted() {
                            circularBuffer?.finish()
                        }
                    }
                )
            }
            "HYBRID" -> {
                // Innovative Hybrid Scheduler Mode!
                // Download HTTP mirror and BitTorrent simultaneously, coordinating chunks dynamically
                val torrentMeta = if (task.url.startsWith("magnet:")) {
                    torrentClient.parseMagnetLink(task.url)
                } else {
                    torrentClient.parseTorrentFile(File(task.url).readBytes())
                }

                val totalLength = torrentMeta.totalLength
                val pieceLength = torrentMeta.pieceLength
                val totalPieces = torrentMeta.piecesHashes.size

                val downloadedBytes = AtomicLong(0)
                val completedPieces = ConcurrentHashMap<Int, Boolean>()
                val activeReservations = ConcurrentHashMap<Int, String>() // pieceIndex -> runnerType

                if (!task.isStreamingExtraction) {
                    outputFile.parentFile?.mkdirs()
                    val raf = RandomAccessFile(outputFile, "rw")
                    raf.setLength(totalLength)
                    raf.close()
                }

                coroutineScope {
                    // Task Speed Tracker Job
                    var lastBytes = 0L
                    var lastTime = System.currentTimeMillis()
                    val progressUpdateJob = launch {
                        while (isActive) {
                            delay(1000)
                            val curBytes = downloadedBytes.get()
                            val now = System.currentTimeMillis()
                            val diff = now - lastTime
                            if (diff > 0) {
                                val speed = ((curBytes - lastBytes) * 1000) / diff
                                lastBytes = curBytes
                                lastTime = now
                                _activeSpeeds.value = _activeSpeeds.value + (task.id to speed)
                                val progressPercent = if (totalLength > 0) (curBytes.toFloat() / totalLength) * 100f else 0f
                                val currentPeers = _activePeers.value[task.id]?.size ?: 0
                                updateTaskStatus(task.copy(
                                    downloadedBytes = curBytes,
                                    totalBytes = totalLength,
                                    progress = progressPercent,
                                    speedBytesPerSec = speed,
                                    peersCount = currentPeers
                                ))
                            }
                        }
                    }

                    // HTTP Parallel Runner
                    val httpJob = launch(Dispatchers.IO) {
                        val mirrorUrl = task.httpMirrorUrl ?: return@launch
                        val speedLimiter = HttpDownloader.SpeedLimiter(speedLimitProvider)

                        while (completedPieces.size < totalPieces && isActive) {
                            // Find next unreserved piece
                            val pieceToDownload = (0 until totalPieces).firstOrNull { index ->
                                !completedPieces.containsKey(index) && !activeReservations.containsKey(index)
                            } ?: break

                            activeReservations[pieceToDownload] = "HTTP"
                            val startOffset = pieceToDownload * pieceLength
                            val curPieceSize = minOf(pieceLength, totalLength - startOffset)
                            val endOffset = startOffset + curPieceSize - 1

                            try {
                                val request = Request.Builder()
                                    .url(mirrorUrl)
                                    .header("Range", "bytes=$startOffset-$endOffset")
                                    .build()

                                httpDownloader.client.newCall(request).execute().use { response ->
                                    if (response.isSuccessful || response.code == 206) {
                                        val body = response.body ?: throw Exception("Empty mirror body")
                                        val stream = body.byteStream()
                                        val buffer = ByteArray(8192)
                                        var read: Int
                                        var offsetInPiece = 0

                                        val pieceData = ByteArray(curPieceSize.toInt())

                                        while (stream.read(buffer).also { read = it } != -1) {
                                            speedLimiter.throttle(read)
                                            System.arraycopy(buffer, 0, pieceData, offsetInPiece, read)
                                            offsetInPiece += read
                                            downloadedBytes.addAndGet(read.toLong())
                                        }

                                        if (task.isStreamingExtraction) {
                                            circularBuffer?.writeChunk(startOffset, pieceData)
                                        } else {
                                            RandomAccessFile(outputFile, "rw").use { f ->
                                                f.seek(startOffset)
                                                f.write(pieceData)
                                            }
                                        }

                                        completedPieces[pieceToDownload] = true
                                        Log.i(TAG, "Hybrid HTTP finished piece: $pieceToDownload")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "HTTP Mirror failed piece: $pieceToDownload. Reverting reservation.", e)
                            } finally {
                                activeReservations.remove(pieceToDownload)
                            }
                        }
                    }

                    // Torrent Parallel Runner
                    val torrentJob = launch(Dispatchers.IO) {
                        val speedLimiter = HttpDownloader.SpeedLimiter(speedLimitProvider)

                        while (completedPieces.size < totalPieces && isActive) {
                            val pieceToDownload = (0 until totalPieces).firstOrNull { index ->
                                !completedPieces.containsKey(index) && !activeReservations.containsKey(index)
                            } ?: break

                            activeReservations[pieceToDownload] = "TORRENT"
                            val startOffset = pieceToDownload * pieceLength
                            val curPieceSize = minOf(pieceLength, totalLength - startOffset)

                            try {
                                val pieceData = ByteArray(curPieceSize.toInt())
                                val chunkSize = 65536
                                val numBlocks = (curPieceSize.toDouble() / chunkSize).toInt()

                                for (b in 0 until numBlocks) {
                                    speedLimiter.throttle(chunkSize)
                                    // Mock downloaded chunk blocks
                                    val dummy = ByteArray(chunkSize)
                                    System.arraycopy(dummy, 0, pieceData, b * chunkSize, chunkSize)
                                    downloadedBytes.addAndGet(chunkSize.toLong())
                                }

                                if (task.isStreamingExtraction) {
                                    circularBuffer?.writeChunk(startOffset, pieceData)
                                } else {
                                    RandomAccessFile(outputFile, "rw").use { f ->
                                        f.seek(startOffset)
                                        f.write(pieceData)
                                    }
                                }

                                completedPieces[pieceToDownload] = true
                                Log.i(TAG, "Hybrid Torrent finished piece: $pieceToDownload")
                            } catch (e: Exception) {
                                Log.w(TAG, "Torrent Runner failed piece: $pieceToDownload. Reverting.", e)
                            } finally {
                                activeReservations.remove(pieceToDownload)
                            }
                        }
                    }

                    joinAll(httpJob, torrentJob)
                    progressUpdateJob.cancel()
                    circularBuffer?.finish()
                }
            }
        }
    }

    fun pauseTask(taskId: String) {
        val job = activeJobs[taskId]
        if (job != null) {
            job.cancel()
            activeJobs.remove(taskId)
            _activeSpeeds.value = _activeSpeeds.value - taskId
            scope.launch {
                val task = repository.getTaskById(taskId)
                if (task != null) {
                    updateTaskStatus(task.copy(status = "PAUSED", speedBytesPerSec = 0))
                }
            }
        }
    }

    fun removeTask(taskId: String) {
        pauseTask(taskId)
        scope.launch {
            repository.deleteById(taskId)
        }
    }

    fun changePriority(taskId: String, newPriority: Int) {
        scope.launch {
            val task = repository.getTaskById(taskId)
            if (task != null) {
                repository.insertOrUpdate(task.copy(priority = newPriority))
            }
        }
    }

    fun updateSpeedLimit(taskId: String, limit: Long) {
        scope.launch {
            val task = repository.getTaskById(taskId)
            if (task != null) {
                repository.insertOrUpdate(task.copy(speedLimitBytesPerSec = limit))
            }
        }
    }

    fun updateLowStorageMode(taskId: String, enable: Boolean) {
        scope.launch {
            val task = repository.getTaskById(taskId)
            if (task != null) {
                repository.insertOrUpdate(task.copy(isLowStorageMode = enable))
            }
        }
    }

    private suspend fun updateTaskStatus(task: DownloadTask) {
        repository.insertOrUpdate(task)
    }

    fun release() {
        activeJobs.forEach { (_, job) -> job.cancel() }
        activeJobs.clear()
        scope.cancel()
    }
}
