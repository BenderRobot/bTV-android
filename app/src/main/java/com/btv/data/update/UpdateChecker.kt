package com.btv.data.update

import android.util.Log
import com.btv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Where this build stands compared to the latest GitHub Release. */
sealed interface UpdateStatus {
    data object Unknown : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val version: String) : UpdateStatus
}

/**
 * Compares the installed version (version.properties, "2.4.0") with the
 * latest Release of the public repo (tag "v2.4.0").
 */
object UpdateChecker {
    private const val TAG = "BtvUpdate"

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    val installedVersion: String get() = BuildConfig.VERSION_NAME

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var lastCheckAt = 0L

    /**
     * Asks GitHub for the latest Release; offline or rate-limited, the status
     * stays as it was. Not more often than [minIntervalMs]: GitHub allows 60
     * anonymous calls an hour, and Home asks each time it is shown.
     */
    suspend fun check(minIntervalMs: Long = 10 * 60_000L): UpdateStatus {
        val now = System.currentTimeMillis()
        if (lastCheckAt != 0L && now - lastCheckAt < minIntervalMs) return _status.value
        lastCheckAt = now
        val latest = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Latest release: HTTP ${response.code}")
                        null
                    } else {
                        val tag = Json.parseToJsonElement(response.body?.string().orEmpty())
                            .jsonObject["tag_name"] as? JsonPrimitive
                        tag?.contentOrNull?.removePrefix("v")
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Latest release check failed: ${error.javaClass.simpleName}")
                null
            }
        }
        if (latest != null) _status.value = compareVersions(installedVersion, latest)
        return _status.value
    }
}

/**
 * "2.4.0" against "2.10.1", number by number. An installed version in
 * another form (the first, dated releases "2026.10.08-1901", "dev") predates
 * these numbers: any numbered release is newer.
 */
internal fun compareVersions(installed: String, latest: String): UpdateStatus {
    val latestParts = semanticParts(latest) ?: return UpdateStatus.Unknown
    val installedParts = semanticParts(installed) ?: return UpdateStatus.Available(latest)
    for (i in 0 until 3) {
        if (latestParts[i] != installedParts[i]) {
            return if (latestParts[i] > installedParts[i]) UpdateStatus.Available(latest) else UpdateStatus.UpToDate
        }
    }
    return UpdateStatus.UpToDate
}

/** As shown to the user: "v2.4.0"; an older dated build reads as its date. */
fun displayVersion(version: String): String {
    if (semanticParts(version) != null) return "v$version"
    val match = DATED_FORMAT.matchEntire(version) ?: return version
    val (y, m, d, h, min) = match.destructured
    return "$d/$m/$y $h:$min"
}

private fun semanticParts(version: String): List<Int>? =
    SEMANTIC_FORMAT.matchEntire(version)?.destructured?.toList()?.map { it.toInt() }

private val SEMANTIC_FORMAT = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
private val DATED_FORMAT = Regex("""^(\d{4})\.(\d{2})\.(\d{2})-(\d{2})(\d{2})$""")
