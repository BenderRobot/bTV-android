package com.btv.data.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class StoredCredentials(val serverUrl: String, val username: String, val password: String)

/** Only authenticated ciphertext is written to the no-backup directory. */
internal class CredentialsVault(file: File, private val keyAlias: String = "btv_credentials_v1") {
    private val atomicFile = AtomicFile(file)
    private val keyStore: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun exists(): Boolean = atomicFile.baseFile.exists() || File(atomicFile.baseFile.path + ".bak").exists()

    fun write(credentials: StoredCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(HEADER)
        val plaintext = Json.encodeToString(credentials).toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        val output = atomicFile.startWrite()
        try {
            output.write(HEADER)
            output.write(cipher.iv)
            output.write(encrypted)
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    fun readOrReset(): StoredCredentials? {
        // I/O errors propagate without erasing recoverable credentials.
        val bytes = atomicFile.readFully()
        return try {
            require(bytes.size >= HEADER.size + IV_SIZE + TAG_SIZE)
            require(bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER))
            val key = keyStore.getKey(keyAlias, null) as? SecretKey
                ?: throw MissingKeyException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, HEADER.size, IV_SIZE))
            cipher.updateAAD(HEADER)
            val offset = HEADER.size + IV_SIZE
            val plaintext = cipher.doFinal(bytes, offset, bytes.size - offset)
            try { Json.decodeFromString<StoredCredentials>(plaintext.toString(Charsets.UTF_8)) }
            finally { plaintext.fill(0) }
        } catch (_: AEADBadTagException) {
            // Tampered or encrypted with a key that no longer exists: unrecoverable.
            clear()
            null
        } catch (_: MissingKeyException) {
            clear()
            null
        } catch (error: GeneralSecurityException) {
            // A keystore that is not ready yet (early boot, Fire OS update)
            // must not log the user out for good: fail this attempt only.
            throw java.io.IOException("Credentials keystore unavailable", error)
        } catch (_: SerializationException) {
            clear()
            null
        } catch (_: IllegalArgumentException) {
            clear()
            null
        }
    }

    fun clear() {
        atomicFile.delete()
        keyStore.deleteEntry(keyAlias)
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private class MissingKeyException : GeneralSecurityException("Credentials key unavailable")

    companion object {
        private val HEADER = byteArrayOf(0x42, 0x54, 0x56, 0x01)
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 16
    }
}
