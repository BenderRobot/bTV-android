package com.btv.util

// Tizen's browser could not read MKV, so it asked the provider to remux it
// to HLS. Media3 can extract Matroska directly; keep that native route on
// Android to avoid a server-side remux and preserve embedded tracks.
// Live uses its own fixed TS route and does not call this function.
private val ANDROID_NATIVE_EXTENSIONS = setOf("mp4", "mkv", "m3u8", "ts", "webm", "m4v")

fun resolveExtension(containerExtension: String?, allowedOutputFormats: List<String>, defaultExt: String): String {
    val rawExt = containerExtension?.trim()?.removePrefix(".")?.lowercase().orEmpty()
    if (rawExt.isEmpty()) return defaultExt
    if (rawExt in ANDROID_NATIVE_EXTENSIONS) return rawExt
    if (allowedOutputFormats.any { it.equals("m3u8", ignoreCase = true) }) return "m3u8"
    return rawExt
}
