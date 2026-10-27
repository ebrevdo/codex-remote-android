package com.codex.remote

import com.codex.remote.data.rpc.ThreadPage
import com.codex.remote.data.rpc.collectAllThreadPages
import com.codex.remote.domain.RemoteThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Import history once in the background; subsequent notifications refresh only recent tasks. */
internal class ThreadCatalogRefresh(
    private val scope: CoroutineScope,
    initialThreads: List<RemoteThread>,
    private val loadPage: suspend (String?) -> ThreadPage,
    private val onThreads: (List<RemoteThread>) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onLoading: (Boolean) -> Unit = {},
) {
    private val threads = initialThreads.associateByTo(linkedMapOf()) { it.id }
    private val removedIds = mutableSetOf<String>()
    private val updatesDuringImport = linkedMapOf<String, RemoteThread>()
    private var imported = false
    private var pending = false
    private var job: Job? = null
    private var publishedThreads: List<RemoteThread>? = null

    fun upsert(thread: RemoteThread) {
        removedIds.remove(thread.id)
        if (!imported) updatesDuringImport[thread.id] = thread
        threads[thread.id] = thread
        publish()
    }

    fun refresh(threadId: String? = null, removed: Boolean = false, restored: Boolean = false) {
        if (threadId != null) {
            if (removed) {
                removedIds.add(threadId)
                threads.remove(threadId)
                publish()
            } else if (restored) {
                removedIds.remove(threadId)
            }
        }
        pending = true
        if (job?.isActive == true) return
        onLoading(true)
        job = scope.launch {
            try {
                while (pending) {
                    if (imported) delay(250)
                    pending = false
                    if (imported) {
                        merge(loadPage(null).threads)
                    } else {
                        val all = collectAllThreadPages(onPage = ::merge, loadPage = loadPage)
                        threads.clear()
                        merge(all)
                        updatesDuringImport.clear()
                        imported = true
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onError(error)
            } finally {
                onLoading(false)
            }
        }
    }

    fun close() {
        job?.cancel()
    }

    private fun merge(updated: List<RemoteThread>) {
        updated.filterNot { it.id in removedIds }.forEach { threads[it.id] = updatesDuringImport[it.id] ?: it }
        updatesDuringImport.values.filterNot { it.id in removedIds }.forEach { threads[it.id] = it }
        publish()
    }

    private fun publish() {
        val previous = publishedThreads
        if (previous != null && previous.size == threads.size && previous.all { threads[it.id] == it }) return
        val sorted = threads.values.sortedWith(compareByDescending<RemoteThread> { it.updatedAt }.thenBy { it.id })
        publishedThreads = sorted
        onThreads(sorted)
    }
}
