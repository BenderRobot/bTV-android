package com.btv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive
        return primitive?.contentOrNull.orEmpty()
    }
}

object FlexibleIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)

    override fun deserialize(decoder: Decoder): Int {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeInt()
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive
        return primitive?.contentOrNull?.toIntOrNull() ?: 0
    }
}

@Serializable
data class XtreamCategory(
    @SerialName("category_id") @Serializable(with = FlexibleStringSerializer::class) val categoryId: String = "",
    @SerialName("category_name") @Serializable(with = FlexibleStringSerializer::class) val categoryName: String = "",
    @SerialName("parent_id") @Serializable(with = FlexibleStringSerializer::class) val parentId: String? = null,
    @SerialName("category_type") @Serializable(with = FlexibleStringSerializer::class) val categoryType: String? = null
)

@Serializable
data class XtreamChannel(
    @SerialName("stream_id") @Serializable(with = FlexibleStringSerializer::class) val streamId: String = "",
    @SerialName("name") @Serializable(with = FlexibleStringSerializer::class) val name: String = "",
    @SerialName("stream_icon") @Serializable(with = FlexibleStringSerializer::class) val streamIcon: String? = null,
    @SerialName("category_id") @Serializable(with = FlexibleStringSerializer::class) val categoryId: String? = null,
    @SerialName("category_name") @Serializable(with = FlexibleStringSerializer::class) val categoryName: String? = null,
    @SerialName("epg_channel_id") @Serializable(with = FlexibleStringSerializer::class) val epgChannelId: String? = null,
    @SerialName("tv_archive") @Serializable(with = FlexibleIntSerializer::class) val tvArchive: Int? = null,
    @SerialName("tv_archive_duration") @Serializable(with = FlexibleIntSerializer::class) val tvArchiveDuration: Int? = null,
    @SerialName("video_type") @Serializable(with = FlexibleStringSerializer::class) val videoType: String? = null,
    @SerialName("stream_type") @Serializable(with = FlexibleStringSerializer::class) val streamType: String? = null,
    @SerialName("container_extension") @Serializable(with = FlexibleStringSerializer::class) val containerExtension: String? = null,
    @SerialName("last_modified") @Serializable(with = FlexibleStringSerializer::class) val lastModified: String? = null
)

@Serializable
data class XtreamVod(
    @SerialName("stream_id") @Serializable(with = FlexibleStringSerializer::class) val streamId: String = "",
    @SerialName("name") @Serializable(with = FlexibleStringSerializer::class) val name: String = "",
    @SerialName("stream_icon") @Serializable(with = FlexibleStringSerializer::class) val streamIcon: String? = null,
    @SerialName("category_id") @Serializable(with = FlexibleStringSerializer::class) val categoryId: String? = null,
    @SerialName("category_name") @Serializable(with = FlexibleStringSerializer::class) val categoryName: String? = null,
    @SerialName("movie_image") @Serializable(with = FlexibleStringSerializer::class) val movieImage: String? = null,
    @SerialName("plot") @Serializable(with = FlexibleStringSerializer::class) val plot: String? = null,
    @SerialName("cast") @Serializable(with = FlexibleStringSerializer::class) val cast: String? = null,
    @SerialName("rating") @Serializable(with = FlexibleStringSerializer::class) val rating: String? = null,
    @SerialName("year") @Serializable(with = FlexibleStringSerializer::class) val year: String? = null,
    @SerialName("duration") @Serializable(with = FlexibleStringSerializer::class) val duration: String? = null,
    @SerialName("video_type") @Serializable(with = FlexibleStringSerializer::class) val videoType: String? = null,
    @SerialName("container_extension") @Serializable(with = FlexibleStringSerializer::class) val containerExtension: String? = null,
    @SerialName("added") @Serializable(with = FlexibleStringSerializer::class) val added: String? = null
)

@Serializable
data class XtreamSeries(
    @SerialName("series_id") @Serializable(with = FlexibleStringSerializer::class) val seriesId: String = "",
    @SerialName("name") @Serializable(with = FlexibleStringSerializer::class) val title: String = "",
    @SerialName("cover") @Serializable(with = FlexibleStringSerializer::class) val cover: String? = null,
    @SerialName("category_id") @Serializable(with = FlexibleStringSerializer::class) val categoryId: String? = null,
    @SerialName("category_name") @Serializable(with = FlexibleStringSerializer::class) val categoryName: String? = null,
    @SerialName("plot") @Serializable(with = FlexibleStringSerializer::class) val plot: String? = null,
    @SerialName("cast") @Serializable(with = FlexibleStringSerializer::class) val cast: String? = null,
    @SerialName("rating") @Serializable(with = FlexibleStringSerializer::class) val rating: String? = null,
    @SerialName("year") @Serializable(with = FlexibleStringSerializer::class) val year: String? = null,
    @SerialName("genre") @Serializable(with = FlexibleStringSerializer::class) val genre: String? = null,
    // Xtream's get_series has no "added" field for series - Tizen uses
    // last_modified as the recency proxy instead (js/data.js normalizeList).
    @SerialName("last_modified") @Serializable(with = FlexibleStringSerializer::class) val lastModified: String? = null
)

// get_vod_streams never includes plot/genre/cast/director (confirmed by
// Tizen's own comment in js/browse.js: "get_vod_streams ne fournit pas
// plot/genre/casting") - that detail only comes from a per-item get_vod_info
// call, fetched on demand and cached (mirrors Tizen's loadVodInfo).
@Serializable
data class XtreamVodInfoResponse(
    @SerialName("info") val info: XtreamVodInfo? = null
)

