package com.btv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.btv.data.cache.CatalogCache
import com.btv.data.cache.preloadCatalogCategories
import com.btv.data.db.BtvDatabase
import com.btv.data.db.AccountScope
import com.btv.data.repository.NewEpisodesRepository
import com.btv.ui.player.applyStylePrefs
import com.btv.data.model.AuthSession
import com.btv.data.repository.AuthRepository
import com.btv.data.repository.FavoritesRepository
import com.btv.data.repository.HistoryRepository
import com.btv.data.repository.PlaybackProgressRepository
import com.btv.data.store.CredentialsStore
import com.btv.data.store.PreferencesStore
import com.btv.domain.usecase.GetFavoritesUseCase
import com.btv.domain.usecase.GetPlaybackProgressUseCase
import com.btv.domain.usecase.GetRecentlyWatchedUseCase
import com.btv.domain.usecase.ToggleFavoriteUseCase
import com.btv.ui.HomeViewModel
import com.btv.ui.HomeViewModelFactory
import com.btv.ui.LoginScreen
import com.btv.ui.SplashScreen
import com.btv.ui.browse.BrowseRoute
import com.btv.ui.browse.ContentType
import com.btv.ui.browse.ShowAllSnapshotStore
import com.btv.ui.player.PlayerLaunchRequest
import com.btv.ui.player.PlayerScreen
import com.btv.ui.player.PlayerViewModel
import com.btv.ui.player.PlayerViewModelFactory
import com.btv.ui.settings.SettingsScreen
import com.btv.ui.theme.BtvTheme
import com.btv.ui.components.btvFocusScale
import com.btv.ui.components.onTap
import java.io.File

class MainActivity : ComponentActivity() {
    internal var activePlayerViewModel: PlayerViewModel? = null

    // Lint false positive: this overrides the public Activity method, not the
    // androidx.core-restricted ComponentActivity one.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (activePlayerViewModel?.onMediaKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val credentialsStore = CredentialsStore(this)
        val authRepository = AuthRepository(credentialsStore)
        val preferencesStore = PreferencesStore(this)

        // Manually wired (matching authRepository/preferencesStore above)
        // rather than through the existing Hilt UseCaseModule: BrowseViewModel
        // is built via a plain ViewModelProvider.Factory (needs the runtime
        // `session`, which isn't a Hilt-injectable singleton), not
        // `hiltViewModel()`, so Hilt's graph never reached it. Without this,
        // these use cases were always null - Favoris/Historique/Continuer à
        // regarder silently never read or wrote anything, and the Room
        // database was never even created on disk.
        val database = BtvDatabase.getInstance(this)
        val accountScope = AccountScope.global
        if (savedInstanceState == null) {
            accountScope.clear()
            CatalogCache.clear()
        }
        val favoritesRepository = FavoritesRepository(database.favoritesDao(), accountScope)
        val historyRepository = HistoryRepository(database.historyDao(), accountScope)
        val playbackProgressRepository = PlaybackProgressRepository(database.playbackProgressDao(), accountScope)
        val getFavoritesUseCase = GetFavoritesUseCase(favoritesRepository)
        val toggleFavoriteUseCase = ToggleFavoriteUseCase(favoritesRepository)
        val getRecentlyWatchedUseCase = GetRecentlyWatchedUseCase(historyRepository)
        val getPlaybackProgressUseCase = GetPlaybackProgressUseCase(playbackProgressRepository)

        // A TV / TV box (remote) or a phone / tablet (touch).
        val isTv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            getSystemService(android.app.UiModeManager::class.java)?.currentModeType ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        setContent {
            BtvApp(
                authRepository, credentialsStore, database, accountScope, preferencesStore,
                getFavoritesUseCase, toggleFavoriteUseCase, getPlaybackProgressUseCase, getRecentlyWatchedUseCase,
                isTv
            )
        }
    }
}

