package com.codex.remote

import com.codex.remote.data.rpc.ThreadPage
import com.codex.remote.domain.RemoteThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadCatalogRefreshTest {
    @Test fun recentTasksAppearBeforeOldHistoryAndNotificationBurstsDoNotRescanHistory() = runTest {
        val oldPage = CompletableDeferred<ThreadPage>()
        val cursors = mutableListOf<String?>()
        var published = emptyList<RemoteThread>()
        val catalog = ThreadCatalogRefresh(
            this, listOf(thread("cached", 1)),
            loadPage = { cursor ->
                cursors += cursor
                if (cursor == null) ThreadPage(listOf(thread("recent", 3)), "old-page") else oldPage.await()
            },
            onThreads = { published = it }, onError = { throw AssertionError(it) },
        )

        repeat(20) { catalog.refresh() }
        runCurrent()
        // Initial loading starts immediately; only later notification bursts are delayed.
        assertEquals(listOf(null, "old-page"), cursors)
        assertEquals(listOf("recent", "cached"), published.map { it.id })
        repeat(20) { catalog.refresh() }
        oldPage.complete(ThreadPage(listOf(thread("older", 2)), null))
        advanceUntilIdle()
        assertEquals(listOf(null, "old-page", null), cursors)
        assertEquals(listOf("recent", "older"), published.map { it.id })

        repeat(20) { catalog.refresh() }
        advanceUntilIdle()
        assertEquals(listOf(null, "old-page", null, null), cursors)
        assertEquals(listOf("recent", "older"), published.map { it.id })
        catalog.close()
    }

    @Test fun loadingRemainsVisibleUntilEveryPageArrivesAndPublishesEachPage() = runTest {
        val first = CompletableDeferred<ThreadPage>()
        val second = CompletableDeferred<ThreadPage>()
        val last = CompletableDeferred<ThreadPage>()
        val loading = mutableListOf<Boolean>()
        var published = emptyList<RemoteThread>()
        val catalog = ThreadCatalogRefresh(
            this, emptyList(),
            loadPage = { cursor ->
                when (cursor) {
                    null -> first.await()
                    "second" -> second.await()
                    else -> last.await()
                }
            },
            onThreads = { published = it }, onError = { throw AssertionError(it) },
            onLoading = { loading += it },
        )
        catalog.refresh()
        runCurrent()
        assertEquals(listOf(true), loading)
        assertTrue(published.isEmpty())

        first.complete(ThreadPage(listOf(thread("recent", 3)), "second"))
        runCurrent()
        assertEquals(listOf("recent"), published.map { it.id })
        assertEquals(listOf(true), loading)

        second.complete(ThreadPage(listOf(thread("older", 2)), "last"))
        runCurrent()
        assertEquals(listOf("recent", "older"), published.map { it.id })
        assertEquals(listOf(true), loading)

        last.complete(ThreadPage(listOf(thread("oldest", 1)), null))
        advanceUntilIdle()
        assertEquals(listOf("recent", "older", "oldest"), published.map { it.id })
        assertEquals(listOf(true, false), loading)
        catalog.close()
    }

    @Test fun confirmedEmptyHistoryFinishesLoading() = runTest {
        val response = CompletableDeferred<ThreadPage>()
        val loading = mutableListOf<Boolean>()
        val catalog = ThreadCatalogRefresh(
            this, emptyList(), loadPage = { response.await() },
            onThreads = { assertTrue(it.isEmpty()) }, onError = { throw AssertionError(it) },
            onLoading = { loading += it },
        )
        catalog.refresh()
        runCurrent()
        assertEquals(listOf(true), loading)
        response.complete(ThreadPage(emptyList(), null))
        advanceUntilIdle()
        assertEquals(listOf(true, false), loading)
        catalog.close()
    }

    @Test fun unchangedNotificationsDoNotRebuildTheProjectList() = runTest {
        var publications = 0
        val catalog = ThreadCatalogRefresh(
            this, emptyList(), loadPage = { ThreadPage(listOf(thread("same", 1)), null) },
            onThreads = { publications++ }, onError = { throw AssertionError(it) },
        )
        catalog.refresh()
        advanceUntilIdle()
        repeat(5) {
            catalog.refresh()
            advanceUntilIdle()
        }
        assertEquals(1, publications)
        catalog.close()
    }

    @Test fun archiveNotificationDuringImportCannotBeUndoneByAnOlderPage() = runTest {
        val oldPage = CompletableDeferred<ThreadPage>()
        var published = emptyList<RemoteThread>()
        val catalog = ThreadCatalogRefresh(
            this, emptyList(),
            loadPage = { cursor ->
                if (cursor == null) ThreadPage(listOf(thread("keep", 3)), "old-page") else oldPage.await()
            },
            onThreads = { published = it }, onError = { throw AssertionError(it) },
        )
        catalog.refresh()
        advanceTimeBy(250)
        runCurrent()
        catalog.refresh("archived", removed = true)
        catalog.refresh("archived") // A later close/status notification does not unarchive it.
        oldPage.complete(ThreadPage(listOf(thread("archived", 2), thread("old", 1)), null))
        advanceUntilIdle()
        assertEquals(listOf("keep", "old"), published.map { it.id })
        catalog.close()
    }

    @Test fun localEditsAndNewTasksSurviveImportAndRecentPageRefreshes() = runTest {
        val oldPage = CompletableDeferred<ThreadPage>()
        var published = emptyList<RemoteThread>()
        val catalog = ThreadCatalogRefresh(
            this, listOf(thread("old", 1)),
            loadPage = { cursor ->
                if (cursor == null) ThreadPage(listOf(thread("recent", 3)), "old-page") else oldPage.await()
            },
            onThreads = { published = it }, onError = { throw AssertionError(it) },
        )
        catalog.refresh()
        advanceTimeBy(250)
        runCurrent()
        val pinned = thread("old", 1).copy(isPinned = true, title = "Renamed")
        catalog.upsert(pinned)
        catalog.upsert(thread("new-task", 4))
        oldPage.complete(ThreadPage(listOf(thread("old", 1)), null))
        advanceUntilIdle()
        catalog.refresh()
        advanceUntilIdle()
        assertEquals(listOf("new-task", "recent", "old"), published.map { it.id })
        assertEquals(pinned, published.last())
        catalog.close()
    }

    @Test fun failedCatalogRefreshKeepsCachedTasksAndCanBeRetried() = runTest {
        var fail = true
        val errors = mutableListOf<Throwable>()
        val loading = mutableListOf<Boolean>()
        var published = listOf(thread("cached", 1))
        val catalog = ThreadCatalogRefresh(
            this, published,
            loadPage = {
                if (fail) error("catalog unavailable")
                ThreadPage(listOf(thread("new", 2)), null)
            },
            onThreads = { published = it }, onError = { errors += it },
            onLoading = { loading += it },
        )
        catalog.refresh()
        advanceUntilIdle()
        assertEquals(1, errors.size)
        assertEquals(listOf(true, false), loading)
        assertEquals("cached", published.single().id)
        fail = false
        catalog.refresh()
        advanceUntilIdle()
        assertEquals("new", published.single().id)
        assertEquals(listOf(true, false, true, false), loading)
        catalog.close()
    }

    @Test fun closingCatalogCancelsInFlightHistoryBeforeItCanPublish() = runTest {
        val oldPage = CompletableDeferred<ThreadPage>()
        val published = mutableListOf<List<RemoteThread>>()
        val loading = mutableListOf<Boolean>()
        val catalog = ThreadCatalogRefresh(
            this, emptyList(), loadPage = { oldPage.await() },
            onThreads = { published += it }, onError = { throw AssertionError(it) },
            onLoading = { loading += it },
        )
        catalog.refresh()
        advanceTimeBy(250)
        runCurrent()
        catalog.close()
        oldPage.complete(ThreadPage(listOf(thread("stale", 1)), null))
        advanceUntilIdle()
        assertTrue(published.isEmpty())
        assertEquals(listOf(true, false), loading)
    }

    private fun thread(id: String, updatedAt: Long) = RemoteThread(id, id, "/project", updatedAt, "idle")
}