@Serializable
data class XtreamVodInfo(
    @SerialName("plot") @Serializable(with = FlexibleStringSerializer::class) val plot: String = "",
    @SerialName("description") @Serializable(with = FlexibleStringSerializer::class) val description: String = "",
    @SerialName("genre") @Serializable(with = FlexibleStringSerializer::class) val genre: String = "",
    @SerialName("duration") @Serializable(with = FlexibleStringSerializer::class) val duration: String = "",
    @SerialName("country") @Serializable(with = FlexibleStringSerializer::class) val country: String = "",
    @SerialName("director") @Serializable(with = FlexibleStringSerializer::class) val director: String = "",
    @SerialName("cast") @Serializable(with = FlexibleStringSerializer::class) val cast: String = "",
    @SerialName("rating") @Serializable(with = FlexibleStringSerializer::class) val rating: String = "",
    @SerialName("releasedate") @Serializable(with = FlexibleStringSerializer::class) val releaseDate: String = "",
    // Tizen reads both spellings and falls back to duration_secs (js/browse.js updateSynopsisPanel).
    @SerialName("release_date") @Serializable(with = FlexibleStringSerializer::class) val releaseDateAlt: String = "",
    @SerialName("duration_secs") @Serializable(with = FlexibleIntSerializer::class) val durationSecs: Int = 0
)

// get_short_epg replies with an envelope, not a bare array - and
// title/description are base64-encoded (see com.btv.util.decodeEpgText).
// Mirrors Tizen's js/data.js `loadLiveEpgListings`.
@Serializable
data class XtreamEpgResponse(
    @SerialName("epg_listings") val epgListings: List<XtreamEpgListing> = emptyList()
)

@Serializable
data class XtreamEpgListing(
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("start") val start: String? = null,
    @SerialName("end") val end: String? = null,
    @SerialName("start_timestamp") @Serializable(with = FlexibleStringSerializer::class) val startTimestamp: String = "",
    @SerialName("stop_timestamp") @Serializable(with = FlexibleStringSerializer::class) val stopTimestamp: String = ""
)

// get_series_info: seasons metadata + episodes grouped by season number
// (mirrors Tizen's loadSeriesInfo/mapSeasonEpisodes in js/browse.js and
// js/data.js). Playing an episode needs this - the flat get_series list
// only has enough to show the series card, never a playable stream id.
@Serializable
data class XtreamSeriesInfoResponse(
    @SerialName("seasons") val seasons: List<XtreamSeasonMeta> = emptyList(),
    @SerialName("episodes") val episodes: Map<String, List<XtreamEpisode>> = emptyMap(),
    /**
     * The series' own details (plot, cast...). Kept raw: panels send an
     * empty array instead of an object when there are none, and a strict
     * type would then fail the whole call - episodes included.
     */
    @SerialName("info") val info: kotlinx.serialization.json.JsonElement? = null
) {
    /** [info] read leniently; null when the panel sent nothing usable. */
    fun details(): XtreamSeriesDetails? {
        val obj = info as? kotlinx.serialization.json.JsonObject ?: return null
        fun text(key: String): String =
            (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it != "null" }?.trim().orEmpty()
        val backdrop = when (val raw = obj["backdrop_path"]) {
            is kotlinx.serialization.json.JsonArray -> (raw.firstOrNull() as? kotlinx.serialization.json.JsonPrimitive)?.content
            is kotlinx.serialization.json.JsonPrimitive -> raw.content
            else -> null
        }?.takeIf { it.isNotBlank() && it != "null" }
        return XtreamSeriesDetails(
            plot = text("plot"),
            cast = text("cast"),
            director = text("director"),
            genre = text("genre"),
            releaseDate = text("releaseDate").ifBlank { text("release_date") },
            rating = text("rating"),
            episodeRunTime = text("episode_run_time"),
            backdropUrl = backdrop
        )
    }
}

/** What get_series_info says about the series itself. */
data class XtreamSeriesDetails(
    val plot: String,
    val cast: String,
    val director: String,
    val genre: String,
    val releaseDate: String,
    val rating: String,
    val episodeRunTime: String,
    val backdropUrl: String?
)

@Serializable
data class XtreamSeasonMeta(
    @SerialName("season_number") @Serializable(with = FlexibleStringSerializer::class) val seasonNumber: String = "",
    @SerialName("name") @Serializable(with = FlexibleStringSerializer::class) val name: String? = null,
    @SerialName("cover") @Serializable(with = FlexibleStringSerializer::class) val cover: String? = null,
    @SerialName("cover_big") @Serializable(with = FlexibleStringSerializer::class) val coverBig: String? = null,
    @SerialName("overview") @Serializable(with = FlexibleStringSerializer::class) val overview: String? = null,
    @SerialName("air_date") @Serializable(with = FlexibleStringSerializer::class) val airDate: String? = null
)

@Serializable
data class XtreamEpisode(
    @SerialName("id") @Serializable(with = FlexibleStringSerializer::class) val id: String = "",
    @SerialName("episode_num") @Serializable(with = FlexibleStringSerializer::class) val episodeNum: String = "",
    @SerialName("title") @Serializable(with = FlexibleStringSerializer::class) val title: String? = null,
    @SerialName("container_extension") @Serializable(with = FlexibleStringSerializer::class) val containerExtension: String? = null,
    @SerialName("info") val info: XtreamEpisodeInfo? = null,
    /** When the panel added it (Unix seconds as text): finds the newest episode. */
    @SerialName("added") @Serializable(with = FlexibleStringSerializer::class) val added: String? = null
)

@Serializable
data class XtreamEpisodeInfo(
    @SerialName("movie_image") @Serializable(with = FlexibleStringSerializer::class) val movieImage: String? = null,
    @SerialName("plot") @Serializable(with = FlexibleStringSerializer::class) val plot: String? = null,
    @SerialName("duration") @Serializable(with = FlexibleStringSerializer::class) val duration: String? = null
)
