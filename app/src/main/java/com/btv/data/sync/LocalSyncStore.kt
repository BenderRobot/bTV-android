package com.btv.data.sync

import com.btv.data.db.BtvDatabase
import com.btv.data.db.entities.FavoritesEntity
import com.btv.data.db.entities.HistoryEntity
import com.btv.data.db.entities.PlaybackProgressEntity
import com.btv.data.db.entities.TrackPreferenceEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The local side of the sync: reads this device's records as [SyncItem]s
 * and writes the records pulled from the server, in the same Room tables
 * the rest of the app uses (so "Continuer à regarder", the resume prompt and
 * Favoris pick them up on their own).
 */
class LocalSyncStore(
    private val database: BtvDatabase,
    /** Shared account settings; null leaves them out (tests, older callers). */
    private val preferences: com.btv.data.store.PreferencesStore? = null
) {

    suspend fun snapshot(accountKey: String): List<SyncItem> = buildList {
        database.playbackProgressDao().getAllForSync(accountKey).forEach { add(it.toSyncItem()) }
        database.historyDao().getAllForSync(accountKey).forEach { add(it.toSyncItem()) }
        database.favoritesDao().getAllForSync(accountKey).forEach { add(it.toSyncItem()) }
        database.trackPreferenceDao().getAllForSync(accountKey).forEach { add(it.toSyncItem()) }
        preferences?.syncedSettings()?.forEach { setting ->
            add(
                SyncItem(
                    SyncKinds.SETTING, setting.name,
                    buildJsonObject { put("values", JsonArray(setting.values.map { JsonPrimitive(it) })) },
                    deleted = false, updatedAt = setting.updatedAt
                )
            )
        }
    }.distinctBy { it.key }

    /** Applies one record from the server (already judged newer). */
    suspend fun apply(accountKey: String, item: SyncItem) {
        val type = item.id.substringBefore('|')
        val streamId = item.id.substringAfter('|')
        val p = item.payload
        when (item.kind) {
            SyncKinds.PROGRESS -> {
                val dao = database.playbackProgressDao()
                if (item.deleted) dao.deleteByStreamId(accountKey, type, streamId)
                else dao.insert(
                    PlaybackProgressEntity(
                        accountKey = accountKey,
                        streamId = streamId,
                        type = type,
                        progressMs = p.long("progressMs") ?: 0L,
                        durationMs = p.long("durationMs") ?: 0L,
                        isCompleted = p.bool("isCompleted") ?: false,
                        lastProgressedAt = item.updatedAt,
                        seriesId = p.str("seriesId"),
                        episodeNumber = p.int("episodeNumber"),
                        seasonNumber = p.int("seasonNumber"),
                        containerExtension = p.str("containerExtension")
                    )
                )
            }
            SyncKinds.HISTORY -> {
                val dao = database.historyDao()
                if (item.deleted) dao.deleteByStreamId(accountKey, type, streamId)
                else dao.insert(
                    HistoryEntity(
                        accountKey = accountKey,
                        streamId = streamId,
                        type = type,
                        name = p.str("name").orEmpty(),
                        categoryId = p.str("categoryId").orEmpty(),
                        categoryName = p.str("categoryName").orEmpty(),
                        posterUrl = p.str("posterUrl"),
                        seriesId = p.str("seriesId"),
                        episodeNumber = p.int("episodeNumber"),
                        seasonNumber = p.int("seasonNumber"),
                        containerExtension = p.str("containerExtension"),
                        viewCount = p.int("viewCount") ?: 1,
                        lastViewedAt = item.updatedAt
                    )
                )
            }
            SyncKinds.FAVORITE -> {
                val dao = database.favoritesDao()
                if (item.deleted) dao.deleteByStreamId(accountKey, type, streamId)
                else dao.insert(
                    FavoritesEntity(
                        accountKey = accountKey,
                        streamId = streamId,
                        type = type,
                        name = p.str("name").orEmpty(),
                        categoryId = p.str("categoryId").orEmpty(),
                        categoryName = p.str("categoryName").orEmpty(),
                        posterUrl = p.str("posterUrl"),
                        containerExtension = p.str("containerExtension"),
                        addedAt = p.long("addedAt") ?: item.updatedAt
                    )
                )
            }
            SyncKinds.SETTING -> {
                val values = if (item.deleted) emptyList()
                else (p["values"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                preferences?.applySyncedSetting(item.id, values, item.updatedAt)
            }
            SyncKinds.TRACK -> {
                val dao = database.trackPreferenceDao()
                if (item.deleted) dao.delete(accountKey, type, streamId)
                else dao.upsert(
                    TrackPreferenceEntity(
                        accountKey = accountKey,
                        type = type,
                        streamId = streamId,
                        audioLabel = p.str("audioLabel"),
                        subtitleLabel = p.str("subtitleLabel"),
                        updatedAt = item.updatedAt
                    )
                )
            }
        }
    }

    // ---- Room rows -> shared records (no account key, no credentials) ----

    private fun PlaybackProgressEntity.toSyncItem() = SyncItem(
        SyncKinds.PROGRESS, "$type|$streamId",
        buildJsonObject {
            put("progressMs", progressMs)
            put("durationMs", durationMs)
            put("isCompleted", isCompleted)
            putOpt("seriesId", seriesId)
            putOpt("episodeNumber", episodeNumber)
            putOpt("seasonNumber", seasonNumber)
            putOpt("containerExtension", containerExtension)
        },
        deleted = false, updatedAt = lastProgressedAt
    )

    private fun HistoryEntity.toSyncItem() = SyncItem(
        SyncKinds.HISTORY, "$type|$streamId",
        buildJsonObject {
            put("name", name)
            put("categoryId", categoryId)
            put("categoryName", categoryName)
            putOpt("posterUrl", posterUrl)
            putOpt("seriesId", seriesId)
            putOpt("episodeNumber", episodeNumber)
            putOpt("seasonNumber", seasonNumber)
            putOpt("containerExtension", containerExtension)
            put("viewCount", viewCount)
        },
        deleted = false, updatedAt = lastViewedAt
    )

    private fun FavoritesEntity.toSyncItem() = SyncItem(
        SyncKinds.FAVORITE, "$type|$streamId",
        buildJsonObject {
            put("name", name)
            put("categoryId", categoryId)
            put("categoryName", categoryName)
            putOpt("posterUrl", posterUrl)
            putOpt("containerExtension", containerExtension)
            put("addedAt", addedAt)
        },
        deleted = false, updatedAt = addedAt
    )

    private fun TrackPreferenceEntity.toSyncItem() = SyncItem(
        SyncKinds.TRACK, "$type|$streamId",
        buildJsonObject {
            putOpt("audioLabel", audioLabel)
            putOpt("subtitleLabel", subtitleLabel)
        },
        deleted = false, updatedAt = updatedAt
    )

    private fun kotlinx.serialization.json.JsonObjectBuilder.putOpt(key: String, value: String?) {
        if (value != null) put(key, value)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putOpt(key: String, value: Int?) {
        if (value != null) put(key, value)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
