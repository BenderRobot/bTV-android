package com.btv.data.db

import com.btv.data.model.AuthSession
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Namespaces local media data without storing server credentials in Room. */
class AccountScope {
    private val _key = MutableStateFlow<String?>(null)
    val key: StateFlow<String?> = _key

    fun activate(session: AuthSession) {
        activate(session.serverUrl, session.username)
    }

    fun activate(serverUrl: String, username: String) {
        _key.value = keyFor(serverUrl, username)
    }

    fun clear() {
        _key.value = null
    }

    fun requireKey(): String = checkNotNull(_key.value) { "No active account" }

    companion object {
        val global = AccountScope()

        fun keyFor(serverUrl: String, username: String): String {
            val server = serverUrl.trim().trimEnd('/')
            val identity = "${server.length}:$server${username.length}:$username"
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
