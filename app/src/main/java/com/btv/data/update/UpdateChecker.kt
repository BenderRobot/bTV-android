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
 * Compares the installed version (set by build-android.ps1, same text as the
 * Release tag without its "v") with the latest Release of the public repo.
 * Versions are dated "yyyy.MM.dd-HHmm", so plain text order is release order.
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

    /** Asks GitHub for the latest Release; offline or rate-limited, the status stays as it was. */
    suspend fun check(): UpdateStatus {
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

/** An installed version in another format (older APKs: "dev", "1.0") predates versioning: any release is newer. */
internal fun compareVersions(installed: String, latest: String): UpdateStatus = when {
    !latest.matches(VERSION_FORMAT) -> UpdateStatus.Unknown
    !installed.matches(VERSION_FORMAT) -> UpdateStatus.Available(latest)
    latest > installed -> UpdateStatus.Available(latest)
    else -> UpdateStatus.UpToDate
}

/** "2026.10.08-1901" -> "08/10/2026 19:01", as shown in the Release name. */
fun displayVersion(version: String): String {
    val match = VERSION_FORMAT.matchEntire(version) ?: return version
    val (y, m, d, h, min) = match.destructured
    return "$d/$m/$y $h:$min"
}

private val VERSION_FORMAT = Regex("""^(\d{4})\.(\d{2})\.(\d{2})-(\d{2})(\d{2})$""")
