package com.btv.util

import android.util.Base64

/** Xtream's EPG title/description fields are base64-encoded UTF-8, same as Tizen's `decodeEpgText` (js/utils.js). */
fun decodeEpgText(base64: String?): String {
    if (base64.isNullOrBlank()) return ""
    return try {
        String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        ""
    }
}
