package com.btv.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The two Supabase functions of the sync (supabase/migrations): push a batch
 * of changes, pull the changes after a server position. Plain HTTPS calls
 * with the project's public key; the tables themselves are closed.
 */
class SyncApi(
    private val baseUrl: String,
    private val publicKey: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    suspend fun push(syncKey: String, items: List<SyncItem>) {
        if (items.isEmpty()) return
        call("sync_push", buildJsonObject {
            put("p_key", syncKey)
            put("p_items", JsonArray(items.map { it.toJson() }))
        })
    }

    suspend fun pull(syncKey: String, since: Long, limit: Int): List<RemoteSyncItem> {
        val body = call("sync_pull", buildJsonObject {
            put("p_key", syncKey)
            put("p_since", since)
            put("p_limit", limit)
        })
        return Json.parseToJsonElement(body).jsonArray.map { element ->
            val row = element.jsonObject
            RemoteSyncItem(
                item = SyncItem(
                    kind = row.getValue("kind").jsonPrimitive.content,
                    id = row.getValue("item_id").jsonPrimitive.content,
                    payload = row["payload"] as? JsonObject ?: JsonObject(emptyMap()),
                    deleted = row.getValue("deleted").jsonPrimitive.boolean,
                    updatedAt = row.getValue("updated_at").jsonPrimitive.long
                ),
                seq = row.getValue("seq").jsonPrimitive.long
            )
        }
    }

    private suspend fun call(function: String, body: JsonObject): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/rest/v1/rpc/$function")
            .header("apikey", publicKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            // The key and the payload are never logged: only the status.
            if (!response.isSuccessful) throw IOException("Supabase $function: HTTP ${response.code}")
            text
        }
    }

    private fun SyncItem.toJson() = buildJsonObject {
        put("kind", kind)
        put("id", id)
        put("payload", payload)
        put("deleted", JsonPrimitive(deleted))
        put("updatedAt", updatedAt)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
