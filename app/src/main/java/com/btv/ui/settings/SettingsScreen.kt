package com.btv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btv.data.model.AuthSession
import com.btv.data.model.XtreamCategory
import com.btv.data.repository.AuthRepository
import com.btv.data.store.CatalogSection
import com.btv.data.store.PreferencesStore
import com.btv.data.store.SubtitleStyleField
import com.btv.data.store.SubtitleStylePrefs
import com.btv.ui.player.SubtitleStyleOptions
import com.btv.ui.components.btvFocusSurface
import com.btv.ui.components.btvSelectionBar
import com.btv.ui.components.onTap
import com.btv.ui.theme.BtvDimens
import com.btv.ui.theme.BtvShapes
import com.btv.ui.theme.BtvType
import com.btv.ui.theme.BtvTheme
import kotlinx.coroutines.launch

private enum class SettingsPanel(val label: String) {
    SERVER("Serveur"),
    DISPLAY("Affichage"),
    SUBTITLES("Sous-titres"),
    PLAYER("Lecteur"),
    PARENTAL("Contrôle parental"),
    LANGUAGE("Filtrage par langue"),
    CATEGORIES("Catégories masquées")
}

private enum class SettingsZone { NAV, CONTENT }

@Composable
fun SettingsScreen(
    authRepository: AuthRepository,
    session: AuthSession,
    preferencesStore: PreferencesStore,
    onEditServer: () -> Unit = {},
    onLogout: () -> Unit = {},
    // Given by MainActivity: lets the mini-player hand the focus back here.
    returnFocusRequester: FocusRequester? = null,
    miniPlayerFocusRequester: FocusRequester? = null,
    onBack: () -> Unit
) {
    val viewModel: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = SettingsViewModelFactory(authRepository, session, preferencesStore)
    )
    val uiState by viewModel.uiState.collectAsState()
    val disabledPrefixes by viewModel.disabledLanguagePrefixes.collectAsState()
    val hasPin by viewModel.hasPin.collectAsState()
    val revealedAdultIds by viewModel.revealedAdultIds.collectAsState()
    val pinPrompt by viewModel.pinFlow.prompt.collectAsState()

    var zone by remember { mutableStateOf(SettingsZone.NAV) }
    var navIndex by remember { mutableIntStateOf(0) }
    var contentIndex by remember { mutableIntStateOf(0) }
    var categorySection by remember { mutableStateOf(CatalogSection.MOVIES) }
    val subtitleStyle by preferencesStore.subtitleStyle.collectAsState(initial = SubtitleStylePrefs())
    val settingsScope = androidx.compose.runtime.rememberCoroutineScope()
    val themePreference by preferencesStore.theme.collectAsState(initial = "dark")
    val textSizePercent by preferencesStore.textSize.collectAsState(initial = 100)
    val liveBufferSeconds by preferencesStore.liveBufferSeconds.collectAsState(
        initial = PreferencesStore.DEFAULT_LIVE_BUFFER_SECONDS
    )
    fun cyclePlayer() {
        settingsScope.launch { preferencesStore.cycleLiveBufferSeconds() }
    }
    // Tizen toggleAppTheme / cycleSettingsValue('text-size'): applied immediately.
    val accentPreference by preferencesStore.accentColor.collectAsState(initial = "green")
    fun cycleDisplay(row: Int) {
        settingsScope.launch {
            if (row == 0) {
                preferencesStore.setTheme(if (themePreference == "light") "dark" else "light")
            } else if (row == 2) {
                val accents = com.btv.ui.theme.AccentColor.entries
                val current = com.btv.ui.theme.AccentColor.fromKey(accentPreference)
                preferencesStore.setAccentColor(accents[(accents.indexOf(current) + 1) % accents.size].key)
            } else {
                val options = com.btv.ui.theme.TEXT_SIZE_PERCENT_OPTIONS
                val index = options.indexOfFirst { it.first == textSizePercent }.takeIf { it >= 0 } ?: 1
                preferencesStore.setTextSize(options[(index + 1) % options.size].first)
            }
        }
    }
    fun cycleSubtitle(row: Int) {
        val (field, count) = when (row) {
            0 -> SubtitleStyleField.FONT to SubtitleStyleOptions.fonts.size
            1 -> SubtitleStyleField.COLOR to SubtitleStyleOptions.colors.size
            2 -> SubtitleStyleField.BACKGROUND to SubtitleStyleOptions.backgrounds.size
            else -> SubtitleStyleField.SIZE to SubtitleStyleOptions.sizes.size
        }
        settingsScope.launch { preferencesStore.cycleSubtitleStyle(field, count) }
    }
    // Logging out wipes the saved credentials: a second OK confirms it.
    var confirmLogout by remember { mutableStateOf(false) }
    // "Mettre à jour" comes first, only when GitHub has a newer version.
    val context = androidx.compose.ui.platform.LocalContext.current
    val updateStatus by com.btv.data.update.UpdateChecker.status.collectAsState()
    val serverActions = buildList {
        if (updateStatus is com.btv.data.update.UpdateStatus.Available) add(SERVER_ACTION_UPDATE)
        add(SERVER_ACTION_EDIT)
        add(SERVER_ACTION_LOGOUT)
    }
    LaunchedEffect(zone, navIndex, contentIndex) {
        if (zone != SettingsZone.CONTENT || serverActions.getOrNull(contentIndex) != SERVER_ACTION_LOGOUT) confirmLogout = false
    }
    fun runServerAction(index: Int) {
        when (serverActions.getOrNull(index)) {
            SERVER_ACTION_UPDATE -> (updateStatus as? com.btv.data.update.UpdateStatus.Available)?.let {
                com.btv.data.update.AppUpdater.update(context, it.version)
            }
            SERVER_ACTION_EDIT -> onEditServer()
            SERVER_ACTION_LOGOUT -> if (confirmLogout) onLogout() else confirmLogout = true
        }
    }

    val panels = SettingsPanel.entries
    val currentPanel = panels[navIndex]

    val categoriesForSection = uiState.categoriesBySection[categorySection].orEmpty()
    val languageLoadError = uiState.failedSections.isNotEmpty()
    val categoryLoadError = categorySection in uiState.failedSections
    val hiddenIds by viewModel.hiddenIdsFor(categorySection).collectAsState()

    // Touch: tapping a row does what OK does on it.
    fun activate(index: Int) {
        zone = SettingsZone.CONTENT
        contentIndex = index
        when (currentPanel) {
            SettingsPanel.LANGUAGE -> {
                if (languageLoadError && index == 0) {
                    viewModel.loadCategories()
                } else {
                    uiState.availableLanguagePrefixes.getOrNull(index - if (languageLoadError) 1 else 0)?.let {
                        viewModel.toggleLanguagePrefix(it)
                    }
                }
            }
            SettingsPanel.CATEGORIES -> {
                if (index == 0) {
                    categorySection = nextSection(categorySection)
                    contentIndex = 0
                } else if (categoryLoadError && index == 1) {
                    viewModel.loadCategories()
                } else {
                    categoriesForSection.getOrNull(index - 1 - if (categoryLoadError) 1 else 0)?.let {
                        viewModel.toggleCategory(categorySection, it)
                    }
                }
            }
            SettingsPanel.SUBTITLES -> cycleSubtitle(index)
            SettingsPanel.DISPLAY -> cycleDisplay(index)
            SettingsPanel.PLAYER -> cyclePlayer()
            SettingsPanel.PARENTAL -> viewModel.changePin()
            SettingsPanel.SERVER -> runServerAction(index)
        }
    }

    val internalNavFocusRequester = remember { FocusRequester() }
    val navFocusRequester = returnFocusRequester ?: internalNavFocusRequester
    val contentFocusRequester = remember { FocusRequester() }

    // Same retry pattern proven on BrowseScreen: a single requestFocus()
    // attempt can lose the race against layout on slower devices, leaving
    // nothing focused and D-Pad input dead.
    LaunchedEffect(zone, pinPrompt == null) {
        if (pinPrompt != null) return@LaunchedEffect
        val requester = if (zone == SettingsZone.NAV) navFocusRequester else contentFocusRequester
        var attempts = 0
        while (attempts < 20) {
            try {
                requester.requestFocus()
                break
            } catch (e: IllegalStateException) {
                attempts++
                kotlinx.coroutines.delay(50)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BtvTheme.colors.bgBlack)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (keyEvent.key) {
                    Key.Back -> {
                        if (zone == SettingsZone.CONTENT) {
                            zone = SettingsZone.NAV
                        } else {
                            onBack()
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        Row(Modifier.fillMaxSize()) {
            // Sidebar
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(BtvDimens.sidebarWidth)
                    .background(BtvTheme.colors.surface)
                    .padding(horizontal = BtvDimens.sidebarPadding, vertical = 20.dp)
                    .focusRequester(navFocusRequester)
                    // Focus coming back (e.g. from the mini-player) is the menu again.
                    .onFocusChanged { if (it.isFocused) zone = SettingsZone.NAV }
                    .focusable()
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (keyEvent.key) {
                            Key.DirectionUp -> {
                                if (navIndex > 0) { navIndex--; true } else false
                            }
                            Key.DirectionDown -> {
                                when {
                                    navIndex < panels.size - 1 -> { navIndex++; true }
                                    miniPlayerFocusRequester != null -> { miniPlayerFocusRequester.requestFocus(); true }
                                    else -> false
                                }
                            }
                            Key.DirectionRight, Key.DirectionCenter, Key.Enter -> {
                                zone = SettingsZone.CONTENT
                                contentIndex = 0
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                // Touch: the arrow and title go back, like the Back key.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .onTap { onBack() }
                        .padding(start = 6.dp)
                ) {
                    androidx.compose.material3.Icon(
                        painter = androidx.compose.ui.res.painterResource(com.btv.R.drawable.ic_lucide_arrow_left),
                        contentDescription = null,
                        tint = BtvTheme.colors.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Réglages", color = BtvTheme.colors.textPrimary, style = BtvType.section)
                }

                panels.forEachIndexed { index, panel ->
                    val isSelected = index == navIndex
                    val isFocused = zone == SettingsZone.NAV && isSelected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 2.dp)
                            .btvSelectionBar(isSelected)
                            .btvFocusSurface(isFocused, focusedColor = BtvTheme.colors.overlayMedium)
                            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
                            .pointerInput(index) {
                                detectTapGestures {
                                    navIndex = index
                                    zone = SettingsZone.CONTENT
                                    contentIndex = 0
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = panel.label,
                            color = if (isFocused || isSelected) BtvTheme.colors.textPrimary else BtvTheme.colors.textSecondary,
                            style = BtvType.body,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                        // The "Mettre à jour" button waits in Serveur.
                        if (panel == SettingsPanel.SERVER && updateStatus is com.btv.data.update.UpdateStatus.Available) {
                            Spacer(Modifier.weight(1f))
                            Box(
                                Modifier
                                    .size(9.dp)
                                    .background(com.btv.ui.theme.BtvGreenBright, androidx.compose.foundation.shape.CircleShape)
                            )
                        }
                    }
                }
            }

            // Content
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .background(BtvTheme.colors.bgBlack)
                    .padding(horizontal = 40.dp, vertical = 30.dp)
                    .focusRequester(contentFocusRequester)
                    .focusable()
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (keyEvent.key) {
                            Key.DirectionLeft -> {
                                zone = SettingsZone.NAV
                                true
                            }
                            Key.DirectionRight -> {
                                when {
                                    zone != SettingsZone.CONTENT -> false
                                    currentPanel == SettingsPanel.SUBTITLES -> { cycleSubtitle(contentIndex); true }
                                    currentPanel == SettingsPanel.DISPLAY -> { cycleDisplay(contentIndex); true }
                                    currentPanel == SettingsPanel.PLAYER -> { cyclePlayer(); true }
                                    else -> false
                                }
                            }
                            Key.DirectionUp -> {
                                if (zone == SettingsZone.CONTENT && contentIndex > 0) {
                                    contentIndex--
                                    true
                                } else false
                            }
                            Key.DirectionDown -> {
                                if (zone == SettingsZone.CONTENT) {
                                    val maxIndex = when (currentPanel) {
                                        SettingsPanel.LANGUAGE -> uiState.availableLanguagePrefixes.size - 1 + if (languageLoadError) 1 else 0
                                        SettingsPanel.CATEGORIES -> categoriesForSection.size + if (categoryLoadError) 1 else 0 // section row at index 0
                                        SettingsPanel.SERVER -> serverActions.size - 1
                                        SettingsPanel.SUBTITLES -> SUBTITLE_ROWS - 1
                                        SettingsPanel.DISPLAY -> 2
                                        SettingsPanel.PLAYER -> 0
                                        SettingsPanel.PARENTAL -> 0
                                    }
                                    if (contentIndex < maxIndex) {
                                        contentIndex++
                                        true
                                    } else if (miniPlayerFocusRequester != null) {
                                        miniPlayerFocusRequester.requestFocus()
                                        true
                                    } else false
                                } else false
                            }
                            Key.DirectionCenter, Key.Enter -> {
                                if (zone == SettingsZone.CONTENT) {
                                    when (currentPanel) {
                                        SettingsPanel.LANGUAGE -> {
                                            if (languageLoadError && contentIndex == 0) {
                                                viewModel.loadCategories()
                                            } else {
                                                uiState.availableLanguagePrefixes.getOrNull(contentIndex - if (languageLoadError) 1 else 0)?.let {
                                                    viewModel.toggleLanguagePrefix(it)
                                                }
                                            }
                                        }
                                        SettingsPanel.CATEGORIES -> {
                                            if (contentIndex == 0) {
                                                categorySection = nextSection(categorySection)
                                                contentIndex = 0
                                            } else if (categoryLoadError && contentIndex == 1) {
                                                viewModel.loadCategories()
                                            } else {
                                                categoriesForSection.getOrNull(contentIndex - 1 - if (categoryLoadError) 1 else 0)?.let {
                                                    viewModel.toggleCategory(categorySection, it)
                                                }
                                            }
                                        }
                                        SettingsPanel.SUBTITLES -> cycleSubtitle(contentIndex)
                                        SettingsPanel.DISPLAY -> cycleDisplay(contentIndex)
                                        SettingsPanel.PLAYER -> cyclePlayer()
                                        SettingsPanel.PARENTAL -> viewModel.changePin()
                                        SettingsPanel.SERVER -> runServerAction(contentIndex)
                                    }
                                    true
                                } else false
                            }
                            else -> false
                        }
                    }
            ) {
                androidx.compose.runtime.CompositionLocalProvider(LocalSettingsTap provides { index -> activate(index) }) {
                when (currentPanel) {
                    SettingsPanel.SERVER -> ServerPanel(
                        uiState = uiState,
                        actions = serverActions,
                        focusedIndex = if (zone == SettingsZone.CONTENT) contentIndex else -1,
                        confirmLogout = confirmLogout
                    )
                    SettingsPanel.DISPLAY -> DisplayPanel(
                        accent = com.btv.ui.theme.AccentColor.fromKey(accentPreference),
                        isLight = themePreference == "light",
                        textSizePercent = textSizePercent,
                        focusedIndex = if (zone == SettingsZone.CONTENT) contentIndex else -1
                    )
                    SettingsPanel.PARENTAL -> ParentalPanel(
                        hasPin = hasPin,
                        focusedIndex = if (zone == SettingsZone.CONTENT) contentIndex else -1
                    )
                    SettingsPanel.PLAYER -> PlayerPanel(
                        liveBufferSeconds = liveBufferSeconds,
                        focusedIndex = if (zone == SettingsZone.CONTENT) contentIndex else -1
                    )
                    SettingsPanel.SUBTITLES -> SubtitlesPanel(
                        prefs = subtitleStyle,
                        focusedIndex = if (zone == SettingsZone.CONTENT) contentIndex else -1
                    )
                    SettingsPanel.LANGUAGE -> LanguagePanel(
                        prefixes = uiState.availableLanguagePrefixes,
                        disabledPrefixes = disabledPrefixes,
                        focusedIndex = if (zone == SettingsZone.CONTENT) contentIndex else -1,
                        isLoading = uiState.isLoading,
                        hasError = languageLoadError
                    )
                    SettingsPanel.CATEGORIES -> CategoriesPanel(
                        section = categorySection,
                        categories = categoriesForSection,
                        // Reading revealedAdultIds here recomposes the list when one is unlocked.
                        isHidden = { category -> revealedAdultIds.let { viewModel.isCategoryHidden(categorySection, category, hiddenIds) } },
                        isAdult = { category ->
                            categorySection == CatalogSection.LIVE && com.btv.data.store.isAdultCategoryName(category.categoryName)
                        },
                        focusedIndex = if (zone == SettingsZone.CONTENT) contentIndex else -1,
                        isLoading = uiState.isLoading,
                        hasError = categoryLoadError
                    )
                }
                }
            }
        }

        pinPrompt?.let { prompt ->
            com.btv.ui.parental.PinDialog(
                prompt = prompt,
                onSubmit = viewModel.pinFlow::submit,
                onCancel = viewModel.pinFlow::cancel
            )
        }
    }
}

/** Touch: what a tapped content row runs (its index, as OK would). */
private val LocalSettingsTap = androidx.compose.runtime.staticCompositionLocalOf<(Int) -> Unit> { {} }

private fun nextSection(current: CatalogSection): CatalogSection = when (current) {
    CatalogSection.MOVIES -> CatalogSection.SERIES
    CatalogSection.SERIES -> CatalogSection.LIVE
    CatalogSection.LIVE -> CatalogSection.MOVIES
}

private const val SUBTITLE_ROWS = 4

/** Tizen settings-panel-subtitles: four cycling rows and a live preview. */
@Composable
private fun SubtitlesPanel(prefs: SubtitleStylePrefs, focusedIndex: Int) {
    val font = SubtitleStyleOptions.font(prefs)
    val color = SubtitleStyleOptions.color(prefs)
    val background = SubtitleStyleOptions.background(prefs)
    val size = SubtitleStyleOptions.size(prefs)
    Column {
        Text("Sous-titres", color = BtvTheme.colors.textPrimary, style = BtvType.section)
        Spacer(Modifier.height(6.dp))
        Text("OK ou Droite pour changer. S'applique aussi pendant la lecture.", color = BtvTheme.colors.textMuted, style = BtvType.meta)
        Spacer(Modifier.height(16.dp))
        listOf("Police" to font.label, "Couleur" to color.label, "Fond" to background.label, "Taille" to size.label)
            .forEachIndexed { index, (label, value) ->
                CycleRow(label, value, focusedIndex == index, tapIndex = index)
            }
        Spacer(Modifier.height(20.dp))
        // Preview over a mid-grey "video" so every background choice is visible.
        val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(Color(0xFF3A4048), BtvShapes.card),
            contentAlignment = Alignment.BottomCenter
        ) {
            Text(
                text = "Exemple de sous-titre",
                color = Color(color.value),
                fontSize = (screenHeightDp * size.value).sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily(font.value),
                style = if (background.value == 0) androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset(2f, 2f), 4f)
                ) else androidx.compose.ui.text.TextStyle.Default,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .background(Color(background.value))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/** Android-only: the PIN that guards adult Live categories (hidden by default, PIN to show or play). */
@Composable
private fun ParentalPanel(hasPin: Boolean, focusedIndex: Int) {
    Column {
        Text("Contrôle parental", color = BtvTheme.colors.textPrimary, style = BtvType.section)
        Spacer(Modifier.height(6.dp))
        Text(
            "Les catégories adultes du Direct sont masquées par défaut. Le code PIN est demandé pour les " +
                "afficher (Catégories masquées) et pour lancer chacune de leurs chaînes.",
            color = BtvTheme.colors.textMuted,
            style = BtvType.meta
        )
        Spacer(Modifier.height(16.dp))
        CycleRow("Code PIN", if (hasPin) "Défini · OK pour modifier" else "Aucun · OK pour créer", focusedIndex == 0, tapIndex = 0)
    }
}

/** Android-only: how much live stream is held in reserve before a channel starts. */
@Composable
private fun PlayerPanel(liveBufferSeconds: Int, focusedIndex: Int) {
    val value = when (liveBufferSeconds) {
        5 -> "5 s · démarrage rapide"
        30 -> "30 s · le plus stable"
        else -> "$liveBufferSeconds s"
    }
    Column {
        Text("Lecteur", color = BtvTheme.colors.textPrimary, style = BtvType.section)
        Spacer(Modifier.height(6.dp))
        Text(
            "OK ou Droite pour changer. Une réserve plus grande absorbe des coupures réseau plus longues, " +
                "mais une chaîne met plus de temps à démarrer. S'applique à la prochaine chaîne lancée.",
            color = BtvTheme.colors.textMuted,
            style = BtvType.meta
        )
        Spacer(Modifier.height(16.dp))
        CycleRow("Réserve du direct", value, focusedIndex == 0, tapIndex = 0)
    }
}

/** Tizen settings-panel-theme / text-size rows. The player is never affected. */
@Composable
private fun DisplayPanel(accent: com.btv.ui.theme.AccentColor, isLight: Boolean, textSizePercent: Int, focusedIndex: Int) {
    val sizeLabel = com.btv.ui.theme.TEXT_SIZE_PERCENT_OPTIONS.firstOrNull { it.first == textSizePercent }?.second ?: "Normale"
    Column {
        Text("Affichage", color = BtvTheme.colors.textPrimary, style = BtvType.section)
        Spacer(Modifier.height(6.dp))
        Text(
            "OK ou Droite pour changer. La taille agrandit toute l'interface, sauf le lecteur vidéo.",
            color = BtvTheme.colors.textMuted,
            style = BtvType.meta
        )
        Spacer(Modifier.height(16.dp))
        CycleRow("Thème", if (isLight) "Clair" else "Sombre", focusedIndex == 0, tapIndex = 0)
        CycleRow("Taille du texte", sizeLabel, focusedIndex == 1, tapIndex = 1)
        CycleRow("Couleur", accent.label, focusedIndex == 2, tapIndex = 2)
        // Every accent at a glance, the one in use ringed.
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 6.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
        ) {
            com.btv.ui.theme.AccentColor.entries.forEach { option ->
                Box(
                    Modifier
                        .size(22.dp)
                        .border(2.dp, if (option == accent) BtvTheme.colors.textPrimary else Color.Transparent, androidx.compose.foundation.shape.CircleShape)
                        .padding(3.dp)
                        .background(option.main, androidx.compose.foundation.shape.CircleShape)
                )
            }
        }
    }
}

@Composable
private fun CycleRow(label: String, value: String, isFocused: Boolean, tapIndex: Int = -1) {
    val tap = LocalSettingsTap.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onTap { if (tapIndex >= 0) tap(tapIndex) }
            .padding(bottom = BtvDimens.listSpacing)
            .btvFocusSurface(isFocused, shape = BtvShapes.card, restColor = BtvTheme.colors.surface, focusedColor = BtvTheme.colors.surface2)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = BtvTheme.colors.textPrimary, style = BtvType.body, modifier = Modifier.weight(1f))
        Text(
            if (isFocused) "‹  $value  ›" else value,
            color = if (isFocused) BtvTheme.colors.accentOnSurface else BtvTheme.colors.textSecondary,
            style = BtvType.label
        )
    }
}

