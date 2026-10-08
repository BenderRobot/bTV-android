package com.btv.data.sync

import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/** What is shared between devices. */
object SyncKinds {
    const val PROGRESS = "progress"
    const val HISTORY = "history"
    const val FAVORITE = "favorite"
    const val TRACK = "track"
}

/**
 * One shared record. [id] is "TYPE|streamId" (e.g. "VOD|1234"); [updatedAt]
 * is the device time of the change - the most recent change wins.
 */
data class SyncItem(
    val kind: String,
    val id: String,
    val payload: JsonObject,
    val deleted: Boolean,
    val updatedAt: Long
) {
    val key: String get() = "$kind|$id"
    val hash: Int get() = if (deleted) 0 else payload.toString().hashCode()
}

/** A record as the server returns it, with its position in the server's change log. */
data class RemoteSyncItem(val item: SyncItem, val seq: Long)

/** The last version this device and the server agreed on for one record. */
data class SyncedEntry(val hash: Int, val updatedAt: Long, val deleted: Boolean = false)

/** Per account: how far the server log was read, and the agreed version of each record. */
data class SyncState(val cursor: Long = 0, val entries: Map<String, SyncedEntry> = emptyMap())

/**
 * Key shared by the devices of one IPTV account. Derived from the server,
 * the username AND the password, so it cannot be guessed from what is
 * visible in the app; the password itself never leaves the device. A new
 * IPTV password simply starts a new shared history.
 */
fun syncKeyFor(serverUrl: String, username: String, password: String): String {
    val server = serverUrl.trim().trimEnd('/')
    val identity = "btv-sync-v1\n${server.length}:$server\n${username.length}:$username\n${password.length}:$password"
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * What this device must send: records that changed since the last agreed
 * version, and deletions (agreed records that are gone locally). A change
 * whose own timestamp did not move (a film marked as watched keeps its
 * progress time) is stamped [now] so it still wins on the other devices.
 */
fun localChanges(current: List<SyncItem>, state: SyncState, now: Long): List<SyncItem> {
    val changes = ArrayList<SyncItem>()
    val present = HashSet<String>(current.size)
    for (item in current) {
        present += item.key
        val agreed = state.entries[item.key]
        if (agreed != null && !agreed.deleted && agreed.hash == item.hash) continue
        val stamp = if (agreed != null && item.updatedAt <= agreed.updatedAt) maxOf(now, agreed.updatedAt + 1) else item.updatedAt
        changes += item.copy(updatedAt = stamp)
    }
    for ((key, agreed) in state.entries) {
        if (agreed.deleted || key in present) continue
        val kind = key.substringBefore('|')
        val id = key.substringAfter('|')
        changes += SyncItem(kind, id, JsonObject(emptyMap()), deleted = true, updatedAt = maxOf(now, agreed.updatedAt + 1))
    }
    return changes
}

/**
 * Whether a record pulled from the server must be written locally.
 * [localUpdatedAt] / [localHash] describe the local record (null: none).
 * The server echoing back what this device itself sent is never applied:
 * it could undo a newer local change not pushed yet.
 */
fun shouldApplyRemote(remote: SyncItem, agreed: SyncedEntry?, localUpdatedAt: Long?, localHash: Int?): Boolean {
    if (agreed != null && agreed.hash == remote.hash && agreed.deleted == remote.deleted) return false
    if (localHash != null && !remote.deleted && localHash == remote.hash) return false
    if (localUpdatedAt == null) return !remote.deleted
    return remote.updatedAt > localUpdatedAt
}
