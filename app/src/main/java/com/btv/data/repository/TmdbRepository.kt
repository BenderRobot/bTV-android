package com.btv.data.repository

import com.btv.data.api.TmdbApi
import com.btv.data.model.TmdbCastPerson
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w185"
private const val TMDB_CAST_LIMIT = 8

// Port of Tizen's TMDB integration (js/data.js fetchTmdbCast/loadSynopsisCastPhotos):
// the Xtream API only ever returns cast as plain text, never a photo, so this
// is a purely cosmetic best-effort enrichment layered on top of it - any
// failure (network, no match, rate limit) just falls back to the plain text
// already on screen, nothing ever blocks on this.
//
// Personal, free, read-only TMDB token (revocable at themoviedb.org/settings/api)
// - same one already committed in the Tizen reference (js/data.js), reused
// here for the same reason it's there: a purely client-side app with no
// server/build step has nowhere else to keep it but in the shipped code.
private const val TMDB_BEARER_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI4ZjBiMDE5NTU2MjNmNjMyMDhkY2FmZWRjZDBmOWQ1ZCIsIm5iZiI6MTc4ODM2NTQ5Ni40LCJzdWIiOiI2YTk4NGFiODhmMzdjZjlkNmZiZDYwYTQiLCJzY29wZXMiOlsiYXBpX3JlYWQiXSwidmVyc2lvbiI6MX0.bpuXWvdRn1-qbKMavJJM7bzTq3DIDxpT1dya1VuDZSI"

class TmdbRepository {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // Insertion-order eviction once full, same cap/strategy as CatalogCache's
    // other in-memory caches - a null value means "looked up, no match",
    // cached too so a repeated miss doesn't keep re-hitting the network.
    private val cache = LinkedHashMap<String, List<TmdbCastPerson>?>()
    private val cacheMax = 300

    private val api: TmdbApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $TMDB_BEARER_TOKEN")
                    .addHeader("Accept", "application/json")
                    .build()
                chain.proceed(request)
            })
            .build()
        Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmdbApi::class.java)
    }

    // Xtream panels tag titles with noise TMDB never matches against
    // ("|FR| The Whisper Man", "[VOSTFR]", "(MULTI)"...) - exact port of
    // Tizen's cleanTitleForTmdb.
    private fun cleanTitle(name: String): String {
        return name
            .replace(Regex("""\|[^|]*\|"""), " ")
            .replace(Regex("""\[[^]]*]"""), " ")
            .replace(Regex("""\((?:multi|vost(?:fr)?|vf|vo|4k|hdr10?|dolby ?(?:vision|atmos)?)\)""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    @Synchronized
    private fun cacheGet(key: String): List<TmdbCastPerson>? = cache[key]

    @Synchronized
    private fun cachePut(key: String, value: List<TmdbCastPerson>?) {
        if (key !in cache && cache.size >= cacheMax) {
            cache.keys.firstOrNull()?.let { cache.remove(it) }
        }
        cache[key] = value
    }

    /** mediaType: "movie" | "tv". Returns null on no match/failure (caller keeps its plain-text cast). */
    suspend fun fetchCast(mediaType: String, rawTitle: String, year: String?): List<TmdbCastPerson>? {
        val title = cleanTitle(rawTitle)
        if (title.isEmpty()) return null
        val cacheKey = "${mediaType}_${title}_${year.orEmpty()}"
        if (cacheKey in cache) return cacheGet(cacheKey)
        return try {
            val searchResult = api.search(
                mediaType = mediaType,
                query = title,
                year = if (mediaType == "movie") year else null,
                firstAirDateYear = if (mediaType == "tv") year else null
            )
            val match = searchResult.results.firstOrNull()
            if (match == null) {
                cachePut(cacheKey, null)
                return null
            }
            val credits = api.credits(mediaType, match.id)
            val cast = credits.cast.take(TMDB_CAST_LIMIT).map {
                TmdbCastPerson(name = it.name, photoUrl = it.profile_path?.let { path -> "$TMDB_IMAGE_BASE$path" })
            }
            cachePut(cacheKey, cast)
            cast
        } catch (e: Exception) {
            null
        }
    }
}