private const val SERVER_ACTION_UPDATE = 0
private const val SERVER_ACTION_EDIT = 1
private const val SERVER_ACTION_LOGOUT = 2

@Composable
private fun ServerPanel(uiState: SettingsUiState, actions: List<Int>, focusedIndex: Int, confirmLogout: Boolean) {
    Column {
        Text("Serveur", color = BtvTheme.colors.textPrimary, style = BtvType.section)
        Spacer(Modifier.height(16.dp))
        InfoRow("Adresse", uiState.serverUrl)
        InfoRow("Utilisateur", uiState.username)
        InfoRow("Expiration", uiState.expirationDate ?: "—")
        VersionRow()
        Spacer(Modifier.height(16.dp))
        actions.forEachIndexed { index, action ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            when (action) {
                SERVER_ACTION_UPDATE -> UpdateButton(focusedIndex == index, index)
                SERVER_ACTION_EDIT -> RetryRow("Modifier le serveur", focusedIndex == index, tapIndex = index)
                SERVER_ACTION_LOGOUT -> RetryRow(
                    if (confirmLogout) "Confirmer la déconnexion (OK)" else "Se déconnecter",
                    focusedIndex == index,
                    tapIndex = index
                )
            }
        }
        if (confirmLogout) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Les identifiants enregistrés seront effacés. Favoris et progression restent liés à ce compte.",
                color = BtvTheme.colors.textMuted,
                style = BtvType.meta
            )
        }
    }
}

