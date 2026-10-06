package com.btv.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

val Context.preferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "btv_preferences")

class PreferencesStore(private val context: Context) {

    companion object {
        private val THEME = stringPreferencesKey("theme") // "light", "dark", "auto"
        private val LANGUAGE = stringPreferencesKey("language") // "fr", "en"
        private val TEXT_SIZE = intPreferencesKey("text_size") // 100-200 (%)
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

        val LIVE_BUFFER_OPTIONS = listOf(5, 10, 20, 30)
        const val DEFAULT_LIVE_BUFFER_SECONDS = 5

        private fun keyFor(section: CatalogSection) = when (section) {
            CatalogSection.MOVIES -> HIDDEN_CATEGORIES_MOVIES
            CatalogSection.SERIES -> HIDDEN_CATEGORIES_SERIES
            CatalogSection.LIVE -> HIDDEN_CATEGORIES_LIVE
        }
    }

    fun hiddenCategoryIds(section: CatalogSection): Flow<Set<String>> =
        context.preferencesDataStore.data.map { it[keyFor(section)] ?: emptySet() }

    // NonCancellable: these are launched from viewModelScope, which gets
    // cancelled as soon as the settings screen is left. Without this, a
    // quick back-navigation right after toggling can cancel the DataStore
    // write before it commits to disk, so the checkbox silently reverts
    // the next time the screen is opened.
    suspend fun toggleCategoryHidden(section: CatalogSection, categoryId: String) {
        withContext(NonCancellable) {
            context.preferencesDataStore.edit { prefs ->
                val key = keyFor(section)
                val current = prefs[key] ?: emptySet()
                prefs[key] = if (categoryId in current) current - categoryId else current + categoryId
            }
        }
    }

