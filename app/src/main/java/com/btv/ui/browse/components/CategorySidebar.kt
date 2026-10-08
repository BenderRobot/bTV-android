package com.btv.ui.browse.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btv.ui.browse.BrowseCategory
import com.btv.ui.theme.BtvGreen
import com.btv.ui.theme.BtvTheme

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
    onTogglePin: (String) -> Unit = {}
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
    var longPressHandled by remember { mutableStateOf(false) }
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
            .background(colors.surface)
            .padding(16.dp)
    ) {
        // Header: back arrow + title
        var backFocused by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .focusable()
                .onFocusChanged { backFocused = it.isFocused }
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
                .then(
                    if (backFocused) Modifier.border(2.dp, BtvGreen, RoundedCornerShape(8.dp)).padding(4.dp)
                    else Modifier.padding(4.dp)
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "←",
                color = colors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 10.dp)
            )
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Search bar
        CompactSearchField(
            value = categorySearch,
            onValueChange = onSearchChanged,
            placeholder = "Rechercher une catégorie...",
            focusRequester = searchFocusRequester,
            modifier = Modifier.fillMaxWidth(),
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

        Spacer(Modifier.height(6.dp))

        // Categories list
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            itemsIndexed(filteredCategories) { index, category ->
                val isSelected = category.id == selectedCategoryId
                val isCategoryFocused = focusedIndex == index

                CategoryItem(
                    category = category,
                    isSelected = isSelected,
                    isFocused = isCategoryFocused,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(categoryFocusRequesters[index])
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.key) {
                                    Key.DirectionDown -> {
                                        if (selectedIndex < filteredCategories.size - 1) {
                                            selectedIndex++
                                            focusIndex(selectedIndex)
                                            onCategorySelected(filteredCategories[selectedIndex].id)
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

                                    Key.DirectionCenter, Key.Enter -> {
                                        // Held OK repeats KeyDown: the first repeat pins, once.
                                        if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                                            longPressHandled = false
                                            onCategorySelected(category.id)
                                        } else if (canPin && !longPressHandled && !category.isQuickAccess) {
                                            longPressHandled = true
                                            refocusAfterPinId = category.id
                                            onTogglePin(category.id)
                                        }
                                        true
                                    }

                                    Key.Menu -> {
                                        if (canPin && !category.isQuickAccess) {
                                            refocusAfterPinId = category.id
                                            onTogglePin(category.id)
                                        }
                                        true
                                    }

                                    else -> false
                                }
                            } else {
                                false
                            }
                        }
                        .focusable()
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                focusedIndex = index
                                if (!isSelected) {
                                    selectedIndex = index
                                    onCategorySelected(category.id)
                                }
                            } else if (focusedIndex == index) {
                                focusedIndex = -1
                            }
                        },
                    onClick = {
                        selectedIndex = index
                        onCategorySelected(category.id)
                    }
                )

                Spacer(Modifier.height(1.dp))

                // Divider after the top block (quick access + pinned)
                val next = filteredCategories.getOrNull(index + 1)
                val isLastQuickAccess = (category.isQuickAccess || category.isPinned) &&
                    next?.isQuickAccess != true && next?.isPinned != true
                if (isLastQuickAccess) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.border)
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
        if (canPin) {
            Text(
                "☰ ou OK maintenu : épingler en haut",
                color = colors.textMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
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
    // Port of Tizen's .browse-cat-item.focused (style.css): a subtle
    // translucent white wash + a green border, NOT a solid accent fill -
    // the focused row still reads as a dark sidebar row, just picked out,
    // matching how every other focused row in the app (search field,
    // settings nav) is styled.
    val colors = BtvTheme.colors
    val backgroundColor by animateColorAsState(
        targetValue = if (isFocused) colors.overlaySoft else Color.Transparent,
        animationSpec = tween(150)
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> BtvGreen
            isSelected -> BtvGreen.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        animationSpec = tween(150)
    )

    val textColor by animateColorAsState(
        targetValue = when {
            isFocused || isSelected -> colors.textPrimary
            category.isQuickAccess || category.isPinned -> colors.textSecondary
            else -> colors.textMuted
        },
        animationSpec = tween(150)
    )

    Row(
        modifier = modifier
            .background(color = backgroundColor, shape = RoundedCornerShape(8.dp))
            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (category.isPinned) "📌 ${category.name}" else category.name,
                color = textColor,
                fontSize = 15.sp,
                fontWeight = if (category.isQuickAccess || category.isPinned) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            category.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    color = textColor.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (category.itemCount > 0) {
            Text(
                text = "${category.itemCount}",
                color = textColor.copy(alpha = 0.7f),
                fontSize = 13.sp
            )
        }
    }
}
