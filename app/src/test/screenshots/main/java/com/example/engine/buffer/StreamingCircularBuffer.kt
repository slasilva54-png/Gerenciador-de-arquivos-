package com.example.engine.buffer

import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class StreamingCircularBuffer(
    private val totalSize: Long,
    private val maxBufferSize: Int = 16 * 1024 * 1024 // 16 MB max memory cache
) : InputStream() {

    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private val writeCondition = lock.newCondition()

    // Map of offset -> chunk data
    private val chunks = mutableMapOf<Long, ByteArray>()
    private var readPosition: Long = 0
    private var currentBufferBytes = 0
    private var isFinished = false
    private var errorOccurred = false

    fun writeChunk(offset: Long, data: ByteArray) {
        lock.withLock {
            // Flow control: if adding this chunk exceeds maxBufferSize, block download threads
            while (currentBufferBytes + data.size > maxBufferSize && !isFinished && !errorOccurred) {
                try {
                    writeCondition.await()
                } catch (e: InterruptedException) {
                    return
                }
            }

            if (isFinished || errorOccurred) return

            chunks[offset] = data
            currentBufferBytes += data.size
            condition.signalAll() // Notify reading/decompressing threads
        }
    }

    fun finish() {
        lock.withLock {
            isFinished = true
            condition.signalAll()
            writeCondition.signalAll()
        }
    }

    fun setError() {
        lock.withLock {
            errorOccurred = true
            condition.signalAll()
            writeCondition.signalAll()
        }
    }

    fun getBufferedSize(): Int = lock.withLock { currentBufferBytes }

    override fun read(): Int {
        val b = ByteArray(1)
        val read = read(b, 0, 1)
        return if (read == -1) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0

        lock.withLock {
            while (true) {
                if (errorOccurred) {
                    return -1
                }

                // Find the chunk containing the current readPosition
                val matchingChunkOffset = chunks.keys.firstOrNull { offset ->
                    val chunkData = chunks[offset] ?: return@firstOrNull false
                    readPosition >= offset && readPosition < offset + chunkData.size
                }

                if (matchingChunkOffset != null) {
                    val chunkData = chunks[matchingChunkOffset]!!
                    val chunkRelativeOffset = (readPosition - matchingChunkOffset).toInt()
                    val availableInChunk = chunkData.size - chunkRelativeOffset
                    val toCopy = minOf(len, availableInChunk)

                    System.arraycopy(chunkData, chunkRelativeOffset, b, off, toCopy)
                    readPosition += toCopy

                    // Clean up fully consumed chunks to free RAM (auto-clean)
                    if (readPosition >= matchingChunkOffset + chunkData.size) {
                        chunks.remove(matchingChunkOffset)
                        currentBufferBytes -= chunkData.size
                        writeCondition.signalAll() // Let download threads resume fetching
                    }

                    return toCopy
                }

                // If finished and no chunks match, we've hit EOF
                if (isFinished && chunks.isEmpty()) {
                    return -1
                }

                try {
                    // Block and wait for chunks to be downloaded and written
                    condition.await()
                } catch (e: InterruptedException) {
                    return -1
                }
            }
        }
    }

    override fun close() {
        lock.withLock {
            isFinished = true
            chunks.clear()
            currentBufferBytes = 0
            condition.signalAll()
            writeCondition.signalAll()
        }
    }
}