/** Downloads the new version and opens Android's "Update" screen. */
@Composable
private fun UpdateButton(isFocused: Boolean, tapIndex: Int) {
    val state by com.btv.data.update.AppUpdater.state.collectAsState()
    // Back from Android's permission or install screen: the button is usable again.
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) com.btv.data.update.AppUpdater.reset()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val label = when (val s = state) {
        is com.btv.data.update.InstallState.Downloading -> "Téléchargement… ${s.percent} %"
        com.btv.data.update.InstallState.Installing -> "Installation en cours…"
        else -> "Mettre à jour"
    }
    RetryRow(label, isFocused, tapIndex = tapIndex)
    val hint = when (val s = state) {
        com.btv.data.update.InstallState.NeedsPermission ->
            "Autorise bTV à installer des applis (Paramètres Android › Sources inconnues, sur Fire TV : " +
                "Ma Fire TV › Options pour les développeurs › Installer des applis inconnues), puis réappuie."
        is com.btv.data.update.InstallState.Failed -> s.message
        else -> null
    }
    if (hint != null) {
        Spacer(Modifier.height(6.dp))
        Text(hint, color = BtvTheme.colors.textMuted, style = BtvType.meta)
    }
}

/** Installed version, and whether a newer one is on GitHub (Releases). */
@Composable
private fun VersionRow() {
    val status by com.btv.data.update.UpdateChecker.status.collectAsState()
    LaunchedEffect(Unit) { com.btv.data.update.UpdateChecker.check(minIntervalMs = 60_000L) }
    val installed = com.btv.data.update.UpdateChecker.installedVersion
    Row(modifier = Modifier.padding(bottom = 12.dp)) {
        Text("Version", color = BtvTheme.colors.textMuted, style = BtvType.body, modifier = Modifier.width(140.dp))
        Column {
            Text(com.btv.data.update.displayVersion(installed), color = BtvTheme.colors.textPrimary, style = BtvType.body)
            when (val s = status) {
                com.btv.data.update.UpdateStatus.UpToDate ->
                    Text("À jour", color = BtvTheme.colors.accentOnSurface, style = BtvType.meta)
                is com.btv.data.update.UpdateStatus.Available ->
                    Text(
                        "Nouvelle version du ${com.btv.data.update.displayVersion(s.version)} disponible",
                        color = BtvTheme.colors.accentOnSurface,
                        style = BtvType.meta
                    )
                com.btv.data.update.UpdateStatus.Unknown -> Unit
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(label, color = BtvTheme.colors.textMuted, style = BtvType.body, modifier = Modifier.width(140.dp))
        Text(value, color = BtvTheme.colors.textPrimary, style = BtvType.body)
    }
}

@Composable
private fun LanguagePanel(
    prefixes: List<String>,
    disabledPrefixes: Set<String>,
    focusedIndex: Int,
    isLoading: Boolean,
    hasError: Boolean
) {
    Column {
        Text("Filtrage par langue", color = BtvTheme.colors.textPrimary, style = BtvType.section)
        Spacer(Modifier.height(6.dp))
        Text(
            "Décoche une langue pour masquer ses catégories et ses chaînes (films, séries, direct, rediffusion).",
            color = BtvTheme.colors.textMuted,
            style = BtvType.meta
        )
        Spacer(Modifier.height(16.dp))
        if (!isLoading && hasError) {
            RetryRow("Certaines langues indisponibles — Réessayer", focusedIndex == 0, tapIndex = 0)
            Spacer(Modifier.height(8.dp))
        }
        when {
            isLoading -> Text("Chargement...", color = BtvTheme.colors.textMuted, style = BtvType.meta)
            prefixes.isEmpty() && !hasError -> Text("Aucun préfixe de langue détecté.", color = BtvTheme.colors.textMuted, style = BtvType.meta)
            else -> {
                val lazyListState = rememberLazyListState()
                // No per-item FocusRequester here (focus lives on the parent
                // content Box; this list is just a `focusedIndex`-driven
                // highlight) - the list never scrolled itself, so past the
                // first screenful, "focused" rows keyed past the visible
                // window and appeared to do nothing.
                LaunchedEffect(focusedIndex) {
                    val listIndex = focusedIndex - if (hasError) 1 else 0
                    if (listIndex >= 0 && prefixes.isNotEmpty()) {
                        try {
                            lazyListState.animateScrollToItem(maxOf(0, listIndex - 2))
                        } catch (e: IllegalStateException) {
                        }
                    }
                }
                LazyColumn(state = lazyListState) {
                    items(prefixes) { prefix ->
                        val index = prefixes.indexOf(prefix)
                        val isHidden = prefix in disabledPrefixes
                        ToggleRow(
                            label = prefix,
                            checked = !isHidden,
                            isFocused = index + (if (hasError) 1 else 0) == focusedIndex,
                            tapIndex = index + (if (hasError) 1 else 0)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoriesPanel(
    section: CatalogSection,
    categories: List<XtreamCategory>,
    isHidden: (XtreamCategory) -> Boolean,
    isAdult: (XtreamCategory) -> Boolean,
    focusedIndex: Int,
    isLoading: Boolean,
    hasError: Boolean
) {
    val sectionLabel = when (section) {
        CatalogSection.MOVIES -> "Films"
        CatalogSection.SERIES -> "Séries"
        CatalogSection.LIVE -> "Direct et rediffusion"
    }
    Column {
        Text("Catégories masquées", color = BtvTheme.colors.textPrimary, style = BtvType.section)
        Spacer(Modifier.height(16.dp))
        val tap = LocalSettingsTap.current
        Row(
            modifier = Modifier
                .onTap { tap(0) }
                .btvFocusSurface(focusedIndex == 0, shape = BtvShapes.card, restColor = BtvTheme.colors.surface, focusedColor = BtvTheme.colors.surface2)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text("Section", color = BtvTheme.colors.textSecondary, style = BtvType.body)
            Spacer(Modifier.width(12.dp))
            Text(sectionLabel, color = BtvTheme.colors.accentOnSurface, style = BtvType.label)
        }
        Spacer(Modifier.height(12.dp))
        if (!isLoading && hasError) {
            RetryRow("Chargement impossible — Réessayer", focusedIndex == 1, tapIndex = 1)
            Spacer(Modifier.height(8.dp))
        }
        when {
            isLoading -> Text("Chargement...", color = BtvTheme.colors.textMuted, style = BtvType.meta)
            categories.isEmpty() && !hasError -> Text("Aucune catégorie.", color = BtvTheme.colors.textMuted, style = BtvType.meta)
            else -> {
                val lazyListState = rememberLazyListState()
                // focusedIndex 0 is the "Section" row above this list, so
                // this list's own index is offset by -1.
                LaunchedEffect(focusedIndex) {
                    val listIndex = focusedIndex - 1 - if (hasError) 1 else 0
                    if (listIndex >= 0 && categories.isNotEmpty()) {
                        try {
                            lazyListState.animateScrollToItem(maxOf(0, listIndex - 2))
                        } catch (e: IllegalStateException) {
                        }
                    }
                }
                LazyColumn(state = lazyListState) {
                    items(categories) { cat ->
                        val index = categories.indexOf(cat)
                        ToggleRow(
                            label = if (isAdult(cat)) "🔒 ${cat.categoryName}" else cat.categoryName,
                            checked = !isHidden(cat),
                            isFocused = (index + 1 + if (hasError) 1 else 0) == focusedIndex,
                            tapIndex = (index + 1 + if (hasError) 1 else 0)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RetryRow(label: String, isFocused: Boolean, tapIndex: Int = -1) {
    val tap = LocalSettingsTap.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onTap { if (tapIndex >= 0) tap(tapIndex) }
            .btvFocusSurface(isFocused, shape = BtvShapes.card, restColor = BtvTheme.colors.surface, focusedColor = BtvTheme.colors.surface2)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(label, color = if (isFocused) BtvTheme.colors.textPrimary else BtvTheme.colors.textSecondary, style = BtvType.label)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, isFocused: Boolean, tapIndex: Int = -1) {
    val tap = LocalSettingsTap.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onTap { if (tapIndex >= 0) tap(tapIndex) }
            .padding(bottom = 2.dp)
            .btvFocusSurface(isFocused, focusedColor = BtvTheme.colors.surface2)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Checked/unchecked look depends ONLY on `checked`, never on focus -
        // otherwise a focused+unchecked box (black border, transparent fill)
        // sits on the same highlight as a checked box and becomes visually
        // indistinguishable from "checked" on a real TV screen.
        Box(
            modifier = Modifier
                .width(20.dp)
                .height(20.dp)
                .border(2.dp, if (checked) BtvTheme.colors.focusRing else BtvTheme.colors.textMuted, BtvShapes.small)
                .background(if (checked) BtvTheme.colors.focusRing else Color.Transparent, BtvShapes.small),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(com.btv.R.drawable.ic_lucide_check),
                    contentDescription = null,
                    tint = BtvTheme.colors.onAccent,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            color = if (isFocused || checked) BtvTheme.colors.textPrimary else BtvTheme.colors.textMuted,
            style = BtvType.body
        )
    }
}
