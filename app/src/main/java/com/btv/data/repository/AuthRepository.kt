package com.btv.data.repository

import com.btv.data.api.XtreamApi
import com.btv.data.model.AuthSession
import com.btv.data.model.XtreamCategory
import com.btv.data.model.XtreamChannel
import com.btv.data.model.XtreamEpgListing
import com.btv.data.model.XtreamSeries
import com.btv.data.model.XtreamSeriesInfoResponse
import com.btv.data.model.XtreamVod
import com.btv.data.model.XtreamVodInfo
import com.btv.data.store.CredentialsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeToSequence
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthRepository(
    private val credentialsStore: CredentialsStore
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    // Share connections across catalog, EPG and authentication requests.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** The server's own refusal, as opposed to not reaching it. */
    class LoginRejectedException(message: String) : Exception(message)

    companion object {
        const val AUTO_LOGIN_ATTEMPT_MS = 9_000L

        fun isRejection(error: Throwable?): Boolean =
            error is LoginRejectedException || (error as? retrofit2.HttpException)?.code() in setOf(401, 403)

        /** A user-facing reason - never the raw exception text, which may quote a URL with the password. */
        fun loginErrorMessage(error: Throwable?): String = when {
            error is LoginRejectedException -> error.message ?: "Identifiants refusés"
            (error as? retrofit2.HttpException)?.code() in setOf(401, 403) -> "Identifiants refusés ou abonnement expiré"
            error is retrofit2.HttpException -> "Le serveur a répondu avec une erreur (${error.code()})"
            error is java.net.UnknownHostException -> "Adresse du serveur introuvable"
            error is java.io.IOException -> "Serveur injoignable. Vérifiez l'adresse et la connexion Internet."
            error is kotlinx.serialization.SerializationException -> "Ce serveur ne répond pas comme un serveur IPTV compatible"
            else -> "Échec de connexion"
        }
    }

    private inline fun <T> captureNetworkResult(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    suspend fun login(serverUrl: String, username: String, password: String): Result<AuthSession> = withContext(Dispatchers.IO) {
        try {
            val normalizedUrl = normalizeServerUrl(serverUrl)
            val service = createService(normalizedUrl)

            val response = service.login(username, password)
            val userInfo = response.userInfo ?: return@withContext Result.failure(LoginRejectedException("Réponse du serveur invalide"))
            if (userInfo.auth != 1) {
                return@withContext Result.failure(LoginRejectedException("Identifiants refusés ou abonnement expiré"))
            }

            val session = AuthSession(
                serverUrl = normalizedUrl,
                username = username,
                password = password,
                userInfo = userInfo
            )
            credentialsStore.save(session)
            Result.success(session)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Exception messages may contain a credential-bearing request URL.
            val status = (error as? retrofit2.HttpException)?.code()?.toString() ?: error.javaClass.simpleName
            android.util.Log.w("BtvAuth", "Login failed: $status")
            Result.failure(error)
        }
    }

    /** Why the saved account did (not) open at launch - each case gets its own screen. */
    sealed interface AutoLoginOutcome {
        data class Success(val session: AuthSession) : AutoLoginOutcome
        data object NoSavedAccount : AutoLoginOutcome
        /** The server answered and refused: wrong credentials, expired or suspended account. */
        data class Rejected(val saved: AuthSession, val message: String) : AutoLoginOutcome
        /** No answer (no network, server down, timeout): credentials are probably still fine. */
        data class Unreachable(val saved: AuthSession?, val noNetwork: Boolean) : AutoLoginOutcome
    }

    /**
     * Re-validates the saved account against the server on every launch -
     * an expired account would otherwise "succeed" locally while every
     * catalog call fails. Bounded: two attempts of [AUTO_LOGIN_ATTEMPT_MS]
     * instead of OkHttp's 60s read timeout, so a dead server costs ~20s.
     */
    suspend fun autoLogin(hasNetwork: () -> Boolean = { true }): AutoLoginOutcome = withContext(Dispatchers.IO) {
        val saved = try {
            credentialsStore.load()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Keystore not ready (early boot): keep the account, offer a retry.
            android.util.Log.w("BtvAuth", "Saved session unavailable: ${error.javaClass.simpleName}")
            return@withContext AutoLoginOutcome.Unreachable(null, noNetwork = false)
        } ?: return@withContext AutoLoginOutcome.NoSavedAccount

        if (!hasNetwork()) return@withContext AutoLoginOutcome.Unreachable(saved, noNetwork = true)
        repeat(2) { attempt ->
            val result = kotlinx.coroutines.withTimeoutOrNull(AUTO_LOGIN_ATTEMPT_MS) {
                login(saved.serverUrl, saved.username, saved.password)
            }
            if (result != null) {
                result.getOrNull()?.let { return@withContext AutoLoginOutcome.Success(it) }
                val error = result.exceptionOrNull()
                if (isRejection(error)) {
                    return@withContext AutoLoginOutcome.Rejected(saved, loginErrorMessage(error))
                }
            }
            if (attempt == 0) kotlinx.coroutines.delay(1_500)
        }
        AutoLoginOutcome.Unreachable(saved, noNetwork = !hasNetwork())
    }

    suspend fun logout() {
        credentialsStore.clear()
    }

    suspend fun getLiveCategories(session: AuthSession): Result<List<XtreamCategory>> = withContext(Dispatchers.IO) {
        captureNetworkResult {
            val service = createService(session.serverUrl)
            service.getLiveCategories(session.username, session.password)
        }
    }

    suspend fun getLiveStreams(session: AuthSession, categoryId: String? = null): Result<List<XtreamChannel>> = withContext(Dispatchers.IO) {
        captureNetworkResult {
            val service = createService(session.serverUrl)
            service.getLiveStreams(session.username, session.password, categoryId = categoryId)
        }
    }

    suspend fun getVodCategories(session: AuthSession): Result<List<XtreamCategory>> = withContext(Dispatchers.IO) {
        captureNetworkResult {
            val service = createService(session.serverUrl)
            service.getVodCategories(session.username, session.password)
        }
    }

    suspend fun getVodStreams(session: AuthSession, categoryId: String? = null): Result<List<XtreamVod>> = withContext(Dispatchers.IO) {
        captureNetworkResult {
            val service = createService(session.serverUrl)
            service.getVodStreams(session.username, session.password, categoryId = categoryId)
        }
    }

    suspend fun getSeriesCategories(session: AuthSession): Result<List<XtreamCategory>> = withContext(Dispatchers.IO) {
        captureNetworkResult {
            val service = createService(session.serverUrl)
            service.getSeriesCategories(session.username, session.password)
        }
    }

    suspend fun getSeries(session: AuthSession, categoryId: String? = null): Result<List<XtreamSeries>> = withContext(Dispatchers.IO) {
        captureNetworkResult {
            val service = createService(session.serverUrl)
            service.getSeries(session.username, session.password, categoryId = categoryId)
        }
    }

    /**
     * Unfiltered `action` list (get_vod_streams, get_series, get_live_streams)
     * decoded one element at a time: parsing the whole 30MB+ VOD response at
     * once is what OOM-crashed the app. [onItem] returns false to stop early.
     * A non-array reply (HTML error page, auth object) fails the decode.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun <T> streamCatalog(
        session: AuthSession,
        action: String,
        serializer: KSerializer<T>,
        onItem: (T) -> Boolean
    ): Result<Int> = withContext(Dispatchers.IO) {
        captureNetworkResult {
            val service = createService(session.serverUrl)
            val call = service.getCatalogStream(session.username, session.password, action)
            suspendCancellableCoroutine<Int> { continuation ->
                // A streaming read is blocking I/O. Cancel the underlying
                // OkHttp call to interrupt it; closing its body concurrently
                // with a read is unsafe for HTTP/1.1's fixed-length source.
                val worker = CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val response = call.execute()
                        if (!response.isSuccessful) throw HttpException(response)
                        val body = response.body() ?: throw IllegalStateException("Empty catalog response")
                        val count = body.use {
                            var items = 0
                            for (item in json.decodeToSequence(it.byteStream(), serializer, DecodeSequenceMode.ARRAY_WRAPPED)) {
                                coroutineContext.ensureActive()
                                items++
                                if (!onItem(item)) {
                                    call.cancel()
                                    break
                                }
                            }
                            items
                        }
                        if (continuation.isActive) continuation.resume(count)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
                continuation.invokeOnCancellation {
                    call.cancel()
                    worker.cancel()
                }
            }
        }
    }

    suspend fun getVodInfo(session: AuthSession, vodId: String): Result<XtreamVodInfo> = withContext(Dispatchers.IO) {
        captureNetworkResult {
            val service = createService(session.serverUrl)
            // Like Tizen's `data.info || {}`: some panels answer "info": [] or
            // omit it for a title without metadata; that is "no details", not an error.
            val info = (service.getVodInfo(session.username, session.password, vodId = vodId) as? JsonObject)
                ?.get("info") as? JsonObject
            if (info == null) XtreamVodInfo() else json.decodeFromJsonElement(XtreamVodInfo.serializer(), info)
        }
    }

    suspend fun getSeriesInfo(session: AuthSession, seriesId: String): Result<XtreamSeriesInfoResponse> = withContext(Dispatchers.IO) {
        captureNetworkResult {
            val service = createService(session.serverUrl)
            service.getSeriesInfo(session.username, session.password, seriesId = seriesId)
        }
    }

    suspend fun getShortEpg(session: AuthSession, streamId: String, limit: Int = 8): Result<List<XtreamEpgListing>> = withContext(Dispatchers.IO) {
        captureNetworkResult {
            val service = createService(session.serverUrl)
            service.getShortEpg(session.username, session.password, streamId = streamId, limit = limit).epgListings
        }
    }

    /** Full/past EPG for a channel (js/data.js fetchReplayPrograms) - source of the "Rediffusion" catch-up list. */
    suspend fun getSimpleDataTable(session: AuthSession, streamId: String): Result<List<XtreamEpgListing>> = withContext(Dispatchers.IO) {
        captureNetworkResult {
            val service = createService(session.serverUrl)
            service.getSimpleDataTable(session.username, session.password, streamId = streamId).epgListings
        }
    }

    /**
     * Port of Tizen's buildTimeshiftUrl (js/utils.js): "start" is already in
     * the panel's local time ("YYYY-MM-DD HH:MM:SS") - reformatted as-is,
     * never reinterpreted through a timezone, to avoid any offset drift.
     */
    fun buildTimeshiftUrl(session: AuthSession, streamId: String, startStr: String, durationMinutes: Int): String? {
        val match = Regex("""^(\d{4}-\d{2}-\d{2}) (\d{2}):(\d{2})""").find(startStr) ?: return null
        val (date, hour, minute) = match.destructured
        return normalizeServerUrl(session.serverUrl).toHttpUrl().newBuilder()
            .addPathSegment("timeshift")
            .addPathSegment(session.username)
            .addPathSegment(session.password)
            .addPathSegment(durationMinutes.toString())
            .addEncodedPathSegment("$date:$hour-$minute")
            .addPathSegment("$streamId.ts")
            .build().toString()
    }

    fun buildStreamUrl(session: AuthSession, streamId: String, type: String, extension: String? = null): String {
        val mediaType = when (type.lowercase()) {
            "movie" -> "movie"
            "series" -> "series"
            else -> "live"
        }
        val suffix = extension ?: if (mediaType == "live") "ts" else "mp4"
        return normalizeServerUrl(session.serverUrl).toHttpUrl().newBuilder()
            .addPathSegment(mediaType)
            .addPathSegment(session.username)
            .addPathSegment(session.password)
            .addPathSegment("$streamId.$suffix")
            .build().toString()
    }

    private fun normalizeServerUrl(raw: String): String {
        val trimmed = raw.trim().removeSuffix("/")
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "http://$trimmed"
        }
    }

    private fun createService(baseUrl: String): XtreamApi {
        return Retrofit.Builder()
            .baseUrl("${normalizeServerUrl(baseUrl)}/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(XtreamApi::class.java)
    }
}
