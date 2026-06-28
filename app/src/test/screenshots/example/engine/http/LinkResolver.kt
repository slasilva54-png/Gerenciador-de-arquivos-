package com.example.engine.http

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder

object LinkResolver {
    private const val TAG = "LinkResolver"

    data class ResolvedResult(
        val directUrl: String,
        val fileName: String?
    )

    fun resolve(url: String, client: OkHttpClient): ResolvedResult {
        try {
            if (url.contains("mediafire.com")) {
                return resolveMediaFire(url, client)
            } else if (url.contains("drive.google.com") || url.contains("docs.google.com")) {
                return resolveGoogleDrive(url, client)
            } else if (url.contains("dropbox.com")) {
                return resolveDropbox(url)
            } else if (url.contains("github.com") && url.contains("/blob/")) {
                return resolveGitHub(url)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving URL $url", e)
        }
        // Fallback to original
        return ResolvedResult(url, null)
    }

    private fun resolveMediaFire(url: String, client: OkHttpClient): ResolvedResult {
        Log.i(TAG, "Resolving MediaFire link: $url")
        // Try to get filename from original URL path first
        // E.g. https://www.mediafire.com/file/7x868u8u8/my_file.zip/file
        var extractedFileName: String? = null
        try {
            if (url.contains("/file/")) {
                val pathSegments = url.substringAfter("mediafire.com/file/").split("/")
                if (pathSegments.size >= 2) {
                    extractedFileName = URLDecoder.decode(pathSegments[1], "UTF-8")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse filename from MediaFire URL path", e)
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                
                // Regular expressions to find the direct download link
                val patterns = listOf(
                    Regex("""href=["'](https?://download[a-zA-Z0-9\-\.]*\.mediafire\.com/[^"']+)["']"""),
                    Regex("""(https?://download[a-zA-Z0-9\-\.]*\.mediafire\.com/[^\s"'\>]+)"""),
                    Regex("""href=["'](https?://www\.mediafire\.com/download/[^"']+)["']""")
                )

                for (pattern in patterns) {
                    val match = pattern.find(html)
                    if (match != null) {
                        val directUrl = match.groupValues.getOrNull(1) ?: match.value
                        Log.i(TAG, "Found MediaFire direct URL: $directUrl")

                        // Try to find the filename in the HTML if not already extracted
                        if (extractedFileName == null) {
                            // Extract filename from heading / page title or layout
                            // usually there is a <div class="filename">my_file.zip</div>
                            val nameMatch = Regex("""class="filename">([^<]+)""").find(html)
                            if (nameMatch != null) {
                                extractedFileName = nameMatch.groupValues[1].trim()
                            }
                        }
                        return ResolvedResult(directUrl, extractedFileName)
                    }
                }
            }
        }
        return ResolvedResult(url, extractedFileName)
    }

    private fun resolveGoogleDrive(url: String, client: OkHttpClient): ResolvedResult {
        Log.i(TAG, "Resolving Google Drive link: $url")
        var fileId: String? = null
        
        val fileIdMatch = Regex("""/file/d/([a-zA-Z0-9_\-]+)""").find(url)
        if (fileIdMatch != null) {
            fileId = fileIdMatch.groupValues[1]
        } else {
            val idMatch = Regex("""[?&]id=([a-zA-Z0-9_\-]+)""").find(url)
            if (idMatch != null) {
                fileId = idMatch.groupValues[1]
            }
        }

        if (fileId == null) {
            return ResolvedResult(url, null)
        }

        var directUrl = "https://drive.google.com/uc?export=download&id=$fileId"
        var resolvedFileName: String? = null

        try {
            // Execute HEAD request first to check if we get a direct file or a warning page
            val checkRequest = Request.Builder().url(directUrl).build()
            client.newCall(checkRequest).execute().use { response ->
                val contentType = response.header("Content-Type") ?: ""
                val contentDisposition = response.header("Content-Disposition") ?: ""
                
                if (contentDisposition.contains("filename=")) {
                    resolvedFileName = parseContentDispositionFilename(contentDisposition)
                }

                // If it returns HTML, it's likely the large file virus scan warning page
                if (contentType.contains("text/html")) {
                    val html = response.body?.string() ?: ""
                    val confirmMatch = Regex("""confirm=([a-zA-Z0-9_\-]+)""").find(html)
                    if (confirmMatch != null) {
                        val confirmToken = confirmMatch.groupValues[1]
                        directUrl = "https://drive.google.com/uc?export=download&id=$fileId&confirm=$confirmToken"
                        Log.i(TAG, "Bypassed Google Drive virus scan screen using token. New URL: $directUrl")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing check on Google Drive link", e)
        }

        return ResolvedResult(directUrl, resolvedFileName)
    }

    private fun resolveDropbox(url: String): ResolvedResult {
        Log.i(TAG, "Resolving Dropbox link: $url")
        var directUrl = url
        if (url.contains("www.dropbox.com")) {
            directUrl = url.replace("www.dropbox.com", "dl.dropboxusercontent.com")
            if (directUrl.contains("?dl=0")) {
                directUrl = directUrl.substringBefore("?dl=0")
            } else if (directUrl.contains("?dl=1")) {
                directUrl = directUrl.substringBefore("?dl=1")
            }
        } else if (url.contains("?dl=0")) {
            directUrl = url.replace("?dl=0", "?dl=1")
        }

        // Parse filename from URL path
        var fileName: String? = null
        try {
            val decodedUrl = URLDecoder.decode(directUrl, "UTF-8")
            val cleanUrl = decodedUrl.substringBefore("?")
            fileName = cleanUrl.substringAfterLast("/")
            if (fileName.isEmpty()) fileName = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Dropbox filename", e)
        }

        return ResolvedResult(directUrl, fileName)
    }

    private fun resolveGitHub(url: String): ResolvedResult {
        Log.i(TAG, "Resolving GitHub page link: $url")
        // e.g., https://github.com/user/repo/blob/branch/file.ext
        // raw link: https://raw.githubusercontent.com/user/repo/branch/file.ext
        val directUrl = url.replace("github.com", "raw.githubusercontent.com")
                           .replace("/blob/", "/")
        
        var fileName: String? = null
        try {
            fileName = directUrl.substringAfterLast("/")
            if (fileName.isEmpty()) fileName = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse GitHub filename", e)
        }

        return ResolvedResult(directUrl, fileName)
    }

    private fun parseContentDispositionFilename(header: String): String? {
        try {
            val key = "filename="
            if (header.contains(key)) {
                var name = header.substringAfter(key).trim()
                if (name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length - 1)
                } else if (name.contains(";")) {
                    name = name.substringBefore(";").trim()
                }
                return URLDecoder.decode(name, "UTF-8")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed parsing content-disposition header", e)
        }
        return null
    }
}
