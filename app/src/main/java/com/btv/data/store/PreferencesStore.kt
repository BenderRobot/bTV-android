package com.btv.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.catch
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Indices into SubtitleStyleOptions (ui/player/SubtitleStyle.kt); "Normale" size by default. */
data class SubtitleStylePrefs(
    val fontIndex: Int = 0,
    val colorIndex: Int = 0,
    val backgroundIndex: Int = 0,
    val sizeIndex: Int = 1
)

enum class SubtitleStyleField { FONT, COLOR, BACKGROUND, SIZE }

/** A live channel family's remembered quality: the exact sibling stream to start on. */
data class LiveQualityChoice(val streamId: String, val name: String)

/** Mirrors the Tizen app's per-section "hidden categories" scoping. */
enum class CatalogSection { MOVIES, SERIES, LIVE }

/** Pin scope of the Rediffusion sidebar (its entries are channels, not panel categories). */
const val PIN_SCOPE_REPLAY = "replay"

val Context.preferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "btv_preferences",
    // A corrupt preferences file (power cut mid-write) resets to defaults
    // instead of crashing every screen that reads a setting, at every launch.
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { emptyPreferences() }
)

class PreferencesStore(private val context: Context) {

    // An unreadable file (I/O error) reads as defaults rather than crashing.
    private val data = context.preferencesDataStore.data.catch { error ->
        if (error is java.io.IOException) {
            android.util.Log.w("BtvPrefs", "Preferences unreadable: ${error.javaClass.simpleName}")
            emit(emptyPreferences())
        } else throw error
    }

    // A full disk loses this one setting change, not the whole app.
    private suspend fun safeEdit(transform: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        try {
            context.preferencesDataStore.edit(transform)
        } catch (error: java.io.IOException) {
            android.util.Log.w("BtvPrefs", "Preference not saved: ${error.javaClass.simpleName}")
        }
    }

    companion object {
        private val THEME = stringPreferencesKey("theme") // "light", "dark", "auto"
        private val ACCENT_COLOR = stringPreferencesKey("accent_color")
        private val LANGUAGE = stringPreferencesKey("language") // "fr", "en"
        private val TEXT_SIZE = intPreferencesKey("text_size") // 100-200 (%)
        private val REPLAY_PANEL_OFFSET = longPreferencesKey("replay_panel_offset_ms")
        private val SUBTITLE_LANGUAGE = stringPreferencesKey("subtitle_language")
        private val AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        private val REMEMBER_POSITION = booleanPreferencesKey("remember_position")
        private val AUTO_QUALITY = stringPreferencesKey("auto_quality") // "high", "medium", "low", "auto"
        private val HIDE_CATEGORIES = stringPreferencesKey("hide_categories") // JSON array
        private val LAST_HOME_INDEX = intPreferencesKey("last_home_index")
        private val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        private val AUTO_LOGIN_ENABLED = booleanPreferencesKey("auto_login_enabled")
        private val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")

        // Per-section hidden category ids (same scoping as the Tizen app's
        // localStorage keys iptv_hidden_categories_<section>).
        private val HIDDEN_CATEGORIES_MOVIES = stringSetPreferencesKey("hidden_categories_movies")
        private val HIDDEN_CATEGORIES_SERIES = stringSetPreferencesKey("hidden_categories_series")
        private val HIDDEN_CATEGORIES_LIVE = stringSetPreferencesKey("hidden_categories_live")

        // Language-prefix filter (e.g. "AR", "EN" extracted from "|AR| ..."
        // category names) - global, applied across all sections. This does
        // NOT exist on Tizen; it's an Android-only addition on top of the
        // per-category hide feature above.
        private val SUBTITLE_FONT = intPreferencesKey("subtitle_font_index")
        private val SUBTITLE_COLOR = intPreferencesKey("subtitle_color_index")
        private val SUBTITLE_BACKGROUND = intPreferencesKey("subtitle_background_index")
        private val SUBTITLE_SIZE = intPreferencesKey("subtitle_size_index")
        private val DISABLED_LANGUAGE_PREFIXES = stringSetPreferencesKey("disabled_language_prefixes")

        // Live robustness (see docs/IMPLEMENTATION_DIRECT_ROBUSTE_2026-10-06.md).
        private val LIVE_BUFFER_SECONDS = intPreferencesKey("live_buffer_seconds")
        // One entry per channel family: "<channel key>\u001F<stream id>\u001F<stream name>".
        private val LIVE_QUALITY_CHOICES = stringSetPreferencesKey("live_quality_choices")
        private const val FIELD_SEPARATOR = '\u001F'

        // Parental control: "<salt hex>:<sha-256 hex>" of the PIN, and the
        // adult Live categories the user unlocked (hidden by default).
        private val PARENTAL_PIN = stringPreferencesKey("parental_pin")
        private val REVEALED_ADULT_CATEGORIES = stringSetPreferencesKey("revealed_adult_categories")

        val LIVE_BUFFER_OPTIONS = listOf(5, 10, 20, 30)
        const val DEFAULT_LIVE_BUFFER_SECONDS = 5

        private fun keyFor(section: CatalogSection) = when (section) {
            CatalogSection.MOVIES -> HIDDEN_CATEGORIES_MOVIES
            CatalogSection.SERIES -> HIDDEN_CATEGORIES_SERIES
            CatalogSection.LIVE -> HIDDEN_CATEGORIES_LIVE
        }
    }

