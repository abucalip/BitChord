package com.music.bitchord

import androidx.compose.runtime.saveable.SaverScope
import com.music.bitchord.data.sources.LibraryAlbum
import com.music.bitchord.data.sources.LibraryPage
import com.music.bitchord.ui.screens.PagedList
import com.music.bitchord.ui.screens.albumPageSaver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavidromeAlbumPageRestoreTest {
    private fun album(i: Int) = LibraryAlbum(
        id = "id$i", title = "Title $i", artist = if (i % 2 == 0) "" else "Artist $i",
        artistId = if (i % 3 == 0) null else "artist$i", year = if (i % 4 == 0) null else 2000 + i,
        trackCount = if (i % 5 == 0) null else i, artworkRef = if (i % 6 == 0) null else "art$i",
    )

    @Test
    fun restoredListShowsItsItemsAndResumesAtItsNextOffset() = runBlocking {
        val requested = mutableListOf<Int>()
        val list = PagedList(restored = LibraryPage((0 until 3).map(::album), nextOffset = 50)) { offset ->
            requested += offset
            Result.success(LibraryPage(emptyList<LibraryAlbum>(), null))
        }
        assertEquals(3, list.items.size)
        assertTrue(list.started)
        list.loadNext()
        assertEquals(listOf(50), requested)
    }

    @Test
    fun restoredExhaustedListNeverRequestsMore() = runBlocking {
        var calls = 0
        val list = PagedList(restored = LibraryPage((0 until 2).map(::album), nextOffset = null)) {
            calls++
            Result.success(LibraryPage(emptyList<LibraryAlbum>(), null))
        }
        list.loadNext()
        assertEquals(0, calls)
        assertTrue(list.exhausted)
    }

    @Test
    fun snapshotRoundTripsThroughTheSaverWithEveryField() {
        val page = LibraryPage((0 until 12).map(::album), nextOffset = 12)
        val list = PagedList(restored = page) { Result.success(LibraryPage(emptyList<LibraryAlbum>(), null)) }
        val saver = albumPageSaver
        val saved = with(saver) { SaverScope { true }.save(list.snapshot()) }
        assertEquals(page, saver.restore(saved!!))
    }

    @Test
    fun malformedSavedStateRestoresNothing() {
        assertNull(albumPageSaver.restore(listOf<Any?>("not an offset")))
    }
}
