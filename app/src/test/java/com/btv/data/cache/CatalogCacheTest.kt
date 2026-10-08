package com.btv.data.cache

import com.btv.data.model.XtreamCategory
import com.btv.data.model.XtreamChannel
import com.btv.data.model.XtreamEpgListing
import com.btv.data.model.XtreamSeries
import com.btv.data.model.XtreamVod
import com.btv.data.store.CatalogSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogCacheTest {
    @Test
    fun failedFetchDoesNotHideCategoriesFromTheNextRetry() = runBlocking {
        CatalogCache.clear()
        val category = XtreamCategory(categoryId = "42", categoryName = "Films")

        assertTrue(CatalogCache.loadCategories(CatalogSection.MOVIES) {
            Result.failure(IllegalStateException("HTTP 429"))
        }.isFailure)
        assertNull(CatalogCache.categoriesFor(CatalogSection.MOVIES))

        assertEquals(listOf(category), CatalogCache.loadCategories(CatalogSection.MOVIES) {
            Result.success(listOf(category))
        }.getOrThrow())
        assertEquals(listOf(category), CatalogCache.categoriesFor(CatalogSection.MOVIES))
        CatalogCache.clear()
    }

    @Test
    fun previousSessionCannotRepopulateCacheAfterClear() = runBlocking {
        CatalogCache.clear()
        val oldGeneration = CatalogCache.generationToken()
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Result<List<XtreamCategory>>>()
        val oldRequest = async {
            CatalogCache.loadCategories(CatalogSection.LIVE) {
                started.complete(Unit)
                answer.await()
            }
        }
        started.await()
        CatalogCache.clear()
        answer.complete(Result.success(listOf(XtreamCategory("old", "Ancien compte"))))

        assertTrue(oldRequest.await().isFailure)
        assertNull(CatalogCache.categoriesFor(CatalogSection.LIVE))
        var fetchedAfterClear = false
        assertTrue(CatalogCache.loadCategories(CatalogSection.SERIES, oldGeneration) {
            fetchedAfterClear = true
            Result.success(listOf(XtreamCategory("old", "Ancien compte")))
        }.isFailure)
        assertFalse(fetchedAfterClear)
        CatalogCache.clear()
    }

    @Test
    fun epgFailureRemainsRetryable() = runBlocking {
        CatalogCache.clear()
        val generation = CatalogCache.generationToken()
        val program = EpgProgramInfo("Journal", 1_000L, 2_000L)

        assertTrue(CatalogCache.loadEpg("channel", generation) {
            Result.failure(IllegalStateException("HTTP 429"))
        }.isFailure)
        assertEquals(listOf(program), CatalogCache.loadEpg("channel", generation) {
            Result.success(listOf(program))
        }.getOrThrow().value)
        CatalogCache.clear()
    }

    @Test
    fun shortEpgExpiresAndKeepsAnExplicitStaleFallbackAfterRefreshFailure() = runBlocking {
        CatalogCache.clear()
        val generation = CatalogCache.generationToken()
        var now = 1_000_000L
        var requests = 0
        val old = EpgProgramInfo("Ancien", 1L, 2L)
        val fresh = EpgProgramInfo("Récent", 3L, 4L)

        val first = CatalogCache.loadEpg("channel", generation, nowMs = { now }) {
            requests++
            Result.success(listOf(old))
        }.getOrThrow()
        assertFalse(first.isStale)
        now += 60_000L
        assertEquals(listOf(old), CatalogCache.loadEpg("channel", generation, nowMs = { now }) {
            error("Un guide encore frais ne doit pas être rechargé")
        }.getOrThrow().value)
        now += 5 * 60_000L
        val stale = CatalogCache.loadEpg("channel", generation, nowMs = { now }) {
            requests++
            Result.failure(IllegalStateException("HTTP 503"))
        }.getOrThrow()
        assertTrue(stale.isStale)
        assertEquals(listOf(old), stale.value)
        assertEquals(2, requests)

        val recovered = CatalogCache.loadEpg("channel", generation, forceRefresh = true, nowMs = { now }) {
            requests++
            Result.success(listOf(fresh))
        }.getOrThrow()
        assertFalse(recovered.isStale)
        assertEquals(listOf(fresh), recovered.value)
        assertEquals(3, requests)
        CatalogCache.clear()
    }

    @Test
    fun oldEpgResponseCannotRestoreStaleGuideAfterSessionChange() = runBlocking {
        CatalogCache.clear()
        val generation = CatalogCache.generationToken()
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Result<List<EpgProgramInfo>>>()
        val oldRequest = async {
            CatalogCache.loadEpg("channel", generation) {
                started.complete(Unit)
                answer.await()
            }
        }
        started.await()
        CatalogCache.clear()
        answer.complete(Result.success(listOf(EpgProgramInfo("Ancien", 1L, 2L))))
        assertTrue(oldRequest.await().isFailure)
        assertTrue(CatalogCache.loadEpg("channel", CatalogCache.generationToken()) {
            Result.failure(IllegalStateException("HTTP 503"))
        }.isFailure)
        CatalogCache.clear()
    }

    @Test
    fun replayEpgRefreshesAfterFifteenMinutesAndKeepsStaleDataIfOffline() = runBlocking {
        CatalogCache.clear()
        val generation = CatalogCache.generationToken()
        var now = 1_000_000L
        var requests = 0
        val old = XtreamEpgListing(title = "Ancien", startTimestamp = "100", stopTimestamp = "200")
        val recent = XtreamEpgListing(title = "Récent", startTimestamp = "300", stopTimestamp = "400")

        assertEquals(listOf(old), CatalogCache.loadFullEpg("channel", generation, nowMs = { now }) {
            requests++
            Result.success(listOf(old))
        }.getOrThrow().value)
        now += 14 * 60_000L
        assertEquals(listOf(old), CatalogCache.loadFullEpg("channel", generation, nowMs = { now }) {
            error("Le guide de rediffusion est encore frais")
        }.getOrThrow().value)

        now += 60_000L
        val stale = CatalogCache.loadFullEpg("channel", generation, nowMs = { now }) {
            requests++
            Result.failure(IllegalStateException("HTTP 503"))
        }.getOrThrow()
        assertTrue(stale.isStale)
        assertEquals(listOf(old), stale.value)

        val recovered = CatalogCache.loadFullEpg("channel", generation, nowMs = { now }) {
            requests++
            Result.success(listOf(recent))
        }.getOrThrow()
        assertFalse(recovered.isStale)
        assertEquals(listOf(recent), recovered.value)
        assertEquals(3, requests)
        CatalogCache.clear()
    }

    @Test
    fun replayEpgSeededFromDiskShowsAtOnceAndStillExpires() = runBlocking {
        CatalogCache.clear()
        val generation = CatalogCache.generationToken()
        val disk = XtreamEpgListing(title = "Disque", startTimestamp = "100", stopTimestamp = "200")
        val network = XtreamEpgListing(title = "Réseau", startTimestamp = "300", stopTimestamp = "400")
        val now = 10_000_000L

        CatalogCache.seedFullEpg("channel", listOf(disk), fetchedAtMs = now - 20 * 60_000L, expectedGeneration = generation)
        val peeked = CatalogCache.peekFullEpg("channel")!!
        assertEquals(listOf(disk), peeked.value)
        assertFalse(CatalogCache.isFullEpgFresh(peeked.fetchedAtMs, now))

        val refreshed = CatalogCache.loadFullEpg("channel", generation, nowMs = { now }) { Result.success(listOf(network)) }.getOrThrow()
        assertTrue(refreshed.isFresh)
        assertEquals(listOf(network), refreshed.value)

        // A disk copy never overwrites what memory already holds.
        CatalogCache.seedFullEpg("channel", listOf(disk), fetchedAtMs = now, expectedGeneration = generation)
        assertEquals(listOf(network), CatalogCache.peekFullEpg("channel")!!.value)
        // Still fresh: served from memory, not fetched, not marked fresh again.
        val cached = CatalogCache.loadFullEpg("channel", generation, nowMs = { now + 60_000L }) { error("Encore frais") }.getOrThrow()
        assertFalse(cached.isFresh)
        CatalogCache.clear()
    }

    @Test
    fun replayEpgEvictsTheLeastRecentlyVisitedChannel() = runBlocking {
        CatalogCache.clear()
        val generation = CatalogCache.generationToken()
        val listing = listOf(XtreamEpgListing(title = "P"))
        for (i in 0 until 40) {
            CatalogCache.loadFullEpg("c$i", generation, nowMs = { 0L }) { Result.success(listing) }
        }
        // Revisit c0: c1 becomes the oldest visit.
        CatalogCache.loadFullEpg("c0", generation, nowMs = { 0L }) { error("En mémoire") }
        CatalogCache.loadFullEpg("c40", generation, nowMs = { 0L }) { Result.success(listing) }
        assertTrue(CatalogCache.peekFullEpg("c0") != null)
        assertNull(CatalogCache.peekFullEpg("c1"))
        assertTrue(CatalogCache.peekFullEpg("c40") != null)
        CatalogCache.clear()
    }

    @Test
    fun replayEpgFromOldSessionCannotBecomeStaleFallback() = runBlocking {
        CatalogCache.clear()
        val oldGeneration = CatalogCache.generationToken()
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Result<List<XtreamEpgListing>>>()
        val oldRequest = async {
            CatalogCache.loadFullEpg("channel", oldGeneration) {
                started.complete(Unit)
                answer.await()
            }
        }
        started.await()
        CatalogCache.clear()
        answer.complete(Result.success(listOf(XtreamEpgListing(title = "Ancien"))))
        assertTrue(oldRequest.await().isFailure)
        assertTrue(CatalogCache.loadFullEpg("channel", CatalogCache.generationToken()) {
            Result.failure(IllegalStateException("HTTP 503"))
        }.isFailure)
        CatalogCache.clear()
    }

    @Test
    fun oldVodResponseCannotReplaceNewSessionsItems() = runBlocking {
        CatalogCache.clear()
        val oldGeneration = CatalogCache.generationToken()
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Result<List<XtreamVod>>>()
        val oldRequest = async {
            CatalogCache.loadVodStreams("same-id", oldGeneration) {
                started.complete(Unit)
                answer.await()
            }
        }
        started.await()
        CatalogCache.clear()
        val newGeneration = CatalogCache.generationToken()
        val current = XtreamVod(streamId = "new", name = "Nouveau compte")
        assertEquals(listOf(current), CatalogCache.loadVodStreams("same-id", newGeneration) {
            Result.success(listOf(current))
        }.getOrThrow())
        answer.complete(Result.success(listOf(XtreamVod(streamId = "old", name = "Ancien compte"))))

        assertTrue(oldRequest.await().isFailure)
        assertEquals(listOf(current), CatalogCache.loadVodStreams("same-id", newGeneration) {
            error("The new session's cache should be reused")
        }.getOrThrow())
        CatalogCache.clear()
    }

    @Test
    fun categoryCacheEvictsLeastRecentlyUsedCategoryWithoutLosingOtherSections() = runBlocking {
        CatalogCache.clear()
        val generation = CatalogCache.generationToken()
        for (index in 0 until 32) {
            CatalogCache.loadVodStreams("cat-$index", generation) {
                Result.success(listOf(XtreamVod(streamId = "$index", name = "Film $index")))
            }.getOrThrow()
        }
        CatalogCache.loadVodStreams("cat-0", generation) {
            error("La catégorie récemment consultée doit rester en cache")
        }.getOrThrow()
        CatalogCache.loadVodStreams("cat-32", generation) {
            Result.success(listOf(XtreamVod(streamId = "32", name = "Film 32")))
        }.getOrThrow()
        var refetched = false
        CatalogCache.loadVodStreams("cat-1", generation) {
            refetched = true
            Result.success(emptyList())
        }.getOrThrow()
        assertTrue(refetched)
        CatalogCache.loadVodStreams("cat-0", generation) {
            error("L'entrée récemment utilisée ne doit pas être évincée")
        }.getOrThrow()
        CatalogCache.clear()
    }

    @Test
    fun categoryCacheLimitsTotalItemsAndDoesNotRetainOversizedLists() = runBlocking {
        CatalogCache.clear()
        val generation = CatalogCache.generationToken()
        val twoThousand = List(2_000) { XtreamVod(streamId = "$it", name = "Film $it") }
        CatalogCache.loadVodStreams("a", generation) { Result.success(twoThousand) }.getOrThrow()
        CatalogCache.loadVodStreams("b", generation) { Result.success(twoThousand) }.getOrThrow()
        CatalogCache.loadVodStreams("c", generation) {
            Result.success(listOf(XtreamVod(streamId = "new", name = "Nouveau")))
        }.getOrThrow()
        var aRefetched = false
        CatalogCache.loadVodStreams("a", generation) {
            aRefetched = true
            Result.success(emptyList())
        }.getOrThrow()
        assertTrue(aRefetched)

        val hugeLive = List(2_001) { XtreamChannel(streamId = "$it", name = "Chaîne $it") }
        var liveFetches = 0
        repeat(2) {
            assertEquals(hugeLive, CatalogCache.loadLiveStreams("huge", generation) {
                liveFetches++
                Result.success(hugeLive)
            }.getOrThrow())
        }
        assertEquals(2, liveFetches)

        val hugeSeries = List(2_001) { XtreamSeries(seriesId = "$it", title = "Série $it") }
        var seriesFetches = 0
        repeat(2) {
            assertEquals(hugeSeries, CatalogCache.loadSeries("huge", generation) {
                seriesFetches++
                Result.success(hugeSeries)
            }.getOrThrow())
        }
        assertEquals(2, seriesFetches)
        CatalogCache.clear()
    }
}
