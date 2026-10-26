package com.vidflow.app

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import com.vidflow.app.data.DownloadRepository
import com.vidflow.app.notifications.NotificationHelper
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VidFlowApplication : Application() {

    val repository by lazy { DownloadRepository(this) }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("vidflow_prefs", MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)

        // Initialize yt-dlp + FFmpeg in background.
        // FFmpeg is needed to merge DASH video+audio streams (YouTube 1080p/720p, Facebook, etc.)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YoutubeDL.getInstance().init(this@VidFlowApplication)
                Log.d("VidFlow", "yt-dlp initialized")

                // Update yt-dlp once per day to ensure latest extractor support
                // (fixes Instagram, Threads, Facebook DASH on newer content)
                val lastUpdate = prefs.getLong("ytdlp_last_update", 0L)
                val oneDayMs   = 24 * 60 * 60 * 1000L
                if (System.currentTimeMillis() - lastUpdate > oneDayMs) {
                    try {
                        val status = YoutubeDL.getInstance().updateYoutubeDL(
                            this@VidFlowApplication,
                            YoutubeDL.UpdateChannel._STABLE,
                        )
                        Log.d("VidFlow", "yt-dlp update: $status")
                        prefs.edit().putLong("ytdlp_last_update", System.currentTimeMillis()).apply()
                    } catch (e: Exception) {
                        Log.w("VidFlow", "yt-dlp update failed (non-critical): ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("VidFlow", "yt-dlp init failed", e)
            }

            try {
                FFmpeg.getInstance().init(this@VidFlowApplication)
                Log.d("VidFlow", "FFmpeg initialized")
            } catch (e: Exception) {
                Log.e("VidFlow", "FFmpeg init failed", e)
            }
        }
    }
}
