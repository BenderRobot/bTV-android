package com.btv.ui.browse.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.btv.R
import com.btv.ui.browse.BrowseCategory
import com.btv.ui.components.BtvOverline
import com.btv.ui.components.BtvSearchField
import com.btv.ui.components.btvFocusSurface
import com.btv.ui.components.btvSelectionBar
import com.btv.ui.components.onTap
import com.btv.ui.theme.BtvDimens
import com.btv.ui.theme.BtvMotion
import com.btv.ui.theme.BtvShapes
import com.btv.ui.theme.BtvTheme
import com.btv.ui.theme.BtvType

@Composable
fun CategorySidebar(
    title: String,
    categories: List<BrowseCategory>,
    selectedCategoryId: String?,
    categorySearch: String,
    isFocused: Boolean,
    onCategorySelected: (String) -> Unit,
    onSearchChanged: (String) -> Unit,
    onBack: () -> Unit = {},
    // ☰ (Menu) or a long OK on a category pins it to the top of the list.
    canPin: Boolean = false,
    onTogglePin: (String) -> Unit = {},
    onFocusMiniPlayer: (() -> Unit)? = null
) {
    val filteredCategories = categories.filter {
        it.searchName.contains(categorySearch, ignoreCase = true)
    }

    var selectedIndex by remember { mutableIntStateOf(0) }
    // The row that really holds focus (-1: none, e.g. the search field has it).
    // The highlight follows it, not the sidebar panel's own focus state.
    var focusedIndex by remember { mutableIntStateOf(-1) }
    // A pin moves the row: focus follows it to its new place.
    var refocusAfterPinId by remember { mutableStateOf<String?>(null) }
    // OK press tracking for pinning: which row saw the key-down (a key-up
    // left over from the previous screen must do nothing) and the last plain
    // OK, for the double press.
    var okDownId by remember { mutableStateOf<String?>(null) }
    var lastOkId by remember { mutableStateOf<String?>(null) }
    var lastOkUpTime by remember { mutableStateOf(0L) }
    val categoryFocusRequesters = remember(filteredCategories.size) {
        List(filteredCategories.size) { FocusRequester() }
    }
    val searchFocusRequester = remember { FocusRequester() }
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Scrolls the target into composition BEFORE requesting focus on it -
    // LazyColumn only composes items near the viewport, so a FocusRequester
    // for an off-screen index is never attached and requestFocus() on it
    // just silently fails (caught below), which is why the list previously
    // never scrolled past whatever was already visible.
    fun focusIndex(index: Int) {
        coroutineScope.launch {
            try {
                lazyListState.animateScrollToItem(index = maxOf(0, index - 2))
            } catch (e: IllegalStateException) {
            }
            var attempts = 0
            while (attempts < 10) {
                try {
                    categoryFocusRequesters.getOrNull(index)?.requestFocus()
                    break
                } catch (e: IllegalStateException) {
                    attempts++
                    delay(30)
                }
            }
        }
    }

    LaunchedEffect(selectedCategoryId, filteredCategories) {
        val index = filteredCategories.indexOfFirst { it.id == selectedCategoryId }
        if (index >= 0 && index != selectedIndex) {
            selectedIndex = index
        }
        refocusAfterPinId?.let { pinnedId ->
            refocusAfterPinId = null
            val moved = filteredCategories.indexOfFirst { it.id == pinnedId }
            if (moved >= 0) {
                selectedIndex = moved
                focusIndex(moved)
            }
        }
    }

    // When the sidebar panel itself gains focus (e.g. via the outer
    // FocusRequester in BrowseScreen), delegate actual focus down to the
    // currently selected item - the panel Box has no focusable of its own.
    LaunchedEffect(isFocused, categoryFocusRequesters) {
        if (isFocused && categoryFocusRequesters.isNotEmpty()) {
            focusIndex(selectedIndex)
        }
    }

    val colors = BtvTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(colors.surface)
            .padding(horizontal = BtvDimens.sidebarPadding, vertical = 20.dp)
    ) {
        // Header: back + section title
        var backFocused by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp)
                // Before focusable(): an onFocusChanged placed after it never
                // sees this row's own focus.
                .onFocusChanged { backFocused = it.isFocused }
                .focusable()
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown &&
                        (keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter)
                    ) {
                        onBack()
                        true
                    } else {
                        false
                    }
                }
                .btvFocusSurface(backFocused, focusedColor = colors.overlayMedium)
                .onTap { onBack() }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_arrow_left),
                contentDescription = "Retour",
                tint = if (backFocused) colors.textPrimary else colors.textSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = com.btv.util.displayCategory(title),
                style = BtvType.section,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        BtvSearchField(
            value = categorySearch,
            onValueChange = onSearchChanged,
            placeholder = "Rechercher une catégorie",
            focusRequester = searchFocusRequester,
            modifier = Modifier.fillMaxWidth(),
            fontSize = BtvType.meta.fontSize,
            onDpadDown = {
                if (filteredCategories.isNotEmpty()) focusIndex(selectedIndex)
                true
            },
            onBack = {
                if (categorySearch.isNotEmpty()) {
                    onSearchChanged("")
                } else if (filteredCategories.isNotEmpty()) {
                    focusIndex(selectedIndex)
                }
                true
            }
        )

        Spacer(Modifier.height(10.dp))

        // Categories list. Group labels live inside the row's own item so
        // LazyColumn indices stay equal to category indices (focusIndex
        // scrolls by index).
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            itemsIndexed(filteredCategories) { index, category ->
                val isSelected = category.id == selectedCategoryId
                val isCategoryFocused = focusedIndex == index

                groupLabel(category, filteredCategories.getOrNull(index - 1))?.let { label ->
                    Column {
                        if (index > 0) Spacer(Modifier.height(14.dp))
                        // Accent-coloured: the group headings must stand out from the entries.
                        BtvOverline(label, Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp), color = colors.accentOnSurface)
                    }
                }

                CategoryItem(
                    category = category,
                    isSelected = isSelected,
                    isFocused = isCategoryFocused,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(categoryFocusRequesters[index])
                        .onKeyEvent { keyEvent ->
                            val isOk = keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter ||
                                keyEvent.key == Key.NumPadEnter
                            val pinnable = canPin && !category.isQuickAccess
                            fun pin() {
                                refocusAfterPinId = category.id
                                onTogglePin(category.id)
                            }
                            if (isOk && keyEvent.type == KeyEventType.KeyUp) {
                                if (okDownId != category.id) return@onKeyEvent false
                                okDownId = null
                                val native = keyEvent.nativeKeyEvent
                                val isDouble = lastOkId == category.id && native.eventTime - lastOkUpTime < DOUBLE_OK_MS
                                when {
                                    pinnable && isDouble -> {
                                        lastOkId = null
                                        pin()
                                    }
                                    else -> {
                                        lastOkId = category.id
                                        lastOkUpTime = native.eventTime
                                        onCategorySelected(category.id)
                                    }
                                }
                                return@onKeyEvent true
                            }
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.key) {
                                    Key.DirectionDown -> {
                                        if (selectedIndex < filteredCategories.size - 1) {
                                            selectedIndex++
                                            focusIndex(selectedIndex)
                                            onCategorySelected(filteredCategories[selectedIndex].id)
                                        } else {
                                            onFocusMiniPlayer?.invoke()
                                        }
                                        true
                                    }

                                    Key.DirectionUp -> {
                                        if (selectedIndex > 0) {
                                            selectedIndex--
                                            focusIndex(selectedIndex)
                                            onCategorySelected(filteredCategories[selectedIndex].id)
                                        } else {
                                            try { searchFocusRequester.requestFocus() } catch (e: IllegalStateException) {}
                                        }
                                        true
                                    }

                                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                        // Acted on at release: a double OK pins, a single one selects.
                                        if (keyEvent.nativeKeyEvent.repeatCount == 0) okDownId = category.id
                                        true
                                    }

                                    Key.Menu -> {
                                        if (pinnable) pin()
                                        true
                                    }

                                    else -> false
                                }
                            } else {
                                false
                            }
                        }
                        // Before focusable() so it sees this row's own focus.
                        // Visual only: selection keeps following the explicit
                        // Up/Down/OK handlers above, as it always did.
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                focusedIndex = index
                            } else if (focusedIndex == index) {
                                focusedIndex = -1
                            }
                        }
                        .focusable()
                        // Touch: tap selects, long press pins (OK / double OK on the remote).
                        .onTap(
                            onLongPress = if (canPin && !category.isQuickAccess) {
                                { refocusAfterPinId = category.id; onTogglePin(category.id) }
                            } else null
                        ) {
                            selectedIndex = index
                            onCategorySelected(category.id)
                        },
                    onClick = {
                        selectedIndex = index
                        onCategorySelected(category.id)
                    }
                )

                Spacer(Modifier.height(2.dp))
            }
        }
        if (canPin) {
            Text(
                if (com.btv.ui.theme.LocalIsTv.current) "Double OK : épingler / désépingler"
                else "Appui long : épingler / désépingler",
                color = colors.textMuted,
                style = BtvType.meta.copy(fontSize = BtvType.overline.fontSize),
                modifier = Modifier.padding(top = 10.dp, start = 4.dp)
            )
        }
    }
}

