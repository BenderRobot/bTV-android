package com.btv.data.repository

import com.btv.data.db.AccountScope
import com.btv.data.db.dao.TrackPreferenceDao
import com.btv.data.db.entities.TrackPreferenceEntity

/** Per-content audio/subtitle choices (Tizen getTrackPref/saveTrackPref, js/data.js). */
class TrackPreferenceRepository(
    private val dao: TrackPreferenceDao,
    private val accountScope: AccountScope = AccountScope.global
) {
    suspend fun get(type: String, streamId: String): TrackPreferenceEntity? {
        val key = accountScope.key.value ?: return null
        return dao.get(key, type, streamId)
    }

    /** [accountKey] is captured by the caller when the choice is made, so a
     * write still in flight during an account switch stays with its account. */
    suspend fun save(accountKey: String, type: String, streamId: String, audioLabel: String?, subtitleLabel: String?) {
        dao.upsert(
            TrackPreferenceEntity(
                accountKey = accountKey,
                type = type,
                streamId = streamId,
                audioLabel = audioLabel,
                subtitleLabel = subtitleLabel,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun currentAccountKey(): String? = accountScope.key.value
}
