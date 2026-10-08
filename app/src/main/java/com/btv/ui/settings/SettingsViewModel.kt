package com.btv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.btv.data.cache.CatalogCache
import com.btv.data.model.AuthSession
import com.btv.data.model.XtreamCategory
import com.btv.data.model.XtreamChannel
import com.btv.data.repository.AuthRepository
import com.btv.data.store.CatalogSection
import com.btv.data.store.PreferencesStore
import com.btv.data.store.ParentalControl
import com.btv.data.store.isAdultCategoryName
import com.btv.ui.browse.extractLanguagePrefix
import com.btv.ui.parental.PinFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isLoading: Boolean = true,
    val serverUrl: String = "",
    val username: String = "",
    val expirationDate: String? = null,
    val categoriesBySection: Map<CatalogSection, List<XtreamCategory>> = emptyMap(),
    val availableLanguagePrefixes: List<String> = emptyList(),
    val failedSections: Set<CatalogSection> = emptySet()
)

class SettingsViewModel(
    private val authRepository: AuthRepository,
    private val session: AuthSession,
    private val preferencesStore: PreferencesStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState
    private var categoriesLoadJob: Job? = null

    val disabledLanguagePrefixes: StateFlow<Set<String>> =
        preferencesStore.disabledLanguagePrefixes.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())

    val hiddenMovieIds: StateFlow<Set<String>> = preferencesStore.hiddenCategoryIds(CatalogSection.MOVIES)
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())
    val hiddenSeriesIds: StateFlow<Set<String>> = preferencesStore.hiddenCategoryIds(CatalogSection.SERIES)
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())
    val hiddenLiveIds: StateFlow<Set<String>> = preferencesStore.hiddenCategoryIds(CatalogSection.LIVE)
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())

    /** Parental control: the PIN dialog, whether a PIN exists, and the adult Live categories unlocked. */
    val pinFlow = PinFlow(ParentalControl(preferencesStore), viewModelScope)
    val hasPin: StateFlow<Boolean> = preferencesStore.parentalPinRecord.map { it != null }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    val revealedAdultIds: StateFlow<Set<String>> = preferencesStore.revealedAdultCategoryIds
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())

    fun changePin() = pinFlow.change()

    /** Adult Live categories are hidden by default; showing one needs the PIN, hiding it again doesn't. */
    fun isCategoryHidden(section: CatalogSection, category: XtreamCategory, hiddenIds: Set<String>): Boolean =
        if (section == CatalogSection.LIVE && isAdultCategoryName(category.categoryName)) {
            category.categoryId !in revealedAdultIds.value
        } else category.categoryId in hiddenIds

    fun toggleCategory(section: CatalogSection, category: XtreamCategory) {
        if (section != CatalogSection.LIVE || !isAdultCategoryName(category.categoryName)) {
            toggleCategoryHidden(section, category.categoryId)
            return
        }
        val revealed = category.categoryId in revealedAdultIds.value
        if (revealed) {
            viewModelScope.launch { preferencesStore.setAdultCategoryRevealed(category.categoryId, false) }
        } else {
            pinFlow.require("Afficher « ${category.categoryName} ».") {
                viewModelScope.launch { preferencesStore.setAdultCategoryRevealed(category.categoryId, true) }
            }
        }
    }

    fun hiddenIdsFor(section: CatalogSection): StateFlow<Set<String>> = when (section) {
        CatalogSection.MOVIES -> hiddenMovieIds
        CatalogSection.SERIES -> hiddenSeriesIds
        CatalogSection.LIVE -> hiddenLiveIds
    }

    init {
        _uiState.update {
            it.copy(
                serverUrl = session.serverUrl,
                username = session.username,
                expirationDate = session.userInfo.exp_date
            )
        }
        loadCategories()
    }

    /**
     * Fetches the (lightweight, categories-only) catalog for all three
     * sections up front - same idea as Tizen's splash preload - so the
     * language-prefix list and the per-section hidden-categories checklist
     * are both ready without extra loading when the user switches tabs.
     */
    fun loadCategories() {
        if (categoriesLoadJob?.isActive == true) return
        val generation = CatalogCache.generationToken()
        categoriesLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, failedSections = emptySet()) }

            // Cache-first: reads the splash-time preload (MainActivity's
            // preloadCatalogCategories) when it's there, so opening Réglages
            // right after launch doesn't re-fetch what's already in memory.
            val (moviesResult, seriesResult, liveResult) = coroutineScope {
                val moviesDeferred = async { CatalogCache.loadCategories(CatalogSection.MOVIES, generation) { authRepository.getVodCategories(session) } }
                val seriesDeferred = async { CatalogCache.loadCategories(CatalogSection.SERIES, generation) { authRepository.getSeriesCategories(session) } }
                val liveDeferred = async { CatalogCache.loadCategories(CatalogSection.LIVE, generation) { authRepository.getLiveCategories(session) } }
                Triple(moviesDeferred.await(), seriesDeferred.await(), liveDeferred.await())
            }
            val movies = moviesResult.getOrElse { emptyList() }
            val series = seriesResult.getOrElse { emptyList() }
            val live = liveResult.getOrElse { emptyList() }
            val failedSections = buildSet {
                if (moviesResult.isFailure) add(CatalogSection.MOVIES)
                if (seriesResult.isFailure) add(CatalogSection.SERIES)
                if (liveResult.isFailure) add(CatalogSection.LIVE)
            }

            val bySection = mapOf(
                CatalogSection.MOVIES to movies.sortedBy { it.categoryName },
                CatalogSection.SERIES to series.sortedBy { it.categoryName },
                CatalogSection.LIVE to live.sortedBy { it.categoryName }
            )

            val prefixes = (movies + series + live)
                .mapNotNull { extractLanguagePrefix(it.categoryName) }
                .distinct()
                .sorted()

            _uiState.update {
                it.copy(
                    isLoading = false,
                    categoriesBySection = bySection,
                    availableLanguagePrefixes = prefixes,
                    failedSections = failedSections
                )
            }

            // Channel names carry tags their categories don't (|BE| channels
            // inside a |FR| category): without them here, those channels
            // could never be filtered out, in Direct or in Rediffusion.
            val adultCategoryIds = live.filter { isAdultCategoryName(it.categoryName) }.mapTo(HashSet()) { it.categoryId }
            val channelPrefixes = CatalogCache.loadLiveNamePrefixes(generation) {
                val found = HashSet<String>()
                authRepository.streamCatalog(session, "get_live_streams", XtreamChannel.serializer()) { channel ->
                    if (channel.categoryId !in adultCategoryIds && !isAdultCategoryName(channel.name)) {
                        extractLanguagePrefix(channel.name)?.let(found::add)
                    }
                    true
                }.map { found }
            }.getOrNull().orEmpty()
            if (channelPrefixes.isNotEmpty()) {
                _uiState.update { state ->
                    state.copy(availableLanguagePrefixes = (state.availableLanguagePrefixes + channelPrefixes).distinct().sorted())
                }
            }
        }
    }

    fun toggleCategoryHidden(section: CatalogSection, categoryId: String) {
        viewModelScope.launch {
            preferencesStore.toggleCategoryHidden(section, categoryId)
        }
    }

    fun toggleLanguagePrefix(prefix: String) {
        viewModelScope.launch {
            preferencesStore.toggleLanguagePrefix(prefix)
        }
    }
}

private fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    value = transform(value)
}

class SettingsViewModelFactory(
    private val authRepository: AuthRepository,
    private val session: AuthSession,
    private val preferencesStore: PreferencesStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SettingsViewModel(authRepository, session, preferencesStore) as T
    }
}
