package com.vidflow.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.vidflow.app.data.model.DownloadStatus
import com.vidflow.app.data.model.Platform
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DownloadRecord): Long

    @Update
    suspend fun update(record: DownloadRecord)

    @Query("UPDATE download_history SET status = :status, progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: Long, status: DownloadStatus, progress: Int)

    @Query("UPDATE download_history SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateError(id: Long, status: DownloadStatus, error: String?)

    @Query("UPDATE download_history SET status = :status, filePath = :filePath, fileSize = :fileSize, completedAt = :completedAt, progress = 100 WHERE id = :id")
    suspend fun markCompleted(id: Long, status: DownloadStatus, filePath: String, fileSize: Long, completedAt: Long)

    @Query("SELECT * FROM download_history ORDER BY createdAt DESC")
    fun getAllRecords(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM download_history WHERE status IN ('QUEUED','FETCHING_INFO','DOWNLOADING') ORDER BY createdAt DESC")
    fun getActiveDownloads(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM download_history WHERE status = 'COMPLETED' ORDER BY completedAt DESC")
    fun getCompletedDownloads(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM download_history WHERE platform = :platform ORDER BY createdAt DESC")
    suspend fun getByPlatform(platform: Platform): List<DownloadRecord>

    @Query("SELECT * FROM download_history WHERE id = :id")
    suspend fun getById(id: Long): DownloadRecord?

    @Query("DELETE FROM download_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM download_history WHERE status = 'COMPLETED'")
    suspend fun clearHistory()
}
