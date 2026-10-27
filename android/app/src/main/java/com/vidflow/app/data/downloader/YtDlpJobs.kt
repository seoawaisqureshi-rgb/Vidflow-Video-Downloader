package com.vidflow.app.data.downloader

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of running yt-dlp processes, keyed by VidFlow download id.
 *
 * Why this exists (brief section 3.1): the old code paused only the UI.
 * The yt-dlp subprocess kept running to completion. The youtubedl-android
 * library tracks each process by the process id passed to execute(); calling
 * destroyProcessById() on it kills that process (and the ffmpeg child it
 * spawned for merging). This registry is the single place that knows which
 * process ids belong to which download.
 *
 * Pause vs cancel, strictly separated:
 * - PAUSE: kill the process, keep every .part / .part-Frag file. Resume
 *   re-runs the exact same command with --continue + --fragment-retries 10
 *   against the SAME output template, so yt-dlp resumes from the last byte
 *   or fragment instead of starting from zero.
 * - CANCEL: kill the process, then delete the .part files and temp files.
 *   Cancel is the only action that deletes partial data.
 */
object YtDlpJobs {

    private const val TAG = "VidFlow/YtDlpJobs"

    private val processIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()
    private val pausedIds = ConcurrentHashMap.newKeySet<String>()
    /** downloadId -> output-template prefix (cacheDir path without extension). */
    private val templates = ConcurrentHashMap<String, String>()

    /** Stable output template for a download id, so resume finds the .part files. */
    fun templateFor(context: Context, downloadId: String): String {
        val safe = downloadId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return templates.getOrPut(downloadId) {
            File(context.cacheDir, "vf_$safe").absolutePath
        }
    }

    fun register(downloadId: String, processId: String) {
        processIds.getOrPut(downloadId) { ConcurrentHashMap.newKeySet() }.add(processId)
    }

    fun unregister(downloadId: String, processId: String) {
        processIds[downloadId]?.remove(processId)
    }

    /** Kill the running yt-dlp process(es) for this download. Keeps .part files. */
    fun pause(downloadId: String) {
        pausedIds.add(downloadId)
        killProcesses(downloadId)
    }

    fun isPaused(downloadId: String): Boolean = pausedIds.contains(downloadId)

    fun clearPaused(downloadId: String) {
        pausedIds.remove(downloadId)
    }

    /** Kill the process(es) and mark cancelled, so the repository cleans up. */
    fun cancel(downloadId: String) {
        cancelledIds.add(downloadId)
        killProcesses(downloadId)
    }

    fun isCancelled(downloadId: String): Boolean = cancelledIds.contains(downloadId)

    fun clearCancelled(downloadId: String) {
        cancelledIds.remove(downloadId)
    }

    private fun killProcesses(downloadId: String) {
        val ids = processIds[downloadId]?.toList().orEmpty()
        for (id in ids) {
            try {
                YoutubeDL.getInstance().destroyProcessById(id)
                Log.d(TAG, "Killed yt-dlp process $id for $downloadId")
            } catch (e: Exception) {
                Log.w(TAG, "destroyProcessById($id) failed: ${e.message}")
            }
        }
    }

    /**
     * Deletes partial + temp files for a download. Called ONLY on cancel
     * (and after a successful save, for leftovers). Never on pause.
     */
    fun deletePartFiles(context: Context, downloadId: String) {
        val prefix = templates[downloadId]
            ?: File(context.cacheDir, "vf_${downloadId.replace(Regex("[^A-Za-z0-9_-]"), "_")}").absolutePath
        try {
            val dir = File(prefix).parentFile ?: context.cacheDir
            val base = File(prefix).name
            dir.listFiles { f -> f.name.startsWith(base) }?.forEach { file ->
                runCatching { file.delete() }
            }
            // The cookies file is per-platform, not per-download: leave it.
        } catch (e: Exception) {
            Log.w(TAG, "Part cleanup failed: ${e.message}")
        } finally {
            templates.remove(downloadId)
            processIds.remove(downloadId)
        }
    }

    /** All files produced so far for this download's template (merged or parts). */
    fun producedFiles(context: Context, downloadId: String): List<File> {
        val prefix = templates[downloadId] ?: return emptyList()
        val dir = File(prefix).parentFile ?: return emptyList()
        val base = File(prefix).name
        return dir.listFiles { f -> f.name.startsWith(base) }?.toList().orEmpty()
    }

    fun forget(downloadId: String) {
        templates.remove(downloadId)
        processIds.remove(downloadId)
        cancelledIds.remove(downloadId)
    }
}
