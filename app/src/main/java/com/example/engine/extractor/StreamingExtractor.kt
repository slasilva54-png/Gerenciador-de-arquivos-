package com.example.engine.extractor

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object StreamingExtractor {
    private const val TAG = "StreamingExtractor"

    interface ExtractionListener {
        fun onEntryExtracted(name: String, size: Long)
        fun onProgressUpdate(message: String)
        fun onError(error: String)
        fun onFinished()
    }

    fun extractZip(inputStream: InputStream, outputDir: File, listener: ExtractionListener) {
        try {
            val zipIn = ZipInputStream(inputStream)
            var entry = zipIn.nextEntry
            while (entry != null) {
                val entryName = entry.name.trim()
                if (entryName.isEmpty() || entryName == "/" || entryName == "." || entryName == "./") {
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                    continue
                }

                val outFile = File(outputDir, entry.name)
                // Prevent path traversal security vulnerabilities (Zip Slip)
                if (!outFile.canonicalPath.startsWith(outputDir.canonicalPath)) {
                    listener.onError("Relative path traversal detected in ZIP entry!")
                    return
                }

                if (outFile.canonicalPath == outputDir.canonicalPath) {
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                    continue
                }

                if (entry.isDirectory || (outFile.exists() && outFile.isDirectory)) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    listener.onProgressUpdate("Extraindo: ${entry.name}")
                    BufferedOutputStream(FileOutputStream(outFile)).use { out ->
                        val buffer = ByteArray(4096)
                        var len: Int
                        while (zipIn.read(buffer).also { len = it } > 0) {
                            out.write(buffer, 0, len)
                        }
                    }
                    listener.onEntryExtracted(entry.name, entry.size)
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
            listener.onFinished()
        } catch (e: Exception) {
            Log.e(TAG, "Error in ZIP streaming extraction", e)
            listener.onError("Falha na extração do ZIP: ${e.message}")
        }
    }

    fun extractTarGz(inputStream: InputStream, outputDir: File, listener: ExtractionListener) {
        try {
            val gzipIn = GZIPInputStream(inputStream)
            val buffer = ByteArray(512)

            while (true) {
                // Read 512 bytes header block
                var headerRead = 0
                while (headerRead < 512) {
                    val r = gzipIn.read(buffer, headerRead, 512 - headerRead)
                    if (r == -1) break
                    headerRead += r
                }
                if (headerRead < 512) break // End of file

                // Check if empty header (TAR padding)
                if (buffer.all { it == 0.toByte() }) {
                    continue
                }

                // Extract file name (first 100 bytes)
                val name = String(buffer, 0, 100).trim { it <= ' ' || it == '\u0000' }
                if (name.isEmpty()) break

                // Extract file size in octal (bytes 124 to 135)
                val sizeStr = String(buffer, 124, 12).trim { it <= ' ' || it == '\u0000' }
                val size = if (sizeStr.isNotEmpty()) sizeStr.toLong(8) else 0L

                // File type flag (byte 156)
                val typeFlag = buffer[156].toInt().toChar()

                val outFile = File(outputDir, name)
                if (!outFile.canonicalPath.startsWith(outputDir.canonicalPath)) {
                    listener.onError("Relative path traversal detected in TAR entry!")
                    return
                }

                if (outFile.canonicalPath == outputDir.canonicalPath) {
                    var remaining = size
                    val tempBuf = ByteArray(4096)
                    while (remaining > 0) {
                        val toRead = minOf(remaining, tempBuf.size.toLong()).toInt()
                        val r = gzipIn.read(tempBuf, 0, toRead)
                        if (r == -1) break
                        remaining -= r
                    }
                    val padding = (512 - (size % 512)) % 512
                    var skipped = 0L
                    while (skipped < padding) {
                        val s = gzipIn.skip(padding - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                    continue
                }

                if (typeFlag == '5') { // Directory
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    listener.onProgressUpdate("Extraindo TAR: $name")
                    BufferedOutputStream(FileOutputStream(outFile)).use { out ->
                        var remaining = size
                        val tempBuf = ByteArray(4096)
                        while (remaining > 0) {
                            val toRead = minOf(remaining, tempBuf.size.toLong()).toInt()
                            val r = gzipIn.read(tempBuf, 0, toRead)
                            if (r == -1) throw IllegalStateException("Unexpected EOF in TAR entry data")
                            out.write(tempBuf, 0, r)
                            remaining -= r
                        }
                    }
                    // Skip TAR padding to next 512-byte boundary
                    val padding = (512 - (size % 512)) % 512
                    var skipped = 0L
                    while (skipped < padding) {
                        val s = gzipIn.skip(padding - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                    listener.onEntryExtracted(name, size)
                }
            }
            listener.onFinished()
        } catch (e: Exception) {
            Log.e(TAG, "Error in TAR.GZ streaming extraction", e)
            listener.onError("Falha na extração do TAR.GZ: ${e.message}")
        }
    }
}
