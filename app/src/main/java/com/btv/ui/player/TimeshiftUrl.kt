package com.btv.ui.player

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The two ways Xtream panels serve an archive; some accept only one. */
enum class TimeshiftFormat { PATH, PHP }

/**
 * A catch-up program as the panel addresses it. Panels serve an archive
 * from a minute, never from a byte offset, so seeking, resuming and
 * reconnecting all rebuild the URL at a later start instead.
 *
 * [start] is the panel's own local time, kept as-is (see
 * AuthRepository.buildTimeshiftUrl): never converted through a timezone.
 */
data class TimeshiftUrl(
    private val server: HttpUrl,
    val username: String,
    val password: String,
    val streamId: String,
    val start: LocalDateTime,
    val durationMinutes: Int,
    val format: TimeshiftFormat
) {
    val durationMs: Long get() = durationMinutes * 60_000L

    /** Same program, served from [offsetMinutes] in, from [streamId], in [format]. */
    fun build(
        offsetMinutes: Int = 0,
        streamId: String = this.streamId,
        format: TimeshiftFormat = this.format
    ): String {
        val offset = offsetMinutes.coerceIn(0, (durationMinutes - 1).coerceAtLeast(0))
        val from = START_FORMAT.format(start.plusMinutes(offset.toLong()))
        val remaining = (durationMinutes - offset).coerceAtLeast(1).toString()
        val builder = server.newBuilder()
        return when (format) {
            TimeshiftFormat.PATH -> builder
                .addPathSegment("timeshift")
                .addPathSegment(username)
                .addPathSegment(password)
                .addPathSegment(remaining)
                .addEncodedPathSegment(from)
                .addPathSegment("$streamId.ts")
            TimeshiftFormat.PHP -> builder
                .addPathSegment("streaming")
                .addPathSegment("timeshift.php")
                .addQueryParameter("username", username)
                .addQueryParameter("password", password)
                .addQueryParameter("stream", streamId)
                .addEncodedQueryParameter("start", from)
                .addQueryParameter("duration", remaining)
        }.build().toString()
    }

    /** What a successful format is remembered by: the account, not the program. */
    val accountKey: String get() = "${server.host}:${server.port}|$username"

    companion object {
        private val START_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")

        fun parse(url: String): TimeshiftUrl? {
            val http = url.toHttpUrlOrNull() ?: return null
            val segments = http.pathSegments
            val server = http.newBuilder().encodedPath("/").query(null).fragment(null).build()
            val timeshiftAt = segments.indexOf("timeshift")
            if (timeshiftAt >= 0 && segments.size >= timeshiftAt + 6) {
                val (user, pass, duration, from, file) = segments.subList(timeshiftAt + 1, timeshiftAt + 6)
                if (file.endsWith(".m3u8", ignoreCase = true)) return null
                return of(server, user, pass, file.substringBeforeLast('.'), from, duration, TimeshiftFormat.PATH)
            }
            if (segments.lastOrNull() == "timeshift.php") {
                return of(
                    server,
                    http.queryParameter("username") ?: return null,
                    http.queryParameter("password") ?: return null,
                    http.queryParameter("stream") ?: return null,
                    http.queryParameter("start") ?: return null,
                    http.queryParameter("duration") ?: return null,
                    TimeshiftFormat.PHP
                )
            }
            return null
        }

        private fun of(
            server: HttpUrl, user: String, pass: String, streamId: String,
            from: String, duration: String, format: TimeshiftFormat
        ): TimeshiftUrl? {
            val start = runCatching { LocalDateTime.parse(from, START_FORMAT) }.getOrNull() ?: return null
            val minutes = duration.toIntOrNull()?.takeIf { it > 0 } ?: return null
            if (streamId.isEmpty()) return null
            return TimeshiftUrl(server, user, pass, streamId, start, minutes, format)
        }
    }
}

/** True for an Xtream catch-up URL, in either format. */
fun isTimeshiftPath(path: String?): Boolean {
    val segments = path?.split('/')?.filter { it.isNotEmpty() } ?: return false
    return "timeshift" in segments || segments.lastOrNull() == "timeshift.php"
}