    fun hiddenCategoryIds(section: CatalogSection): Flow<Set<String>> =
        data.map { it[keyFor(section)] ?: emptySet() }

    // NonCancellable: these are launched from viewModelScope, which gets
    // cancelled as soon as the settings screen is left. Without this, a
    // quick back-navigation right after toggling can cancel the DataStore
    // write before it commits to disk, so the checkbox silently reverts
    // the next time the screen is opened.
    suspend fun toggleCategoryHidden(section: CatalogSection, categoryId: String) {
        withContext(NonCancellable) {
            safeEdit { prefs ->
                val key = keyFor(section)
                val current = prefs[key] ?: emptySet()
                prefs[key] = if (categoryId in current) current - categoryId else current + categoryId
            }
        }
    }

    val subtitleStyle: Flow<SubtitleStylePrefs> = data.map {
        SubtitleStylePrefs(
            fontIndex = it[SUBTITLE_FONT] ?: 0,
            colorIndex = it[SUBTITLE_COLOR] ?: 0,
            backgroundIndex = it[SUBTITLE_BACKGROUND] ?: 0,
            sizeIndex = it[SUBTITLE_SIZE] ?: 1
        )
    }

    /** Tizen cycleSettingsValue('subtitle-*', +1): next option, wrapping around. */
    suspend fun cycleSubtitleStyle(field: SubtitleStyleField, optionCount: Int) {
        val key = when (field) {
            SubtitleStyleField.FONT -> SUBTITLE_FONT
            SubtitleStyleField.COLOR -> SUBTITLE_COLOR
            SubtitleStyleField.BACKGROUND -> SUBTITLE_BACKGROUND
            SubtitleStyleField.SIZE -> SUBTITLE_SIZE
        }
        val default = if (field == SubtitleStyleField.SIZE) 1 else 0
        withContext(NonCancellable) {
            safeEdit { prefs ->
                prefs[key] = ((prefs[key] ?: default) + 1).mod(optionCount)
            }
        }
    }

    val disabledLanguagePrefixes: Flow<Set<String>> =
        data.map { it[DISABLED_LANGUAGE_PREFIXES] ?: emptySet() }

    suspend fun toggleLanguagePrefix(prefix: String) {
        withContext(NonCancellable) {
            safeEdit { prefs ->
                val current = prefs[DISABLED_LANGUAGE_PREFIXES] ?: emptySet()
                prefs[DISABLED_LANGUAGE_PREFIXES] = if (prefix in current) current - prefix else current + prefix
            }
        }
    }

