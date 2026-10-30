package com.vidflow.app.data.downloader

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vidflow.app.data.model.Platform
import com.vidflow.app.data.model.VideoFormat
import com.vidflow.app.data.model.VideoInfo
import com.vidflow.app.data.session.SessionCookies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.OutputStream

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBps: Long,
    val percent: Int,
    /** True while a merge step runs. Speed is meaningless then: UI hides it. */
    val isMerging: Boolean = false,
)

sealed class DownloadResult {
    data class Success(val filePath: String, val fileSize: Long) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
    /** Paused yt-dlp job: .part files kept, resumable with the same download id. */
    object Paused : DownloadResult()
    object Cancelled : DownloadResult()
}

class DownloadEngine(
    private val context: Context,
    private val client: OkHttpClient,
) {
    /**
     * Direct HTTP download with HTTP Range resume (brief 3.5).
     *
     * The bytes always land in a .part file first, so a resumed download can
     * pick up where it stopped: on entry, if a .part file for this download
     * id exists, a "Range: bytes=<already-saved>-" header is sent and the
     * response is appended. A 200 (server ignored Range) restarts from zero.
     * Only on success is the finished file published to Downloads/VidFlow.
     * Cancel deletes the .part file; pause only holds the loop (see the
     * pausedIds wait below) and never deletes anything.
     *
     * Unchanged on purpose: 32KB buffer, 250ms progress interval.
     */
    private fun sanitizeFileName(title: String): String {
        return title
            .replace(Regex("""[\r\n\t\u0000-\u001f\\/:*?"<>|]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.', '_', '-')
            .take(60)
            .ifEmpty { "video" }
    }

    suspend fun download(
        info: VideoInfo,
        format: VideoFormat,
        downloadUrl: String,
        downloadId: String = "",
        onProgress: suspend (DownloadProgress) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {

        val safeTitle = sanitizeFileName(info.title)
        val fileName = "${safeTitle}.${format.ext}"

        val partFile = partFileFor(downloadId, fileName)
        val mimeType = when (format.ext.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "webm" -> if (format.hasAudio && !format.hasVideo) "audio/webm" else "video/webm"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            else -> "video/mp4"
        }
        val isAudioOnly = format.hasAudio && !format.hasVideo

        try {
            val alreadySaved = if (partFile.exists()) partFile.length() else 0L
            val reqBuilder = Request.Builder().url(downloadUrl)
            if (alreadySaved > 0) {
                reqBuilder.addHeader("Range", "bytes=$alreadySaved-")
            }
            when (info.platform) {
                Platform.YOUTUBE -> reqBuilder
                    .addHeader("User-Agent", "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip")
                    .addHeader("Referer", "https://www.youtube.com/")
                Platform.TIKTOK -> reqBuilder
                    .addHeader("User-Agent", "TikTok 31.9.4 rv:319404 (iPhone; iOS 17.0; en_US) Cronet")
                    .addHeader("Referer", "https://www.tiktok.com/")
                Platform.INSTAGRAM -> reqBuilder
                    .addHeader("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15")
                    .addHeader("Referer", "https://www.instagram.com/")
                Platform.FACEBOOK -> reqBuilder
                    .addHeader("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15")
                    .addHeader("Referer", "https://www.facebook.com/")
                else -> reqBuilder
                    .addHeader("User-Agent", "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36")
                    .addHeader("Referer", info.url)
            }
            // Logged-in session (browser/login tab) travels with the download.
            SessionCookies.getCookieHeader(downloadUrl)?.let { cookie ->
                reqBuilder.addHeader("Cookie", cookie)
            }

            val response = client.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful && response.code != 206) {
                return@withContext DownloadResult.Error(
                    "Download failed (HTTP ${response.code}). The link may have expired — please re-analyze the URL and try again.",
                )
            }
            val resumed = response.code == 206 && alreadySaved > 0
            val startAt = if (resumed) alreadySaved else 0L
            if (!resumed && alreadySaved > 0) {
                // Server ignored Range: restart cleanly rather than corrupting.
                partFile.delete()
            }
            val remaining = response.body?.contentLength() ?: -1L
            val totalBytes = when {
                remaining > 0 && resumed -> startAt + remaining
                remaining > 0 -> remaining
                else -> -1L
            }

            var bytesDownloaded = startAt
            var lastReportTime = System.currentTimeMillis()
            var lastReportBytes = bytesDownloaded
            onProgress(DownloadProgress(bytesDownloaded, totalBytes, 0L, if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 99) else -1))

            val outputStream: OutputStream = if (resumed) {
                // Append without truncating: RandomAccessFile seeked to the end.
                // (Never open a plain FileOutputStream here: it truncates.)
                val raf = java.io.RandomAccessFile(partFile, "rw").apply { seek(length()) }
                object : OutputStream() {
                    override fun write(b: Int) = raf.write(b)
                    override fun write(b: ByteArray, off: Int, len: Int) = raf.write(b, off, len)
                    override fun close() = raf.close()
                }
            } else {
                partFile.outputStream()
            }

            try {
                response.body?.source()?.use { source ->
                    val buffer = okio.Buffer()
                    while (isActive) {
                        if (downloadId.isNotEmpty() &&
                            com.vidflow.app.service.DownloadService.cancelledIds.contains(downloadId)
                        ) {
                            break
                        }
                        if (downloadId.isNotEmpty() &&
                            com.vidflow.app.service.DownloadService.pausedIds.contains(downloadId)
                        ) {
                            while (isActive &&
                                com.vidflow.app.service.DownloadService.pausedIds.contains(downloadId)
                            ) {
                                kotlinx.coroutines.delay(200)
                            }
                        }
                        if (!isActive) break
                        val read = source.read(buffer, 32_768)
                        if (read == -1L) break
                        outputStream.write(buffer.readByteArray())
                        bytesDownloaded += read
                        val now = System.currentTimeMillis()
                        val elapsed = (now - lastReportTime).coerceAtLeast(1)
                        if (elapsed >= 250) {
                            val speed = ((bytesDownloaded - lastReportBytes) * 1000) / elapsed
                            val pct = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 99) else -1
                            onProgress(DownloadProgress(bytesDownloaded, totalBytes, speed, pct))
                            lastReportTime = now
                            lastReportBytes = bytesDownloaded
                        }
                    }
                }
            } finally {
                runCatching { outputStream.close() }
                runCatching { response.close() }
            }

            if (!isActive ||
                (downloadId.isNotEmpty() &&
                    com.vidflow.app.service.DownloadService.cancelledIds.contains(downloadId))
            ) {
                // Cancelled mid-stream: cancel is the only action that deletes partials.
                runCatching { partFile.delete() }
                return@withContext DownloadResult.Cancelled
            }

            val finalPath = publishFinishedFile(partFile, fileName, mimeType, isAudioOnly)
            if (finalPath == null) {
                // Don't delete the part file: leave it for retry.
                val partSize = if (partFile.exists()) partFile.length() else -1L
                return@withContext DownloadResult.Error(
                    "Could not save file to Downloads. Part size: ${partSize / 1024}KB. Check storage space and permissions."
                )
            }
            DownloadResult.Success(finalPath, bytesDownloaded)
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { partFile.delete() }
            DownloadResult.Cancelled
        } catch (e: Exception) {
            DownloadResult.Error("Download failed: ${e.message ?: "Unknown error"}")
        }
    }

    /** Deletes the .part file for a download (cancel path only). */
    fun deletePartFile(downloadId: String, fileNameHint: String = "") {
        runCatching { partFileFor(downloadId, fileNameHint).delete() }
    }

    private fun partFileFor(downloadId: String, fileName: String): File {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val key = downloadId.ifEmpty { fileName }.replace(Regex("[^A-Za-z0-9_-]"), "_")
            File(context.cacheDir, "vf_direct_$key.part")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "VidFlow",
            )
            dir.mkdirs()
            File(dir, "$fileName.part")
        }
    }

    /** Moves the finished .part file into Downloads/VidFlow (or renames pre-Q). */
    private fun publishFinishedFile(
        partFile: File,
        fileName: String,
        mimeType: String,
        isAudioOnly: Boolean,
    ): String? {
        if (!partFile.exists() || partFile.length() == 0L) return null

        val nameWithoutExt = fileName.substringBeforeLast(".")
        val ext = fileName.substringAfterLast(".", "mp4")
        val cleanName = "${sanitizeFileName(nameWithoutExt)}.$ext"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val targetDir = "${Environment.DIRECTORY_DOWNLOADS}/VidFlow"
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, targetDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collectionUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            var insertedUri: android.net.Uri? = null
            try {
                insertedUri = context.contentResolver.insert(collectionUri, cv)
                if (insertedUri != null) {
                    context.contentResolver.openOutputStream(insertedUri)?.use { out ->
                        partFile.inputStream().buffered(65536).use { inp -> inp.copyTo(out, 65536) }
                    }
                    val updateCv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    context.contentResolver.update(insertedUri, updateCv, null, null)
                    runCatching { partFile.delete() }
                    return insertedUri.toString()
                }
            } catch (e: Exception) {
                android.util.Log.e("VidFlow/Publish", "MediaStore save failed: ${e.message}", e)
                if (insertedUri != null) {
                    runCatching { context.contentResolver.delete(insertedUri, null, null) }
                }
            }
        }

        // Direct filesystem fallback (pre-Q or if MediaStore insert failed)
        return try {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "VidFlow"
            )
            dir.mkdirs()
            val dest = File(dir, cleanName)
            if (dest.exists()) dest.delete()
            partFile.copyTo(dest, overwrite = true)
            runCatching { partFile.delete() }
            dest.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("VidFlow/Publish", "Direct file save failed: ${e.message}", e)
            null
        }
    }
}
