package com.btv.data.sync

import android.content.Context
import android.util.Log
import com.btv.BuildConfig
import com.btv.data.db.AccountScope
import com.btv.data.db.BtvDatabase
import com.btv.data.model.AuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Shares watch progress, history, favourites and audio/subtitle choices
 * between the devices of one IPTV account, through Supabase.
 *
 * Local-first: the app keeps reading and writing its Room tables as before;
 * this only copies changes both ways in the background - when the app comes
 * to the foreground or leaves it, on the home screen, and every minute while
 * it is open. Offline or with the Supabase project paused, nothing breaks:
 * the next successful round catches up.
 */
object SyncManager {
    private const val TAG = "BtvSync"
    private const val PERIOD_MS = 60_000L
    private const val PULL_PAGE = 1000
    private const val PUSH_BATCH = 200
    /** Bumped when [SyncItem.hash] changes: older state files are dropped (a full, harmless resync). */
    private const val STATE_VERSION = 3 // 3: settings shared too - a full resync sends them once

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var loop: Job? = null
    @Volatile private var active: Active? = null

    private class Active(
        val syncKey: String,
        val accountKey: String,
        val store: LocalSyncStore,
        val stateFile: File,
        val api: SyncApi
    )

    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_KEY.isNotBlank()

    /** Starts syncing [session]'s account (replaces any previous one). */
    fun start(context: Context, session: AuthSession) {
        if (!isConfigured) return
        val syncKey = syncKeyFor(session.serverUrl, session.username, session.password)
        val appContext = context.applicationContext
        active = Active(
            syncKey = syncKey,
            accountKey = AccountScope.keyFor(session.serverUrl, session.username),
            store = LocalSyncStore(BtvDatabase.getInstance(appContext), com.btv.data.store.PreferencesStore(appContext)),
            // The file name is derived from the key, never the key itself.
            stateFile = File(appContext.noBackupFilesDir, "sync_state_${syncKey.take(16)}.json"),
            api = SyncApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
        )
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                syncOnce()
                delay(PERIOD_MS)
            }
        }
    }

    /** Logout: stop sharing (local data stays, as before). */
    fun stop() {
        loop?.cancel()
        loop = null
        active = null
    }

    /** A sync round as soon as possible (app shown or hidden, back on the home screen...). */
    fun requestSync() {
        if (active == null) return
        scope.launch { syncOnce() }
    }

    private suspend fun syncOnce() = mutex.withLock {
        val current = active ?: return@withLock
        try {
            var state = readState(current.stateFile)
            state = pull(current, state)
            state = push(current, state)
            writeState(current.stateFile, state)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Offline, project paused...: the next round retries. Never the key or data in logs.
            Log.w(TAG, "Sync round skipped: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private suspend fun pull(current: Active, start: SyncState): SyncState {
        var cursor = start.cursor
        val entries = start.entries.toMutableMap()
        var local = current.store.snapshot(current.accountKey).associateBy { it.key }
        while (true) {
            val page = current.api.pull(current.syncKey, cursor, PULL_PAGE)
            for (remote in page) {
                val item = remote.item
                val mine = local[item.key]
                // A setting chosen here before sharing existed (no date) gives way to
                // the one already shared by another device.
                val legacySetting = item.kind == SyncKinds.SETTING && mine != null && mine.updatedAt <= 0L && mine.hash != item.hash
                val applied = legacySetting || shouldApplyRemote(item, entries[item.key], mine?.updatedAt, mine?.hash)
                if (applied) current.store.apply(current.accountKey, item)
                // Agreed on when both sides now hold it. A newer or different local
                // record is left out, so it goes out with its own time on the push.
                if (applied || mine == null || mine.hash == item.hash) {
                    entries[item.key] = SyncedEntry(item.hash, item.updatedAt, item.deleted)
                }
                cursor = maxOf(cursor, remote.seq)
            }
            if (page.size < PULL_PAGE) break
            local = current.store.snapshot(current.accountKey).associateBy { it.key }
        }
        return SyncState(cursor, entries)
    }

    private suspend fun push(current: Active, start: SyncState): SyncState {
        val changes = localChanges(current.store.snapshot(current.accountKey), start, System.currentTimeMillis())
        if (changes.isEmpty()) return start
        val entries = start.entries.toMutableMap()
        changes.chunked(PUSH_BATCH).forEach { batch ->
            current.api.push(current.syncKey, batch)
            batch.forEach { entries[it.key] = SyncedEntry(it.hash, it.updatedAt, it.deleted) }
        }
        Log.i(TAG, "Pushed ${changes.size} change(s)")
        return start.copy(entries = entries)
    }

    // ---- state file: cursor + agreed version of each record ----

    private fun readState(file: File): SyncState = try {
        if (!file.exists()) SyncState()
        else {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val entries = root["entries"]?.jsonObject.orEmpty().mapValues { (_, value) ->
                val e = value.jsonObject
                SyncedEntry(
                    hash = (e["h"] as? JsonPrimitive)?.intOrNull ?: 0,
                    updatedAt = (e["t"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    deleted = (e["d"] as? JsonPrimitive)?.booleanOrNull ?: false
                )
            }
            if ((root["v"] as? JsonPrimitive)?.intOrNull != STATE_VERSION) SyncState()
            else SyncState(root.getValue("cursor").jsonPrimitive.long, entries)
        }
    } catch (error: Exception) {
        // Unreadable state: start over (a full pull; nothing is lost, newer data still wins).
        SyncState()
    }

    private fun writeState(file: File, state: SyncState) {
        val json = buildJsonObject {
            put("v", STATE_VERSION)
            put("cursor", state.cursor)
            put("entries", JsonObject(state.entries.mapValues { (_, e) ->
                buildJsonObject {
                    put("h", e.hash)
                    put("t", e.updatedAt)
                    if (e.deleted) put("d", true)
                }
            }))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }
}
