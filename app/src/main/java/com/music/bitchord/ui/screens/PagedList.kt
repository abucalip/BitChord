package com.music.bitchord.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.bitchord.data.sources.LibraryPage

/**
 * A listing fetched a page at a time.
 *
 * [loadNext] is safe to call whenever the list nears its end: it does nothing
 * while a page is in flight, after the last page, or while an error is showing
 * (that waits for [retry], so a failing server is not hammered by scrolling).
 *
 * The secondary constructor starts from a page taken earlier with [snapshot],
 * so a list survives its screen being recreated; loading resumes where that
 * page ended.
 */
@Stable
internal class PagedList<T>(private val fetch: suspend (offset: Int) -> Result<LibraryPage<T>>) {
    var items by mutableStateOf<List<T>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    private var nextOffset: Int? = 0
    var started = false
        private set

    constructor(restored: LibraryPage<T>?, fetch: suspend (offset: Int) -> Result<LibraryPage<T>>) : this(fetch) {
        if (restored != null) {
            items = restored.items
            nextOffset = restored.nextOffset
            started = true
        }
    }

    /** What has been loaded so far, as a page the secondary constructor can restore from. */
    fun snapshot(): LibraryPage<T> = LibraryPage(items, nextOffset)

    val exhausted: Boolean get() = nextOffset == null

    suspend fun loadNext() {
        val offset = nextOffset ?: return
        if (loading || error != null) return
        started = true
        loading = true
        fetch(offset).fold(
            onSuccess = { page ->
                items = items + page.items
                nextOffset = page.nextOffset
            },
            onFailure = { error = it },
        )
        loading = false
    }

    suspend fun retry() {
        error = null
        loadNext()
    }
}
