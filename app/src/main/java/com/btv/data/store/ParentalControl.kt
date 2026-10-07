package com.btv.data.store

import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.security.SecureRandom

private val ADULT_CATEGORY_REGEX = Regex(
    """(?<![\p{L}\p{N}])(ADULTS?|ADULTES?|XXX+|PORN\w*|EROTI\w*|18\s?\+|\+\s?18)(?![\p{L}\p{N}])""",
    RegexOption.IGNORE_CASE
)

/** "FOR ADULTS", "|XXX| ADULTES", "+18"... - hidden and PIN-protected by default. */
fun isAdultCategoryName(name: String): Boolean = ADULT_CATEGORY_REGEX.containsMatchIn(name)

/** Where the PIN lives; an interface so the PIN flow is testable without DataStore. */
interface PinStore {
    suspend fun hasPin(): Boolean
    suspend fun verify(pin: String): Boolean
    suspend fun setPin(pin: String)
}

/**
 * Parental control PIN, stored as a salted SHA-256 - never the PIN itself.
 * It only keeps a child away from adult channels: four digits are no
 * protection against someone holding the device's data.
 */
class ParentalControl(private val store: PreferencesStore) : PinStore {

    override suspend fun hasPin(): Boolean = store.parentalPinRecord.first() != null

    override suspend fun verify(pin: String): Boolean {
        val record = store.parentalPinRecord.first() ?: return false
        val salt = record.substringBefore(':', "")
        return salt.isNotEmpty() && record == recordFor(salt, pin)
    }

    override suspend fun setPin(pin: String) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes).toHex()
        store.setParentalPinRecord(recordFor(salt, pin))
    }

    private fun recordFor(salt: String, pin: String): String =
        "$salt:" + MessageDigest.getInstance("SHA-256").digest("$salt$pin".toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
