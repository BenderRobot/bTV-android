package com.btv.domain.usecase

import com.btv.data.store.PreferencesStore
import kotlinx.coroutines.flow.Flow

class GetPreferencesUseCase(private val preferencesStore: PreferencesStore) {

    fun getTheme(): Flow<String> = preferencesStore.theme

    fun getLanguage(): Flow<String> = preferencesStore.language

    fun getTextSize(): Flow<Int> = preferencesStore.textSize

    fun getSubtitleLanguage(): Flow<String> = preferencesStore.subtitleLanguage

    fun getAutoPlayNext(): Flow<Boolean> = preferencesStore.autoPlayNext

    fun getRememberPosition(): Flow<Boolean> = preferencesStore.rememberPosition

    fun getAutoQuality(): Flow<String> = preferencesStore.autoQuality

    fun getLastHomeIndex(): Flow<Int> = preferencesStore.lastHomeIndex

    fun getDeveloperMode(): Flow<Boolean> = preferencesStore.developerMode

    suspend fun setTheme(theme: String) = preferencesStore.setTheme(theme)

    suspend fun setLanguage(language: String) = preferencesStore.setLanguage(language)

    suspend fun setTextSize(size: Int) = preferencesStore.setTextSize(size)

    suspend fun setSubtitleLanguage(language: String) = preferencesStore.setSubtitleLanguage(language)

    suspend fun setAutoPlayNext(enabled: Boolean) = preferencesStore.setAutoPlayNext(enabled)

    suspend fun setRememberPosition(enabled: Boolean) = preferencesStore.setRememberPosition(enabled)

    suspend fun setAutoQuality(quality: String) = preferencesStore.setAutoQuality(quality)

    suspend fun setLastHomeIndex(index: Int) = preferencesStore.setLastHomeIndex(index)

    suspend fun setDeveloperMode(enabled: Boolean) = preferencesStore.setDeveloperMode(enabled)

    suspend fun clearAllPreferences() = preferencesStore.clearAllPreferences()
}
