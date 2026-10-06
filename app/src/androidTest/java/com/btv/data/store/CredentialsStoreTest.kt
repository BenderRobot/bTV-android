package com.btv.data.store

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import com.btv.data.model.AuthSession
import com.btv.data.model.UserInfo
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Isolated test files/aliases: never reads or clears the installed user's credentials. */
class CredentialsStoreTest {
    private val id = UUID.randomUUID().toString()
    private val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "credential-test-$id").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val legacy = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "legacy.preferences_pb") }
    private val encryptedFile = File(directory, "credentials.enc")
    private val alias = "btv_test_$id"
    private val vault = CredentialsVault(encryptedFile, alias)
    private val store = CredentialsStore(legacy, vault)
    private val session = AuthSession("https://example.invalid", "migration-user", "fake-password-for-migration-test", UserInfo(auth = 1))

    private suspend fun seedLegacy() {
        legacy.edit {
            it[stringPreferencesKey("server_url")] = session.serverUrl
            it[stringPreferencesKey("username")] = session.username
            it[stringPreferencesKey("password")] = session.password
        }
    }

    @After fun cleanup() {
        scope.cancel()
        vault.clear()
        directory.deleteRecursively()
    }

    @Test fun migratesLegacyThenSurvivesNewStoreInstance() = runBlocking {
        seedLegacy()
        assertEquals(session.password, store.load()?.password)
        assertTrue(legacy.data.first().asMap().isEmpty())
        assertFalse(encryptedFile.readBytes().toString(Charsets.ISO_8859_1).contains(session.password))
        val reopened = CredentialsStore(legacy, CredentialsVault(encryptedFile, alias))
        assertEquals(session.username, reopened.load()?.username)
    }

    @Test fun eachWriteUsesFreshIvAndLogoutRemovesBothStores() = runBlocking {
        store.save(session)
        val first = encryptedFile.readBytes()
        store.save(session)
        assertFalse(first.contentEquals(encryptedFile.readBytes()))
        assertEquals(session.password, store.load()?.password)
        store.clear()
        assertNull(store.load())
        assertFalse(encryptedFile.exists())
        assertTrue(legacy.data.first().asMap().isEmpty())
    }

    @Test fun missingKeyDoesNotResurrectLegacyAndAllowsNewLogin() = runBlocking {
        store.save(session)
        seedLegacy() // Simulates an interrupted earlier migration with stale plaintext remaining.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertNull(store.load())
        assertTrue(legacy.data.first().asMap().isEmpty())
        store.save(session.copy(password = "replacement-password"))
        assertEquals("replacement-password", store.load()?.password)
    }

    @Test fun alteredCiphertextReturnsToLogin() = runBlocking {
        store.save(session)
        val bytes = encryptedFile.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        encryptedFile.writeBytes(bytes)
        assertNull(store.load())
        assertFalse(encryptedFile.exists())
    }

    @Test fun truncatedFileReturnsToLogin() = runBlocking {
        encryptedFile.writeBytes(byteArrayOf(1, 2))
        assertNull(store.load())
    }

    @Test fun failedEncryptedCommitPreservesLegacyCredentials() = runBlocking {
        seedLegacy()
        val blocker = File(directory, "not-a-directory").apply { writeText("blocked") }
        val brokenStore = CredentialsStore(legacy, CredentialsVault(File(blocker, "credentials.enc"), alias))
        try {
            brokenStore.load()
            fail("Migration should fail if its encrypted output cannot be committed")
        } catch (_: java.io.IOException) {
            assertEquals(session.password, legacy.data.first()[stringPreferencesKey("password")])
        }
    }
}
