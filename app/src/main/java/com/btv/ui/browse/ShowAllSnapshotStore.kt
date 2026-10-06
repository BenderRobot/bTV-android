package com.btv.ui.browse

import android.util.AtomicFile
import android.net.Uri
import com.btv.data.db.AccountScope
import com.btv.data.model.AuthSession
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

@Serializable
internal data class ShowAllRef(val categoryId: String, val id: String, val name: String)

/** The visible card without its credential-bearing playback URL. */
@Serializable
internal data class CachedShowAllCard(
    val id: String,
    val name: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val rating: String? = null,
    val year: String? = null,
    val duration: String? = null,
    val genre: String? = null,
    val extension: String? = null
) {
    companion object {
        fun from(item: ContentItem, session: AuthSession): CachedShowAllCard = CachedShowAllCard(
            id = item.id,
            name = item.name,
            posterUrl = safeImageUrl(item.posterUrl, session),
            backdropUrl = safeImageUrl(item.backdropUrl, session),
            plot = item.plot,
            cast = item.cast,
            rating = item.rating,
            year = item.year,
            duration = item.duration,
            genre = item.genre,
            extension = item.streamUrl?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
        )

        private fun safeImageUrl(url: String?, session: AuthSession): String? = url?.takeUnless {
            val decoded = Uri.decode(it)
            listOf(session.username, session.password).any { secret ->
                secret.isNotEmpty() && (it.contains(secret) || decoded.contains(secret))
            }
        }
    }
}

@Serializable
internal data class ShowAllSnapshot(
    val version: Int,
    val accountKey: String,
    val type: String,
    val filterKey: String,
    val savedAtMillis: Long,
    val refs: List<ShowAllRef>,
    val preview: List<CachedShowAllCard>
)

/** Atomic, account-scoped snapshot. Android may remove it without affecting playback. */
class ShowAllSnapshotStore(
    private val directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val json = Json { ignoreUnknownKeys = true }

    internal suspend fun read(session: AuthSession, type: ContentType, disabledPrefixes: Set<String>): ShowAllSnapshot? =
        withContext(Dispatchers.IO) {
            val accountKey = AccountScope.keyFor(session.serverUrl, session.username)
            val filterKey = filterKey(disabledPrefixes)
            val file = fileFor(accountKey, type, filterKey)
            if ((!file.exists() && !File(file.path + ".bak").exists()) || file.length() > MAX_FILE_BYTES) return@withContext null
            val atomic = AtomicFile(file)
            try {
                @OptIn(ExperimentalSerializationApi::class)
                val snapshot = GZIPInputStream(atomic.openRead()).use { json.decodeFromStream<ShowAllSnapshot>(it) }
                val age = nowMillis() - snapshot.savedAtMillis
                if (snapshot.version != VERSION || snapshot.accountKey != accountKey ||
                    snapshot.type != type.name || snapshot.filterKey != filterKey ||
                    age !in 0..TTL_MILLIS || snapshot.refs.size > MAX_REFS ||
                    snapshot.preview.size > MAX_PREVIEW || snapshot.preview.size > snapshot.refs.size
                ) return@withContext null
                snapshot
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                atomic.delete()
                null
            }
        }

    internal suspend fun write(
        session: AuthSession, type: ContentType, disabledPrefixes: Set<String>,
        refs: List<ShowAllRef>, preview: List<ContentItem>
    ) = withContext(Dispatchers.IO) {
        if (refs.size > MAX_REFS || preview.size > MAX_PREVIEW || preview.size > refs.size) return@withContext
        val accountKey = AccountScope.keyFor(session.serverUrl, session.username)
        val filterKey = filterKey(disabledPrefixes)
        val snapshot = ShowAllSnapshot(
            VERSION, accountKey, type.name, filterKey, nowMillis(), refs,
            preview.map { CachedShowAllCard.from(it, session) }
        )
        val file = fileFor(accountKey, type, filterKey)
        directory.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            @OptIn(ExperimentalSerializationApi::class)
            val gzip = GZIPOutputStream(output)
            json.encodeToStream(snapshot, gzip)
            gzip.finish()
            gzip.flush()
            currentCoroutineContext().ensureActive()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    fun invalidate(session: AuthSession) {
        val prefix = "show_all_${AccountScope.keyFor(session.serverUrl, session.username)}_"
        directory.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { AtomicFile(it).delete() }
    }

    private fun fileFor(accountKey: String, type: ContentType, filterKey: String): File =
        File(directory, "show_all_${accountKey}_${type.name}_$filterKey.json.gz")

    private fun filterKey(disabledPrefixes: Set<String>): String {
        val encoded = disabledPrefixes.sorted().joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    companion object {
        private const val VERSION = 1
        private const val TTL_MILLIS = 60 * 60 * 1_000L
        private const val MAX_REFS = 200_000
        private const val MAX_PREVIEW = 300
        private const val MAX_FILE_BYTES = 16L * 1024 * 1024
    }
}