@Composable
private fun BtvApp(
    authRepository: AuthRepository,
    credentialsStore: CredentialsStore,
    database: BtvDatabase,
    accountScope: AccountScope,
    preferencesStore: PreferencesStore,
    getFavoritesUseCase: GetFavoritesUseCase,
    toggleFavoriteUseCase: ToggleFavoriteUseCase,
    getPlaybackProgressUseCase: GetPlaybackProgressUseCase,
    getRecentlyWatchedUseCase: GetRecentlyWatchedUseCase,
    isTv: Boolean
) {
    // Réglages → Affichage (Tizen iptv_theme / iptv_text_size): dark unless "light".
    val themePreference by preferencesStore.theme.collectAsState(initial = "dark")
    val textSizePercent by preferencesStore.textSize.collectAsState(initial = 100)
    val accentPreference by preferencesStore.accentColor.collectAsState(initial = "green")
    BtvTheme(
        darkTheme = themePreference != "light",
        textScale = textSizePercent / 100f,
        accent = com.btv.ui.theme.AccentColor.fromKey(accentPreference),
        isTv = isTv
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            // Deliberately not restored after process death: a back stack
            // revived without its session lands on a screen with nothing to
            // show (player without media, Browse without account). Every
            // fresh process starts over from the account check.
            val navLocalContext = androidx.compose.ui.platform.LocalContext.current
            val navController = remember {
                androidx.navigation.NavHostController(navLocalContext).apply {
                    navigatorProvider.addNavigator(androidx.navigation.compose.ComposeNavigator())
                    navigatorProvider.addNavigator(androidx.navigation.compose.DialogNavigator())
                }
            }
            val currentBackStackEntry by navController.currentBackStackEntryAsState()
            // Phone: every screen has an upright layout and follows the
            // phone's rotation (rotation lock respected). The player handles
            // its own (its "Plein écran" switch can hold landscape); leaving
            // it, the next screen gets the free rotation back. A TV is never
            // touched.
            val currentRoute = currentBackStackEntry?.destination?.route
            val orientationActivity = LocalContext.current as? android.app.Activity
            LaunchedEffect(currentRoute, isTv) {
                if (isTv || currentRoute == "player") return@LaunchedEffect
                orientationActivity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }
            val miniPlayerFocusRequester = remember { FocusRequester() }
            val browseContentFocusRequester = remember { FocusRequester() }
            val settingsFocusRequester = remember { FocusRequester() }
            val homeMenuFocusRequester = remember { FocusRequester() }
            var isLoggedIn by remember { mutableStateOf(false) }
            var isChecking by remember { mutableStateOf(true) }
            var session by remember { mutableStateOf<AuthSession?>(null) }
            // Set by "Modifier le serveur": the login screen is prefilled and
            // can be cancelled while the current session stays active.
            var loginPrefill by remember { mutableStateOf<AuthSession?>(null) }
            // Startup outcome when the saved account couldn't be opened.
            var startupUnreachable by remember { mutableStateOf<AuthRepository.AutoLoginOutcome.Unreachable?>(null) }
            var loginError by remember { mutableStateOf<String?>(null) }
            var startupAttempt by remember { mutableStateOf(0) }
            val appScope = androidx.compose.runtime.rememberCoroutineScope()
            val homeViewModel: HomeViewModel = viewModel(factory = HomeViewModelFactory(authRepository))
            val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
            val newEpisodesRepository = remember(database, accountScope) {
                NewEpisodesRepository(database.seriesEpisodeSnapshotDao(), database.favoritesDao(), accountScope)
            }

            // Activity-scoped (not route-scoped): the same ExoPlayer/
            // PlayerViewModel instance must survive navigating back to
            // Browse for the mini-player to keep playing underneath,
            // mirroring Tizen's reused <video>/AVPlay element rather than
            // tearing it down on every exit (see PlayerViewModel's doc
            // comment).
            val activity = LocalContext.current as MainActivity
            val playerViewModel: PlayerViewModel = viewModel(
                viewModelStoreOwner = activity,
                factory = PlayerViewModelFactory(
                    activity.applicationContext,
                    getPlaybackProgressUseCase,
                    getRecentlyWatchedUseCase,
                    trackPreferenceRepository = com.btv.data.repository.TrackPreferenceRepository(
                        database.trackPreferenceDao(), accountScope
                    )
                )
            )
            SideEffect {
                playerViewModel.setSeriesEpisodeLoader { seriesId ->
                    session?.let { com.btv.ui.player.loadSeriesEpisodesBySeason(authRepository, it, seriesId) }
                }
                playerViewModel.setLiveEpgLoader { channelId ->
                    session?.let { com.btv.ui.player.loadLiveEpg(authRepository, it, channelId) }
                }
                playerViewModel.setLiveVariantLoader { selected, categoryId ->
                    session?.let { com.btv.ui.player.loadLiveVariantCandidates(authRepository, it, selected, categoryId) }
                }
                playerViewModel.setContentInfoLoader { type, streamId, seriesId, title ->
                    session?.let { com.btv.ui.player.loadPlayerInfo(authRepository, it, type, streamId, seriesId, title) }
                }
            }
            LaunchedEffect(playerViewModel) {
                playerViewModel.bindLivePreferences(
                    preferencesStore.liveQualityChoices,
                    preferencesStore.liveBufferSeconds,
                    preferencesStore::rememberLiveQuality
                )
            }
            DisposableEffect(activity, playerViewModel) {
                activity.activePlayerViewModel = playerViewModel
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            playerViewModel.onAppForegrounded()
                            com.btv.data.sync.SyncManager.requestSync()
                        }
                        Lifecycle.Event.ON_STOP -> if (!activity.isChangingConfigurations) {
                            playerViewModel.onAppBackgrounded()
                            // Hand the latest position to the other devices before leaving.
                            com.btv.data.sync.SyncManager.requestSync()
                        }
                        else -> Unit
                    }
                }
                activity.lifecycle.addObserver(observer)
                onDispose {
                    activity.lifecycle.removeObserver(observer)
                    activity.activePlayerViewModel = null
                }
            }
            val playingFlow = remember(playerViewModel) {
                playerViewModel.uiState.map { it.isPlaying }.distinctUntilChanged()
            }
            val isPlaying by playingFlow.collectAsState(initial = false)
            val miniPlayerFlow = remember(playerViewModel) {
                playerViewModel.uiState.map { it.isMiniPlayer }.distinctUntilChanged()
            }
            val initialMiniPlayer = remember(playerViewModel) { playerViewModel.uiState.value.isMiniPlayer }
            val isMiniPlayerActive by miniPlayerFlow.collectAsState(initial = initialMiniPlayer)
            DisposableEffect(activity, isPlaying) {
                if (isPlaying) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            var pendingPlayerLaunch by remember { mutableStateOf<PlayerLaunchRequest?>(null) }

            LaunchedEffect(startupAttempt) {
                if (startupAttempt == 0) {
                // Housekeeping only: a database that can't be opened or written
                // must not crash every launch before the user even sees a screen.
                com.btv.util.guarded("BtvStartup", "Legacy password scrub") { database.scrubLegacyRoomPasswords() }
                // v4 rows have no account identity. Bind them only to the
                // credentials already on this device before any new login.
                runCatching { credentialsStore.load() }.getOrNull()?.let { old ->
                    com.btv.util.guarded("BtvStartup", "Legacy rows claim") {
                        database.claimLegacyRows(AccountScope.keyFor(old.serverUrl, old.username))
                    }
                }
                }
                isChecking = true
                startupUnreachable = null
                when (val outcome = authRepository.autoLogin { com.btv.ui.hasNetwork(appContext) }) {
                    is AuthRepository.AutoLoginOutcome.Success -> {
                        session = outcome.session
                        accountScope.activate(outcome.session)
                        isLoggedIn = true
                    }
                    // Refused by the server: back to the form, already filled in, with the reason.
                    is AuthRepository.AutoLoginOutcome.Rejected -> {
                        accountScope.clear()
                        loginPrefill = outcome.saved
                        loginError = outcome.message
                    }
                    // No answer: the account is probably fine - offer a retry instead of the form.
                    is AuthRepository.AutoLoginOutcome.Unreachable -> {
                        accountScope.clear()
                        startupUnreachable = outcome
                    }
                    AuthRepository.AutoLoginOutcome.NoSavedAccount -> accountScope.clear()
                }
                isChecking = false
            }

            LaunchedEffect(session) {
                // Progress, history, favourites and track choices shared between devices.
                session?.let { com.btv.data.sync.SyncManager.start(appContext, it) } ?: com.btv.data.sync.SyncManager.stop()
                session?.let { s ->
                    // Run in parallel, not sequentially: both should get the
                    // whole splash window, same as Tizen's video-timer +
                    // category-preload racing each other (js/player.js
                    // showSplashAndPreload) rather than one blocking the other.
                    launch { homeViewModel.load(s) }
                    launch { preloadCatalogCategories(authRepository, s) }
                    launch { notifyNewFavoriteEpisodes(appContext, authRepository, s, newEpisodesRepository) }
                    launch {
                        val update = com.btv.data.update.UpdateChecker.check()
                        if (update is com.btv.data.update.UpdateStatus.Available) {
                            android.widget.Toast.makeText(
                                activity,
                                "Nouvelle version de bTV disponible (" + com.btv.data.update.displayVersion(update.version) + ")",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }

            val unreachable = startupUnreachable
            if (isChecking && !isLoggedIn) {
                com.btv.ui.StartupConnectingScreen()
            } else if (unreachable != null && !isLoggedIn) {
                com.btv.ui.StartupUnreachableScreen(
                    noNetwork = unreachable.noNetwork,
                    canEditAccount = unreachable.saved != null,
                    onRetry = { startupAttempt++ },
                    onEditAccount = {
                        loginPrefill = unreachable.saved
                        startupUnreachable = null
                    }
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = if (isLoggedIn) "splash" else "login"
                ) {
                composable("home") {
                    var showExitDialog by remember { mutableStateOf(false) }
                    // Back on the home screen (often from the player): share the new position.
                    LaunchedEffect(Unit) { com.btv.data.sync.SyncManager.requestSync() }
                    // "Continuer à regarder": started films/episodes/replays and recent channels.
                    val continueItems by remember(session) {
                        session?.let { s ->
                            com.btv.ui.home.continueWatchingFlow(
                                s, authRepository, preferencesStore, getPlaybackProgressUseCase, getRecentlyWatchedUseCase
                            )
                        } ?: kotlinx.coroutines.flow.flowOf(emptyList())
                    }.collectAsState(initial = emptyList())
                    androidx.activity.compose.BackHandler(enabled = !showExitDialog) { showExitDialog = true }
                    HomeRoute(
                        viewModel = homeViewModel,
                        session = session,
                        menuFocusRequester = homeMenuFocusRequester,
                        miniPlayerFocusRequester = miniPlayerFocusRequester.takeIf { isMiniPlayerActive },
                        onOpenBrowse = { type -> navController.navigate("browsepremium/$type") },
                        onOpenSettings = { navController.navigate("settings") },
                        continueItems = continueItems,
                        onPlayContinue = { item ->
                            appScope.launch {
                                val activeSession = session ?: return@launch
                                // Same parental check as Browse before a channel from history.
                                if (item.channelId != null &&
                                    com.btv.ui.home.isAdultChannel(item, activeSession, authRepository)
                                ) {
                                    android.widget.Toast.makeText(
                                        activity,
                                        "Chaîne protégée : ouvrez-la depuis En direct avec le code PIN.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                    return@launch
                                }
                                pendingPlayerLaunch = item.request
                                playerViewModel.setMiniPlayerActive(false)
                                navController.navigate("player")
                            }
                        },
                        onRefresh = {
                            // Mirrors Tizen's refreshPlaylistData (app-shell.js):
                            // wipe every cache and let the next screen re-fetch,
                            // without leaving the home screen.
                            CatalogCache.clear()
                            session?.let {
                                ShowAllSnapshotStore(File(activity.noBackupFilesDir, "show_all_index")).invalidate(it)
                                homeViewModel.load(it)
                            }
                        }
                    )
                    if (showExitDialog) {
                        com.btv.ui.ExitAppDialog(
                            onCancel = {
                                showExitDialog = false
                                runCatching { homeMenuFocusRequester.requestFocus() }
                            },
                            onConfirm = { activity.finish() }
                        )
                    }
                }
                composable("browsepremium/{type}") { backStackEntry ->
                    val typeStr = backStackEntry.arguments?.getString("type") ?: "live"
                    val contentType = when (typeStr) {
                        "favorites" -> ContentType.FAVORITES
                        "live" -> ContentType.LIVE
                        "movies" -> ContentType.VOD
                        "series" -> ContentType.SERIES
                        "replay" -> ContentType.REPLAY
                        else -> ContentType.VOD
                    }
                    BrowseRoute(
                        contentType = contentType,
                        authRepository = authRepository,
                        session = session,
                        preferencesStore = preferencesStore,
                        getFavoritesUseCase = getFavoritesUseCase,
                        toggleFavoriteUseCase = toggleFavoriteUseCase,
                        getPlaybackProgressUseCase = getPlaybackProgressUseCase,
                        getRecentlyWatchedUseCase = getRecentlyWatchedUseCase,
                        contentFocusRequester = browseContentFocusRequester,
                        miniPlayerFocusRequester = miniPlayerFocusRequester.takeIf { isMiniPlayerActive },
                        onOpenPlayer = { request ->
                            pendingPlayerLaunch = request
                            playerViewModel.setMiniPlayerActive(false)
                            navController.navigate("player")
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("settings") {
                    session?.let { activeSession ->
                        SettingsScreen(
                            authRepository = authRepository,
                            session = activeSession,
                            preferencesStore = preferencesStore,
                            onEditServer = {
                                loginPrefill = activeSession
                                navController.navigate("login")
                            },
                            onLogout = {
                                // Tizen logout(): forget the credentials and start
                                // over. Room data stays keyed to this account and
                                // comes back if the same account logs in again.
                                appScope.launch {
                                    playerViewModel.setMiniPlayerActive(false)
                                    playerViewModel.stopAndExit()?.join()
                                    authRepository.logout()
                                    CatalogCache.clear()
                                    accountScope.clear()
                                    loginPrefill = null
                                    session = null
                                    isLoggedIn = false
                                    // Clearing the back stack also clears every
                                    // route-scoped Browse/Settings ViewModel and its jobs.
                                    navController.navigate("login") {
                                        popUpTo(navController.graph.id) { inclusive = true }
                                    }
                                }
                            },
                            returnFocusRequester = settingsFocusRequester,
                            miniPlayerFocusRequester = miniPlayerFocusRequester.takeIf { isMiniPlayerActive },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
                composable("browse/{type}") { backStackEntry ->
                    BrowseRoute(
                        viewModel = homeViewModel,
                        initialType = backStackEntry.arguments?.getString("type") ?: "live",
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("login") {
                    val prefill = loginPrefill
                    LoginScreen(
                        initialError = loginError,
                        onLoginSuccess = { newSession ->
                            appScope.launch {
                                // The previous media's last progress write must
                                // land in the previous account before switching.
                                playerViewModel.setMiniPlayerActive(false)
                                playerViewModel.stopAndExit()?.join()
                                CatalogCache.clear()
                                accountScope.activate(newSession)
                                loginPrefill = null
                                loginError = null
                                session = newSession
                                isLoggedIn = true
                                navController.navigate("splash") {
                                    popUpTo(navController.graph.id) { inclusive = true }
                                }
                            }
                        },
                        repository = authRepository,
                        prefill = prefill,
                        onCancel = if (prefill != null && session != null) {
                            {
                                loginPrefill = null
                                navController.popBackStack()
                            }
                        } else null
                    )
                }
                composable("player") {
                    com.btv.ui.theme.PlayerSurfaceTheme {
                    PlayerScreen(
                        viewModel = playerViewModel,
                        onLoadInitial = {
                            pendingPlayerLaunch?.let { request ->
                                playerViewModel.loadStreamWithResumeCheck(
                                    streamUrl = request.streamUrl,
                                    contentId = request.contentId,
                                    progressType = request.progressType,
                                    contentName = request.contentName,
                                    zapList = request.zapList,
                                    posterUrl = request.posterUrl,
                                    categoryId = request.categoryId,
                                    categoryName = request.categoryName,
                                    seriesId = request.seriesId,
                                    seriesName = request.seriesName,
                                    seasonNum = request.seasonNum,
                                    liveVariants = request.liveVariants,
                                    applyRememberedQuality = !request.explicitQuality
                                )
                                pendingPlayerLaunch = null
                            }
                        },
                        onExit = { navController.popBackStack() },
                        onMiniPlayer = {
                            playerViewModel.setMiniPlayerActive(true)
                            navController.popBackStack()
                        }
                    )
                    }
                }
                composable("splash") {
                    SplashScreen(
                        onFinished = {
                            navController.navigate("home") {
                                popUpTo("splash") { inclusive = true }
                            }
                        }
                    )
                }
                }

                if (isMiniPlayerActive && currentBackStackEntry?.destination?.route != "player") {
                    MiniPlayerOverlay(
                        viewModel = playerViewModel,
                        modifier = Modifier.align(Alignment.BottomEnd),
                        focusRequester = miniPlayerFocusRequester,
                        onLeaveFocus = {
                            runCatching {
                                when (currentBackStackEntry?.destination?.route) {
                                    "home" -> homeMenuFocusRequester.requestFocus()
                                    "browsepremium/{type}" -> browseContentFocusRequester.requestFocus()
                                    "settings" -> settingsFocusRequester.requestFocus()
                                }
                            }
                        },
                        onExpand = {
                            playerViewModel.setMiniPlayerActive(false)
                            navController.navigate("player")
                        },
                        onClose = {
                            playerViewModel.setMiniPlayerActive(false)
                            playerViewModel.stopAndExit()
                        }
                    )
                }
                }
            }
        }
    }
}

/**
 * Port of Tizen's .mini-player (css/style.css): fixed bottom-right box,
 * always drawn above whatever's underneath, focusable to expand back to
 * fullscreen or close outright - same as Tizen's mini-player-focused key
 * dispatch (js/input.js handleMiniPlayerKey).
 */
@Composable
private fun MiniPlayerOverlay(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester,
    onLeaveFocus: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit
) {
    // Like Tizen's mini-player (outside #app-scale-root): untouched by the
    // text-size zoom and the light theme.
    com.btv.ui.theme.PlayerSurfaceTheme {
        MiniPlayerOverlayContent(viewModel, modifier, focusRequester, onLeaveFocus, onExpand, onClose)
    }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun MiniPlayerOverlayContent(
    viewModel: PlayerViewModel,
    modifier: Modifier,
    focusRequester: FocusRequester,
    onLeaveFocus: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var isFocused by remember { mutableStateOf(false) }
    val colors = com.btv.ui.theme.BtvTheme.colors
    val shape = com.btv.ui.theme.BtvShapes.panel

    // Upright phone: a smaller window, it would hide half the screen otherwise.
    val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_PORTRAIT
    Box(
        modifier = modifier
            .padding(if (portrait) 14.dp else 28.dp)
            .btvFocusScale(isFocused, com.btv.ui.theme.BtvMotion.FOCUS_SCALE_SMALL)
            .width(if (portrait) 192.dp else com.btv.ui.theme.BtvDimens.miniPlayerWidth)
            .height(if (portrait) 108.dp else com.btv.ui.theme.BtvDimens.miniPlayerHeight)
            .shadow(if (isFocused) 18.dp else 10.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Color.Black, shape)
            .border(
                if (isFocused) com.btv.ui.theme.BtvDimens.focusBorder else com.btv.ui.theme.BtvDimens.hairline,
                if (isFocused) colors.focusRing else Color.White.copy(alpha = 0.12f),
                shape
            )
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onTap { onExpand() }
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (keyEvent.key) {
                    Key.DirectionCenter, Key.Enter -> { onExpand(); true }
                    Key.Back -> { onClose(); onLeaveFocus(); true }
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                        onLeaveFocus()
                        true
                    }
                    else -> false
                }
            }
    ) {
        if (viewModel.player != null) {
            val subtitleStyle by com.btv.ui.player.rememberSubtitleStylePrefs()
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        player = viewModel.player
                        useController = false
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                },
                update = { view -> view.subtitleView?.applyStylePrefs(subtitleStyle) },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize()
            )
        }
        // Title over a soft scrim (not a solid bar), and the two remote
        // actions spelled out while the mini-player has the focus.
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                    ),
                    androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                )
                .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 9.dp)
        ) {
            Text(
                com.btv.util.displayTitle(uiState.contentName),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (isFocused) {
                Text(
                    "OK  agrandir   ·   Retour  fermer",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Port of Tizen's notifyNewFavoriteEpisodes (js/data.js): checks favorite
 * series in the background after login and shows a toast when new episodes
 * appeared since the previous check. Starts after the splash preload so it
 * does not compete with it for the panel's request quota.
 */
private suspend fun notifyNewFavoriteEpisodes(
    context: android.content.Context,
    authRepository: AuthRepository,
    session: AuthSession,
    repository: NewEpisodesRepository
) {
    kotlinx.coroutines.delay(10_000)
    val found = try {
        repository.checkFavoriteSeries { seriesId ->
            val ids = authRepository.getSeriesInfo(session, seriesId).getOrNull()
                ?.episodes?.values?.flatten()?.map { it.id }
            kotlinx.coroutines.delay(500)
            ids
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        android.util.Log.w("BtvNewEpisodes", "Check failed: ${error.javaClass.simpleName}")
        return
    }
    if (found.isEmpty()) return
    val names = found.map { it.seriesName }
    val extra = if (names.size > 3) " et ${names.size - 3} autre(s)" else ""
    android.widget.Toast.makeText(
        context,
        "Nouveaux épisodes disponibles : ${names.take(3).joinToString(", ")}$extra",
        android.widget.Toast.LENGTH_LONG
    ).show()
}
