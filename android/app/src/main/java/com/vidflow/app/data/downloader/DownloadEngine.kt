package com.vidflow.app.data.downloader

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vidflow.app.data.model.Platform
import com.vidflow.app.data.model.VideoFormat
import com.vidflow.app.data.model.VideoInfo
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
)

sealed class DownloadResult {
    data class Success(val filePath: String, val fileSize: Long) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
    object Cancelled : DownloadResult()
}

class DownloadEngine(
    private val context: Context,
    private val client: OkHttpClient,
) {
    suspend fun download(
        info: VideoInfo,
        format: VideoFormat,
        downloadUrl: String,
        downloadId: String = "",
        onProgress: suspend (DownloadProgress) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {

        val safeTitle = info.title
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(80).trim().ifEmpty { "video" }
        val fileName = "${safeTitle}.${format.ext}"

        try {
            val reqBuilder = Request.Builder().url(downloadUrl)
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

            val response = client.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) {
                return@withContext DownloadResult.Error(
                    "Download failed (HTTP ${response.code}). The link may have expired — please re-analyze the URL and try again."
                )
            }

            val headerLength = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: -1L
            val bodyLength = response.body?.contentLength() ?: -1L
            val totalBytes = when {
                headerLength > 0 -> headerLength
                bodyLength > 0 -> bodyLength
                else -> -1L
            }
            var bytesDownloaded = 0L
            var lastReportTime = System.currentTimeMillis()
            var lastReportBytes = 0L
            onProgress(DownloadProgress(0L, totalBytes, 0L, if (totalBytes > 0) 0 else -1))

            val mimeType = when (format.ext.lowercase()) {
                "mp3"  -> "audio/mpeg"
                "m4a"  -> "audio/mp4"
                "aac"  -> "audio/aac"
                "webm" -> if (format.hasAudio && !format.hasVideo) "audio/webm" else "video/webm"
                "mov"  -> "video/quicktime"
                "mkv"  -> "video/x-matroska"
                else   -> "video/mp4"
            }
            val isAudioOnly = format.hasAudio && !format.hasVideo

            val outputStream: OutputStream
            val filePath: String

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/VidFlow")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collectionUri = if (isAudioOnly)
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

                val uri = context.contentResolver.insert(collectionUri, cv)
                    ?: return@withContext DownloadResult.Error("Could not create file. Check storage permissions.")

                outputStream = context.contentResolver.openOutputStream(uri)
                    ?: run {
                        context.contentResolver.delete(uri, null, null)
                        return@withContext DownloadResult.Error("Could not open output stream.")
                    }
                filePath = uri.toString()

                response.body?.source()?.use { source ->
                    val buffer = okio.Buffer()
                    while (isActive) {
                        // Pause: busy-wait while paused (releases CPU via delay)
                        if (downloadId.isNotEmpty() &&
                            com.vidflow.app.service.DownloadService.pausedIds.contains(downloadId)) {
                            while (isActive &&
                                   com.vidflow.app.service.DownloadService.pausedIds.contains(downloadId)) {
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
                outputStream.close()

                if (!isActive) {
                    context.contentResolver.delete(uri, null, null)
                    return@withContext DownloadResult.Cancelled
                }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, done, null, null)

            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "VidFlow"
                )
                dir.mkdirs()
                val file = File(dir, fileName)
                outputStream = file.outputStream()
                filePath = file.absolutePath

                response.body?.byteStream()?.use { input ->
                    val buf = ByteArray(32_768)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        // Pause: busy-wait while paused
                        if (downloadId.isNotEmpty() &&
                            com.vidflow.app.service.DownloadService.pausedIds.contains(downloadId)) {
                            while (isActive &&
                                   com.vidflow.app.service.DownloadService.pausedIds.contains(downloadId)) {
                                kotlinx.coroutines.delay(200)
                            }
                        }
                        if (!isActive) {
                            outputStream.close()
                            file.delete()
                            return@withContext DownloadResult.Cancelled
                        }
                        outputStream.write(buf, 0, read)
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
                outputStream.close()
            }

            DownloadResult.Success(filePath, bytesDownloaded)

        } catch (e: kotlinx.coroutines.CancellationException) {
            DownloadResult.Cancelled
        } catch (e: Exception) {
            DownloadResult.Error("Download failed: ${e.message ?: "Unknown error"}")
        }
    }
}