    /** Seconds of live stream held before playback starts - the margin that hides network drops. */
    val liveBufferSeconds: Flow<Int> =
        data.map { it[LIVE_BUFFER_SECONDS] ?: DEFAULT_LIVE_BUFFER_SECONDS }

    suspend fun cycleLiveBufferSeconds() {
        withContext(NonCancellable) {
            safeEdit { prefs ->
                val current = LIVE_BUFFER_OPTIONS.indexOf(prefs[LIVE_BUFFER_SECONDS] ?: DEFAULT_LIVE_BUFFER_SECONDS)
                prefs[LIVE_BUFFER_SECONDS] = LIVE_BUFFER_OPTIONS[(current + 1).mod(LIVE_BUFFER_OPTIONS.size)]
            }
        }
    }

    /** The quality last picked for each live channel family, keyed by liveChannelKey(). */
    val liveQualityChoices: Flow<Map<String, LiveQualityChoice>> = data.map { prefs ->
        prefs[LIVE_QUALITY_CHOICES].orEmpty().mapNotNull { entry ->
            val parts = entry.split(FIELD_SEPARATOR)
            if (parts.size != 3) null else parts[0] to LiveQualityChoice(parts[1], parts[2])
        }.toMap()
    }

    suspend fun rememberLiveQuality(channelKey: String, choice: LiveQualityChoice) {
        if (channelKey.isEmpty()) return
        withContext(NonCancellable) {
            safeEdit { prefs ->
                val others = prefs[LIVE_QUALITY_CHOICES].orEmpty()
                    .filterNot { it.substringBefore(FIELD_SEPARATOR) == channelKey }
                prefs[LIVE_QUALITY_CHOICES] = others.toSet() +
                    listOf(channelKey, choice.streamId, choice.name).joinToString(FIELD_SEPARATOR.toString())
            }
        }
    }

    /** Categories pinned to the top of a section's sidebar, in pin order. */
    fun pinnedCategoryIds(section: CatalogSection): Flow<List<String>> = pinnedCategoryIds(pinScope(section))

