package com.btv.ui.browse

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.btv.data.cache.CatalogCache
import com.btv.data.model.AuthSession
import com.btv.data.model.UserInfo
import com.btv.data.repository.AuthRepository
import com.btv.data.store.CredentialsStore
import com.btv.data.store.PreferencesStore
import com.btv.ui.theme.BtvTheme
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BrowseShowAllTest {
    @get:Rule val compose = createComposeRule()

    private val films = (1..301).joinToString(",") { "{\"stream_id\":\"$it\",\"name\":\"Film $it\",\"category_id\":\"1\"}" }
    private val needle = "{\"stream_id\":\"999\",\"name\":\"Needle Beyond\",\"category_id\":\"7\"}"

    private fun ok(body: String) =
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"

    /** Seven VOD categories; [unfiltered] answers get_vod_streams without category_id. */
    private fun withServer(
        unfiltered: () -> String,
        categoryRequests: AtomicInteger,
        limitedRequests: AtomicInteger,
        block: (BrowseViewModel) -> Unit
    ) {
        CatalogCache.clear()
        val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val responder = thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) { /* request headers */ }
                        val response = when {
                            "get_vod_categories" in requestLine -> ok((1..7).joinToString(",", "[", "]") {
                                "{\"category_id\":\"$it\",\"category_name\":\"A $it\"}"
                            })
                            "get_vod_streams" in requestLine && "category_id=" !in requestLine -> unfiltered()
                            "get_vod_streams" in requestLine && "category_id=7" in requestLine &&
                                limitedRequests.incrementAndGet() == 1 -> {
                                categoryRequests.incrementAndGet()
                                Thread.sleep(1_500)
                                "HTTP/1.1 429 Too Many Requests\r\nRetry-After: 1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            }
                            "get_vod_streams" in requestLine && "category_id=1" in requestLine -> {
                                categoryRequests.incrementAndGet()
                                ok("[$films]")
                            }
                            "get_vod_streams" in requestLine && "category_id=7" in requestLine -> {
                                categoryRequests.incrementAndGet()
                                ok("[$needle]")
                            }
                            else -> {
                                categoryRequests.incrementAndGet()
                                ok("[]")
                            }
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
                BtvTheme(darkTheme = true) { BrowseScreen(viewModel = viewModel, contentType = ContentType.VOD) }
            }
            compose.waitUntil(15_000) { viewModel.uiState.value.categories.count { !it.isQuickAccess } == 7 }
            block(viewModel)
        } finally {
            server.close()
            responder.join(3_000)
            CatalogCache.clear()
        }
    }

    private fun assertSearchFindsNeedleThenRestoresPreview(viewModel: BrowseViewModel) {
        compose.runOnIdle { viewModel.updateContentSearch("Needle") }
        compose.waitUntil(15_000) {
            !viewModel.uiState.value.isLoading && viewModel.uiState.value.contents.any { it.name == "Needle Beyond" }
        }
        assertEquals(1, viewModel.uiState.value.contents.size)
        compose.runOnIdle { viewModel.clearContentSearch() }
        compose.waitUntil(5_000) { viewModel.uiState.value.contents.size == 300 }
        assertTrue(viewModel.uiState.value.contents.first().name.startsWith("Film"))
    }

    @Test fun unfilteredCatalogIsStreamedWithoutPerCategoryRequests() {
        val categoryRequests = AtomicInteger()
        val unfilteredRequests = AtomicInteger()
        withServer(
            unfiltered = { unfilteredRequests.incrementAndGet(); ok("[$films,$needle]") },
            categoryRequests = categoryRequests,
            limitedRequests = AtomicInteger()
        ) { viewModel ->
            compose.runOnIdle { viewModel.selectCategory("show_all") }
            compose.waitUntil(15_000) { !viewModel.uiState.value.isLoading && viewModel.uiState.value.contents.size == 300 }
            assertFalse(viewModel.uiState.value.contents.any { it.name == "Needle Beyond" })
            assertEquals(null, viewModel.uiState.value.error)

            assertSearchFindsNeedleThenRestoresPreview(viewModel)
            // One pass to index, one to rebuild the matching card.
            assertEquals(2, unfilteredRequests.get())
            assertEquals(0, categoryRequests.get())
        }
    }

    @Test fun refusedUnfilteredCatalogFallsBackToCategoriesAndRetriesRateLimits() {
        val categoryRequests = AtomicInteger()
        val limitedRequests = AtomicInteger()
        withServer(
            unfiltered = { "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" },
            categoryRequests = categoryRequests,
            limitedRequests = limitedRequests
        ) { viewModel ->
            compose.runOnIdle { viewModel.selectCategory("show_all") }
            compose.waitUntil(15_000) {
                viewModel.uiState.value.loadingProgress == "Catalogue : 6/7 catégories" &&
                    viewModel.uiState.value.contents.size == 300
            }
            compose.waitUntil(15_000) { !viewModel.uiState.value.isLoading && categoryRequests.get() >= 7 }
            assertEquals(300, viewModel.uiState.value.contents.size)
            assertFalse(viewModel.uiState.value.contents.any { it.name == "Needle Beyond" })
            assertEquals(2, limitedRequests.get())
            assertEquals(null, viewModel.uiState.value.error)

            assertSearchFindsNeedleThenRestoresPreview(viewModel)
        }
    }

    @Test fun htmlUnfilteredReplyFallsBackToCategories() {
        val categoryRequests = AtomicInteger()
        withServer(
            unfiltered = { ok("<html><body>Maintenance</body></html>") },
            categoryRequests = categoryRequests,
            limitedRequests = AtomicInteger(1)
        ) { viewModel ->
            compose.runOnIdle { viewModel.selectCategory("show_all") }
            compose.waitUntil(15_000) { !viewModel.uiState.value.isLoading && categoryRequests.get() >= 7 }
            assertEquals(300, viewModel.uiState.value.contents.size)
            assertEquals(null, viewModel.uiState.value.error)
        }
    }
}
