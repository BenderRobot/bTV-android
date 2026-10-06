package com.btv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btv.data.model.AuthSession
import com.btv.data.model.XtreamChannel
import com.btv.data.model.XtreamCategory
import com.btv.data.model.XtreamSeries
import com.btv.data.model.XtreamVod
import com.btv.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = false,
    val categories: List<String> = emptyList(),
    val liveCategories: List<XtreamCategory> = emptyList(),
    val movieCategories: List<XtreamCategory> = emptyList(),
    val seriesCategories: List<XtreamCategory> = emptyList(),
    val liveChannels: List<XtreamChannel> = emptyList(),
    val vodItems: List<XtreamVod> = emptyList(),
    val seriesItems: List<XtreamSeries> = emptyList(),
    val errorMessage: String? = null
)

class HomeViewModel(
    private val repository: AuthRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    /**
     * Categories only - NOT full stream/vod/series lists. This used to fetch
     * `getLiveStreams`/`getVodStreams`/`getSeries` with no category_id (the
     * entire unfiltered catalog) on every single app launch, which is
     * exactly the pattern that OOM-crashed the app before per-category
     * fetching was introduced elsewhere (BrowseViewModel) - it just hadn't
     * been hit hard enough to notice here yet. Nothing reachable in the UI
     * actually reads the old `liveChannels`/`vodItems`/`seriesItems` fields
     * (only the dead, unused-route `BrowseRoute(viewModel: HomeViewModel...)`
     * in HomeRoute.kt did), so they're dropped rather than made "safe".
     */
    fun load(session: AuthSession) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val results = coroutineScope {
                val categories = async { repository.getLiveCategories(session) }
                val vodCategories = async { repository.getVodCategories(session) }
                val seriesCategories = async { repository.getSeriesCategories(session) }
                CatalogResults(
                    categories = categories.await(),
                    vodCategories = vodCategories.await(),
                    seriesCategories = seriesCategories.await()
                )
            }

            val categoriesResult = results.categories
            val vodCategoriesResult = results.vodCategories
            val seriesCategoriesResult = results.seriesCategories

            val categories = categoriesResult.getOrElse { emptyList() }
                .mapNotNull { it.categoryName }
                .ifEmpty { listOf("Live") }

            val liveCategories = categoriesResult.getOrElse { emptyList() }
                .filter { it.categoryId.isNotBlank() && it.categoryName.isNotBlank() }
                .distinctBy { it.categoryId }
                .sortedBy { it.categoryName }
            val movieCategories = vodCategoriesResult.getOrElse { emptyList() }
                .filter { it.categoryId.isNotBlank() && it.categoryName.isNotBlank() }
                .distinctBy { it.categoryId }
                .sortedBy { it.categoryName }
            val seriesCategories = seriesCategoriesResult.getOrElse { emptyList() }
                .filter { it.categoryId.isNotBlank() && it.categoryName.isNotBlank() }
                .distinctBy { it.categoryId }
                .sortedBy { it.categoryName }

            _uiState.value = HomeUiState(
                isLoading = false,
                categories = categories,
                liveCategories = liveCategories,
                movieCategories = movieCategories,
                seriesCategories = seriesCategories,
                errorMessage = if (categoriesResult.isFailure || vodCategoriesResult.isFailure || seriesCategoriesResult.isFailure) {
                    "Chargement du catalogue incomplet"
                } else null
            )
        }
    }
}

private data class CatalogResults(
    val categories: Result<List<com.btv.data.model.XtreamCategory>>,
    val vodCategories: Result<List<com.btv.data.model.XtreamCategory>>,
    val seriesCategories: Result<List<com.btv.data.model.XtreamCategory>>
)
