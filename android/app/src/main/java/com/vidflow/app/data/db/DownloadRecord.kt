package com.vidflow.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vidflow.app.data.model.DownloadStatus
import com.vidflow.app.data.model.Platform

@Entity(tableName = "download_history")
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val thumbnail: String?,
    val platform: Platform,
    val quality: String,
    val ext: String,
    val filePath: String?,
    val fileSize: Long?,
    val duration: Long?,
    val status: DownloadStatus,
    val progress: Int = 0,          // 0–100
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)
