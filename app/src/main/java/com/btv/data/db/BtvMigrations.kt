package com.btv.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing v4 data belongs to the encrypted credentials active at upgrade time. */
const val UNCLAIMED_LEGACY_ACCOUNT = "__legacy_unclaimed__"

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `favorites_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `accountKey` TEXT NOT NULL, `streamId` TEXT NOT NULL, `type` TEXT NOT NULL,
            `name` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `categoryName` TEXT NOT NULL,
            `posterUrl` TEXT, `containerExtension` TEXT, `addedAt` INTEGER NOT NULL)
        """.trimIndent())
        db.execSQL("""
            INSERT INTO favorites_new (id, accountKey, streamId, type, name, categoryId, categoryName, posterUrl, containerExtension, addedAt)
            SELECT f.id, '$UNCLAIMED_LEGACY_ACCOUNT', f.streamId, f.type, f.name, f.categoryId, f.categoryName,
                   f.posterUrl, f.containerExtension, f.addedAt
            FROM favorites f
            WHERE f.id = (SELECT MAX(f2.id) FROM favorites f2 WHERE f2.type = f.type AND f2.streamId = f.streamId)
        """.trimIndent())
        db.execSQL("DROP TABLE favorites")
        db.execSQL("ALTER TABLE favorites_new RENAME TO favorites")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_favorites_accountKey_type_streamId ON favorites (accountKey, type, streamId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_accountKey_type ON favorites (accountKey, type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_addedAt ON favorites (addedAt)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `history_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `accountKey` TEXT NOT NULL, `streamId` TEXT NOT NULL, `type` TEXT NOT NULL,
            `name` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `categoryName` TEXT NOT NULL,
            `posterUrl` TEXT, `seriesId` TEXT, `episodeNumber` INTEGER, `seasonNumber` INTEGER,
            `containerExtension` TEXT, `viewCount` INTEGER NOT NULL, `lastViewedAt` INTEGER NOT NULL)
        """.trimIndent())
        db.execSQL("""
            INSERT INTO history_new (id, accountKey, streamId, type, name, categoryId, categoryName, posterUrl,
                                     seriesId, episodeNumber, seasonNumber, containerExtension, viewCount, lastViewedAt)
            SELECT h.id, '$UNCLAIMED_LEGACY_ACCOUNT', h.streamId, h.type, h.name, h.categoryId, h.categoryName,
                   h.posterUrl, h.seriesId, h.episodeNumber, h.seasonNumber, h.containerExtension, h.viewCount, h.lastViewedAt
            FROM history h
            WHERE h.id = (SELECT MAX(h2.id) FROM history h2 WHERE h2.type = h.type AND h2.streamId = h.streamId)
        """.trimIndent())
        db.execSQL("DROP TABLE history")
        db.execSQL("ALTER TABLE history_new RENAME TO history")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_history_accountKey_type_streamId ON history (accountKey, type, streamId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_history_accountKey_type ON history (accountKey, type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_history_lastViewedAt ON history (lastViewedAt)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `playback_progress_new` (`accountKey` TEXT NOT NULL,
            `streamId` TEXT NOT NULL, `type` TEXT NOT NULL, `progressMs` INTEGER NOT NULL,
            `durationMs` INTEGER NOT NULL, `progressPercent` REAL NOT NULL, `isCompleted` INTEGER NOT NULL,
            `lastProgressedAt` INTEGER NOT NULL, `seriesId` TEXT, `episodeNumber` INTEGER,
            `seasonNumber` INTEGER, `containerExtension` TEXT,
            PRIMARY KEY(`accountKey`, `type`, `streamId`))
        """.trimIndent())
        db.execSQL("""
            INSERT INTO playback_progress_new (accountKey, streamId, type, progressMs, durationMs,
                                               progressPercent, isCompleted, lastProgressedAt, seriesId,
                                               episodeNumber, seasonNumber, containerExtension)
            SELECT '$UNCLAIMED_LEGACY_ACCOUNT', streamId, type, progressMs, durationMs,
                   progressPercent, isCompleted, lastProgressedAt, seriesId,
                   episodeNumber, seasonNumber, containerExtension FROM playback_progress
        """.trimIndent())
        db.execSQL("DROP TABLE playback_progress")
        db.execSQL("ALTER TABLE playback_progress_new RENAME TO playback_progress")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_progress_accountKey_type ON playback_progress (accountKey, type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_progress_lastProgressedAt ON playback_progress (lastProgressedAt)")

        // Credentials now live only in the encrypted vault. Old Room rows
        // can still contain a password from previous versions.
        db.execSQL("UPDATE user_session SET password = NULL")
    }
}

/** Adds the favorite-series episode snapshots used to flag new episodes. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `series_episode_snapshot` (
                `accountKey` TEXT NOT NULL,
                `seriesId` TEXT NOT NULL,
                `knownEpisodeIds` TEXT NOT NULL,
                `newEpisodeIds` TEXT NOT NULL,
                `checkedAt` INTEGER NOT NULL,
                PRIMARY KEY(`accountKey`, `seriesId`)
            )
        """.trimIndent())
    }
}

/** Adds per-content audio/subtitle choices kept across restarts. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `track_preference` (
                `accountKey` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `streamId` TEXT NOT NULL,
                `audioLabel` TEXT,
                `subtitleLabel` TEXT,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`accountKey`, `type`, `streamId`)
            )
        """.trimIndent())
    }
}

/** Rediffusion kept on disk: archivable channels and their archived programs, per account. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `replay_channels` (
                `accountKey` TEXT NOT NULL,
                `streamId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `streamIcon` TEXT,
                `categoryId` TEXT,
                `categoryName` TEXT,
                `archiveDays` INTEGER,
                `fetchedAt` INTEGER NOT NULL,
                PRIMARY KEY(`accountKey`, `streamId`)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `replay_programs` (
                `accountKey` TEXT NOT NULL,
                `streamId` TEXT NOT NULL,
                `startTs` INTEGER NOT NULL,
                `stopTs` INTEGER NOT NULL,
                `start` TEXT,
                `end` TEXT,
                `title` TEXT,
                `description` TEXT,
                `fetchedAt` INTEGER NOT NULL,
                PRIMARY KEY(`accountKey`, `streamId`, `startTs`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_replay_programs_stopTs` ON `replay_programs` (`stopTs`)")
    }
}