    /** [scope]: a catalog section's scope (see [pinScope]) or [PIN_SCOPE_REPLAY] for Rediffusion channels. */
    fun pinnedCategoryIds(scope: String): Flow<List<String>> = data.map { prefs ->
        prefs[pinnedKey(scope)]?.split(PINNED_IDS_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()
    }

    /** Pins at the end of the list, or unpins; returns the new order. */
    suspend fun togglePinnedCategory(section: CatalogSection, categoryId: String): List<String> =
        togglePinnedCategory(pinScope(section), categoryId)

    suspend fun togglePinnedCategory(scope: String, categoryId: String): List<String> {
        var result = emptyList<String>()
        withContext(NonCancellable) {
            safeEdit { prefs ->
                val current = prefs[pinnedKey(scope)]?.split(PINNED_IDS_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()
                result = if (categoryId in current) current - categoryId else current + categoryId
                prefs[pinnedKey(scope)] = result.joinToString(PINNED_IDS_SEPARATOR.toString())
            }
        }
        return result
    }

    private val PINNED_IDS_SEPARATOR = ','

    /** Historic keys: pinned_categories_movies / _series / _live. */
    fun pinScope(section: CatalogSection): String = section.name.lowercase()

    private fun pinnedKey(scope: String) = stringPreferencesKey("pinned_categories_$scope")

    val parentalPinRecord: Flow<String?> = data.map { it[PARENTAL_PIN] }

    suspend fun setParentalPinRecord(record: String) {
        withContext(NonCancellable) {
            safeEdit { it[PARENTAL_PIN] = record }
        }
    }

    val revealedAdultCategoryIds: Flow<Set<String>> =
        data.map { it[REVEALED_ADULT_CATEGORIES] ?: emptySet() }

    suspend fun setAdultCategoryRevealed(categoryId: String, revealed: Boolean) {
        withContext(NonCancellable) {
            safeEdit { prefs ->
                val current = prefs[REVEALED_ADULT_CATEGORIES] ?: emptySet()
                prefs[REVEALED_ADULT_CATEGORIES] = if (revealed) current + categoryId else current - categoryId
            }
        }
    }

    /** Accent color key (see AccentColor), "green" = the app icon's. */
    /**
     * How far the IPTV panel's clock is from UTC, learned from a Rediffusion
     * guide. Home needs it to rebuild a started replay's timeshift address
     * without loading the guide again. Null until first learned.
     */
    val replayPanelOffsetMs: Flow<Long?> = data.map { it[REPLAY_PANEL_OFFSET] }

    suspend fun setReplayPanelOffsetMs(offsetMs: Long) {
        withContext(NonCancellable) { safeEdit { it[REPLAY_PANEL_OFFSET] = offsetMs } }
    }

    val accentColor: Flow<String> = data.map { it[ACCENT_COLOR] ?: "green" }

    suspend fun setAccentColor(key: String) {
        withContext(NonCancellable) { safeEdit { it[ACCENT_COLOR] = key } }
    }

    val theme: Flow<String> = data.map { it[THEME] ?: "auto" }
    val language: Flow<String> = data.map { it[LANGUAGE] ?: "fr" }
    val textSize: Flow<Int> = data.map { it[TEXT_SIZE] ?: 100 }
    val subtitleLanguage: Flow<String> = data.map { it[SUBTITLE_LANGUAGE] ?: "auto" }
    val autoPlayNext: Flow<Boolean> = data.map { it[AUTO_PLAY_NEXT] ?: true }
    val rememberPosition: Flow<Boolean> = data.map { it[REMEMBER_POSITION] ?: true }
    val autoQuality: Flow<String> = data.map { it[AUTO_QUALITY] ?: "auto" }
    val hideCategories: Flow<String> = data.map { it[HIDE_CATEGORIES] ?: "[]" }
    val lastHomeIndex: Flow<Int> = data.map { it[LAST_HOME_INDEX] ?: 0 }
    val developerMode: Flow<Boolean> = data.map { it[DEVELOPER_MODE] ?: false }
    val autoLoginEnabled: Flow<Boolean> = data.map { it[AUTO_LOGIN_ENABLED] ?: true }
    val notificationsEnabled: Flow<Boolean> = data.map { it[NOTIFICATIONS_ENABLED] ?: true }

    suspend fun setTheme(theme: String) {
        safeEdit { it[THEME] = theme }
    }

    suspend fun setLanguage(language: String) {
        safeEdit { it[LANGUAGE] = language }
    }

    suspend fun setTextSize(size: Int) {
        safeEdit { it[TEXT_SIZE] = size.coerceIn(80, 200) }
    }

    suspend fun setSubtitleLanguage(language: String) {
        safeEdit { it[SUBTITLE_LANGUAGE] = language }
    }

    suspend fun setAutoPlayNext(enabled: Boolean) {
        safeEdit { it[AUTO_PLAY_NEXT] = enabled }
    }

    suspend fun setRememberPosition(enabled: Boolean) {
        safeEdit { it[REMEMBER_POSITION] = enabled }
    }

    suspend fun setAutoQuality(quality: String) {
        safeEdit { it[AUTO_QUALITY] = quality }
    }

    suspend fun setHideCategories(categories: String) {
        safeEdit { it[HIDE_CATEGORIES] = categories }
    }

    suspend fun setLastHomeIndex(index: Int) {
        safeEdit { it[LAST_HOME_INDEX] = index }
    }

    suspend fun setDeveloperMode(enabled: Boolean) {
        safeEdit { it[DEVELOPER_MODE] = enabled }
    }

    suspend fun setAutoLoginEnabled(enabled: Boolean) {
        safeEdit { it[AUTO_LOGIN_ENABLED] = enabled }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        safeEdit { it[NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun clearAllPreferences() {
        safeEdit { it.clear() }
    }
}
