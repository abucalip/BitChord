package com.music.bitchord

import com.music.bitchord.data.sources.LibraryPage
import com.music.bitchord.data.sources.navidrome.NavidromeArt
import com.music.bitchord.data.sources.navidrome.NavidromeArtKeyer
import com.music.bitchord.ui.screens.PagedList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The paging behind the Navidrome library lists, and the identity covers are cached under. */
class NavidromeBrowseTest {

    private fun pagesOf(vararg pages: List<Int>): (Int) -> Result<LibraryPage<Int>> {
        var calls = 0
        return { offset ->
            val page = pages[calls++]
            Result.success(LibraryPage(page, if (calls < pages.size) offset + page.size else null))
        }
    }

    // ── PagedList ─────────────────────────────────────────────────────────

    @Test
    fun `pages append in order until the last one`() = runTest {
        val list = PagedList(pagesOf(listOf(1, 2), listOf(3, 4), listOf(5)).let { f -> { o: Int -> f(o) } })
        assertFalse(list.started)

        list.loadNext()
        assertEquals(listOf(1, 2), list.items)
        assertFalse(list.exhausted)

        list.loadNext()
        list.loadNext()
        assertEquals(listOf(1, 2, 3, 4, 5), list.items)
        assertTrue(list.exhausted)
    }

    @Test
    fun `nothing is requested after the last page`() = runTest {
        var calls = 0
        val list = PagedList<Int> { calls++; Result.success(LibraryPage(listOf(1), null)) }
        list.loadNext()
        list.loadNext()
        list.loadNext()
        assertEquals(1, calls)
        assertEquals(listOf(1), list.items)
    }

    @Test
    fun `the offset passed on is the one the previous page named`() = runTest {
        val seen = mutableListOf<Int>()
        val list = PagedList<Int> { offset ->
            seen += offset
            Result.success(LibraryPage(listOf(offset), if (offset < 100) offset + 50 else null))
        }
        repeat(4) { list.loadNext() }
        assertEquals(listOf(0, 50, 100), seen)
    }

    @Test
    fun `an error stops further loading until retry and keeps what was shown`() = runTest {
        var fail = true
        var calls = 0
        val list = PagedList<Int> { offset ->
            calls++
            if (offset == 1 && fail) Result.failure(RuntimeException("down"))
            else Result.success(LibraryPage(listOf(offset), if (offset < 2) offset + 1 else null))
        }
        list.loadNext()
        list.loadNext()
        assertEquals("down", list.error)
        assertEquals(listOf(0), list.items)

        // Scrolling again must not hammer a failing server.
        list.loadNext()
        list.loadNext()
        assertEquals(2, calls)

        fail = false
        list.retry()
        assertNull(list.error)
        assertEquals(listOf(0, 1), list.items)
    }

    @Test
    fun `an empty first page is exhausted and empty`() = runTest {
        val list = PagedList<Int> { Result.success(LibraryPage(emptyList(), null)) }
        list.loadNext()
        assertTrue(list.started)
        assertTrue(list.exhausted)
        assertTrue(list.items.isEmpty())
        assertNull(list.error)
    }

    @Test
    fun `a page already in flight is not requested twice`() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val list = PagedList<Int> {
            calls++
            gate.await()
            Result.success(LibraryPage(listOf(1), null))
        }
        val first = async { list.loadNext() }
        testScheduler.advanceUntilIdle()
        list.loadNext()
        assertTrue(list.loading)
        gate.complete(Unit)
        first.await()
        assertEquals(1, calls)
    }

    // ── Artwork identity ──────────────────────────────────────────────────

    @Test
    fun `cover keys name the server and reference and never a url`() {
        val key = NavidromeArtKeyer.keyOf(NavidromeArt("srv-1", "al-9", 300))
        assertEquals("navidrome:srv-1:al-9:300", key)
        assertFalse(key.contains("http"))
        assertFalse(key.contains("rest"))
    }

    @Test
    fun `the same reference on two servers is two covers`() {
        assertNotEquals(
            NavidromeArtKeyer.keyOf(NavidromeArt("srv-1", "al-9", 300)),
            NavidromeArtKeyer.keyOf(NavidromeArt("srv-2", "al-9", 300)),
        )
    }

    @Test
    fun `different sizes are cached separately`() {
        assertNotEquals(
            NavidromeArtKeyer.keyOf(NavidromeArt("s", "r", 150)),
            NavidromeArtKeyer.keyOf(NavidromeArt("s", "r", 600)),
        )
    }
}