    val subtitleStyle: Flow<SubtitleStylePrefs> = context.preferencesDataStore.data.map {
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
            context.preferencesDataStore.edit { prefs ->
                prefs[key] = ((prefs[key] ?: default) + 1).mod(optionCount)
            }
        }
    }

    val disabledLanguagePrefixes: Flow<Set<String>> =
        context.preferencesDataStore.data.map { it[DISABLED_LANGUAGE_PREFIXES] ?: emptySet() }

    suspend fun toggleLanguagePrefix(prefix: String) {
        withContext(NonCancellable) {
            context.preferencesDataStore.edit { prefs ->
                val current = prefs[DISABLED_LANGUAGE_PREFIXES] ?: emptySet()
                prefs[DISABLED_LANGUAGE_PREFIXES] = if (prefix in current) current - prefix else current + prefix
            }
        }
    }

    /** Seconds of live stream held before playback starts - the margin that hides network drops. */
    val liveBufferSeconds: Flow<Int> =
        context.preferencesDataStore.data.map { it[LIVE_BUFFER_SECONDS] ?: DEFAULT_LIVE_BUFFER_SECONDS }

    suspend fun cycleLiveBufferSeconds() {
        withContext(NonCancellable) {
            context.preferencesDataStore.edit { prefs ->
                val current = LIVE_BUFFER_OPTIONS.indexOf(prefs[LIVE_BUFFER_SECONDS] ?: DEFAULT_LIVE_BUFFER_SECONDS)
                prefs[LIVE_BUFFER_SECONDS] = LIVE_BUFFER_OPTIONS[(current + 1).mod(LIVE_BUFFER_OPTIONS.size)]
            }
        }
    }

    /** The quality last picked for each live channel family, keyed by liveChannelKey(). */
    val liveQualityChoices: Flow<Map<String, LiveQualityChoice>> = context.preferencesDataStore.data.map { prefs ->
        prefs[LIVE_QUALITY_CHOICES].orEmpty().mapNotNull { entry ->
            val parts = entry.split(FIELD_SEPARATOR)
            if (parts.size != 3) null else parts[0] to LiveQualityChoice(parts[1], parts[2])
        }.toMap()
    }

    suspend fun rememberLiveQuality(channelKey: String, choice: LiveQualityChoice) {
        if (channelKey.isEmpty()) return
        withContext(NonCancellable) {
            context.preferencesDataStore.edit { prefs ->
                val others = prefs[LIVE_QUALITY_CHOICES].orEmpty()
                    .filterNot { it.substringBefore(FIELD_SEPARATOR) == channelKey }
                prefs[LIVE_QUALITY_CHOICES] = others.toSet() +
                    listOf(channelKey, choice.streamId, choice.name).joinToString(FIELD_SEPARATOR.toString())
            }
        }
    }

    val theme: Flow<String> = context.preferencesDataStore.data.map { it[THEME] ?: "auto" }
    val language: Flow<String> = context.preferencesDataStore.data.map { it[LANGUAGE] ?: "fr" }
    val textSize: Flow<Int> = context.preferencesDataStore.data.map { it[TEXT_SIZE] ?: 100 }
    val subtitleLanguage: Flow<String> = context.preferencesDataStore.data.map { it[SUBTITLE_LANGUAGE] ?: "auto" }
    val autoPlayNext: Flow<Boolean> = context.preferencesDataStore.data.map { it[AUTO_PLAY_NEXT] ?: true }
    val rememberPosition: Flow<Boolean> = context.preferencesDataStore.data.map { it[REMEMBER_POSITION] ?: true }
    val autoQuality: Flow<String> = context.preferencesDataStore.data.map { it[AUTO_QUALITY] ?: "auto" }
    val hideCategories: Flow<String> = context.preferencesDataStore.data.map { it[HIDE_CATEGORIES] ?: "[]" }
    val lastHomeIndex: Flow<Int> = context.preferencesDataStore.data.map { it[LAST_HOME_INDEX] ?: 0 }
    val developerMode: Flow<Boolean> = context.preferencesDataStore.data.map { it[DEVELOPER_MODE] ?: false }
    val autoLoginEnabled: Flow<Boolean> = context.preferencesDataStore.data.map { it[AUTO_LOGIN_ENABLED] ?: true }
    val notificationsEnabled: Flow<Boolean> = context.preferencesDataStore.data.map { it[NOTIFICATIONS_ENABLED] ?: true }

    suspend fun setTheme(theme: String) {
        context.preferencesDataStore.edit { it[THEME] = theme }
    }

    suspend fun setLanguage(language: String) {
        context.preferencesDataStore.edit { it[LANGUAGE] = language }
    }

    suspend fun setTextSize(size: Int) {
        context.preferencesDataStore.edit { it[TEXT_SIZE] = size.coerceIn(80, 200) }
    }

    suspend fun setSubtitleLanguage(language: String) {
        context.preferencesDataStore.edit { it[SUBTITLE_LANGUAGE] = language }
    }

    suspend fun setAutoPlayNext(enabled: Boolean) {
        context.preferencesDataStore.edit { it[AUTO_PLAY_NEXT] = enabled }
    }

    suspend fun setRememberPosition(enabled: Boolean) {
        context.preferencesDataStore.edit { it[REMEMBER_POSITION] = enabled }
    }

    suspend fun setAutoQuality(quality: String) {
        context.preferencesDataStore.edit { it[AUTO_QUALITY] = quality }
    }

    suspend fun setHideCategories(categories: String) {
        context.preferencesDataStore.edit { it[HIDE_CATEGORIES] = categories }
    }

    suspend fun setLastHomeIndex(index: Int) {
        context.preferencesDataStore.edit { it[LAST_HOME_INDEX] = index }
    }

    suspend fun setDeveloperMode(enabled: Boolean) {
        context.preferencesDataStore.edit { it[DEVELOPER_MODE] = enabled }
    }

    suspend fun setAutoLoginEnabled(enabled: Boolean) {
        context.preferencesDataStore.edit { it[AUTO_LOGIN_ENABLED] = enabled }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.preferencesDataStore.edit { it[NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun clearAllPreferences() {
        context.preferencesDataStore.edit { it.clear() }
    }
}
