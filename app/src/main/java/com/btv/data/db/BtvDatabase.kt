package com.btv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.btv.data.db.dao.FavoritesDao
import com.btv.data.db.dao.HistoryDao
import com.btv.data.db.dao.PlaybackProgressDao
import com.btv.data.db.dao.SeriesEpisodeSnapshotDao
import com.btv.data.db.dao.TrackPreferenceDao
import com.btv.data.db.dao.UserSessionDao
import com.btv.data.db.dao.EpgDao
import com.btv.data.db.dao.ReplayDao
import com.btv.data.db.entities.FavoritesEntity
import com.btv.data.db.entities.HistoryEntity
import com.btv.data.db.entities.PlaybackProgressEntity
import com.btv.data.db.entities.SeriesEpisodeSnapshotEntity
import com.btv.data.db.entities.TrackPreferenceEntity
import com.btv.data.db.entities.UserSessionEntity
import com.btv.data.db.entities.LiveCategoryEntity
import com.btv.data.db.entities.LiveChannelEntity
import com.btv.data.db.entities.VodCategoryEntity
import com.btv.data.db.entities.VodEntity
import com.btv.data.db.entities.SeriesCategoryEntity
import com.btv.data.db.entities.SeriesEntity
import com.btv.data.db.entities.EpgProgramEntity
import com.btv.data.db.entities.ReplayChannelEntity
import com.btv.data.db.entities.ReplayProgramEntity

@Database(
    entities = [
        LiveCategoryEntity::class,
        LiveChannelEntity::class,
        VodCategoryEntity::class,
        VodEntity::class,
        SeriesCategoryEntity::class,
        SeriesEntity::class,
        FavoritesEntity::class,
        HistoryEntity::class,
        PlaybackProgressEntity::class,
        UserSessionEntity::class,
        EpgProgramEntity::class,
        SeriesEpisodeSnapshotEntity::class,
        TrackPreferenceEntity::class,
        ReplayChannelEntity::class,
        ReplayProgramEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class BtvDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun historyDao(): HistoryDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun userSessionDao(): UserSessionDao
    abstract fun epgDao(): EpgDao
    abstract fun seriesEpisodeSnapshotDao(): SeriesEpisodeSnapshotDao
    abstract fun trackPreferenceDao(): TrackPreferenceDao
    abstract fun replayDao(): ReplayDao

    suspend fun scrubLegacyRoomPasswords() = withTransaction {
        openHelper.writableDatabase.execSQL("UPDATE user_session SET password = NULL")
    }

    /** Only the account found in the pre-upgrade encrypted vault may inherit v4 rows. */
    suspend fun claimLegacyRows(accountKey: String) = withTransaction {
        val db = openHelper.writableDatabase
        for (table in listOf("favorites", "history", "playback_progress")) {
            db.execSQL("UPDATE OR IGNORE $table SET accountKey = ? WHERE accountKey = ?", arrayOf(accountKey, UNCLAIMED_LEGACY_ACCOUNT))
            db.execSQL("DELETE FROM $table WHERE accountKey = ?", arrayOf(UNCLAIMED_LEGACY_ACCOUNT))
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: BtvDatabase? = null

        fun getInstance(context: Context): BtvDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BtvDatabase::class.java,
                    "btv_database"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
