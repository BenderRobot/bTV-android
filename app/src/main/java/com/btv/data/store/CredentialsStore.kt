package com.btv.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.btv.data.model.AuthSession
import com.btv.data.model.UserInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.credentialsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "btv_credentials",
    // Legacy, normally empty store: a corrupt file must not block startup.
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

class CredentialsStore internal constructor(
    private val legacyStore: DataStore<Preferences>,
    private val vault: CredentialsVault
) {
    constructor(context: Context) : this(
        context.applicationContext.credentialsDataStore,
        CredentialsVault(File(context.applicationContext.noBackupFilesDir, "btv_credentials.enc"))
    )

    suspend fun save(session: AuthSession) = withContext(Dispatchers.IO + NonCancellable) {
        lock.withLock {
            // Commit the encrypted replacement before removing the legacy copy.
            vault.write(StoredCredentials(session.serverUrl, session.username, session.password))
            legacyStore.edit { it.clear() }
        }
    }

    suspend fun load(): AuthSession? = withContext(Dispatchers.IO + NonCancellable) {
        lock.withLock {
            val stored = if (vault.exists()) {
                // Never resurrect an old account after loss of the encrypted file's key.
                legacyStore.edit { it.clear() }
                vault.readOrReset()
            } else {
                val legacy = legacyStore.data.first()
                val server = legacy[SERVER_KEY]
                val user = legacy[USERNAME_KEY]
                val password = legacy[PASSWORD_KEY]
                if (server == null || user == null || password == null) {
                    legacyStore.edit { it.clear() }
                    null
                } else {
                    StoredCredentials(server, user, password).also {
                        vault.write(it)
                        legacyStore.edit { prefs -> prefs.clear() }
                    }
                }
            }
            stored?.let {
                AuthSession(it.serverUrl, it.username, it.password, UserInfo(auth = 1, username = it.username))
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO + NonCancellable) {
        lock.withLock {
            legacyStore.edit { it.clear() }
            vault.clear()
        }
    }

    companion object {
        private val lock = Mutex()
        private val SERVER_KEY = stringPreferencesKey("server_url")
        private val USERNAME_KEY = stringPreferencesKey("username")
        private val PASSWORD_KEY = stringPreferencesKey("password")
    }
}