/** Two OK presses this close on the same row pin (or unpin) it. */
private const val DOUBLE_OK_MS = 450L

/**
 * Label opening a new group: the app's own entries (Favoris, Tout afficher,
 * Continuer...), the user's pins, then the provider's categories.
 */
private fun groupLabel(category: BrowseCategory, previous: BrowseCategory?): String? {
    fun group(c: BrowseCategory) = when {
        c.isQuickAccess -> 0
        c.isPinned -> 1
        else -> 2
    }
    val current = group(category)
    if (previous != null && group(previous) == current) return null
    return when (current) {
        0 -> "Accès rapide"
        1 -> "Épinglées"
        else -> "Catégories"
    }
}

@Composable
private fun CategoryItem(
    category: BrowseCategory,
    isSelected: Boolean,
    isFocused: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    // Thin rows: no fill at rest, a short accent bar for the selected
    // category, a lifted fill + accent ring for the one under the remote.
    val colors = BtvTheme.colors
    val textColor by animateColorAsState(
        targetValue = when {
            isFocused || isSelected -> colors.textPrimary
            category.isQuickAccess || category.isPinned -> colors.textSecondary
            else -> colors.textSecondary.copy(alpha = 0.85f)
        },
        animationSpec = tween(BtvMotion.FOCUS_MS),
        label = "categoryText"
    )

    Row(
        modifier = modifier
            .btvSelectionBar(isSelected)
            .btvFocusSurface(isFocused, shape = BtvShapes.control, focusedColor = colors.overlayMedium)
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = com.btv.util.displayCategory(category.name),
                color = textColor,
                style = BtvType.body,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            category.subtitle?.let { subtitle ->
                Text(
                    text = com.btv.util.displayCategory(subtitle),
                    color = colors.textMuted,
                    style = BtvType.meta.copy(fontSize = BtvType.overline.fontSize),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (category.itemCount > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${category.itemCount}",
                color = if (isFocused) colors.textSecondary else colors.textMuted,
                style = BtvType.meta
            )
        }
    }
}
