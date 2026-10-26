package com.vidflow.app.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import android.content.Context
import com.vidflow.app.data.model.DownloadStatus
import com.vidflow.app.data.model.Platform

class Converters {
    @TypeConverter fun fromStatus(s: DownloadStatus): String = s.name
    @TypeConverter fun toStatus(s: String): DownloadStatus = DownloadStatus.valueOf(s)
    @TypeConverter fun fromPlatform(p: Platform): String = p.name
    @TypeConverter fun toPlatform(p: String): Platform = Platform.valueOf(p)
}

@Database(entities = [DownloadRecord::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vidflow_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
