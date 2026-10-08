package com.btv.util

import kotlinx.coroutines.CancellationException

/**
 * Runs a local write (Room, DataStore, files) whose failure must not take
 * the app down - a full Fire TV Stick throws SQLiteFullException/IOException
 * from any of them. Returns null on failure, after logging the error class
 * only (messages may carry paths or credentials).
 */
suspend fun <T> guarded(tag: String, what: String, block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    android.util.Log.w(tag, "$what failed: ${error.javaClass.simpleName}")
    null
}
