package com.btv.ui.browse

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import com.btv.data.cache.CatalogCache
import com.btv.data.model.AuthSession
import com.btv.data.model.XtreamEpgListing
import com.btv.data.model.UserInfo
import com.btv.data.repository.AuthRepository
import com.btv.data.store.CredentialsStore
import com.btv.data.store.PreferencesStore
import com.btv.ui.theme.BtvTheme
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import org.junit.Rule
import org.junit.Test

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class BrowseNetworkErrorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dpadCanRetryAFailedCatalogRequest() {
        CatalogCache.clear()
        val requests = AtomicInteger()
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val responder = thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { /* request headers */ }
                        requests.incrementAndGet()
                        socket.getOutputStream().write(
                            "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                        )
                        socket.getOutputStream().flush()
                    }
                } catch (_: SocketException) {
                    if (!server.isClosed) throw IllegalStateException("Test server disconnected")
                }
            }
        }

        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = AuthRepository(CredentialsStore(context))
            val session = AuthSession("http://127.0.0.1:${server.localPort}", "test", "test", UserInfo(auth = 1))
            val viewModel = BrowseViewModel(repository, session, PreferencesStore(context))
            compose.setContent {
                BtvTheme(darkTheme = true) {
                    BrowseScreen(viewModel = viewModel, contentType = ContentType.LIVE)
                }
            }

            compose.waitUntil(15_000) {
                requests.get() >= 1 && viewModel.uiState.value.retryTarget == BrowseRetryTarget.CATALOG &&
                    !viewModel.uiState.value.isLoading
            }
            compose.onNodeWithText("Impossible de charger les catégories du serveur IPTV.").assertExists()
            compose.onNodeWithText("Aucune chaîne disponible.").assertDoesNotExist()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            compose.onNodeWithContentDescription("Réessayer le chargement").assertIsFocused()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitUntil(15_000) { requests.get() >= 2 }
        } finally {
            server.close()
            responder.join(2_000)
            CatalogCache.clear()
        }
    }

    @Test fun dpadCanRetryAFailedChannelList() {
        CatalogCache.clear()
        val streamRequests = AtomicInteger()
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val responder = thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) { /* request headers */ }
                        val response = if ("get_live_categories" in requestLine) {
                            val body = "[{\"category_id\":\"1\",\"category_name\":\"Test\"}]"
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                        } else {
                            streamRequests.incrementAndGet()
                            "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        }
                        socket.getOutputStream().write(response.toByteArray())
                        socket.getOutputStream().flush()
                    }
                } catch (_: SocketException) {
                    if (!server.isClosed) throw IllegalStateException("Test server disconnected")
                }
            }
        }

        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val viewModel = BrowseViewModel(
                AuthRepository(CredentialsStore(context)),
                AuthSession("http://127.0.0.1:${server.localPort}", "test", "test", UserInfo(auth = 1)),
                PreferencesStore(context)
            )
            compose.setContent {
                BtvTheme(darkTheme = true) {
                    BrowseScreen(viewModel = viewModel, contentType = ContentType.LIVE)
                }
            }
            compose.waitUntil(15_000) { viewModel.uiState.value.categories.any { it.id == "1" } }
            compose.runOnIdle { viewModel.selectCategory("1") }
            compose.waitUntil(15_000) {
                streamRequests.get() >= 1 && viewModel.uiState.value.retryTarget == BrowseRetryTarget.CONTENT &&
                    !viewModel.uiState.value.isLoading
            }
            compose.onNodeWithText("Impossible de charger cette catégorie.").assertExists()
            compose.onNodeWithText("Aucune chaîne disponible.").assertDoesNotExist()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            compose.onNodeWithContentDescription("Réessayer le chargement").assertIsFocused()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitUntil(15_000) { streamRequests.get() >= 2 }
            compose.waitUntil(15_000) {
                viewModel.uiState.value.retryTarget == BrowseRetryTarget.CONTENT && !viewModel.uiState.value.isLoading
            }
            compose.runOnIdle { viewModel.selectCategory("show_all") }
            compose.waitUntil(15_000) {
                streamRequests.get() >= 3 && viewModel.uiState.value.error == "Impossible de charger 1 catégorie(s). Réessayer."
            }
            compose.onNodeWithText("Impossible de charger 1 catégorie(s). Réessayer.").assertExists()
            compose.onNodeWithText("Aucune chaîne disponible.").assertDoesNotExist()
        } finally {
            server.close()
            responder.join(2_000)
            CatalogCache.clear()
        }
    }

    @Test fun failedShortEpgIsVisibleAndRetryableInLiveMenu() {
        CatalogCache.clear()
        val epgRequests = AtomicInteger()
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val responder = thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) { /* request headers */ }
                        val body = when {
                            "get_live_categories" in requestLine -> "[{\"category_id\":\"1\",\"category_name\":\"Test\"}]"
                            "get_live_streams" in requestLine -> "[{\"stream_id\":\"11\",\"name\":\"Test chaîne\",\"category_id\":\"1\"}]"
                            else -> null
                        }
                        val response = if (body != null) {
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                        } else {
                            epgRequests.incrementAndGet()
                            "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        }
                        socket.getOutputStream().write(response.toByteArray())
                        socket.getOutputStream().flush()
                    }
                } catch (_: SocketException) {
                    if (!server.isClosed) throw IllegalStateException("Test server disconnected")
                }
            }
        }
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val viewModel = BrowseViewModel(
                AuthRepository(CredentialsStore(context)),
                AuthSession("http://127.0.0.1:${server.localPort}", "test", "test", UserInfo(auth = 1)),
                PreferencesStore(context)
            )
            compose.setContent {
                BtvTheme(darkTheme = true) { BrowseScreen(viewModel = viewModel, contentType = ContentType.LIVE) }
            }
            compose.waitUntil(15_000) { viewModel.uiState.value.categories.any { it.id == "1" } }
            compose.runOnIdle { viewModel.selectCategory("1") }
            compose.waitUntil(15_000) {
                epgRequests.get() >= 1 && viewModel.uiState.value.liveEpgError != null
            }
            compose.onNodeWithText("Impossible de charger le guide TV. Réessayer.").assertExists()
            compose.onNodeWithText("Programme non disponible.").assertDoesNotExist()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitForIdle()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.onNodeWithContentDescription("Réessayer le guide TV").assertIsFocused()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitUntil(15_000) { epgRequests.get() >= 2 }
        } finally {
            server.close()
            responder.join(2_000)
            CatalogCache.clear()
        }
    }

    @Test fun expiredReplayGuideKeepsProgramsAndOffersDpadRetry() {
        CatalogCache.clear()
        val now = System.currentTimeMillis()
        val archived = XtreamEpgListing(
            title = android.util.Base64.encodeToString("Programme archivé".toByteArray(), android.util.Base64.NO_WRAP),
            start = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(now - 2 * 60 * 60 * 1000L)),
            startTimestamp = ((now - 2 * 60 * 60 * 1000L) / 1000).toString(),
            stopTimestamp = ((now - 60 * 60 * 1000L) / 1000).toString()
        )
        runBlocking {
            CatalogCache.loadFullEpg("11", CatalogCache.generationToken(), nowMs = { 0L }) {
                Result.success(listOf(archived))
            }.getOrThrow()
        }
        val epgRequests = AtomicInteger()
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val responder = thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) { /* request headers */ }
                        val body = if ("get_live_streams" in requestLine) {
                            "[{\"stream_id\":\"11\",\"name\":\"Chaîne archive\",\"tv_archive\":1,\"tv_archive_duration\":1}]"
                        } else null
                        val response = if (body != null) {
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                        } else {
                            epgRequests.incrementAndGet()
                            "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        }
                        socket.getOutputStream().write(response.toByteArray())
                        socket.getOutputStream().flush()
                    }
                } catch (_: SocketException) {
                    if (!server.isClosed) throw IllegalStateException("Test server disconnected")
                }
            }
        }
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val viewModel = BrowseViewModel(
                AuthRepository(CredentialsStore(context)),
                AuthSession("http://127.0.0.1:${server.localPort}", "test", "test", UserInfo(auth = 1)),
                PreferencesStore(context)
            )
            compose.setContent {
                BtvTheme(darkTheme = true) { BrowseScreen(viewModel = viewModel, contentType = ContentType.REPLAY) }
            }
            compose.waitUntil(15_000) {
                epgRequests.get() >= 1 && viewModel.uiState.value.error?.startsWith("Rediffusion ancienne") == true
            }
            compose.onNodeWithText("Programme archivé").assertExists()
            compose.onNodeWithText("Rediffusion ancienne : actualisation impossible. Réessayer.").assertExists()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            compose.onNodeWithContentDescription("Réessayer le chargement").assertIsFocused()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitUntil(15_000) { epgRequests.get() >= 2 }
        } finally {
            server.close()
            responder.join(2_000)
            CatalogCache.clear()
        }
    }
}
